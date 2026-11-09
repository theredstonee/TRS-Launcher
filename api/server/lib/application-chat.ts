import { createHash, randomBytes } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { basename, join } from 'node:path'
import { z } from 'zod'
import type { AppContext } from './context'
import { all, one, placeholders, run, tx } from './db'
import { badRequest, forbidden, notFound, tooLarge, unavailable } from './errors'
import { myApplications, notifyApplicant } from './applications'
import { scanApplicationFile } from './application-scanner'
import { ChatCipher } from './crypto'
import { sanitizeText, textLength } from './safety'
import { can, teamOf, type Staff } from './team'

export const MAX_APPLICATION_FILE_BYTES = 30 * 1024 * 1024
const MAX_FILES_PER_MESSAGE = 5
const MAX_PENDING_PER_APPLICATION = 10
const MAX_APPLICATION_STORAGE_BYTES = 2 * 1024 * 1024 * 1024
const PENDING_FILE_TTL_MS = 60 * 60 * 1000
const SCAN_RETRY_MS = 60 * 1000
const SCAN_LEASE_MS = 5 * 60 * 1000
const MAX_SCAN_ATTEMPTS = 5
const FILE_ID = /^f[0-9a-f]{24}$/

interface ApplicationAccess {
  id: string
  uuid: string
  status: string
  lang: 'de' | 'en' | 'es'
  contact_email: string | null
  isApplicant: boolean
}

interface ApplicationMessageRow {
  id: string
  application_id: string
  sender_uuid: string
  body: Uint8Array
  key_id: string
  created_at: number
}

interface ApplicationFileRow {
  id: string
  application_id: string
  message_id: string | null
  uploader_uuid: string
  file_name: string
  bytes: number
  sha256: string
  key_id: string
  scan_status: 'pending' | 'scanning' | 'clean' | 'infected' | 'failed' | 'released'
  scan_signature: string | null
  scan_attempts: number
  next_scan_at: number
  scan_started_at: number | null
  created_at: number
  scanned_at: number | null
}

export interface ApplicationFileView {
  id: string
  name: string
  bytes: number
  status: ApplicationFileRow['scan_status']
  signature: string | null
}

export interface ApplicationMessageView {
  id: string
  sender: { uuid: string, name: string | null }
  text: string
  createdAt: string
  files: ApplicationFileView[]
}

function access(ctx: AppContext, uuid: string, applicationId: string): ApplicationAccess {
  const app = one<Omit<ApplicationAccess, 'isApplicant'>>(
    ctx.db,
    'SELECT id, uuid, status, lang, contact_email FROM team_applications WHERE id = ?',
    applicationId,
  )
  if (!app) throw notFound('application_not_found', 'Application not found')
  if (app.uuid === uuid) return { ...app, isApplicant: true }
  const staff = teamOf(ctx, uuid)
  if (!staff || !can(staff, 'applications.view')) throw notFound('application_not_found', 'Application not found')
  return { ...app, isApplicant: false }
}

function filePath(ctx: AppContext, id: string): string {
  return join(ctx.applicationFileDir, id.slice(1, 3), `${id}.bin`)
}

function safeFileName(raw: string): string {
  const leaf = basename(raw.replaceAll('\\', '/')).normalize('NFC')
  const clean = [...leaf].filter((c) => c >= ' ' && c !== '\u007f' && c !== '\u202e').join('').trim().slice(0, 180)
  if (!clean || clean === '.' || clean === '..') throw badRequest('invalid_file_name', 'File name is invalid')
  return clean
}

