import { existsSync, readdirSync } from 'node:fs'
import { IncomingMessage, ServerResponse } from 'node:http'
import { Socket } from 'node:net'
import { join } from 'node:path'
import { crc32 } from 'node:zlib'
import { DatabaseSync } from 'node:sqlite'
import { createEvent } from 'h3'
import { decode as jpegDecode } from 'jpeg-js'
import { describe, expect, it } from 'vitest'
import { readEvidenceFile } from '../server/lib/attachments'
import { setContext } from '../server/lib/context'
import { all, migrate, one, run } from '../server/lib/db'
import { MIGRATIONS } from '../server/lib/migrations'
import { adminReportAction, adminReportDetail, createAnonymousShareReport, createReport } from '../server/lib/moderation'
import { createSanction, type Staff } from '../server/lib/sanctions'
import { chatReportBody } from '../server/lib/schemas'
import {
  deleteShare,
  getShare,
  listShares,
  publicShareView,
  sweepExpiredShares,
  uploadShare,
  type ShareView,
} from '../server/lib/shares'
import { deleteUser } from '../server/lib/users'
import imageRoute from '../server/routes/v1/shares/[id]/image.get'
import publicRoute from '../server/routes/v1/shares/[id].get'
import { code, codeAsync, players } from './chathelpers'
import { ADMIN, login, makeEnv, solidPng, type TestEnv } from './helpers'

const DAY = 24 * 60 * 60 * 1000
const ADMIN_STAFF: Staff = { uuid: ADMIN, role: 'admin' } as Staff

/** PNG mit tEXt-Metadaten (muss nach dem Neukodieren weg sein). */
function pngWithText(w: number, h: number): Buffer {
  const png = solidPng(w, h)
  const iend = png.length - 12
  const data = Buffer.from('Comment\0secret-gps-51.5,7.4')
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length)
  const type = Buffer.from('tEXt')
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(Buffer.concat([type, data])) >>> 0)
  return Buffer.concat([png.subarray(0, iend), len, type, data, crc, png.subarray(iend)])
}

async function upload(env: TestEnv, uuid: string, img = solidPng(64, 36)): Promise<ShareView> {
  return uploadShare(env.ctx, uuid, img, 'image/png')
}

function files(env: TestEnv): string[] {
  if (!existsSync(env.ctx.shareDir)) return []
  return readdirSync(env.ctx.shareDir).flatMap((d) => readdirSync(join(env.ctx.shareDir, d)))
}

async function callRoute(env: TestEnv, route: (e: ReturnType<typeof createEvent>) => unknown, url: string, params: Record<string, string>) {
  setContext(env.ctx)
  const req = new IncomingMessage(new Socket())
  req.method = 'GET'
  req.url = url
  req.headers = {}
  const res = new ServerResponse(req)
  const event = createEvent(req, res)
  event.context.params = params
  try {
    const body = await route(event)
    return { body, headers: res.getHeaders(), status: res.statusCode }
  } catch (e) {
    return { error: e as { status: number, code: string } }
  } finally {
    setContext(undefined)
  }
}

