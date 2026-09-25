import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { ApiError, badRequest, conflict, notFound } from './errors'
import { sha256Hex } from './ids'
import { sanitizeSkinUpload } from './png'

/**
 * TRS-Sync: eigene Skins, eigene Presets, Theme/Akzent/Sprache sowie TRS-Client-Einstellungen und Garderobe je Konto.
 * Skins liegen als BLOB in SQLite (klein, neu kodiert) – so sind Bild, Metadaten und Grabstein
 * immer in derselben Transaktion, und Kontolöschung (ON DELETE CASCADE) sowie Sicherungen
 * von `data/` erfassen alles ohne verwaiste Dateien.
 */

export const MAX_SYNC_SKINS = 60
/** Grabsteine gelöschter Skins bleiben 30 Tage, damit andere Geräte die Löschung übernehmen. */
export const TOMBSTONE_TTL_MS = 30 * 24 * 60 * 60 * 1000
/** Höchstens so viele Grabsteine je Konto (die ältesten fallen weg). */
export const MAX_TOMBSTONES = 500
/** `data` von Presets, serialisiert. */
export const MAX_PRESETS_BYTES = 64 * 1024
/** `data` der Client-Dokumente (TRS-Client-Einstellungen, Garderobe), serialisiert. */
export const MAX_CLIENT_DOC_BYTES = 64 * 1024
/** Body-Grenzen der Sync-Routen: Base64 von 128 KiB ≈ 171 KiB + Name; Presets 64 KiB + Spielraum für Leerraum. */
export const SYNC_SKIN_BODY_LIMIT = 192 * 1024
export const SYNC_PRESETS_BODY_LIMIT = 96 * 1024
/** Uhren der Clients dürfen so weit vorgehen; alles darüber ist kaputt und würde jeden Abgleich gewinnen. */
export const MAX_CLOCK_SKEW_MS = 24 * 60 * 60 * 1000

export type SkinVariant = 'classic' | 'slim'
export type SyncDocKind = 'presets' | 'settings' | 'client' | 'wardrobe'

/** Größengrenze je Dokument-Art (`settings` ist per Schema auf drei kurze Werte begrenzt). */
const DOC_LIMITS: Partial<Record<SyncDocKind, { bytes: number, label: string }>> = {
  presets: { bytes: MAX_PRESETS_BYTES, label: 'Presets' },
  client: { bytes: MAX_CLIENT_DOC_BYTES, label: 'Client settings' },
  wardrobe: { bytes: MAX_CLIENT_DOC_BYTES, label: 'Wardrobe' },
}

interface SkinRow {
  id: string
  name: string
  variant: SkinVariant
  sha256: string
  updated_at: number
}

export interface SyncSkinView {
  id: string
  name: string
  variant: SkinVariant
  sha256: string
  updatedAt: string
}

export interface SyncDocView {
  data: Record<string, unknown>
  updatedAt: string
}

export interface SyncOverview {
  skins: SyncSkinView[]
  deletedSkins: { id: string, deletedAt: string }[]
  presets: SyncDocView | null
  settings: SyncDocView | null
  /** TRS-Client: Module, HUD, Tasten, Einführung/NEW-Stand (vom Client festgelegt). */
  client: SyncDocView | null
  /** TRS-Client-Garderobe: Outfits, Favoriten (vom Client festgelegt). */
  wardrobe: SyncDocView | null
}

const iso = (ms: number) => new Date(ms).toISOString()

function skinView(r: SkinRow): SyncSkinView {
  return { id: r.id, name: r.name, variant: r.variant, sha256: r.sha256, updatedAt: iso(r.updated_at) }
}

const SKIN_COLUMNS = 'id, name, variant, sha256, updated_at'

function getSkinRow(ctx: AppContext, uuid: string, id: string): SkinRow | undefined {
  return one<SkinRow>(ctx.db, `SELECT ${SKIN_COLUMNS} FROM sync_skins WHERE uuid = ? AND id = ?`, uuid, id)
}