export function uploadApplicationFile(ctx: AppContext, uuid: string, applicationId: string, rawName: string, data: Buffer): ApplicationFileView {
  if (!ctx.config.clamav) throw unavailable('clamav_unavailable', 'Application file scanning is not configured')
  access(ctx, uuid, applicationId)
  if (data.length === 0) throw badRequest('empty_file', 'File is empty')
  if (data.length > MAX_APPLICATION_FILE_BYTES) throw tooLarge()
  const used = one<{ bytes: number | null }>(ctx.db, 'SELECT SUM(bytes) AS bytes FROM team_application_files')?.bytes ?? 0
  if (used + data.length > MAX_APPLICATION_STORAGE_BYTES) throw badRequest('application_storage_full', 'Application file storage is full')
  const name = safeFileName(rawName)
  const pending = one<{ n: number }>(
    ctx.db,
    "SELECT COUNT(*) AS n FROM team_application_files WHERE application_id = ? AND uploader_uuid = ? AND message_id IS NULL AND created_at >= ?",
    applicationId, uuid, ctx.now() - PENDING_FILE_TTL_MS,
  )!.n
  if (pending >= MAX_PENDING_PER_APPLICATION) throw badRequest('too_many_pending_files', 'Too many unlinked files')
  const id = `f${randomBytes(12).toString('hex')}`
  const t = ctx.now()
  const target = filePath(ctx, id)
  mkdirSync(join(target, '..'), { recursive: true })
  const temp = `${target}.${randomBytes(4).toString('hex')}.tmp`
  writeFileSync(temp, ctx.cipher.encrypt(data, `appfile:${id}`), { mode: 0o600 })
  renameSync(temp, target)
  try {
    run(
      ctx.db,
      `INSERT INTO team_application_files (id, application_id, uploader_uuid, file_name, bytes, sha256, key_id,
         scan_status, scan_attempts, next_scan_at, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', 0, ?, ?)`,
      id, applicationId, uuid, name, data.length, createHash('sha256').update(data).digest('hex'), ctx.cipher.activeId, t, t,
    )
  } catch (error) {
    rmSync(target, { force: true })
    throw error
  }
  return { id, name, bytes: data.length, status: 'pending', signature: null }
}

const messageSchema = z.strictObject({ text: z.string().max(8000).optional(), fileIds: z.array(z.string().regex(FILE_ID)).max(MAX_FILES_PER_MESSAGE).default([]) })

export function sendApplicationMessage(ctx: AppContext, uuid: string, applicationId: string, raw: unknown): ApplicationMessageView {
  const app = access(ctx, uuid, applicationId)
  if (app.status === 'withdrawn') throw forbidden('application_closed', 'This application is closed')
  const parsed = messageSchema.safeParse(raw)
  if (!parsed.success) throw badRequest('invalid_request', 'Request validation failed')
  const text = sanitizeText(parsed.data.text ?? '').trim()
  const fileIds = [...new Set(parsed.data.fileIds)]
  if (textLength(text) > 2000) throw badRequest('message_too_long', 'Message is too long')
  if (!text && fileIds.length === 0) throw badRequest('empty_message', 'Write a message or attach a file')
  if (fileIds.length !== parsed.data.fileIds.length) throw badRequest('duplicate_file', 'A file can only be attached once')
  const files = fileIds.length
    ? all<ApplicationFileRow>(ctx.db, `SELECT * FROM team_application_files WHERE id IN (${placeholders(fileIds.length)})`, ...fileIds)
    : []
  if (files.length !== fileIds.length || files.some((file) => file.application_id !== applicationId || file.uploader_uuid !== uuid || file.message_id || !['pending', 'clean'].includes(file.scan_status))) {
    throw badRequest('invalid_attachment', 'One or more files are not available to attach')
  }
  const id = `m${randomBytes(12).toString('hex')}`
  const at = ctx.now()
  tx(ctx.db, () => {
    run(ctx.db, 'INSERT INTO team_application_messages (id, application_id, sender_uuid, body, key_id, created_at) VALUES (?, ?, ?, ?, ?, ?)',
      id, applicationId, uuid, ctx.cipher.encrypt(text, `appmsg:${id}`), ctx.cipher.activeId, at)
    for (const fileId of fileIds) {
      const linked = run(ctx.db,
        "UPDATE team_application_files SET message_id = ? WHERE id = ? AND application_id = ? AND uploader_uuid = ? AND message_id IS NULL AND scan_status IN ('pending', 'clean')",
        id, fileId, applicationId, uuid,
      )
      if (linked !== 1) throw badRequest('invalid_attachment', 'One or more files are no longer available to attach')
    }
  })
  const applicantView = myApplications(ctx, app.uuid).find((item) => item.id === applicationId)
  if (!app.isApplicant && applicantView) ctx.events.publish(app.uuid, { type: 'application_updated', application: applicantView }, { meOnly: true })
  if (!app.isApplicant && app.contact_email) notifyApplicant(ctx, app.contact_email, app.lang, 'message', applicationId)
  const sender = one<{ name: string }>(ctx.db, 'SELECT name FROM users WHERE uuid = ?', uuid)
  return {
    id,
    sender: { uuid, name: sender?.name ?? null },
    text,
    createdAt: new Date(at).toISOString(),
    files: files.map((file) => ({ id: file.id, name: file.file_name, bytes: file.bytes, status: file.scan_status, signature: null })),
  }
}

