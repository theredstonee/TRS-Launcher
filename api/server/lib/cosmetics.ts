import { existsSync, readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { writeAtomic } from './capes'
import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { EMOTE_BY_ID, EMOTES } from './emotes'
import { ApiError, badRequest, conflict, forbidden, notFound } from './errors'
import { newUploadCosmeticId, sha256Hex } from './ids'
import { emitCosmetics } from './playerevents'
import { decodeRgba, encodeRgba, inspectPng } from './png'
import { usedMask, WEARABLE_SLOTS, type CosmeticSlot, type Template, type WearableSlot } from './templates'
import { assertNotSanctioned } from './sanctions'
import { isAdmin } from './users'

export type CosmeticUnlock = 'free' | 'code' | 'admin' | 'owner'
export type CosmeticStatus = 'approved' | 'pending' | 'rejected'

/** Größte Upload-Datei (Streifen mit bis zu 16 Frames in scale 2). */
export const MAX_COSMETIC_UPLOAD_BYTES = 512 * 1024
/** Uploads: scale 1 oder 2, höchstens 16 Frames, Frame-Dauer ≥ 50 ms. */
export const MAX_UPLOAD_COSMETIC_SCALE = 2
export const MAX_UPLOAD_FRAMES = 16
export const MIN_UPLOAD_FRAME_TIME_MS = 50

export interface CosmeticRow {
  id: string
  kind: 'builtin' | 'upload'
  slot: CosmeticSlot
  template: string | null
  name: string
  owner_uuid: string | null
  status: CosmeticStatus
  unlock: CosmeticUnlock
  sha256: string | null
  width: number | null
  height: number | null
  scale: number | null
  frames: number
  frame_time_ms: number | null
  emissive: number
  sort: number
  retired: number
  created_at: number
  reviewed_at: number | null
  reviewed_by: string | null
  reject_reason: string | null
}

export interface CosmeticTexture {
  /** Absolute URL; `?v=` ändert sich mit dem Inhalt. */
  url: string
  /** Maße EINES Frames in Pixeln (= Vorlagen-Texturgröße × scale). */
  width: number
  height: number
  scale: number
  animated: boolean
  frames: number
  frameTimeMs: number | null
}

export interface CosmeticView {
  id: string
  name: string
  slot: CosmeticSlot
  kind: 'builtin' | 'upload'
  unlock: CosmeticUnlock
  status: CosmeticStatus
  /** Vorlagen-ID (`null` bei Emotes). */
  template: string | null
  texture: CosmeticTexture | null
  /** Ohne Weltlicht rendern (volle Helligkeit). Nur mitgelieferte Designs. */
  emissive: boolean
  emote: { durationMs: number, loop: boolean } | null
}

export interface CosmeticCatalogEntry extends CosmeticView {
  owned: boolean
  equipped: boolean
  rejectReason?: string | null
}

export type EquippedView = Record<WearableSlot, CosmeticView | null>

export function cosmeticUrl(ctx: AppContext, c: Pick<CosmeticRow, 'id' | 'sha256'>): string {
  return `${ctx.config.publicBaseUrl}/v1/cosmetics/${c.id}.png?v=${(c.sha256 ?? '').slice(0, 12)}`
}

export function cosmeticView(ctx: AppContext, c: CosmeticRow): CosmeticView {
  const emote = c.slot === 'emote' ? EMOTE_BY_ID.get(c.id) : undefined
  return {
    id: c.id,
    name: c.name,
    slot: c.slot,
    kind: c.kind,
    unlock: c.unlock,
    status: c.status,
    template: c.template,
    texture:
      c.sha256 && c.width && c.height && c.scale
        ? {
            url: cosmeticUrl(ctx, c),
            width: c.width,
            height: c.height,
            scale: c.scale,
            animated: c.frames > 1,
            frames: c.frames,
            frameTimeMs: c.frames > 1 ? c.frame_time_ms : null,
          }
        : null,
    emissive: c.emissive === 1,
    emote: emote ? { durationMs: emote.durationMs, loop: emote.loop } : null,
  }
}

export function getCosmetic(ctx: AppContext, id: string): CosmeticRow | undefined {
  return one<CosmeticRow>(ctx.db, 'SELECT * FROM cosmetics WHERE id = ?', id)
}

/** Vorlage bekannt (Emotes brauchen keine)? Kosmetik mit entfernter Vorlage wird nirgends ausgeliefert. */
export function renderable(ctx: AppContext, c: Pick<CosmeticRow, 'slot' | 'template'>): boolean {
  return c.slot === 'emote' || (c.template !== null && ctx.templates.get(c.template) !== undefined)
}

// ---------------------------------------------------------------- Katalog einspielen

export interface BuiltinCosmetic {
  id: string
  name: string
  template: string
  unlock: 'free' | 'code' | 'admin'
  sort: number
  scale: number
  frames: number
  frameTimeMs: number | null
  emissive: boolean
  png: Buffer
}

function assertNoSlotClash(ctx: AppContext, id: string, slot: CosmeticSlot): void {
  const existing = one<{ slot: CosmeticSlot, kind: string }>(ctx.db, 'SELECT slot, kind FROM cosmetics WHERE id = ?', id)
  if (existing && (existing.kind !== 'builtin' || (existing.slot === 'emote') !== (slot === 'emote'))) {
    throw new Error(`cosmetic id ${id} is already used by another item`)
  }
}

/** Mitgelieferte Designs → DB + `<DATA_DIR>/cosmetics`. Fehlende werden ausgemustert (Träger behalten sie). */
export function seedBuiltinCosmetics(ctx: AppContext, list: BuiltinCosmetic[]): void {
  const t = ctx.now()
  const ids = new Set<string>()
  for (const c of list) {
    const tpl = ctx.templates.get(c.template)
    if (!tpl) throw new Error(`builtin cosmetic ${c.id}: unknown template ${c.template}`)
    if (EMOTE_BY_ID.has(c.id)) throw new Error(`builtin cosmetic ${c.id}: id is an emote id`)
    const { header } = inspectPng(c.png)
    const w = tpl.textureWidth * c.scale
    const h = tpl.textureHeight * c.scale
    if (c.frames < 1 || c.frames > 64 || header.width !== w || header.height !== h * c.frames) {
      throw new Error(`builtin cosmetic ${c.id}: ${header.width}x${header.height} does not match ${w}x${h} x ${c.frames} frames`)
    }
    if (c.frames > 1 && !c.frameTimeMs) throw new Error(`builtin cosmetic ${c.id}: animated without frameTimeMs`)
    assertNoSlotClash(ctx, c.id, tpl.slot)
    const sha = sha256Hex(c.png)
    const file = join(ctx.cosmeticDir, `${c.id}.png`)
    if (!existsSync(file) || sha256Hex(readFileSync(file)) !== sha) writeAtomic(ctx.cosmeticDir, `${c.id}.png`, c.png)
    run(
      ctx.db,
      `INSERT INTO cosmetics (id, kind, slot, template, name, owner_uuid, status, unlock, sha256, width, height, scale,
         frames, frame_time_ms, emissive, sort, retired, created_at)
       VALUES (?, 'builtin', ?, ?, ?, NULL, 'approved', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
       ON CONFLICT(id) DO UPDATE SET slot = excluded.slot, template = excluded.template, name = excluded.name,
         unlock = excluded.unlock, sha256 = excluded.sha256, width = excluded.width, height = excluded.height,
         scale = excluded.scale, frames = excluded.frames, frame_time_ms = excluded.frame_time_ms,
         emissive = excluded.emissive, sort = excluded.sort, retired = 0
       WHERE cosmetics.kind = 'builtin'`,
      c.id, tpl.slot, tpl.id, c.name, c.unlock, sha, w, h, c.scale, c.frames, c.frames > 1 ? c.frameTimeMs : null,
      c.emissive ? 1 : 0, c.sort, t,
    )
    ids.add(c.id)
  }
  for (const row of all<{ id: string }>(ctx.db, "SELECT id FROM cosmetics WHERE kind = 'builtin' AND slot <> 'emote' AND retired = 0")) {
    if (!ids.has(row.id)) run(ctx.db, 'UPDATE cosmetics SET retired = 1 WHERE id = ?', row.id)
  }
}

/** Feste Emote-Liste → DB (damit Codes, Zuteilungen und Freischaltung wie bei Kosmetik funktionieren). */
export function seedEmotes(ctx: AppContext): void {
  const t = ctx.now()
  EMOTES.forEach((e, sort) => {
    assertNoSlotClash(ctx, e.id, 'emote')
    run(
      ctx.db,
      `INSERT INTO cosmetics (id, kind, slot, template, name, owner_uuid, status, unlock, frames, emissive, sort, retired, created_at)
       VALUES (?, 'builtin', 'emote', NULL, ?, NULL, 'approved', ?, 1, 0, ?, 0, ?)
       ON CONFLICT(id) DO UPDATE SET name = excluded.name, unlock = excluded.unlock, sort = excluded.sort, retired = 0
       WHERE cosmetics.kind = 'builtin'`,
      e.id, e.name, e.unlock, 1000 + sort, t,
    )
  })
  for (const row of all<{ id: string }>(ctx.db, "SELECT id FROM cosmetics WHERE slot = 'emote' AND retired = 0")) {
    if (!EMOTE_BY_ID.has(row.id)) run(ctx.db, 'UPDATE cosmetics SET retired = 1 WHERE id = ?', row.id)
  }
}

// ---------------------------------------------------------------- Besitz, Katalog, Ausrüsten

/** Darf `uuid` dieses Teil benutzen (tragen bzw. Emote abspielen)? */
export function canUseCosmetic(ctx: AppContext, uuid: string, c: CosmeticRow): boolean {
  if (!renderable(ctx, c)) return false
  if (c.kind === 'upload') return c.owner_uuid === uuid && c.status !== 'rejected'
  if (c.retired) return false
  if (c.unlock === 'free') return true
  if (isAdmin(ctx, uuid)) return true
  return one(ctx.db, 'SELECT 1 AS x FROM user_cosmetics WHERE uuid = ? AND cosmetic_id = ?', uuid, c.id) !== undefined
}

function equippedMap(ctx: AppContext, uuid: string): Map<WearableSlot, CosmeticRow> {
  const rows = all<CosmeticRow & { eq_slot: WearableSlot }>(
    ctx.db,
    `SELECT c.*, e.slot AS eq_slot FROM equipped_cosmetics e JOIN cosmetics c ON c.id = e.cosmetic_id WHERE e.uuid = ?`,
    uuid,
  )
  return new Map(rows.filter((r) => renderable(ctx, r)).map((r) => [r.eq_slot, r]))
}

/** Ausgerüstete Teile aus Sicht des Besitzers (auch wartende Uploads). */
export function equippedView(ctx: AppContext, uuid: string): EquippedView {
  const m = equippedMap(ctx, uuid)
  const out = {} as EquippedView
  for (const s of WEARABLE_SLOTS) {
    const r = m.get(s)
    out[s] = r ? cosmeticView(ctx, r) : null
  }
  return out
}

/** Katalog aus Sicht eines Nutzers: alle mitgelieferten Teile und Emotes + eigene Uploads. */
export function cosmeticCatalog(ctx: AppContext, uuid: string): CosmeticCatalogEntry[] {
  const equipped = new Set([...equippedMap(ctx, uuid).values()].map((r) => r.id))
  const rows = all<CosmeticRow>(
    ctx.db,
    `SELECT c.* FROM cosmetics c
     WHERE (c.kind = 'builtin' AND (c.retired = 0 OR EXISTS
              (SELECT 1 FROM equipped_cosmetics e WHERE e.uuid = ? AND e.cosmetic_id = c.id)))
        OR (c.kind = 'upload' AND c.owner_uuid = ?)
     ORDER BY c.kind = 'upload', c.sort, c.created_at`,
    uuid, uuid,
  )
  return rows
    .filter((c) => renderable(ctx, c))
    .map((c) => ({
      ...cosmeticView(ctx, c),
      owned: canUseCosmetic(ctx, uuid, c),
      equipped: equipped.has(c.id),
      ...(c.kind === 'upload' ? { rejectReason: c.reject_reason } : {}),
    }))
}

/** IDs der Emotes, die `uuid` abspielen darf (in Listenreihenfolge). */
export function ownedEmotes(ctx: AppContext, uuid: string): string[] {
  return all<CosmeticRow>(ctx.db, "SELECT * FROM cosmetics WHERE slot = 'emote' ORDER BY sort")
    .filter((c) => canUseCosmetic(ctx, uuid, c))
    .map((c) => c.id)
}

/**
 * Setzt die übergebenen Plätze (`null` = ablegen, fehlend = unverändert). Alles
 * wird vorher geprüft und dann in einer Transaktion geschrieben.
 */
export function equipCosmetics(
  ctx: AppContext,
  uuid: string,
  patch: Partial<Record<WearableSlot, string | null>>,
): EquippedView {
  const before = equippedMap(ctx, uuid)
  const plan: [WearableSlot, CosmeticRow | null][] = []
  for (const slot of WEARABLE_SLOTS) {
    const id = patch[slot]
    if (id === undefined) continue
    if (id === null) {
      plan.push([slot, null])
      continue
    }
    const c = getCosmetic(ctx, id)
    if (!c || (c.kind === 'upload' && c.owner_uuid !== uuid) || !renderable(ctx, c)) {
      throw notFound('cosmetic_not_found', `Cosmetic ${id} not found`)
    }
    if (c.slot !== slot) throw badRequest('wrong_slot', `Cosmetic ${id} belongs in slot ${c.slot}, not ${slot}`)
    if (!canUseCosmetic(ctx, uuid, c)) throw forbidden('cosmetic_locked', `You have not unlocked cosmetic ${id}`)
    plan.push([slot, c])
  }
  tx(ctx.db, () => {
    for (const [slot, c] of plan) {
      if (c === null) run(ctx.db, 'DELETE FROM equipped_cosmetics WHERE uuid = ? AND slot = ?', uuid, slot)
      else {
        run(
          ctx.db,
          `INSERT INTO equipped_cosmetics (uuid, slot, cosmetic_id) VALUES (?, ?, ?)
           ON CONFLICT(uuid, slot) DO UPDATE SET cosmetic_id = excluded.cosmetic_id`,
          uuid, slot, c.id,
        )
      }
    }
  })
  const changed = plan.some(([slot, c]) => (before.get(slot)?.id ?? null) !== (c?.id ?? null))
  if (changed) emitCosmetics(ctx, uuid)
  return equippedView(ctx, uuid)
}

// ---------------------------------------------------------------- Uploads

const bad = (code: string, message: string) => new ApiError(400, code, message)

export interface CleanCosmetic {
  png: Buffer
  scale: number
  frames: number
  /** Maße eines Frames. */
  width: number
  height: number
}

/**
 * Upload säubern: Struktur prüfen, Größe = Vorlage × scale (1–2), senkrechter
 * Streifen mit ≤ 16 Frames; alles außerhalb der benutzten Bereiche und
 * unsichtbare Pixel werden genullt, dann neu kodiert (keine Metadaten).
 */
export function sanitizeCosmeticUpload(buf: Buffer, t: Template): CleanCosmetic {
  if (buf.length > MAX_COSMETIC_UPLOAD_BYTES) throw new ApiError(413, 'payload_too_large', 'Cosmetic PNG must be at most 512 KB')
  const { header } = inspectPng(buf)
  let scale = 0
  for (let k = 1; k <= MAX_UPLOAD_COSMETIC_SCALE; k++) if (header.width === t.textureWidth * k) scale = k
  const frameH = t.textureHeight * scale
  const frames = scale ? header.height / frameH : 0
  if (!scale || !Number.isInteger(frames) || frames < 1 || frames > MAX_UPLOAD_FRAMES) {
    const sizes = [1, 2].map((k) => `${t.textureWidth * k}x${t.textureHeight * k}`).join(' or ')
    throw bad('invalid_dimensions', `Texture for template ${t.id} must be ${sizes} (animated: frames stacked vertically, at most ${MAX_UPLOAD_FRAMES})`)
  }
  const w = header.width
  const img = decodeRgba(buf, t.textureWidth * MAX_UPLOAD_COSMETIC_SCALE * t.textureHeight * MAX_UPLOAD_COSMETIC_SCALE * MAX_UPLOAD_FRAMES)
  const mask = usedMask(t, scale)
  const perFrame = w * frameH
  const out = Buffer.from(img.data)
  // Modelle werden mit Alpha-Test gerendert: Alpha wird hart auf 0 oder 255 gesetzt,
  // damit three.js und Minecraft (Cutout) exakt dasselbe zeigen. Partikel behalten weiches Alpha.
  const binary = t.kind === 'model'
  let visible = 0
  for (let p = 0; p < w * header.height; p++) {
    const i = p * 4
    const a = out[i + 3]!
    if (a === 0 || (binary && a < 128) || mask[p % perFrame] === 0) out.fill(0, i, i + 4)
    else {
      if (binary) out[i + 3] = 255
      visible++
    }
  }
  if (visible === 0) throw bad('empty_cosmetic', 'The used areas of the texture are fully transparent')
  return { png: encodeRgba(w, header.height, out), scale, frames, width: w, height: frameH }
}

export function uploadCosmetic(
  ctx: AppContext,
  uuid: string,
  body: Buffer,
  opts: { template: string, name?: string, frameTimeMs?: number },
): CosmeticView {
  assertNotSanctioned(ctx, uuid, 'upload_ban')
  const t = ctx.templates.get(opts.template)
  if (!t) throw badRequest('unknown_template', 'Unknown template')
  const counts = one<{ total: number, pending: number }>(
    ctx.db,
    `SELECT COUNT(*) AS total, COALESCE(SUM(status = 'pending'), 0) AS pending
     FROM cosmetics WHERE kind = 'upload' AND owner_uuid = ? AND status <> 'rejected'`,
    uuid,
  )!
  const lim = ctx.config.limits
  if (counts.pending >= lim.maxPendingCosmeticUploadsPerUser) {
    throw conflict('too_many_pending', 'You already have the maximum number of cosmetics waiting for review')
  }
  if (counts.total >= lim.maxCosmeticUploadsPerUser) {
    throw conflict('upload_limit', 'You have reached the maximum number of uploaded cosmetics; delete one first')
  }
  const clean = sanitizeCosmeticUpload(body, t)
  if (clean.frames > 1 && opts.frameTimeMs === undefined) {
    throw bad('frame_time_required', 'Animated textures need the frameTimeMs query parameter')
  }
  const sha = sha256Hex(clean.png)
  const dup = one<{ id: string }>(
    ctx.db,
    "SELECT id FROM cosmetics WHERE kind = 'upload' AND owner_uuid = ? AND sha256 = ? AND template = ? AND status <> 'rejected'",
    uuid, sha, t.id,
  )
  if (dup) throw conflict('duplicate_cosmetic', 'You already uploaded this texture')
  const id = newUploadCosmeticId()
  writeAtomic(ctx.cosmeticDir, `${id}.png`, clean.png)
  try {
    run(
      ctx.db,
      `INSERT INTO cosmetics (id, kind, slot, template, name, owner_uuid, status, unlock, sha256, width, height, scale,
         frames, frame_time_ms, emissive, sort, retired, created_at)
       VALUES (?, 'upload', ?, ?, ?, ?, 'pending', 'owner', ?, ?, ?, ?, ?, ?, 0, 0, 0, ?)`,
      id, t.slot, t.id, opts.name ?? `${t.name} (eigene)`, uuid, sha, clean.width, clean.height, clean.scale,
      clean.frames, clean.frames > 1 ? opts.frameTimeMs! : null, ctx.now(),
    )
  } catch (err) {
    rmSync(join(ctx.cosmeticDir, `${id}.png`), { force: true })
    throw err
  }
  return cosmeticView(ctx, getCosmetic(ctx, id)!)
}

/** Wer trägt dieses Teil gerade? (für Ereignisse vor dem Entfernen/Ablehnen) */
function wearers(ctx: AppContext, id: string): string[] {
  return all<{ uuid: string }>(ctx.db, 'SELECT uuid FROM equipped_cosmetics WHERE cosmetic_id = ?', id).map((r) => r.uuid)
}

export function deleteOwnCosmetic(ctx: AppContext, uuid: string, id: string): void {
  const c = getCosmetic(ctx, id)
  if (!c || c.kind !== 'upload' || c.owner_uuid !== uuid) throw notFound('cosmetic_not_found', 'Cosmetic not found')
  removeCosmetic(ctx, c.id)
}

/** Entfernt einen Upload samt Datei (Ausrüstung fällt per FK weg, Träger bekommen ein Ereignis). */
export function removeCosmetic(ctx: AppContext, id: string): void {
  const worn = wearers(ctx, id)
  run(ctx.db, 'DELETE FROM cosmetics WHERE id = ?', id)
  rmSync(join(ctx.cosmeticDir, `${id}.png`), { force: true })
  for (const u of worn) emitCosmetics(ctx, u)
}

export function reportCosmetic(ctx: AppContext, reporter: string, id: string, reason: string, note: string | undefined): void {
  const c = getCosmetic(ctx, id)
  if (!c || c.kind !== 'upload' || c.status !== 'approved' || c.owner_uuid === reporter) {
    throw notFound('cosmetic_not_found', 'Cosmetic not found')
  }
  run(
    ctx.db,
    `INSERT INTO cosmetic_reports (cosmetic_id, reporter_uuid, reason, note, created_at) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(cosmetic_id, reporter_uuid) DO UPDATE SET reason = excluded.reason, note = excluded.note`,
    c.id, reporter, reason, note ?? null, ctx.now(),
  )
}

// ---------------------------------------------------------------- Textur

export interface CosmeticTextureFile {
  png: Buffer
  sha256: string
  public: boolean
}

/** Sichtbar für alle, wenn freigegeben; wartende/abgelehnte nur für Besitzer und Admins. */
export function visibleTo(c: CosmeticRow, viewer: { uuid: string, admin: boolean } | null): boolean {
  return c.status === 'approved' || !!(viewer && (viewer.admin || viewer.uuid === c.owner_uuid))
}

export function readCosmeticTexture(
  ctx: AppContext,
  id: string,
  viewer: { uuid: string, admin: boolean } | null,
): CosmeticTextureFile {
  const c = getCosmetic(ctx, id)
  if (!c || !c.sha256 || !visibleTo(c, viewer)) throw notFound('cosmetic_not_found', 'Cosmetic not found')
  let png: Buffer
  try {
    png = readFileSync(join(ctx.cosmeticDir, `${c.id}.png`))
  } catch {
    throw notFound('cosmetic_not_found', 'Cosmetic not found')
  }
  return { png, sha256: c.sha256, public: c.status === 'approved' }
}

// ---------------------------------------------------------------- Zuteilung (Admin, Codes)

export function grantCosmetic(ctx: AppContext, uuid: string, id: string, source: 'code' | 'admin'): boolean {
  return (
    run(
      ctx.db,
      'INSERT INTO user_cosmetics (uuid, cosmetic_id, source, granted_at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING',
      uuid, id, source, ctx.now(),
    ) > 0
  )
}

/** Zuteilung entziehen; ist das Teil ausgerüstet, wird es abgelegt. */
export function revokeCosmetic(ctx: AppContext, uuid: string, id: string): boolean {
  const { n, unequipped } = tx(ctx.db, () => ({
    n: run(ctx.db, 'DELETE FROM user_cosmetics WHERE uuid = ? AND cosmetic_id = ?', uuid, id),
    unequipped: run(ctx.db, 'DELETE FROM equipped_cosmetics WHERE uuid = ? AND cosmetic_id = ?', uuid, id),
  }))
  if (unequipped > 0) emitCosmetics(ctx, uuid)
  return n > 0
}

// ---------------------------------------------------------------- Moderation (Admin)

export function uploadOr404(ctx: AppContext, id: string): CosmeticRow {
  const c = getCosmetic(ctx, id)
  if (!c || c.kind !== 'upload') throw notFound('cosmetic_not_found', 'Cosmetic not found')
  return c
}

export function setReviewStatus(
  ctx: AppContext,
  actor: string,
  id: string,
  status: 'approved' | 'rejected',
  reason: string | undefined,
): CosmeticView {
  const c = uploadOr404(ctx, id)
  const worn = wearers(ctx, c.id)
  tx(ctx.db, () => {
    run(
      ctx.db,
      'UPDATE cosmetics SET status = ?, reviewed_at = ?, reviewed_by = ?, reject_reason = ? WHERE id = ?',
      status, ctx.now(), actor, status === 'rejected' ? (reason ?? null) : null, c.id,
    )
    if (status === 'approved') run(ctx.db, 'DELETE FROM cosmetic_reports WHERE cosmetic_id = ?', c.id)
    else run(ctx.db, 'DELETE FROM equipped_cosmetics WHERE cosmetic_id = ?', c.id)
  })
  // Freigabe: andere sehen das Teil ab jetzt. Ablehnung: es wurde abgelegt.
  for (const u of worn) emitCosmetics(ctx, u)
  return cosmeticView(ctx, getCosmetic(ctx, c.id)!)
}
