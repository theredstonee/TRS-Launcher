import { createHash, randomBytes } from 'node:crypto'
import { closeSync, existsSync, fsyncSync, mkdirSync, openSync, readSync, readdirSync, rmSync, statSync, writeSync } from 'node:fs'
import { join } from 'node:path'
import { z } from 'zod'
import { writeFileAtomic } from './attachments'
import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { ApiError, badRequest, conflict, notFound } from './errors'
import { safeEqual, sha256Hex } from './ids'
import { inspectPackFile } from './packfile'
import { newPackId, packStorageUsed } from './packs'
import { assertNotSanctioned } from './sanctions'

/**
 * Wiederaufnehmbarer Upload großer Modpacks (§27.7). Cloudflare nimmt höchstens 100 MB in einem Request an,
 * ein Pack darf bis `PACK_MAX_MB` (Vorgabe 1 GB) groß sein. Der Client schickt 32-MiB-Stücke; der Server legt sie
 * unter `<DATA_DIR>/packs/tmp/<id>/` ab, setzt sie beim Abschluss zusammen, prüft SHA-256, Größe und das
 * `.mrpack` und gibt ein `uploadToken` zurück. Damit legt `POST /v1/packs` bzw. `PUT /v1/packs/{id}/file` das Pack an.
 *
 * Eine Sitzung gilt 24 Stunden. Höchstens drei offene Uploads je Konto. Abgelaufene Sitzungen räumt der Sweep ab.
 */

/** Antwort von `POST /v1/packs/uploads` – Clients müssen `chunkSize` daraus nehmen, nicht raten. */
export const PACK_CHUNK_BYTES = 33_554_432
/** Ein Request-Body (alter Launcher) bleibt unter der Cloudflare-Grenze. Größeres geht nur in Stücken. */
export const PACK_SINGLE_MAX_BYTES = 100 * 1024 * 1024
const UPLOAD_TTL_MS = 24 * 60 * 60 * 1000
const MAX_OPEN = 3
const MAX_CHUNKS = 4096

export const PACK_UPLOAD_TOKEN = /^up_[A-Za-z0-9_-]{43}$/

export const packUploadBody = z.strictObject({
  size: z.number().int().positive(),
  sha256: z.string().regex(/^[0-9a-fA-F]{64}$/).transform((s) => s.toLowerCase()),
  name: z.string().trim().min(1).max(64).optional(),
})

export interface PackUploadCreated {
  uploadId: string
  chunkSize: number
  expiresAt: string
}

export interface PackUploadStatus {
  received: number[]
  size: number
  chunkSize: number
}

interface Session {
  id: string
  owner_uuid: string
  size: number
  sha256: string
  name: string | null
  chunk_size: number
  created_at: number
  expires_at: number
  completed_at: number | null
  token_hash: string | null
  consumed_at: number | null
}

interface ChunkRow {
  idx: number
  sha256: string
  bytes: number
}

const iso = (t: number) => new Date(t).toISOString()

function tmpDir(ctx: AppContext, id: string): string {
  return join(ctx.packDir, 'tmp', id)
}

function chunkPath(ctx: AppContext, id: string, index: number): string {
  return join(tmpDir(ctx, id), String(index))
}

function assembledPath(ctx: AppContext, id: string): string {
  return join(tmpDir(ctx, id), 'assembled.mrpack')
}

function chunkCount(size: number, chunkSize: number): number {
  return Math.ceil(size / chunkSize)
}

function expectedLength(size: number, chunkSize: number, index: number): number {
  const count = chunkCount(size, chunkSize)
  if (!Number.isInteger(index) || index < 0 || index >= count) {
    throw badRequest('invalid_chunk', 'Chunk index is out of range')
  }
  return index === count - 1 ? size - index * chunkSize : chunkSize
}

function requireSession(ctx: AppContext, uuid: string, id: string): Session {
  if (!/^[A-Za-z0-9_-]{22}$/.test(id)) throw notFound('upload_not_found', 'Upload not found')
  const s = one<Session>(ctx.db, 'SELECT * FROM pack_upload_sessions WHERE id = ?', id)
  if (!s || s.owner_uuid !== uuid || s.consumed_at !== null) throw notFound('upload_not_found', 'Upload not found')
  if (s.expires_at <= ctx.now()) throw new ApiError(410, 'upload_expired', 'This upload has expired')
  return s
}