describe('shared screenshots (§23)', () => {
  it('re-encodes the upload without metadata and serves it publicly without owner data', async () => {
    const env = makeEnv()
    const [a] = await players(env, 'Alex')
    const s = await upload(env, a!.uuid, pngWithText(1920, 1080))
    expect(s.id).toMatch(/^[A-Za-z0-9_-]{22}$/)
    expect(s).toMatchObject({ mime: 'image/jpeg', width: 1920, height: 1080 })
    expect(s.url).toBe(`${env.ctx.config.siteUrl}/s/${s.id}`)
    expect(s.imageUrl).toBe(`${env.ctx.config.siteUrl}/v1/shares/${s.id}/image`)
    expect(new Date(s.expiresAt).getTime() - new Date(s.createdAt).getTime()).toBe(30 * DAY)

    const pub = publicShareView(env.ctx, getShare(env.ctx, s.id)!)
    expect(Object.keys(pub).sort()).toEqual(['createdAt', 'expiresAt', 'height', 'id', 'imageUrl', 'mime', 'url', 'width'])
    expect(JSON.stringify(pub)).not.toContain(a!.uuid)
    expect(JSON.stringify(pub)).not.toContain('Alex')

    const img = await callRoute(env, imageRoute, `/v1/shares/${s.id}/image`, { id: s.id })
    expect(img.headers).toMatchObject({
      'content-type': 'image/jpeg',
      'cross-origin-resource-policy': 'cross-origin',
      'cache-control': 'public, max-age=600',
    })
    const bytes = img.body as Buffer
    expect(bytes.includes(Buffer.from('secret-gps'))).toBe(false)
    expect(jpegDecode(bytes, { useTArray: true }).width).toBe(1920)
    const thumb = await callRoute(env, imageRoute, `/v1/shares/${s.id}/image?thumb=1`, { id: s.id })
    expect(jpegDecode(thumb.body as Buffer, { useTArray: true }).width).toBe(480)

    const meta = await callRoute(env, publicRoute, `/v1/shares/${s.id}`, { id: s.id })
    expect(meta.body).toEqual({ share: pub })
    expect(meta.headers['x-robots-tag']).toBe('noindex, nofollow')
    expect(await callRoute(env, publicRoute, '/v1/shares/nope', { id: 'nope' })).toMatchObject({ error: { status: 404, code: 'share_not_found' } })
    expect(await callRoute(env, imageRoute, '/v1/shares/x/image', { id: 'A'.repeat(22) })).toMatchObject({ error: { code: 'share_not_found' } })
  })

  it('keeps transparency as PNG, scales to 4096 px and checks size and type', async () => {
    const env = makeEnv()
    const [a] = await players(env, 'Alex')
    const alpha = await upload(env, a!.uuid, solidPng(5000, 20, [10, 20, 30, 128]))
    expect(alpha).toMatchObject({ mime: 'image/png', width: 4096 })
    expect(await codeAsync(() => uploadShare(env.ctx, a!.uuid, Buffer.alloc(10 * 1024 * 1024 + 1, 1), 'image/png'))).toBe('payload_too_large')
    expect(await codeAsync(() => uploadShare(env.ctx, a!.uuid, solidPng(8, 8), 'image/jpeg'))).toBe('unsupported_media_type')
    expect(await codeAsync(() => uploadShare(env.ctx, a!.uuid, Buffer.from('not an image at all'), 'image/png'))).toBe('unsupported_media_type')
  })

  it('lists own links, deletes only own ones and enforces the limits', async () => {
    const env = makeEnv({ limits: { maxActiveShares: 2, maxSharesPerDay: 3 } })
    const [a, b] = await players(env, 'Alex', 'Bob')
    const s1 = await upload(env, a!.uuid)
    env.clock.advance(1000)
    const s2 = await upload(env, a!.uuid)
    expect(listShares(env.ctx, a!.uuid)).toMatchObject({
      shares: [{ id: s2.id }, { id: s1.id }],
      limits: { active: 2, maxActive: 2, uploadsToday: 2, maxPerDay: 3 },
    })
    expect(listShares(env.ctx, b!.uuid).shares).toEqual([])
    expect(await codeAsync(() => upload(env, a!.uuid))).toBe('shared_image_limit')
    expect(code(() => deleteShare(env.ctx, b!.uuid, s1.id))).toBe('share_not_found')
    deleteShare(env.ctx, a!.uuid, s1.id)
    expect(getShare(env.ctx, s1.id)).toBeUndefined()
    expect(code(() => deleteShare(env.ctx, a!.uuid, s1.id))).toBe('share_not_found')
    // Löschen setzt die Tagesgrenze nicht zurück.
    await upload(env, a!.uuid)
    deleteShare(env.ctx, a!.uuid, listShares(env.ctx, a!.uuid).shares[0]!.id)
    let err: unknown
    try {
      await upload(env, a!.uuid)
    } catch (e) {
      err = e
    }
    expect(err).toMatchObject({ status: 429, code: 'share_daily_limit' })
    expect(Number((err as { headers: Record<string, string> }).headers['Retry-After'])).toBeGreaterThan(24 * 3600 - 10)
    env.clock.advance(DAY)
    expect((await upload(env, a!.uuid)).id).toMatch(/^[A-Za-z0-9_-]{22}$/)
  })

  it('blocks uploads during an upload ban', async () => {
    const env = makeEnv()
    await login(env, 'Admin', ADMIN)
    const [a] = await players(env, 'Alex')
    createSanction(env.ctx, ADMIN_STAFF, { uuid: a!.uuid, kind: 'upload_ban', minutes: 60, reasonCode: 'other', reason: 'test' })
    let err: unknown
    try {
      await upload(env, a!.uuid)
    } catch (e) {
      err = e
    }
    expect(err).toMatchObject({ status: 403, code: 'sanctioned' })
    expect((err as { details: { until: string } }).details.until).toBeTruthy()
    env.clock.advance(61 * 60_000)
    expect((await upload(env, a!.uuid)).mime).toBe('image/jpeg')
  })

  it('expires after 30 days and disappears with the account', async () => {
    const env = makeEnv()
    const [a, b] = await players(env, 'Alex', 'Bob')
    const s = await upload(env, a!.uuid)
    await upload(env, b!.uuid)
    expect(files(env)).toHaveLength(4)
    env.clock.advance(30 * DAY - 1)
    expect(getShare(env.ctx, s.id)).toBeDefined()
    env.clock.advance(1)
    expect(getShare(env.ctx, s.id)).toBeUndefined()
    // Nicht gefegte, aber abgelaufene Links sind trotzdem weg.
    expect(listShares(env.ctx, a!.uuid).shares).toEqual([])
    expect(sweepExpiredShares(env.ctx)).toBe(2)
    expect(files(env)).toEqual([])
    expect(one(env.ctx.db, 'SELECT COUNT(*) AS n FROM shared_image_uploads')).toEqual({ n: 0 })

    const c = await upload(env, a!.uuid)
    expect(files(env)).toHaveLength(2)
    deleteUser(env.ctx, a!.uuid)
    expect(getShare(env.ctx, c.id)).toBeUndefined()
    expect(files(env)).toEqual([])
  })

  it('can be reported by players and anonymously; the team keeps a copy and can delete the link', async () => {
    const env = makeEnv()
    await login(env, 'Admin', ADMIN)
    const [a, b] = await players(env, 'Alex', 'Bob')
    const s = await upload(env, a!.uuid)
    expect(chatReportBody.safeParse({ kind: 'share', reason: 'spam' }).success).toBe(false)
    expect(chatReportBody.safeParse({ kind: 'share', reason: 'spam', shareId: s.id }).success).toBe(true)
    expect(code(() => createReport(env.ctx, a!.uuid, { kind: 'share', reason: 'spam', shareId: s.id }))).toBe('cannot_target_self')
    expect(code(() => createReport(env.ctx, b!.uuid, { kind: 'share', reason: 'spam', shareId: 'B'.repeat(22) }))).toBe('share_not_found')
    const r = createReport(env.ctx, b!.uuid, { kind: 'share', reason: 'inappropriate', shareId: s.id })
    expect(r.kind).toBe('share')
    expect(code(() => createReport(env.ctx, b!.uuid, { kind: 'share', reason: 'spam', shareId: s.id }))).toBe('already_reported')

    expect(createAnonymousShareReport(env.ctx, s.id, 'insult_hate')).toBe(true)
    expect(createAnonymousShareReport(env.ctx, s.id, 'spam')).toBe(false)
    expect(code(() => createAnonymousShareReport(env.ctx, 'C'.repeat(22), 'spam'))).toBe('share_not_found')
    const anon = one<{ id: string, reporter_uuid: string | null, target_uuid: string }>(
      env.ctx.db, 'SELECT id, reporter_uuid, target_uuid FROM chat_reports WHERE reporter_uuid IS NULL',
    )!
    expect(anon.target_uuid).toBe(a!.uuid)

    const d = adminReportDetail(env.ctx, r.id)
    expect(d).toMatchObject({ kind: 'share', shareId: s.id, anonymous: false, target: { uuid: a!.uuid } })
    expect(d.evidence?.share).toMatchObject({ id: s.id, width: 64, height: 36 })
    expect(d.evidence?.images).toEqual([{ id: s.id, width: 64, height: 36, mime: 'image/jpeg', path: `/v1/admin/reports/${r.id}/images/${s.id}` }])
    expect(adminReportDetail(env.ctx, anon.id)).toMatchObject({ anonymous: true, reporter: null })

    const after = adminReportAction(env.ctx, ADMIN, r.id, { action: 'delete_share', includeRelated: true })
    expect(after).toMatchObject({ status: 'resolved', outcome: 'actioned' })
    expect(getShare(env.ctx, s.id)).toBeUndefined()
    expect(files(env)).toEqual([])
    // Beweis-Kopie bleibt (für Einsprüche), die anonyme Meldung ist mit erledigt.
    expect(readEvidenceFile(env.ctx, r.id, s.id)?.data.length).toBeGreaterThan(0)
    expect(adminReportDetail(env.ctx, anon.id).status).toBe('resolved')
    expect(code(() => adminReportAction(env.ctx, ADMIN, r.id, { action: 'delete_message' }))).toBe('no_message')
  })
})