export function applicationMessages(ctx: AppContext, uuid: string, applicationId: string): ApplicationMessageView[] {
  access(ctx, uuid, applicationId)
  const messages = all<ApplicationMessageRow>(ctx.db, 'SELECT * FROM team_application_messages WHERE application_id = ? ORDER BY created_at, id', applicationId)
  if (messages.length === 0) return []
  const names = new Map(all<{ uuid: string, name: string }>(
    ctx.db,
    `SELECT uuid, name FROM users WHERE uuid IN (${placeholders(new Set(messages.map((message) => message.sender_uuid)).size)})`,
    ...new Set(messages.map((message) => message.sender_uuid)),
  ).map((row) => [row.uuid, row.name]))
  const fileMap = new Map<string, ApplicationFileRow[]>()
  for (const file of all<ApplicationFileRow>(ctx.db, `SELECT * FROM team_application_files WHERE message_id IN (${placeholders(messages.length)}) ORDER BY created_at`, ...messages.map((message) => message.id))) {
    const list = fileMap.get(file.message_id!) ?? []
    list.push(file)
    fileMap.set(file.message_id!, list)
  }
  return messages.map((message) => ({
    id: message.id,
    sender: { uuid: message.sender_uuid, name: names.get(message.sender_uuid) ?? null },
    text: ctx.cipher.decryptText(message.body, `appmsg:${message.id}`),
    createdAt: new Date(message.created_at).toISOString(),
    files: (fileMap.get(message.id) ?? []).map((file) => ({
      id: file.id,
      name: file.file_name,
      bytes: file.bytes,
      status: file.scan_status,
      signature: file.scan_status === 'infected' ? file.scan_signature : null,
    })),
  }))
}

export function readApplicationFile(ctx: AppContext, uuid: string, applicationId: string, fileId: string): { name: string, data: Buffer } {
  access(ctx, uuid, applicationId)
  if (!FILE_ID.test(fileId)) throw notFound('application_file_not_found', 'File not found')
  const file = one<ApplicationFileRow>(ctx.db, 'SELECT * FROM team_application_files WHERE id = ? AND application_id = ?', fileId, applicationId)
  if (!file || !['clean', 'released'].includes(file.scan_status) || !file.message_id) throw notFound('application_file_not_found', 'File not found')
  return { name: file.file_name, data: ctx.cipher.decrypt(readFileSync(filePath(ctx, file.id)), `appfile:${file.id}`) }
}

