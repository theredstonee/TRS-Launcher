import { randomBytes } from 'node:crypto'
import { readdirSync, readFileSync, rmSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { writeFileAtomic } from './attachments'
import type { AppContext } from './context'
import { all, one, placeholders, run, tx } from './db'
import { ApiError, notFound } from './errors'
import { sha256Hex } from './ids'
import { processImage, type ImageLimits, type OutputMime } from './images'
import { assertNotSanctioned } from './sanctions'

/**
 * Geteilte Screenshots (§23): ein Bild wird hochgeladen, neu kodiert (keine Metadaten) und ist dann
 * 30 Tage lang für jeden mit dem Link sichtbar (`/s/<id>`). Danach löscht der Sweep es.
 *
 * Datensparsam: öffentlich sind nur Bild, Maße und Zeitpunkte – nie Name oder UUID des Besitzers.
 * Die ID ist 128 Bit Zufall (base64url, 22 Zeichen) → nicht zu erraten.
 * Dateien liegen unverschlüsselt in `<DATA_DIR>/shares/<xx>/<id>.<jpg|png>` (+ `.t.` für die Vorschau):
 * der Inhalt ist ohnehin öffentlich.
 */

export const SHARE_ID = /^[A-Za-z0-9_-]{22}$/
export const MAX_SHARE_BYTES = 10 * 1024 * 1024

export const SHARE_IMAGE_LIMITS: ImageLimits = {
  maxBytes: MAX_SHARE_BYTES,
  maxOutputEdge: 4096,
  quality: 90,
  thumbEdge: 480,
  thumbQuality: 75,
}

/** Gleichzeitig laufende Neukodierungen (große Bilder blockieren den Event-Loop kurz). */
const MAX_PARALLEL = 2
let running = 0

export interface ShareRow {
  id: string
  owner_uuid: string
  mime: OutputMime
  width: number
  height: number
  bytes: number
  thumb_mime: OutputMime
  thumb_width: number
  thumb_height: number
  thumb_bytes: number
  sha256: string
  created_at: number
  expires_at: number
}

/** Öffentliche Sicht – ohne Besitzer. */
export interface PublicShareView {
  id: string
  url: string
  imageUrl: string
  mime: OutputMime
  width: number
  height: number
  createdAt: string
  expiresAt: string
}

/** Sicht des Besitzers. */
export interface ShareView extends PublicShareView {
  thumbUrl: string
  bytes: number
}

export interface ShareLimitsView {
  active: number
  maxActive: number
  uploadsToday: number
  maxPerDay: number
}

const iso = (t: number) => new Date(t).toISOString()
const DAY = 24 * 60 * 60 * 1000

export function newShareId(): string {
  return randomBytes(16).toString('base64url')
}

function ext(mime: OutputMime): string {
  return mime === 'image/png' ? 'png' : 'jpg'
}

function fileOf(ctx: AppContext, row: Pick<ShareRow, 'id' | 'mime' | 'thumb_mime'>, thumb: boolean): string {
  return join(ctx.shareDir, row.id.slice(0, 2), `${row.id}${thumb ? '.t' : ''}.${ext(thumb ? row.thumb_mime : row.mime)}`)
}

export function publicShareView(ctx: AppContext, r: ShareRow): PublicShareView {
  const site = ctx.config.siteUrl
  return {
    id: r.id,
    url: `${site}/s/${r.id}`,
    imageUrl: `${site}/v1/shares/${r.id}/image`,
    mime: r.mime,
    width: r.width,
    height: r.height,
    createdAt: iso(r.created_at),
    expiresAt: iso(r.expires_at),
  }
}

export function shareView(ctx: AppContext, r: ShareRow): ShareView {
  const v = publicShareView(ctx, r)
  return { ...v, thumbUrl: `${v.imageUrl}?thumb=1`, bytes: r.bytes }
}

/** Aktiver (nicht abgelaufener) Link oder `undefined`. */
export function getShare(ctx: AppContext, id: string): ShareRow | undefined {
  if (!SHARE_ID.test(id)) return undefined
  return one<ShareRow>(ctx.db, 'SELECT * FROM shared_images WHERE id = ? AND expires_at > ?', id, ctx.now())
}

export function shareStorageUsed(ctx: AppContext): number {
  return one<{ n: number | null }>(ctx.db, 'SELECT SUM(bytes + thumb_bytes) AS n FROM shared_images')!.n ?? 0
}

export function shareLimits(ctx: AppContext, uuid: string): ShareLimitsView {
  const lim = ctx.config.limits
  const t = ctx.now()
  const active = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM shared_images WHERE owner_uuid = ? AND expires_at > ?', uuid, t)!.n
  const uploadsToday = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM shared_image_uploads WHERE uuid = ? AND at > ?', uuid, t - DAY)!.n
  return { active, maxActive: lim.maxActiveShares, uploadsToday, maxPerDay: lim.maxSharesPerDay }
}

/** Wirft, wenn gerade kein weiterer Link erlaubt ist (vor und nach dem Neukodieren geprüft). */
function assertCanShare(ctx: AppContext, uuid: string): void {
  const l = shareLimits(ctx, uuid)
  if (l.active >= l.maxActive) {
    throw new ApiError(409, 'shared_image_limit', `You can have at most ${l.maxActive} shared images – delete old links first`, { max: l.maxActive })
  }
  if (l.uploadsToday >= l.maxPerDay) {
    const oldest = one<{ at: number }>(
      ctx.db, 'SELECT at FROM shared_image_uploads WHERE uuid = ? AND at > ? ORDER BY at LIMIT 1', uuid, ctx.now() - DAY,
    )
    const retry = Math.max(1, Math.ceil(((oldest?.at ?? ctx.now()) + DAY - ctx.now()) / 1000))
    throw new ApiError(429, 'share_daily_limit', `At most ${l.maxPerDay} shared images per day`, { retryAfter: retry, max: l.maxPerDay }, {
      'Retry-After': String(retry),
    })
  }
}

/** Bild prüfen, neu kodieren, ablegen. Upload-Sperre (Moderation v2) greift. */
export async function uploadShare(ctx: AppContext, uuid: string, body: Buffer, contentType: string): Promise<ShareView> {
  assertNotSanctioned(ctx, uuid, 'upload_ban')
  assertCanShare(ctx, uuid)
  if (shareStorageUsed(ctx) >= ctx.config.shareStorageMaxBytes) {
    console.warn('[trs-api] shared image storage is full (SHARE_STORAGE_MAX_MB)')
    throw new ApiError(507, 'storage_full', 'Image storage is full right now, try again later')
  }
  if (running >= MAX_PARALLEL) {
    throw new ApiError(503, 'busy', 'The server is busy processing images, try again in a few seconds', { retryAfter: 3 }, { 'Retry-After': '3' })
  }
  running++
  let img: Awaited<ReturnType<typeof processImage>>
  try {
    img = await processImage(body, contentType, SHARE_IMAGE_LIMITS)
  } finally {
    running--
  }
  const t = ctx.now()
  const row: ShareRow = {
    id: newShareId(),
    owner_uuid: uuid,
    mime: img.full.mime,
    width: img.full.width,
    height: img.full.height,
    bytes: img.full.data.length,
    thumb_mime: img.thumb.mime,
    thumb_width: img.thumb.width,
    thumb_height: img.thumb.height,
    thumb_bytes: img.thumb.data.length,
    sha256: sha256Hex(img.full.data),
    created_at: t,
    expires_at: t + ctx.config.limits.shareTtlMs,
  }
  // Während des Neukodierens könnten parallele Uploads die Grenzen erreicht haben.
  assertNotSanctioned(ctx, uuid, 'upload_ban')
  assertCanShare(ctx, uuid)
  writeFileAtomic(fileOf(ctx, row, false), img.full.data)
  writeFileAtomic(fileOf(ctx, row, true), img.thumb.data)
  try {
    tx(ctx.db, () => {
      run(
        ctx.db,
        `INSERT INTO shared_images (id, owner_uuid, mime, width, height, bytes, thumb_mime, thumb_width, thumb_height, thumb_bytes,
           sha256, created_at, expires_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        row.id, row.owner_uuid, row.mime, row.width, row.height, row.bytes, row.thumb_mime, row.thumb_width, row.thumb_height,
        row.thumb_bytes, row.sha256, row.created_at, row.expires_at,
      )
      run(ctx.db, 'INSERT INTO shared_image_uploads (uuid, at) VALUES (?, ?)', uuid, t)
    })
  } catch (err) {
    removeShareFiles(ctx, [row])
    throw err
  }
  return shareView(ctx, row)
}

export function listShares(ctx: AppContext, uuid: string): { shares: ShareView[], limits: ShareLimitsView } {
  const rows = all<ShareRow>(
    ctx.db, 'SELECT * FROM shared_images WHERE owner_uuid = ? AND expires_at > ? ORDER BY created_at DESC, id', uuid, ctx.now(),
  )
  return { shares: rows.map((r) => shareView(ctx, r)), limits: shareLimits(ctx, uuid) }
}

/** Eigenen Link löschen. Fremde und unbekannte → 404 (keine Auskunft, ob es ihn gibt). */
export function deleteShare(ctx: AppContext, uuid: string, id: string): void {
  const r = getShare(ctx, id)
  if (!r || r.owner_uuid !== uuid) throw notFound('share_not_found', 'Shared image not found')
  removeShares(ctx, [r])
}

/** Moderation: Link löschen (egal wem er gehört). Rückgabe: gelöschte Zeile oder `null`. */
export function adminDeleteShare(ctx: AppContext, id: string): ShareRow | null {
  const r = SHARE_ID.test(id) ? one<ShareRow>(ctx.db, 'SELECT * FROM shared_images WHERE id = ?', id) : undefined
  if (!r) return null
  removeShares(ctx, [r])
  return r
}

function removeShares(ctx: AppContext, rows: ShareRow[]): void {
  if (rows.length === 0) return
  run(ctx.db, `DELETE FROM shared_images WHERE id IN (${placeholders(rows.length)})`, ...rows.map((r) => r.id))
  removeShareFiles(ctx, rows)
}

export function removeShareFiles(ctx: AppContext, rows: Pick<ShareRow, 'id' | 'mime' | 'thumb_mime'>[]): void {
  for (const r of rows) {
    rmSync(fileOf(ctx, r, false), { force: true })
    rmSync(fileOf(ctx, r, true), { force: true })
  }
}

export function readShareImage(ctx: AppContext, r: ShareRow, thumb: boolean): Buffer {
  return readFileSync(fileOf(ctx, r, thumb))
}

/** Für die Kontolöschung: Zeilen verschwinden per FK, die Dateien danach mit {@link removeShareFiles}. */
export function sharesOf(ctx: AppContext, uuid: string): ShareRow[] {
  return all<ShareRow>(ctx.db, 'SELECT * FROM shared_images WHERE owner_uuid = ?', uuid)
}

/** Abgelaufene Links löschen (Zeilen + Dateien) und das Upload-Protokoll kürzen. */
export function sweepExpiredShares(ctx: AppContext): number {
  const t = ctx.now()
  const rows = all<ShareRow>(ctx.db, 'SELECT * FROM shared_images WHERE expires_at <= ? LIMIT 500', t)
  removeShares(ctx, rows)
  run(ctx.db, 'DELETE FROM shared_image_uploads WHERE at <= ?', t - DAY)
  return rows.length
}

/** Dateien ohne Zeile (Absturz zwischen Schreiben und Eintragen), älter als eine Stunde. */
export function sweepOrphanShareFiles(ctx: AppContext): number {
  let removed = 0
  const cutoff = Date.now() - 60 * 60_000
  let dirs: string[]
  try {
    dirs = readdirSync(ctx.shareDir)
  } catch {
    return 0
  }
  for (const d of dirs) {
    let files: string[]
    try {
      files = readdirSync(join(ctx.shareDir, d))
    } catch {
      continue
    }
    for (const f of files) {
      const m = /^([A-Za-z0-9_-]{22})(?:\.t)?\.(?:jpg|png)$/.exec(f)
      if (m && one(ctx.db, 'SELECT 1 AS x FROM shared_images WHERE id = ?', m[1]!) !== undefined) continue
      const full = join(ctx.shareDir, d, f)
      try {
        if (statSync(full).mtimeMs > cutoff) continue
        rmSync(full, { force: true })
        removed++
      } catch {
        // weiter
      }
    }
  }
  return removed
}