function getDoc(ctx: AppContext, uuid: string, kind: SyncDocKind): SyncDocView | null {
  const r = one<{ data: string, updated_at: number }>(
    ctx.db,
    'SELECT data, updated_at FROM sync_docs WHERE uuid = ? AND kind = ?',
    uuid, kind,
  )
  return r ? { data: JSON.parse(r.data) as Record<string, unknown>, updatedAt: iso(r.updated_at) } : null
}

/** `GET /v1/me/sync` – alles außer den Bildern. */
export function syncOverview(ctx: AppContext, uuid: string): SyncOverview {
  const skins = all<SkinRow>(ctx.db, `SELECT ${SKIN_COLUMNS} FROM sync_skins WHERE uuid = ? ORDER BY updated_at, id`, uuid)
  const tombstones = all<{ id: string, deleted_at: number }>(
    ctx.db,
    'SELECT id, deleted_at FROM sync_skin_tombstones WHERE uuid = ? AND deleted_at > ? ORDER BY deleted_at, id',
    uuid, ctx.now() - TOMBSTONE_TTL_MS,
  )
  return {
    skins: skins.map(skinView),
    deletedSkins: tombstones.map((t) => ({ id: t.id, deletedAt: iso(t.deleted_at) })),
    presets: getDoc(ctx, uuid, 'presets'),
    settings: getDoc(ctx, uuid, 'settings'),
    client: getDoc(ctx, uuid, 'client'),
    wardrobe: getDoc(ctx, uuid, 'wardrobe'),
  }
}

/** Base64 aus dem JSON-Body → PNG-Bytes (Format prüft schon das Schema). */
export function decodeBase64Png(b64: string): Buffer {
  const buf = Buffer.from(b64, 'base64')
  if (buf.length === 0) throw badRequest('invalid_png', 'Skin PNG is empty')
  return buf
}

/** Legt einen Skin an oder ersetzt ihn (idempotent); entfernt einen Grabstein mit gleicher ID. */
export function putSkin(
  ctx: AppContext,
  uuid: string,
  id: string,
  input: { name: string, variant: SkinVariant, png: Buffer },
): SyncSkinView {
  const { png } = sanitizeSkinUpload(input.png)
  const sha = sha256Hex(png)
  const t = ctx.now()
  return tx(ctx.db, () => {
    if (!getSkinRow(ctx, uuid, id)) {
      const n = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM sync_skins WHERE uuid = ?', uuid)!.n
      if (n >= MAX_SYNC_SKINS) throw conflict('skin_limit', `At most ${MAX_SYNC_SKINS} skins can be synced`)
    }
    run(
      ctx.db,
      `INSERT INTO sync_skins (uuid, id, name, variant, png, sha256, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(uuid, id) DO UPDATE SET name = excluded.name, variant = excluded.variant, png = excluded.png,
         sha256 = excluded.sha256, updated_at = excluded.updated_at`,
      uuid, id, input.name, input.variant, png, sha, t,
    )
    run(ctx.db, 'DELETE FROM sync_skin_tombstones WHERE uuid = ? AND id = ?', uuid, id)
    return skinView(getSkinRow(ctx, uuid, id)!)
  })
}

/** Umbenennen / Variante ändern ohne neues Bild. */
export function patchSkin(
  ctx: AppContext,
  uuid: string,
  id: string,
  patch: { name?: string, variant?: SkinVariant },
): SyncSkinView {
  const cur = getSkinRow(ctx, uuid, id)
  if (!cur) throw notFound('skin_not_found', 'Skin not found')
  run(
    ctx.db,
    'UPDATE sync_skins SET name = ?, variant = ?, updated_at = ? WHERE uuid = ? AND id = ?',
    patch.name ?? cur.name, patch.variant ?? cur.variant, ctx.now(), uuid, id,
  )
  return skinView(getSkinRow(ctx, uuid, id)!)
}