function hashFile(path: string): string {
  const h = createHash('sha256')
  const fd = openSync(path, 'r')
  const buf = Buffer.alloc(1024 * 1024)
  try {
    while (true) {
      const n = readSync(fd, buf, 0, buf.length, null)
      if (n <= 0) break
      h.update(buf.subarray(0, n))
    }
  } finally {
    closeSync(fd)
  }
  return h.digest('hex')
}

/** Neue Upload-Sitzung. Gleiche Datei noch einmal anmelden legt eine weitere Sitzung an (höchstens drei). */
export function createPackUpload(ctx: AppContext, uuid: string, input: { size: number, sha256: string, name?: string }): PackUploadCreated {
  assertNotSanctioned(ctx, uuid, 'upload_ban')
  const size = input.size
  const sha = input.sha256.trim().toLowerCase()
  if (!Number.isSafeInteger(size) || size < 1 || !/^[0-9a-f]{64}$/.test(sha)) {
    throw badRequest('invalid_request', 'Request validation failed')
  }
  let name: string | null = null
  if (input.name !== undefined) {
    name = input.name.trim()
    // eslint-disable-next-line no-control-regex
    if (name.length < 1 || name.length > 64 || /[\x00-\x1f\x7f]/.test(name)) throw badRequest('invalid_request', 'Request validation failed')
  }
  if (size > ctx.config.packMaxBytes) throw new ApiError(413, 'payload_too_large', 'The modpack is too large')
  const chunkSize = ctx.config.packChunkBytes
  if (chunkCount(size, chunkSize) > MAX_CHUNKS) throw badRequest('invalid_request', 'The file needs too many chunks')
  const t = ctx.now()
  const open = one<{ n: number }>(
    ctx.db, 'SELECT COUNT(*) AS n FROM pack_upload_sessions WHERE owner_uuid = ? AND expires_at > ?', uuid, t,
  )!.n
  if (open >= MAX_OPEN) {
    throw new ApiError(409, 'upload_limit', 'Finish or wait for your current uploads first', { max: MAX_OPEN })
  }
  const pending = one<{ n: number | null }>(ctx.db, 'SELECT SUM(size) AS n FROM pack_upload_sessions WHERE expires_at > ?', t)!.n ?? 0
  if (packStorageUsed(ctx) + pending + size > ctx.config.packStorageMaxBytes) {
    console.warn('[trs-api] shared modpack storage is full (PACK_STORAGE_MAX_MB)')
    throw new ApiError(507, 'storage_full', 'Modpack storage is full right now, try again later')
  }
  const id = newPackId()
  const expires = t + UPLOAD_TTL_MS
  const dir = tmpDir(ctx, id)
  mkdirSync(dir, { recursive: true })
  try {
    run(
      ctx.db,
      `INSERT INTO pack_upload_sessions (id, owner_uuid, size, sha256, name, chunk_size, created_at, expires_at, completed_at, token_hash, consumed_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)`,
      id, uuid, size, sha, name, chunkSize, t, expires,
    )
  } catch (err) {
    rmSync(dir, { recursive: true, force: true })
    throw err
  }
  return { uploadId: id, chunkSize, expiresAt: iso(expires) }
}

/** Wie viele Bytes dieses Stück haben muss – die Route liest höchstens so viele. */
export function chunkByteLimit(ctx: AppContext, uuid: string, uploadId: string, index: number): number {
  const s = requireSession(ctx, uuid, uploadId)
  if (s.completed_at !== null) throw conflict('upload_closed', 'This upload is already complete')
  return expectedLength(s.size, s.chunk_size, index)
}

