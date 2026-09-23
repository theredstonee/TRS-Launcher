import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { ApiError, badRequest, notFound } from './errors'
import { hmacHex, newRedeemCode, normalizeRedeemCode } from './ids'
import { capeView, getCape, grantCape, canUse, type CapeView } from './capes'
import { canUseCosmetic, cosmeticView, getCosmetic, grantCosmetic, type CosmeticView } from './cosmetics'

interface CodeRow {
  id: number
  code_hash: string
  hint: string
  cape_id: string | null
  cosmetic_id: string | null
  max_uses: number
  uses: number
  expires_at: number | null
  revoked_at: number | null
  note: string | null
  created_at: number
  created_by: string
}

export interface CodeView {
  id: number
  hint: string
  /** Genau eins von beiden ist gesetzt. */
  capeId: string | null
  cosmeticId: string | null
  maxUses: number
  uses: number
  expiresAt: string | null
  revokedAt: string | null
  note: string | null
  createdAt: string
  createdBy: string
}

const iso = (t: number | null) => (t === null ? null : new Date(t).toISOString())

function codeView(r: CodeRow): CodeView {
  return {
    id: r.id,
    hint: r.hint,
    capeId: r.cape_id,
    cosmeticId: r.cosmetic_id,
    maxUses: r.max_uses,
    uses: r.uses,
    expiresAt: iso(r.expires_at),
    revokedAt: iso(r.revoked_at),
    note: r.note,
    createdAt: new Date(r.created_at).toISOString(),
    createdBy: r.created_by,
  }
}

/** Codes liegen nur als HMAC-SHA256 (Schlüssel = SECRET_KEY) in der DB. */
export function hashCode(ctx: AppContext, normalized: string): string {
  return hmacHex(ctx.config.secretKey, `redeem:${normalized}`)
}

export type CodeTarget = { capeId: string } | { cosmeticId: string }

export function createCodes(
  ctx: AppContext,
  actor: string,
  opts: CodeTarget & { maxUses: number, count: number, expiresAt?: string, note?: string },
): { codes: (CodeView & { code: string })[] } {
  let capeId: string | null = null
  let cosmeticId: string | null = null
  if ('capeId' in opts) {
    const cape = getCape(ctx, opts.capeId)
    if (!cape || cape.kind !== 'builtin' || cape.retired) throw notFound('cape_not_found', 'Cape not found')
    if (cape.unlock === 'free') throw badRequest('cape_is_free', 'This cape is free for everyone; no code needed')
    capeId = cape.id
  } else {
    const c = getCosmetic(ctx, opts.cosmeticId)
    if (!c || c.kind !== 'builtin' || c.retired) throw notFound('cosmetic_not_found', 'Cosmetic not found')
    if (c.unlock === 'free') throw badRequest('cosmetic_is_free', 'This cosmetic is free for everyone; no code needed')
    cosmeticId = c.id
  }
  const expires = opts.expiresAt ? Date.parse(opts.expiresAt) : null
  if (expires !== null && expires <= ctx.now()) throw badRequest('invalid_request', 'expiresAt must be in the future')
  const t = ctx.now()
  const out: (CodeView & { code: string })[] = []
  tx(ctx.db, () => {
    for (let i = 0; i < opts.count; i++) {
      const code = newRedeemCode()
      const normalized = normalizeRedeemCode(code)!
      const row = one<CodeRow>(
        ctx.db,
        `INSERT INTO codes (code_hash, hint, cape_id, cosmetic_id, max_uses, uses, expires_at, revoked_at, note, created_at, created_by)
         VALUES (?, ?, ?, ?, ?, 0, ?, NULL, ?, ?, ?) RETURNING *`,
        hashCode(ctx, normalized), normalized.slice(-4), capeId, cosmeticId, opts.maxUses, expires, opts.note ?? null, t, actor,
      )!
      out.push({ ...codeView(row), code })
    }
  })
  return { codes: out }
}

export function listCodes(ctx: AppContext): CodeView[] {
  return all<CodeRow>(ctx.db, 'SELECT * FROM codes ORDER BY id DESC LIMIT 1000').map(codeView)
}

export function revokeCode(ctx: AppContext, id: number): void {
  const n = run(ctx.db, 'UPDATE codes SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL', ctx.now(), id)
  if (n === 0 && !one(ctx.db, 'SELECT 1 AS x FROM codes WHERE id = ?', id)) throw notFound('code_not_found', 'Code not found')
}

export interface RedeemResult {
  /** Was der Code freischaltet: ein Umhang oder ein Kosmetik-Teil/Emote. */
  kind: 'cape' | 'cosmetic'
  cape: CapeView | null
  cosmetic: CosmeticView | null
  alreadyOwned: boolean
}

const invalid = () => new ApiError(404, 'invalid_code', 'This code is not valid')

/**
 * Löst einen Code ein. Fehlschläge werfen ApiError mit Status 404/410; das
 * Zählen der Fehlversuche (Brute-Force-Schutz) macht die Route.
 */
export function redeemCode(ctx: AppContext, uuid: string, normalized: string): RedeemResult {
  const t = ctx.now()
  return tx(ctx.db, () => {
    const row = one<CodeRow>(ctx.db, 'SELECT * FROM codes WHERE code_hash = ?', hashCode(ctx, normalized))
    if (!row || row.revoked_at !== null) throw invalid()
    const cape = row.cape_id !== null ? getCape(ctx, row.cape_id) : undefined
    const cosmetic = row.cosmetic_id !== null ? getCosmetic(ctx, row.cosmetic_id) : undefined
    if ((!cape || cape.retired) && (!cosmetic || cosmetic.retired)) throw invalid()
    if (row.expires_at !== null && row.expires_at <= t) throw new ApiError(410, 'code_expired', 'This code has expired')
    const grant = () => (cape ? grantCape(ctx, uuid, cape.id, 'code') : grantCosmetic(ctx, uuid, cosmetic!.id, 'code'))
    const result = (alreadyOwned: boolean): RedeemResult => ({
      kind: cape ? 'cape' : 'cosmetic',
      cape: cape ? capeView(ctx, cape) : null,
      cosmetic: cosmetic ? cosmeticView(ctx, cosmetic) : null,
      alreadyOwned,
    })
    const already = one(ctx.db, 'SELECT 1 AS x FROM code_redemptions WHERE code_id = ? AND uuid = ?', row.id, uuid)
    if (already || (cape ? canUse(ctx, uuid, cape) : canUseCosmetic(ctx, uuid, cosmetic!))) {
      // Schon freigeschaltet: Code wird nicht verbraucht.
      grant()
      return result(true)
    }
    const took = run(ctx.db, 'UPDATE codes SET uses = uses + 1 WHERE id = ? AND uses < max_uses', row.id)
    if (took === 0) throw new ApiError(410, 'code_used_up', 'This code has already been used')
    run(ctx.db, 'INSERT INTO code_redemptions (code_id, uuid, redeemed_at) VALUES (?, ?, ?)', row.id, uuid, t)
    grant()
    return result(false)
  })
}