/** Löscht den Skin (falls vorhanden) und legt immer einen Grabstein an. */
export function deleteSkin(ctx: AppContext, uuid: string, id: string): void {
  const t = ctx.now()
  tx(ctx.db, () => {
    run(ctx.db, 'DELETE FROM sync_skins WHERE uuid = ? AND id = ?', uuid, id)
    run(
      ctx.db,
      `INSERT INTO sync_skin_tombstones (uuid, id, deleted_at) VALUES (?, ?, ?)
       ON CONFLICT(uuid, id) DO UPDATE SET deleted_at = excluded.deleted_at`,
      uuid, id, t,
    )
    run(
      ctx.db,
      `DELETE FROM sync_skin_tombstones WHERE uuid = ? AND id NOT IN (
         SELECT id FROM sync_skin_tombstones WHERE uuid = ? ORDER BY deleted_at DESC, id LIMIT ?)`,
      uuid, uuid, MAX_TOMBSTONES,
    )
  })
}

/** Bild eines eigenen Skins (nur für das eigene Konto). */
export function readSkinPng(ctx: AppContext, uuid: string, id: string): { png: Buffer, sha256: string } {
  const r = one<{ png: Uint8Array, sha256: string }>(
    ctx.db,
    'SELECT png, sha256 FROM sync_skins WHERE uuid = ? AND id = ?',
    uuid, id,
  )
  if (!r) throw notFound('skin_not_found', 'Skin not found')
  return { png: Buffer.from(r.png), sha256: r.sha256 }
}

/** ISO-Zeit des Clients → ms; weit in der Zukunft liegende Uhren werden abgewiesen. */
export function clientTime(ctx: AppContext, isoTime: string): number {
  const ms = Date.parse(isoTime)
  if (!Number.isFinite(ms) || ms > ctx.now() + MAX_CLOCK_SKEW_MS) {
    throw badRequest('invalid_request', 'Request validation failed', {
      fields: [{ path: 'updatedAt', message: 'must not be more than 24 hours in the future' }],
    })
  }
  return ms
}

/**
 * Presets bzw. Einstellungen schreiben: letzter Schreiber gewinnt nach `updatedAt`.
 * Ist der gespeicherte Stand neuer → 409 `stale` mit `current`. Gleich alt = überschreiben (Wiederholung).
 */
export function putDoc(ctx: AppContext, uuid: string, kind: SyncDocKind, data: Record<string, unknown>, updatedAt: number): SyncDocView {
  const json = JSON.stringify(data)
  const limit = DOC_LIMITS[kind]
  if (limit && Buffer.byteLength(json, 'utf8') > limit.bytes) {
    throw new ApiError(413, 'payload_too_large', `${limit.label} must be at most 64 KB`)
  }
  return tx(ctx.db, () => {
    const cur = one<{ updated_at: number }>(ctx.db, 'SELECT updated_at FROM sync_docs WHERE uuid = ? AND kind = ?', uuid, kind)
    if (cur && cur.updated_at > updatedAt) {
      throw new ApiError(409, 'stale', 'A newer version is stored on the server', { current: getDoc(ctx, uuid, kind) })
    }
    run(
      ctx.db,
      `INSERT INTO sync_docs (uuid, kind, data, updated_at) VALUES (?, ?, ?, ?)
       ON CONFLICT(uuid, kind) DO UPDATE SET data = excluded.data, updated_at = excluded.updated_at`,
      uuid, kind, json, updatedAt,
    )
    return { data: JSON.parse(json) as Record<string, unknown>, updatedAt: iso(updatedAt) }
  })
}

/** Abgelaufene Grabsteine (älter als 30 Tage) entfernen – läuft mit `sweepExpired`. */
export function sweepSyncTombstones(ctx: AppContext): number {
  return run(ctx.db, 'DELETE FROM sync_skin_tombstones WHERE deleted_at <= ?', ctx.now() - TOMBSTONE_TTL_MS)
}