export function releaseApplicationFile(ctx: AppContext, staff: Staff, applicationId: string, fileId: string): ApplicationFileView {
  if (!can(staff, 'applications.review')) throw forbidden('missing_permission', 'You cannot review applications')
  const app = one<{ uuid: string }>(ctx.db, 'SELECT uuid FROM team_applications WHERE id = ?', applicationId)
  if (!app || app.uuid === staff.uuid) throw notFound('application_not_found', 'Application not found')
  const file = one<ApplicationFileRow>(ctx.db, 'SELECT * FROM team_application_files WHERE id = ? AND application_id = ?', fileId, applicationId)
  if (!file || !file.message_id) throw notFound('application_file_not_found', 'File not found')
  if (!['infected', 'failed'].includes(file.scan_status)) throw badRequest('file_not_quarantined', 'Only quarantined files can be reviewed')
  const at = ctx.now()
  tx(ctx.db, () => {
    const changed = run(ctx.db, "UPDATE team_application_files SET scan_status = 'released', scanned_at = ? WHERE id = ? AND scan_status IN ('infected', 'failed')", at, file.id)
    if (changed !== 1) throw badRequest('file_not_quarantined', 'This file is no longer quarantined')
    run(ctx.db, 'INSERT INTO team_application_history (application_id, at, actor, action, detail) VALUES (?, ?, ?, ?, ?)',
      applicationId, at, staff.uuid, 'file_released', file.id)
  })
  const view = myApplications(ctx, app.uuid).find((item) => item.id === applicationId)
  if (view) ctx.events.publish(app.uuid, { type: 'application_updated', application: view }, { meOnly: true })
  return { id: file.id, name: file.file_name, bytes: file.bytes, status: 'released', signature: file.scan_signature }
}

export async function processApplicationScanQueue(ctx: AppContext, budget = 1): Promise<number> {
  if (!ctx.config.clamav || budget <= 0) return 0
  let processed = 0
  while (processed < budget) {
    const file = one<ApplicationFileRow>(
      ctx.db,
      `SELECT * FROM team_application_files WHERE
         (scan_status = 'pending' AND next_scan_at <= ?) OR (scan_status = 'scanning' AND scan_started_at < ?)
       ORDER BY created_at LIMIT 1`,
      ctx.now(), ctx.now() - SCAN_LEASE_MS,
    )
    if (!file) break
    const started = run(
      ctx.db,
      `UPDATE team_application_files SET scan_status = 'scanning', scan_started_at = ?, scan_attempts = scan_attempts + 1
       WHERE id = ? AND ((scan_status = 'pending' AND next_scan_at <= ?) OR (scan_status = 'scanning' AND scan_started_at < ?))`,
      ctx.now(), file.id, ctx.now(), ctx.now() - SCAN_LEASE_MS,
    )
    if (!started) continue
    try {
      const data = ctx.cipher.decrypt(readFileSync(filePath(ctx, file.id)), `appfile:${file.id}`)
      const result = await scanApplicationFile(ctx.config.clamav.host, ctx.config.clamav.port, data)
      run(ctx.db,
        `UPDATE team_application_files SET scan_status = ?, scan_signature = ?, scanned_at = ?, scan_started_at = NULL
         WHERE id = ? AND scan_status = 'scanning'`,
        result.status, result.status === 'infected' ? result.signature.slice(0, 180) : null, ctx.now(), file.id,
      )
    } catch (error) {
      const attempts = file.scan_attempts + 1
      run(ctx.db,
        `UPDATE team_application_files SET scan_status = ?, scan_signature = ?, next_scan_at = ?, scan_started_at = NULL
         WHERE id = ? AND scan_status = 'scanning'`,
        attempts >= MAX_SCAN_ATTEMPTS ? 'failed' : 'pending', attempts >= MAX_SCAN_ATTEMPTS ? 'Scanner nicht erreichbar' : null,
        ctx.now() + SCAN_RETRY_MS * 2 ** Math.max(0, attempts - 1), file.id,
      )
      console.error('[trs-api] application attachment scan failed', error)
    }
    const applicant = one<{ uuid: string }>(ctx.db, 'SELECT uuid FROM team_applications WHERE id = ?', file.application_id)
    if (applicant) {
      const view = myApplications(ctx, applicant.uuid).find((item) => item.id === file.application_id)
      if (view) ctx.events.publish(applicant.uuid, { type: 'application_updated', application: view }, { meOnly: true })
    }
    processed++
  }
  return processed
}

