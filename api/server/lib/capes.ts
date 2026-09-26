import { mkdirSync, readFileSync, renameSync, rmSync, writeFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { badRequest, conflict, forbidden, notFound } from './errors'
import { newUploadCapeId, sha256Hex } from './ids'
import { holdsCape, notifyShareRemoved, shareHolders, shareRole, visibleHolderCount } from './capeshares'
import { emitCape } from './playerevents'
import { BUILTIN_MAX_SCALE, capeLayout, inspectPng, sanitizeCapeUpload } from './png'
import { assertNotSanctioned } from './sanctions'
import { isAdmin } from './users'

export type CapeUnlock = 'free' | 'code' | 'admin' | 'owner'
export type CapeStatus = 'approved' | 'pending' | 'rejected'

export interface CapeRow {
  id: string
  kind: 'builtin' | 'upload'
  name: string
  owner_uuid: string | null
  status: CapeStatus
  unlock: CapeUnlock
  sha256: string
  width: number
  height: number
  frames: number
  frame_time_ms: number | null
  sort: number
  retired: number
  created_at: number
  reviewed_at: number | null
  reviewed_by: string | null
  reject_reason: string | null
}

export interface CapeView {
  id: string
  name: string
  kind: 'builtin' | 'upload'
  unlock: CapeUnlock
  status: CapeStatus
  /** Absolute URL der Textur (animierte Umhänge: senkrechter Frame-Streifen). Ändert sich mit dem Inhalt. */
  url: string
  /** Maße EINES Frames (64·scale × 32·scale). */
  width: number
  height: number
  /** Auflösungsfaktor gegenüber dem Vanilla-Layout 64×32 (1–8, Uploads wie mitgelieferte). UVs = Vanilla-UV × scale. */
  scale: number
  animated: boolean
  frames: number
  frameTimeMs: number | null
}

export interface CatalogEntry extends CapeView {
  owned: boolean
  active: boolean
  rejectReason?: string | null
  /** An Freunde weitergebbar: eigener freigegebener Upload oder angenommener geteilter Umhang. */
  shareable: boolean
  /** Nur bei Umhängen, die dir ein Freund geteilt hat: von wem und wer ihn gemacht hat; sonst `null`. */
  shared: { from: { uuid: string, name: string }, creator: { uuid: string, name: string } } | null
  /** Inhaber, die du bei diesem Umhang siehst (als Ersteller alle, sonst dein Ast); 0 wenn nicht teilbar. */
  holders: number
}

export function capeUrl(ctx: AppContext, c: Pick<CapeRow, 'id' | 'sha256'>): string {
  return `${ctx.config.publicBaseUrl}/v1/capes/${c.id}.png?v=${c.sha256.slice(0, 12)}`
}

export function capeView(ctx: AppContext, c: CapeRow): CapeView {
  return {
    id: c.id,
    name: c.name,
    kind: c.kind,
    unlock: c.unlock,
    status: c.status,
    url: capeUrl(ctx, c),
    width: c.width,
    height: c.height,
    scale: Math.round(c.width / 64),
    animated: c.frames > 1,
    frames: c.frames,
    frameTimeMs: c.frames > 1 ? c.frame_time_ms : null,
  }
}

export function getCape(ctx: AppContext, id: string): CapeRow | undefined {
  return one<CapeRow>(ctx.db, 'SELECT * FROM capes WHERE id = ?', id)
}

export function writeAtomic(dir: string, file: string, data: Buffer): void {
  mkdirSync(dir, { recursive: true })
  const tmp = join(dir, `.${file}.${process.pid}.tmp`)
  writeFileSync(tmp, data, { mode: 0o640 })
  renameSync(tmp, join(dir, file))
}

// ---------------------------------------------------------------- Katalog

export interface BuiltinCape {
  id: string
  name: string
  unlock: 'free' | 'code' | 'admin'
  sort: number
  scale: number
  frames: number
  frameTimeMs: number | null
  png: Buffer
}

/**
 * Übernimmt die mitgelieferten Designs in DB + `<DATA_DIR>/capes`. Nicht mehr
 * enthaltene werden `retired` (bleiben für bestehende Nutzer sichtbar).
 */
export function seedBuiltins(ctx: AppContext, capes: BuiltinCape[]): void {
  const t = ctx.now()
  const ids = new Set<string>()
  for (const c of capes) {
    const { header } = inspectPng(c.png)
    const frames = c.frames
    if (frames < 1 || frames > 64 || header.height % frames !== 0) throw new Error(`builtin cape ${c.id}: bad frame count`)
    const frameH = header.height / frames
    const layout = capeLayout(header.width, frameH, BUILTIN_MAX_SCALE)
    if (!layout || layout.source !== 'full' || layout.scale !== c.scale) {
      throw new Error(`builtin cape ${c.id}: ${header.width}x${header.height} does not match scale ${c.scale} x ${frames} frames`)
    }
    if (frames > 1 && !c.frameTimeMs) throw new Error(`builtin cape ${c.id}: animated without frameTimeMs`)
    const sha = sha256Hex(c.png)
    const file = join(ctx.capeDir, `${c.id}.png`)
    if (!existsSync(file) || sha256Hex(readFileSync(file)) !== sha) writeAtomic(ctx.capeDir, `${c.id}.png`, c.png)
    run(
      ctx.db,
      `INSERT INTO capes (id, kind, name, owner_uuid, status, unlock, sha256, width, height, frames, frame_time_ms, sort, retired, created_at)
       VALUES (?, 'builtin', ?, NULL, 'approved', ?, ?, ?, ?, ?, ?, ?, 0, ?)
       ON CONFLICT(id) DO UPDATE SET name = excluded.name, unlock = excluded.unlock, sha256 = excluded.sha256,
         width = excluded.width, height = excluded.height, frames = excluded.frames,
         frame_time_ms = excluded.frame_time_ms, sort = excluded.sort, retired = 0
       WHERE capes.kind = 'builtin'`,
      c.id, c.name, c.unlock, sha, header.width, frameH, frames, frames > 1 ? c.frameTimeMs : null, c.sort, t,
    )
    ids.add(c.id)
  }
  for (const row of all<{ id: string }>(ctx.db, "SELECT id FROM capes WHERE kind = 'builtin' AND retired = 0")) {
    if (!ids.has(row.id)) run(ctx.db, 'UPDATE capes SET retired = 1 WHERE id = ?', row.id)
  }
}

/** Darf `uuid` diesen Umhang tragen? */
export function canUse(ctx: AppContext, uuid: string, c: CapeRow): boolean {
  if (c.kind === 'upload') {
    if (c.owner_uuid === uuid) return c.status !== 'rejected'
    // Geteilt: nur angenommen und solange der Umhang freigegeben ist.
    return c.status === 'approved' && holdsCape(ctx, uuid, c.id)
  }
  if (c.retired) return false
  if (c.unlock === 'free') return true
  if (isAdmin(ctx, uuid)) return true
  return one(ctx.db, 'SELECT 1 AS x FROM user_capes WHERE uuid = ? AND cape_id = ?', uuid, c.id) !== undefined
}

/**
 * Katalog aus Sicht eines Nutzers: alle Standard-Designs + eigene Uploads + Umhänge, die ihm
 * Freunde geteilt haben (angenommen, freigegeben). Reihenfolge: Standard, eigene, geteilte.
 */
export function catalog(ctx: AppContext, uuid: string): CatalogEntry[] {
  const active = one<{ active_cape_id: string | null }>(ctx.db, 'SELECT active_cape_id FROM users WHERE uuid = ?', uuid)
    ?.active_cape_id ?? null
  const rows = all<CapeRow & { s_from: string | null, from_name: string | null, creator_name: string | null }>(
    ctx.db,
    `SELECT c.*, s.granted_by AS s_from, f.name AS from_name, o.name AS creator_name FROM capes c
     LEFT JOIN cape_shares s ON s.cape_id = c.id AND s.holder_uuid = ? AND s.status = 'accepted'
     LEFT JOIN users f ON f.uuid = s.granted_by
     LEFT JOIN users o ON o.uuid = c.owner_uuid
     WHERE (c.kind = 'builtin' AND (c.retired = 0 OR c.id = ?))
        OR (c.kind = 'upload' AND c.owner_uuid = ?)
        OR (c.kind = 'upload' AND c.status = 'approved' AND s.holder_uuid IS NOT NULL)
     ORDER BY CASE WHEN c.kind = 'builtin' THEN 0 WHEN c.owner_uuid = ? THEN 1 ELSE 2 END, c.sort, c.created_at`,
    uuid, active, uuid, uuid,
  )
  return rows.map((c) => {
    const sharedWithMe = c.kind === 'upload' && c.owner_uuid !== uuid && c.s_from !== null
    const shareable = shareRole(ctx, uuid, c) !== null
    return {
      ...capeView(ctx, c),
      owned: canUse(ctx, uuid, c),
      active: c.id === active,
      ...(c.kind === 'upload' ? { rejectReason: sharedWithMe ? null : c.reject_reason } : {}),
      shareable,
      shared: sharedWithMe
        ? {
            from: { uuid: c.s_from!, name: c.from_name ?? c.s_from! },
            creator: { uuid: c.owner_uuid!, name: c.creator_name ?? c.owner_uuid! },
          }
        : null,
      holders: shareable ? visibleHolderCount(ctx, uuid, c) : 0,
    }
  })
}

export function setActiveCape(ctx: AppContext, uuid: string, capeId: string | null): CapeView | null {
  if (capeId === null) {
    if (run(ctx.db, 'UPDATE users SET active_cape_id = NULL WHERE uuid = ? AND active_cape_id IS NOT NULL', uuid) > 0) {
      emitCape(ctx, uuid)
    }
    return null
  }
  const c = getCape(ctx, capeId)
  if (!c || (c.kind === 'upload' && c.owner_uuid !== uuid && !holdsCape(ctx, uuid, c.id))) {
    throw notFound('cape_not_found', 'Cape not found')
  }
  if (!canUse(ctx, uuid, c)) throw forbidden('cape_locked', 'You have not unlocked this cape')
  const changed = run(
    ctx.db,
    'UPDATE users SET active_cape_id = ? WHERE uuid = ? AND (active_cape_id IS NULL OR active_cape_id <> ?)',
    c.id, uuid, c.id,
  )
  if (changed > 0) emitCape(ctx, uuid)
  return capeView(ctx, c)
}

/** Wer trägt diesen Umhang gerade? */
export function capeWearers(ctx: AppContext, capeId: string): string[] {
  return all<{ uuid: string }>(ctx.db, 'SELECT uuid FROM users WHERE active_cape_id = ?', capeId).map((r) => r.uuid)
}

// ---------------------------------------------------------------- Uploads

/**
 * Eigener Umhang (Status `pending`). Animiert = senkrechter Streifen wie bei den mitgelieferten;
 * dann ist `frameTimeMs` Pflicht. `frames` (optional) muss zur Datei passen.
 */
export function uploadCape(
  ctx: AppContext,
  uuid: string,
  body: Buffer,
  name: string | undefined,
  anim: { frames?: number, frameTimeMs?: number } = {},
): CapeView {
  assertNotSanctioned(ctx, uuid, 'upload_ban')
  const counts = one<{ total: number, pending: number }>(
    ctx.db,
    `SELECT COUNT(*) AS total, COALESCE(SUM(status = 'pending'), 0) AS pending
     FROM capes WHERE kind = 'upload' AND owner_uuid = ? AND status <> 'rejected'`,
    uuid,
  )!
  if (counts.pending >= ctx.config.limits.maxPendingUploadsPerUser) {
    throw conflict('too_many_pending', 'You already have the maximum number of capes waiting for review')
  }
  if (counts.total >= ctx.config.limits.maxUploadsPerUser) {
    throw conflict('upload_limit', 'You have reached the maximum number of uploaded capes; delete one first')
  }
  const clean = sanitizeCapeUpload(body, { frames: anim.frames })
  if (clean.frames > 1 && anim.frameTimeMs === undefined) {
    throw badRequest('frame_time_required', 'Animated capes need the frameTimeMs query parameter')
  }
  const frameTime = clean.frames > 1 ? anim.frameTimeMs! : null
  const sha = sha256Hex(clean.png)
  const dup = one<{ id: string }>(
    ctx.db,
    "SELECT id FROM capes WHERE kind = 'upload' AND owner_uuid = ? AND sha256 = ? AND status <> 'rejected'",
    uuid, sha,
  )
  if (dup) throw conflict('duplicate_cape', 'You already uploaded this cape')
  const id = newUploadCapeId()
  writeAtomic(ctx.capeDir, `${id}.png`, clean.png)
  try {
    run(
      ctx.db,
      `INSERT INTO capes (id, kind, name, owner_uuid, status, unlock, sha256, width, height, frames, frame_time_ms, sort, retired, created_at)
       VALUES (?, 'upload', ?, ?, 'pending', 'owner', ?, ?, ?, ?, ?, 0, 0, ?)`,
      id, name ?? 'Eigener Umhang', uuid, sha, clean.width, clean.height, clean.frames, frameTime, ctx.now(),
    )
  } catch (err) {
    rmSync(join(ctx.capeDir, `${id}.png`), { force: true })
    throw err
  }
  return capeView(ctx, getCape(ctx, id)!)
}

export function deleteOwnUpload(ctx: AppContext, uuid: string, capeId: string): void {
  const c = getCape(ctx, capeId)
  if (!c || c.kind !== 'upload' || c.owner_uuid !== uuid) throw notFound('cape_not_found', 'Cape not found')
  removeCape(ctx, c.id)
}

/**
 * Entfernt einen hochgeladenen Umhang samt Datei (aktive Auswahl wird per FK zurückgesetzt,
 * Teilungen per FK gelöscht; Inhaber bekommen `cape_share_removed`).
 */
export function removeCape(ctx: AppContext, capeId: string): void {
  const worn = capeWearers(ctx, capeId)
  const holders = shareHolders(ctx, capeId)
  run(ctx.db, 'DELETE FROM capes WHERE id = ?', capeId)
  rmSync(join(ctx.capeDir, `${capeId}.png`), { force: true })
  for (const u of worn) emitCape(ctx, u)
  notifyShareRemoved(ctx, capeId, holders)
}

export function reportCape(
  ctx: AppContext,
  reporter: string,
  capeId: string,
  reason: string,
  note: string | undefined,
): void {
  const c = getCape(ctx, capeId)
  // Melden kann man nur, was man sehen kann: freigegebene fremde Uploads.
  if (!c || c.kind !== 'upload' || c.status !== 'approved' || c.owner_uuid === reporter) {
    throw notFound('cape_not_found', 'Cape not found')
  }
  run(
    ctx.db,
    `INSERT INTO cape_reports (cape_id, reporter_uuid, reason, note, created_at) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(cape_id, reporter_uuid) DO UPDATE SET reason = excluded.reason, note = excluded.note`,
    c.id, reporter, reason, note ?? null, ctx.now(),
  )
}

// ---------------------------------------------------------------- Textur

export interface Texture {
  png: Buffer
  sha256: string
  /** Öffentlich cachebar (freigegeben) oder nur für Besitzer/Admins (wartend/abgelehnt). */
  public: boolean
}

export function readTexture(ctx: AppContext, capeId: string, viewer: { uuid: string, admin: boolean } | null): Texture {
  const c = getCape(ctx, capeId)
  if (!c) throw notFound('cape_not_found', 'Cape not found')
  const isPublic = c.status === 'approved'
  if (!isPublic && !(viewer && (viewer.admin || viewer.uuid === c.owner_uuid))) {
    throw notFound('cape_not_found', 'Cape not found')
  }
  let png: Buffer
  try {
    png = readFileSync(join(ctx.capeDir, `${c.id}.png`))
  } catch {
    // Datei fehlt (z. B. Volume teilweise wiederhergestellt) – für Clients wie „nicht vorhanden“.
    throw notFound('cape_not_found', 'Cape not found')
  }
  return { png, sha256: c.sha256, public: isPublic }
}

// ---------------------------------------------------------------- Zuteilung (Admin, Codes)

export function grantCape(ctx: AppContext, uuid: string, capeId: string, source: 'code' | 'admin'): boolean {
  return (
    run(
      ctx.db,
      'INSERT INTO user_capes (uuid, cape_id, source, granted_at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING',
      uuid, capeId, source, ctx.now(),
    ) > 0
  )
}

export function revokeCape(ctx: AppContext, uuid: string, capeId: string): boolean {
  const { n, off } = tx(ctx.db, () => ({
    n: run(ctx.db, 'DELETE FROM user_capes WHERE uuid = ? AND cape_id = ?', uuid, capeId),
    off: run(ctx.db, 'UPDATE users SET active_cape_id = NULL WHERE uuid = ? AND active_cape_id = ?', uuid, capeId),
  }))
  if (off > 0) emitCape(ctx, uuid)
  return n > 0
}