/** Ein Stück speichern. Dasselbe Stück noch einmal (gleiche Prüfsumme) ist in Ordnung. */
export function putPackChunk(ctx: AppContext, uuid: string, uploadId: string, index: number, body: Buffer, chunkSha256: string): void {
  const s = requireSession(ctx, uuid, uploadId)
  if (s.completed_at !== null) throw conflict('upload_closed', 'This upload is already complete')
  const expect = expectedLength(s.size, s.chunk_size, index)
  if (body.length > s.chunk_size || body.length > expect) throw new ApiError(413, 'payload_too_large', 'Chunk is too large')
  if (body.length !== expect) throw badRequest('invalid_chunk', 'Chunk has the wrong size')
  const declared = chunkSha256.trim().toLowerCase()
  if (!/^[0-9a-f]{64}$/.test(declared)) throw badRequest('invalid_request', 'X-Chunk-Sha256 must be 64 hex characters')
  const got = sha256Hex(body)
  if (!safeEqual(got, declared)) throw new ApiError(422, 'checksum_mismatch', 'Chunk checksum does not match')
  const path = chunkPath(ctx, s.id, index)
  let write = true
  tx(ctx.db, () => {
    const prev = one<ChunkRow>(ctx.db, 'SELECT idx, sha256, bytes FROM pack_upload_chunks WHERE upload_id = ? AND idx = ?', s.id, index)
    if (prev) {
      if (prev.sha256 !== got || prev.bytes !== body.length) {
        throw conflict('chunk_conflict', 'This chunk was already uploaded with different bytes')
      }
      write = !existsSync(path) || statSync(path).size !== body.length
      return
    }
    run(ctx.db, 'INSERT INTO pack_upload_chunks (upload_id, idx, sha256, bytes) VALUES (?, ?, ?, ?)', s.id, index, got, body.length)
  })
  if (write) writeFileAtomic(path, body)
}

export function packUploadStatus(ctx: AppContext, uuid: string, uploadId: string): PackUploadStatus {
  const s = requireSession(ctx, uuid, uploadId)
  const rows = all<{ idx: number }>(ctx.db, 'SELECT idx FROM pack_upload_chunks WHERE upload_id = ? ORDER BY idx', s.id)
  return { received: rows.map((r) => r.idx), size: s.size, chunkSize: s.chunk_size }
}

function dropSession(ctx: AppContext, id: string): void {
  rmSync(tmpDir(ctx, id), { recursive: true, force: true })
  run(ctx.db, 'DELETE FROM pack_upload_sessions WHERE id = ?', id)
}

/**
 * Stücke zusammensetzen, SHA-256 und Größe prüfen, als `.mrpack` prüfen. Das Token gilt bis die Sitzung abläuft
 * und wird nur einmal zurückgegeben.
 */
export function completePackUpload(ctx: AppContext, uuid: string, uploadId: string): { uploadToken: string } {
  const s = requireSession(ctx, uuid, uploadId)
  if (s.completed_at !== null) throw conflict('upload_closed', 'This upload is already complete')
  const count = chunkCount(s.size, s.chunk_size)
  const rows = all<ChunkRow>(ctx.db, 'SELECT idx, sha256, bytes FROM pack_upload_chunks WHERE upload_id = ? ORDER BY idx', s.id)
  const have = new Map(rows.map((r) => [r.idx, r]))
  const missing: number[] = []
  for (let i = 0; i < count; i++) {
    const row = have.get(i)
    const path = chunkPath(ctx, s.id, i)
    if (!row || !existsSync(path)) {
      if (row) run(ctx.db, 'DELETE FROM pack_upload_chunks WHERE upload_id = ? AND idx = ?', s.id, i)
      missing.push(i)
    }
  }
  if (missing.length > 0) {
    throw new ApiError(409, 'upload_incomplete', 'Upload is missing chunks', {
      missing: missing.slice(0, 32),
      missingCount: missing.length,
    })
  }
  const dest = assembledPath(ctx, s.id)
  let written = 0
  let digest = ''
  try {
    const out = openSync(dest, 'w', 0o640)
    const total = createHash('sha256')
    const buf = Buffer.alloc(1024 * 1024)
    try {
      for (let i = 0; i < count; i++) {
        const row = have.get(i)!
        const part = createHash('sha256')
        let partBytes = 0
        const inn = openSync(chunkPath(ctx, s.id, i), 'r')
        try {
          while (true) {
            const n = readSync(inn, buf, 0, buf.length, null)
            if (n <= 0) break
            const slice = buf.subarray(0, n)
            part.update(slice)
            total.update(slice)
            let off = 0
            while (off < n) {
              const w = writeSync(out, buf, off, n - off)
              if (w <= 0) throw new Error('short write')
              off += w
            }
            partBytes += n
          }
        } finally {
          closeSync(inn)
        }
        if (part.digest('hex') !== row.sha256 || partBytes !== row.bytes) {
          run(ctx.db, 'DELETE FROM pack_upload_chunks WHERE upload_id = ? AND idx = ?', s.id, i)
          throw new ApiError(409, 'upload_incomplete', 'Upload is missing chunks', { missing: [i], missingCount: 1 })
        }
        written += partBytes
      }
      fsyncSync(out)
    } finally {
      closeSync(out)
    }
    digest = total.digest('hex')
  } catch (err) {
    rmSync(dest, { force: true })
    if (err instanceof ApiError) throw err
    console.error('[trs-api] could not assemble modpack upload', (err as Error).message)
    throw new ApiError(500, 'internal_error', 'An internal error occurred')
  }
  if (written !== s.size || digest !== s.sha256) {
    rmSync(dest, { force: true })
    throw new ApiError(422, 'checksum_mismatch', 'The file checksum does not match')
  }
  try {
    inspectPackFile(dest, written)
  } catch (err) {
    dropSession(ctx, s.id)
    throw err
  }
  const token = `up_${randomBytes(32).toString('base64url')}`
  const n = run(
    ctx.db,
    'UPDATE pack_upload_sessions SET completed_at = ?, token_hash = ? WHERE id = ? AND completed_at IS NULL',
    ctx.now(), sha256Hex(token), s.id,
  )
  if (n !== 1) {
    rmSync(dest, { force: true })
    throw conflict('upload_closed', 'This upload is already complete')
  }
  return { uploadToken: token }
}