export function sweepApplicationFiles(ctx: AppContext): number {
  const cutoff = ctx.now() - PENDING_FILE_TTL_MS
  const files = all<{ id: string }>(ctx.db,
    'SELECT id FROM team_application_files WHERE message_id IS NULL AND created_at < ?', cutoff)
  for (const file of files) {
    run(ctx.db, 'DELETE FROM team_application_files WHERE id = ?', file.id)
    rmSync(filePath(ctx, file.id), { force: true })
  }
  const known = new Set(all<{ id: string }>(ctx.db, 'SELECT id FROM team_application_files').map((file) => file.id))
  let removed = files.length
  if (!existsSync(ctx.applicationFileDir)) return removed
  for (const dir of readdirSync(ctx.applicationFileDir, { withFileTypes: true })) {
    if (!dir.isDirectory() || !/^[0-9a-f]{2}$/.test(dir.name)) continue
    const folder = join(ctx.applicationFileDir, dir.name)
    for (const entry of readdirSync(folder, { withFileTypes: true })) {
      if (!entry.isFile()) continue
      const path = join(folder, entry.name)
      if (entry.name.endsWith('.tmp')) {
        if (statSync(path).mtimeMs < cutoff) { rmSync(path, { force: true }); removed++ }
        continue
      }
      const id = entry.name.endsWith('.bin') ? entry.name.slice(0, -4) : ''
      if (FILE_ID.test(id) && !known.has(id)) { rmSync(path, { force: true }); removed++ }
    }
  }
  return removed
}

/** Rotate application chat material together with the other CHAT_KEYS-backed data. */
export function rotateApplicationKeys(ctx: AppContext, budget = 20): number {
  let rotated = 0
  const pending = all<{ id: string, payload: Uint8Array, key_id: string }>(ctx.db,
    'SELECT id, payload, key_id FROM team_application_verifications WHERE key_id <> ? LIMIT ?', ctx.cipher.activeId, budget)
  for (const row of pending) {
    const keyId = ChatCipher.keyIdOf(row.payload)
    if (keyId !== ctx.cipher.activeId) {
      const payload = JSON.parse(ctx.cipher.decrypt(row.payload, `appverify:${row.id}`).toString('utf8')) as unknown
      run(ctx.db, 'UPDATE team_application_verifications SET payload = ?, key_id = ? WHERE id = ?',
        ctx.cipher.encrypt(Buffer.from(JSON.stringify(payload)), `appverify:${row.id}`), ctx.cipher.activeId, row.id)
    } else run(ctx.db, 'UPDATE team_application_verifications SET key_id = ? WHERE id = ?', ctx.cipher.activeId, row.id)
    rotated++
  }
  const messageBudget = Math.max(0, budget - rotated)
  const messages = all<ApplicationMessageRow>(ctx.db,
    'SELECT id, body, key_id FROM team_application_messages WHERE key_id <> ? LIMIT ?', ctx.cipher.activeId, messageBudget)
  for (const row of messages) {
    const text = ctx.cipher.decryptText(row.body, `appmsg:${row.id}`)
    run(ctx.db, 'UPDATE team_application_messages SET body = ?, key_id = ? WHERE id = ?',
      ctx.cipher.encrypt(text, `appmsg:${row.id}`), ctx.cipher.activeId, row.id)
    rotated++
  }
  const fileBudget = Math.max(0, budget - rotated)
  const files = all<{ id: string, key_id: string }>(ctx.db,
    'SELECT id, key_id FROM team_application_files WHERE key_id <> ? LIMIT ?', ctx.cipher.activeId, fileBudget)
  for (const row of files) {
    const path = filePath(ctx, row.id)
    const bytes = readFileSync(path)
    if (ChatCipher.keyIdOf(bytes) === ctx.cipher.activeId) {
      run(ctx.db, 'UPDATE team_application_files SET key_id = ? WHERE id = ?', ctx.cipher.activeId, row.id)
      rotated++
      continue
    }
    const plain = ctx.cipher.decrypt(bytes, `appfile:${row.id}`)
    const temp = `${path}.${randomBytes(4).toString('hex')}.tmp`
    writeFileSync(temp, ctx.cipher.encrypt(plain, `appfile:${row.id}`), { mode: 0o600 })
    renameSync(temp, path)
    run(ctx.db, 'UPDATE team_application_files SET key_id = ? WHERE id = ?', ctx.cipher.activeId, row.id)
    rotated++
  }
  return rotated
}