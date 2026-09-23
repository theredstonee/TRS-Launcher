import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { ApiError, badRequest, notFound } from './errors'
import { hmacHex, newRedeemCode, normalizeRedeemCode } from './ids'
import { capeView, getCape, grantCape, canUse, type CapeView } from './capes'

interface CodeRow {
  id: number
  code_hash: string
  hint: string
  cape_id: string
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
  capeId: string
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

export function createCodes(
  ctx: AppContext,
  actor: string,
  opts: { capeId: string, maxUses: number, count: number, expiresAt?: string, note?: string },
): { codes: (CodeView & { code: string })[] } {
  const cape = getCape(ctx, opts.capeId)
  if (!cape || cape.kind !== 'builtin' || cape.retired) throw notFound('cape_not_found', 'Cape not found')
  if (cape.unlock === 'free') throw badRequest('cape_is_free', 'This cape is free for everyone; no code needed')
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
        `INSERT INTO codes (code_hash, hint, cape_id, max_uses, uses, expires_at, revoked_at, note, created_at, created_by)
         VALUES (?, ?, ?, ?, 0, ?, NULL, ?, ?, ?) RETURNING *`,
        hashCode(ctx, normalized), normalized.slice(-4), cape.id, opts.maxUses, expires, opts.note ?? null, t, actor,
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
  cape: CapeView
  alreadyOwned: boolean
}

/**
 * Löst einen Code ein. Fehlschläge werfen ApiError mit Status 404/410; das
 * Zählen der Fehlversuche (Brute-Force-Schutz) macht die Route.
 */
export function redeemCode(ctx: AppContext, uuid: string, normalized: string): RedeemResult {
  const t = ctx.now()
  return tx(ctx.db, () => {
    const row = one<CodeRow>(ctx.db, 'SELECT * FROM codes WHERE code_hash = ?', hashCode(ctx, normalized))
    if (!row || row.revoked_at !== null) throw new ApiError(404, 'invalid_code', 'This code is not valid')
    const cape = getCape(ctx, row.cape_id)
    if (!cape || cape.retired) throw new ApiError(404, 'invalid_code', 'This code is not valid')
    if (row.expires_at !== null && row.expires_at <= t) throw new ApiError(410, 'code_expired', 'This code has expired')
    const already = one(ctx.db, 'SELECT 1 AS x FROM code_redemptions WHERE code_id = ? AND uuid = ?', row.id, uuid)
    if (already || canUse(ctx, uuid, cape)) {
      // Schon freigeschaltet: Code wird nicht verbraucht.
      grantCape(ctx, uuid, cape.id, 'code')
      return { cape: capeView(ctx, cape), alreadyOwned: true }
    }
    const took = run(ctx.db, 'UPDATE codes SET uses = uses + 1 WHERE id = ? AND uses < max_uses', row.id)
    if (took === 0) throw new ApiError(410, 'code_used_up', 'This code has already been used')
    run(ctx.db, 'INSERT INTO code_redemptions (code_id, uuid, redeemed_at) VALUES (?, ?, ?)', row.id, uuid, t)
    grantCape(ctx, uuid, cape.id, 'code')
    return { cape: capeView(ctx, cape), alreadyOwned: false }
  })
}