/** Fertige, noch nicht verbrauchte Datei. Prüft Größe und SHA-256 noch einmal. */
export function openCompletedUpload(ctx: AppContext, uuid: string, token: string): { id: string, path: string, size: number, sha256: string } {
  if (!PACK_UPLOAD_TOKEN.test(token)) throw notFound('upload_not_found', 'Upload not found')
  const s = one<Session>(ctx.db, 'SELECT * FROM pack_upload_sessions WHERE token_hash = ?', sha256Hex(token))
  if (!s || s.owner_uuid !== uuid || s.consumed_at !== null || s.completed_at === null) {
    throw notFound('upload_not_found', 'Upload not found')
  }
  if (s.expires_at <= ctx.now()) throw new ApiError(410, 'upload_expired', 'This upload has expired')
  const path = assembledPath(ctx, s.id)
  let size = -1
  try {
    size = statSync(path).size
  } catch {
    throw notFound('upload_not_found', 'Upload not found')
  }
  if (size !== s.size || hashFile(path) !== s.sha256) throw new ApiError(422, 'checksum_mismatch', 'The file checksum does not match')
  return { id: s.id, path, size, sha256: s.sha256 }
}

/** Sitzung verbrauchen (in der Transaktion, die das Pack anlegt). 0 Zeilen → schon verbraucht. */
export function takePackUpload(ctx: AppContext, id: string): void {
  const n = run(ctx.db, 'DELETE FROM pack_upload_sessions WHERE id = ? AND completed_at IS NOT NULL AND consumed_at IS NULL', id)
  if (n !== 1) throw conflict('upload_used', 'This upload was already used')
}

/** Ordner offener Uploads dieses Kontos (vor dem Löschen des Kontos merken, die Zeilen gehen per FK weg). */
export function packUploadTmpDirs(ctx: AppContext, uuid: string): string[] {
  return all<{ id: string }>(ctx.db, 'SELECT id FROM pack_upload_sessions WHERE owner_uuid = ?', uuid).map((r) => tmpDir(ctx, r.id))
}

/** Abgelaufene Sitzungen und Ordner ohne Zeile löschen. */
export function sweepExpiredUploads(ctx: AppContext): number {
  const t = ctx.now()
  const rows = all<{ id: string }>(ctx.db, 'SELECT id FROM pack_upload_sessions WHERE expires_at <= ?', t)
  for (const r of rows) rmSync(tmpDir(ctx, r.id), { recursive: true, force: true })
  const n = run(ctx.db, 'DELETE FROM pack_upload_sessions WHERE expires_at <= ?', t)
  const root = join(ctx.packDir, 'tmp')
  let names: string[]
  try {
    names = readdirSync(root)
  } catch {
    return n
  }
  let extra = 0
  for (const name of names) {
    if (one(ctx.db, 'SELECT 1 AS x FROM pack_upload_sessions WHERE id = ?', name)) continue
    rmSync(join(root, name), { recursive: true, force: true })
    extra++
  }
  return n + extra
}