describe('migration 12', () => {
  it('adds the share kind to reports and keeps notes and evidence rows', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    const upTo11 = MIGRATIONS.filter((m) => m.version <= 11)
    run(db, 'CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)')
    for (const m of upTo11) {
      if (m.sql) db.exec(m.sql)
      m.run?.(db)
      run(db, 'INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)', m.version, 1)
    }
    run(db, "INSERT INTO chat_reports (id, kind, reason, status, created_at, updated_at) VALUES ('r0000000000000001', 'message', 'spam', 'open', 1, 1)")
    run(db, "INSERT INTO chat_report_notes (report_id, at, actor, text) VALUES ('r0000000000000001', 1, 'x', X'00')")
    run(db, "INSERT INTO chat_evidence_files (report_id, attachment_id, mime, width, height, bytes, key_id) VALUES ('r0000000000000001', 'a1', 'image/png', 1, 1, 1, 's1')")
    expect(migrate(db)).toBeGreaterThanOrEqual(1)
    expect(all(db, 'SELECT id, kind, share_id FROM chat_reports')).toEqual([{ id: 'r0000000000000001', kind: 'message', share_id: null }])
    expect(one(db, 'SELECT COUNT(*) AS n FROM chat_report_notes')).toEqual({ n: 1 })
    expect(one(db, 'SELECT COUNT(*) AS n FROM chat_evidence_files')).toEqual({ n: 1 })
    run(db, "INSERT INTO chat_reports (id, kind, share_id, reason, status, created_at, updated_at) VALUES ('r0000000000000002', 'share', 'x', 'spam', 'open', 1, 1)")
    // Fremdschlüssel zeigen auf die neue Tabelle: Löschen nimmt Notizen und Beweise mit.
    run(db, "DELETE FROM chat_reports WHERE id = 'r0000000000000001'")
    expect(one(db, 'SELECT COUNT(*) AS n FROM chat_report_notes')).toEqual({ n: 0 })
    expect(one(db, 'SELECT COUNT(*) AS n FROM chat_evidence_files')).toEqual({ n: 0 })
    expect(all(db, 'PRAGMA foreign_key_check')).toEqual([])
    expect(() => run(db, "INSERT INTO chat_reports (id, kind, reason, status, created_at, updated_at) VALUES ('r3', 'bogus', 'spam', 'open', 1, 1)")).toThrow()
  })
})
