import { randomBytes } from 'node:crypto'
import { mkdirSync, readdirSync, readFileSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import type { AppContext } from './context'
import { all, one, placeholders, run } from './db'
import { ApiError } from './errors'
import { sha256Hex } from './ids'
import { processChatImage, type OutputMime } from './images'

/**
 * Chat-Bilder auf der Platte: `<DATA_DIR>/chat/<xx>/<id>.bin` (Bild) und `<id>.t.bin` (Vorschau),
 * beide AES-GCM-verschlüsselt (AAD `att:<id>` bzw. `att:<id>:t`). Metadaten in `chat_attachments`.
 * Ablauf: hochladen (`POST /v1/chat/attachments`) → ID in einer Nachricht verwenden. Nicht
 * verwendete Uploads verfallen nach `pendingAttachmentTtlMs`.
 */

export const ATTACHMENT_ID = /^a[0-9a-f]{24}$/

export interface AttachmentRow {
  id: string
  uploader_uuid: string
  message_id: string | null
  position: number | null
  mime: OutputMime
  width: number
  height: number
  bytes: number
  thumb_mime: OutputMime
  thumb_width: number
  thumb_height: number
  thumb_bytes: number
  sha256: string
  key_id: string
  created_at: number
}

export interface AttachmentView {
  id: string
  mime: OutputMime
  width: number
  height: number
  bytes: number
  /** Pfad relativ zur API-Basis; Bearer-Auth nötig. */
  path: string
  thumb: { mime: OutputMime, width: number, height: number, bytes: number, path: string }
}

export function newAttachmentId(): string {
  return `a${randomBytes(12).toString('hex')}`
}

export function attachmentView(r: AttachmentRow): AttachmentView {
  return {
    id: r.id,
    mime: r.mime,
    width: r.width,
    height: r.height,
    bytes: r.bytes,
    path: `/v1/chat/attachments/${r.id}`,
    thumb: { mime: r.thumb_mime, width: r.thumb_width, height: r.thumb_height, bytes: r.thumb_bytes, path: `/v1/chat/attachments/${r.id}?thumb=1` },
  }
}

function fileOf(ctx: AppContext, id: string, thumb: boolean): string {
  return join(ctx.chatDir, id.slice(1, 3), `${id}${thumb ? '.t' : ''}.bin`)
}

function evidenceFile(ctx: AppContext, reportId: string, attachmentId: string): string {
  return join(ctx.chatDir, 'evidence', `${reportId}-${attachmentId}.bin`)
}

/** Atomar schreiben (tmp + rename). */
export function writeFileAtomic(path: string, data: Buffer): void {
  mkdirSync(join(path, '..'), { recursive: true })
  const tmp = `${path}.${randomBytes(4).toString('hex')}.tmp`
  writeFileSync(tmp, data, { mode: 0o640 })
  renameSync(tmp, path)
}

/** Belegter Platz aller Chat-Bilder + Beweis-Kopien (Bytes der Klartexte, grob = Platte). */
export function chatStorageUsed(ctx: AppContext): number {
  const a = one<{ n: number | null }>(ctx.db, 'SELECT SUM(bytes + thumb_bytes) AS n FROM chat_attachments')!.n ?? 0
  const e = one<{ n: number | null }>(ctx.db, 'SELECT SUM(bytes) AS n FROM chat_evidence_files')!.n ?? 0
  return a + e
}

export function userStorageUsed(ctx: AppContext, uuid: string): number {
  return one<{ n: number | null }>(ctx.db, 'SELECT SUM(bytes + thumb_bytes) AS n FROM chat_attachments WHERE uploader_uuid = ?', uuid)!.n ?? 0
}

/** Bild prüfen, neu kodieren, verschlüsselt ablegen. Noch keiner Nachricht zugeordnet. */
export async function uploadAttachment(ctx: AppContext, uuid: string, body: Buffer, contentType: string): Promise<AttachmentView> {
  const lim = ctx.config.limits
  const pending = one<{ n: number }>(
    ctx.db, 'SELECT COUNT(*) AS n FROM chat_attachments WHERE uploader_uuid = ? AND message_id IS NULL', uuid,
  )!.n
  if (pending >= lim.maxAttachmentsPerMessage * 3) throw new ApiError(409, 'too_many_pending_attachments', 'Send or wait for your pending images first')
  if (userStorageUsed(ctx, uuid) >= lim.maxAttachmentBytesPerUser) {
    throw new ApiError(409, 'storage_quota', 'Your image storage is full – delete old images first')
  }
  if (chatStorageUsed(ctx) >= ctx.config.chatStorageMaxBytes) {
    console.warn('[trs-api] chat image storage is full (CHAT_STORAGE_MAX_MB)')
    throw new ApiError(507, 'storage_full', 'Image storage is full right now, try again later')
  }
  const img = await processChatImage(body, contentType)
  const id = newAttachmentId()
  const kid = ctx.cipher.activeId
  writeFileAtomic(fileOf(ctx, id, false), ctx.cipher.encrypt(img.full.data, `att:${id}`))
  writeFileAtomic(fileOf(ctx, id, true), ctx.cipher.encrypt(img.thumb.data, `att:${id}:t`))
  run(
    ctx.db,
    `INSERT INTO chat_attachments (id, uploader_uuid, message_id, position, mime, width, height, bytes,
       thumb_mime, thumb_width, thumb_height, thumb_bytes, sha256, key_id, created_at)
     VALUES (?, ?, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    id, uuid, img.full.mime, img.full.width, img.full.height, img.full.data.length,
    img.thumb.mime, img.thumb.width, img.thumb.height, img.thumb.data.length, sha256Hex(img.full.data), kid, ctx.now(),
  )
  return attachmentView(getAttachment(ctx, id)!)
}

export function getAttachment(ctx: AppContext, id: string): AttachmentRow | undefined {
  return one<AttachmentRow>(ctx.db, 'SELECT * FROM chat_attachments WHERE id = ?', id)
}

export function attachmentsOf(ctx: AppContext, messageIds: string[]): Map<string, AttachmentRow[]> {
  const out = new Map<string, AttachmentRow[]>()
  if (messageIds.length === 0) return out
  for (const r of all<AttachmentRow>(
    ctx.db,
    `SELECT * FROM chat_attachments WHERE message_id IN (${placeholders(messageIds.length)}) ORDER BY position`,
    ...messageIds,
  )) {
    const list = out.get(r.message_id!) ?? []
    list.push(r)
    out.set(r.message_id!, list)
  }
  return out
}

/** Entschlüsselte Bytes (Bild oder Vorschau). */
export function readAttachment(ctx: AppContext, row: AttachmentRow, thumb: boolean): Buffer {
  return ctx.cipher.decrypt(readFileSync(fileOf(ctx, row.id, thumb)), `att:${row.id}${thumb ? ':t' : ''}`)
}

export function removeAttachmentFiles(ctx: AppContext, ids: Iterable<string>): void {
  for (const id of ids) {
    rmSync(fileOf(ctx, id, false), { force: true })
    rmSync(fileOf(ctx, id, true), { force: true })
  }
}

/** Nicht verwendete Uploads löschen (Zeilen + Dateien). */
export function sweepPendingAttachments(ctx: AppContext): number {
  const cutoff = ctx.now() - ctx.config.limits.pendingAttachmentTtlMs
  const ids = all<{ id: string }>(ctx.db, 'SELECT id FROM chat_attachments WHERE message_id IS NULL AND created_at < ?', cutoff).map((r) => r.id)
  if (ids.length === 0) return 0
  run(ctx.db, `DELETE FROM chat_attachments WHERE id IN (${placeholders(ids.length)})`, ...ids)
  removeAttachmentFiles(ctx, ids)
  return ids.length
}

// ---------------------------------------------------------------- Beweis-Kopien (Meldungen)

/** Kopiert ein Bild in die Beweise einer Meldung (neu verschlüsselt, AAD `ev:<report>:<att>`). */
export function copyToEvidence(ctx: AppContext, reportId: string, row: AttachmentRow): void {
  const plain = readAttachment(ctx, row, false)
  writeFileAtomic(evidenceFile(ctx, reportId, row.id), ctx.cipher.encrypt(plain, `ev:${reportId}:${row.id}`))
  run(
    ctx.db,
    `INSERT OR IGNORE INTO chat_evidence_files (report_id, attachment_id, mime, width, height, bytes, key_id)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
    reportId, row.id, row.mime, row.width, row.height, row.bytes, ctx.cipher.activeId,
  )
}

export function readEvidenceFile(ctx: AppContext, reportId: string, attachmentId: string): { mime: string, data: Buffer } | null {
  const r = one<{ mime: string }>(
    ctx.db, 'SELECT mime FROM chat_evidence_files WHERE report_id = ? AND attachment_id = ?', reportId, attachmentId,
  )
  if (!r) return null
  try {
    return { mime: r.mime, data: ctx.cipher.decrypt(readFileSync(evidenceFile(ctx, reportId, attachmentId)), `ev:${reportId}:${attachmentId}`) }
  } catch {
    return null
  }
}

export function removeEvidenceFiles(ctx: AppContext, reportId: string): void {
  for (const r of all<{ attachment_id: string }>(ctx.db, 'SELECT attachment_id FROM chat_evidence_files WHERE report_id = ?', reportId)) {
    rmSync(evidenceFile(ctx, reportId, r.attachment_id), { force: true })
  }
  run(ctx.db, 'DELETE FROM chat_evidence_files WHERE report_id = ?', reportId)
}

// ---------------------------------------------------------------- Schlüsseltausch + Aufräumen

/** Verschlüsselt bis zu `max` Bilder mit altem Schlüssel neu. Rückgabe: Anzahl. */
export function rotateAttachmentKeys(ctx: AppContext, max: number): number {
  const kid = ctx.cipher.activeId
  let n = 0
  for (const r of all<AttachmentRow>(ctx.db, 'SELECT * FROM chat_attachments WHERE key_id <> ? LIMIT ?', kid, max)) {
    try {
      for (const thumb of [false, true]) {
        const plain = readAttachment(ctx, r, thumb)
        writeFileAtomic(fileOf(ctx, r.id, thumb), ctx.cipher.encrypt(plain, `att:${r.id}${thumb ? ':t' : ''}`))
      }
      run(ctx.db, 'UPDATE chat_attachments SET key_id = ? WHERE id = ?', kid, r.id)
      n++
    } catch (err) {
      console.error(`[trs-api] could not re-encrypt attachment ${r.id}`, err)
    }
  }
  for (const r of all<{ report_id: string, attachment_id: string }>(
    ctx.db, 'SELECT report_id, attachment_id FROM chat_evidence_files WHERE key_id <> ? LIMIT ?', kid, max,
  )) {
    const f = readEvidenceFile(ctx, r.report_id, r.attachment_id)
    if (!f) continue
    writeFileAtomic(evidenceFile(ctx, r.report_id, r.attachment_id), ctx.cipher.encrypt(f.data, `ev:${r.report_id}:${r.attachment_id}`))
    run(ctx.db, 'UPDATE chat_evidence_files SET key_id = ? WHERE report_id = ? AND attachment_id = ?', kid, r.report_id, r.attachment_id)
    n++
  }
  return n
}

/**
 * Sicherheitsnetz: Dateien ohne Datenbankzeile (z. B. nach Absturz zwischen Schreiben und
 * Eintragen) löschen – nur solche, die älter als eine Stunde sind.
 */
export function sweepOrphanFiles(ctx: AppContext): number {
  let removed = 0
  const cutoff = Date.now() - 60 * 60_000
  let dirs: string[]
  try {
    dirs = readdirSync(ctx.chatDir)
  } catch {
    return 0
  }
  for (const d of dirs) {
    const dir = join(ctx.chatDir, d)
    let files: string[]
    try {
      files = readdirSync(dir)
    } catch {
      continue
    }
    for (const f of files) {
      const full = join(dir, f)
      let known: boolean
      if (d === 'evidence') {
        const m = /^(r[0-9a-f]{16})-(a[0-9a-f]{24})\.bin$/.exec(f)
        known = !!m && one(ctx.db, 'SELECT 1 AS x FROM chat_evidence_files WHERE report_id = ? AND attachment_id = ?', m[1]!, m[2]!) !== undefined
      } else {
        const m = /^(a[0-9a-f]{24})(?:\.t)?\.bin$/.exec(f)
        known = !!m && getAttachment(ctx, m[1]!) !== undefined
      }
      if (known) continue
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
