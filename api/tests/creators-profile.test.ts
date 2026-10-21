import { createServer, type Server } from 'node:http'
import { DatabaseSync } from 'node:sqlite'
import { createApp, createRouter, send, setResponseStatus, toNodeListener } from 'h3'
import { afterEach, describe, expect, it } from 'vitest'
import { checkProfileLink, cleanProfileDescription, descriptionBlocks, isPublicHost } from '../shared/creator-profile'
import { setContext } from '../server/lib/context'
import {
  adminCreateCreator,
  adminRevokeCreator,
  cleanProfile,
  creatorCounts,
  creatorDashboard,
  creatorProfile,
  decideQueueItem,
  redeemCreatorCode,
  reviewQueue,
  siteCreator,
  siteCreators,
  submitProfile,
  uploadFanCape,
  uploadSymbol,
  withdrawProfile,
  inviteHelper,
  answerHelperInvite,
} from '../server/lib/creators'
import { all, migrate, run } from '../server/lib/db'
import { isApiError } from '../server/lib/errors'
import { MIGRATIONS, migrateCreatorProfiles } from '../server/lib/migrations'
import { ownerStaff } from '../server/lib/team'
import { getUser } from '../server/lib/users'
import { createWebSession } from '../server/lib/weblogin'
import dashboardRoute from '../server/routes/v1/creator/dashboard.get'
import codesPost from '../server/routes/v1/creator/codes/index.post'
import profileGet from '../server/routes/v1/creator/profile/index.get'
import profilePut from '../server/routes/v1/creator/profile/index.put'
import profileDelete from '../server/routes/v1/creator/profile/pending.delete'
import symbolPut from '../server/routes/v1/creator/symbol/index.put'
import meCreator from '../server/routes/v1/me/creator/index.get'
import siteList from '../server/routes/v1/site/creators/index.get'
import siteOne from '../server/routes/v1/site/creators/[name].get'
import queueGet from '../server/routes/v1/admin/creators/queue/index.get'
import queueApprove from '../server/routes/v1/admin/creators/queue/[id]/approve.post'
import queueReject from '../server/routes/v1/admin/creators/queue/[id]/reject.post'
import { befriend, code, listen, players } from './chathelpers'
import { ADMIN, login, makeEnv, solidPng, type TestEnv } from './helpers'

const OWNER = ownerStaff(ADMIN)
const DAY = 86_400_000

async function creator(env: TestEnv, name: string, tier: 'starter' | 'partner' | 'top' = 'starter') {
  const l = await login(env, name)
  const c = adminCreateCreator(env.ctx, OWNER, { player: name, tier }).creator
  return { uuid: l.user.uuid, token: l.token, code: c.mainCode! }
}

const queueId = (env: TestEnv, uuid: string, kind = 'profile') =>
  reviewQueue(env.ctx).items.find((i) => i.kind === kind && i.creator.uuid === uuid)!.id

describe('creator profile: validation', () => {
  it('link host rules per type, https only, no IP/localhost/ports/credentials', () => {
    const ok = (type: Parameters<typeof checkProfileLink>[0], url: string) => 'url' in checkProfileLink(type, url)
    expect(ok('youtube', 'https://www.youtube.com/@pixel')).toBe(true)
    expect(ok('youtube', 'https://youtu.be/abc')).toBe(true)
    expect(ok('youtube', 'https://m.youtube.com/@pixel')).toBe(true)
    expect(ok('youtube', 'https://youtube.com.evil.net/@pixel')).toBe(false)
    expect(ok('youtube', 'https://notyoutube.com/x')).toBe(false)
    expect(ok('youtube', 'http://youtube.com/@pixel')).toBe(false)
    expect(ok('twitch', 'https://twitch.tv/pixel')).toBe(true)
    expect(ok('twitch', 'https://youtube.com/pixel')).toBe(false)
    expect(ok('tiktok', 'https://www.tiktok.com/@pixel')).toBe(true)
    expect(ok('discord', 'https://discord.gg/abc')).toBe(true)
    expect(ok('discord', 'https://discord.com/invite/abc')).toBe(true)
    expect(ok('discord', 'https://discordapp.io/abc')).toBe(false)
    expect(ok('instagram', 'https://instagram.com/pixel')).toBe(true)
    expect(ok('x', 'https://x.com/pixel')).toBe(true)
    expect(ok('x', 'https://twitter.com/pixel')).toBe(true)
    expect(ok('x', 'https://x.co/pixel')).toBe(false)
    expect(ok('website', 'https://pixel.example.org/about')).toBe(true)
    expect(ok('website', 'https://127.0.0.1/')).toBe(false)
    expect(ok('website', 'https://[::1]/')).toBe(false)
    expect(ok('website', 'https://localhost/')).toBe(false)
    expect(ok('website', 'https://router.local/')).toBe(false)
    expect(ok('website', 'https://intranet/')).toBe(false)
    expect(ok('website', 'https://pixel.de:8443/')).toBe(false)
    expect(ok('website', 'https://user:pw@pixel.de/')).toBe(false)
    expect(ok('website', 'javascript:alert(1)')).toBe(false)
    expect(ok('website', 'data:text/html,hi')).toBe(false)
    expect(ok('website', `https://pixel.de/${'a'.repeat(300)}`)).toBe(false)
    // Normalisiert: Host klein, ohne Fragment.
    expect(checkProfileLink('website', 'https://PIXEL.de/Shop#top')).toEqual({ url: 'https://pixel.de/Shop' })
    expect(isPublicHost('1.2')).toBe(false)
    expect(isPublicHost('pixel.studio')).toBe(true)
  })

  it('description is cleaned to plain text, paragraphs and simple lists', () => {
    expect(cleanProfileDescription('Hallo\r\nWelt\u202E!\u200B\t ok  \n\n\n\nEnde\u0007')).toBe('Hallo\nWelt!  ok\n\nEnde')
    expect(cleanProfileDescription('<script>alert(1)</script>')).toBe('<script>alert(1)</script>')
    expect(descriptionBlocks('Intro line\nsecond\n\n- one\n* two\n• three\nAfter')).toEqual([
      { kind: 'p', text: 'Intro line\nsecond' },
      { kind: 'ul', items: ['one', 'two', 'three'] },
      { kind: 'p', text: 'After' },
    ])
    const p = cleanProfile({ description: `  ${'x'.repeat(1500)}  `, links: [] })
    expect(p.description).toHaveLength(1500)
    const err = (raw: unknown) => {
      try {
        cleanProfile(raw)
        return null
      } catch (e) {
        return isApiError(e) ? { code: e.code, details: (e as { details?: unknown }).details } : null
      }
    }
    expect(err({ description: 'x'.repeat(1501) })).toEqual({ code: 'invalid_profile', details: { field: 'description', reason: 'too_long' } })
    expect(err({ links: [{ type: 'youtube', url: 'https://twitch.tv/x' }] })).toEqual({ code: 'invalid_profile', details: { field: 'links.0', reason: 'wrong_host' } })
    expect(err({ links: [{ type: 'website', url: 'http://pixel.de' }] })).toEqual({ code: 'invalid_profile', details: { field: 'links.0', reason: 'not_https' } })
    expect(err({ links: [{ type: 'x', url: 'https://x.com/a' }, { type: 'x', url: 'https://X.com/a' }] })).toEqual({ code: 'invalid_profile', details: { field: 'links.1', reason: 'duplicate' } })
    expect(err({ links: Array.from({ length: 8 }, (_, i) => ({ type: 'website', url: `https://a${i}.de` })) })?.code).toBe('invalid_request')
    expect(err({ links: [{ type: 'myspace', url: 'https://myspace.com' }] })?.code).toBe('invalid_request')
    expect(err({ description: 'x', extra: 1 })?.code).toBe('invalid_request')
  })
})

describe('creator profile: review flow', () => {
  it('pending → approve/reject with notification; public keeps the approved version', async () => {
    const env = makeEnv()
    const c = await creator(env, 'Pixelmaus', 'partner')
    const ev = listen(env, c.uuid)
    expect(creatorProfile(env.ctx, c.uuid)).toEqual({ approved: null, pending: null, rejected: null })
    const s1 = submitProfile(env.ctx, c.uuid, { description: 'Ich baue Redstone.\n\n- Farmen\n- Rechner', links: [{ type: 'youtube', url: 'https://youtube.com/@pixel' }] })
    expect(s1.approved).toBeNull()
    expect(s1.pending).toMatchObject({ description: 'Ich baue Redstone.\n\n- Farmen\n- Rechner', links: [{ type: 'youtube', url: 'https://youtube.com/@pixel' }] })
    // Öffentlich noch nichts.
    expect(siteCreator(env.ctx, 'pixelmaus').creator).toMatchObject({ description: '', links: [] })
    expect(creatorCounts(env.ctx).queue).toBe(1)
    const item = reviewQueue(env.ctx).items.find((i) => i.kind === 'profile')!
    expect(item).toMatchObject({ id: expect.stringMatching(/^p\d+$/), creator: { uuid: c.uuid, name: 'Pixelmaus', tier: 'partner' }, profile: { previous: null } })
    decideQueueItem(env.ctx, OWNER, item.id, true)
    expect(ev.of('creator_updated').map((e) => e.kind)).toEqual(['profile_approved'])
    expect(siteCreator(env.ctx, 'PIXELMAUS').creator).toMatchObject({ description: 'Ich baue Redstone.\n\n- Farmen\n- Rechner', links: [{ type: 'youtube', url: 'https://youtube.com/@pixel' }] })

    // Neue Fassung: öffentlich bleibt die alte, bis entschieden ist; zweite Einreichung ersetzt die wartende.
    submitProfile(env.ctx, c.uuid, { description: 'Neu 1', links: [] })
    const s2 = submitProfile(env.ctx, c.uuid, { description: 'Neu 2', links: [{ type: 'website', url: 'https://pixel.de' }] })
    expect(s2.pending?.description).toBe('Neu 2')
    expect(all(env.ctx.db, "SELECT id FROM creator_profiles WHERE status = 'pending'")).toHaveLength(1)
    expect(siteCreators(env.ctx).creators[0]!.description).toBe('Ich baue Redstone.\n\n- Farmen\n- Rechner')
    const q2 = reviewQueue(env.ctx).items.find((i) => i.kind === 'profile')!
    expect(q2.profile?.previous?.description).toBe('Ich baue Redstone.\n\n- Farmen\n- Rechner')
    expect(code(() => decideQueueItem(env.ctx, OWNER, q2.id, false, { reason: '' }))).toBe('invalid_request')
    decideQueueItem(env.ctx, OWNER, q2.id, false, { reason: 'Bitte ohne Werbelink' })
    expect(ev.of('creator_updated').at(-1)).toMatchObject({ kind: 'profile_rejected', reason: 'Bitte ohne Werbelink' })
    const after = creatorDashboard(env.ctx, c.uuid).profile
    expect(after.pending).toBeNull()
    expect(after.rejected).toMatchObject({ reason: 'Bitte ohne Werbelink' })
    expect(after.approved?.description).toBe('Ich baue Redstone.\n\n- Farmen\n- Rechner')
    expect(code(() => decideQueueItem(env.ctx, OWNER, q2.id, true))).toBe('queue_item_not_found')

    // Gleich wie die freigegebene → keine neue Fassung; zurückziehen.
    expect(submitProfile(env.ctx, c.uuid, { description: 'Ich baue Redstone.\n\n- Farmen\n- Rechner', links: [{ type: 'youtube', url: 'https://youtube.com/@pixel' }] }).pending).toBeNull()
    submitProfile(env.ctx, c.uuid, { description: 'Noch mal' })
    withdrawProfile(env.ctx, c.uuid)
    expect(creatorProfile(env.ctx, c.uuid).pending).toBeNull()
    // Freigabe einer neuen Fassung entfernt die alte freigegebene.
    submitProfile(env.ctx, c.uuid, { description: 'Final' })
    decideQueueItem(env.ctx, OWNER, queueId(env, c.uuid), true)
    expect(all(env.ctx.db, "SELECT id FROM creator_profiles WHERE status = 'approved'")).toHaveLength(1)
    expect(siteCreator(env.ctx, 'Pixelmaus').creator.description).toBe('Final')
    const actions = all<{ action: string }>(env.ctx.db, "SELECT action FROM admin_log WHERE action LIKE 'creator.profile.%' ORDER BY id").map((r) => r.action)
    expect(actions).toEqual(['creator.profile.approve', 'creator.profile.reject', 'creator.profile.approve'])
  })

  it('only the creator edits the profile; helpers may read it; inactive creators cannot', async () => {
    const env = makeEnv()
    const c = await creator(env, 'Pixelmaus')
    const ownerRow = getUser(env.ctx, c.uuid)!
    const [helperRow] = await players(env, 'Cutter')
    befriend(env, ownerRow, helperRow!)
    inviteHelper(env.ctx, c.uuid, { uuid: helperRow!.uuid, rights: ['stats'] })
    answerHelperInvite(env.ctx, helperRow!.uuid, c.uuid, { accept: true })
    expect(creatorProfile(env.ctx, helperRow!.uuid, c.uuid)).toEqual({ approved: null, pending: null, rejected: null })
    expect(code(() => submitProfile(env.ctx, helperRow!.uuid, { description: 'x' }, c.uuid))).toBe('creator_helper_right')
    expect(code(() => submitProfile(env.ctx, helperRow!.uuid, { description: 'x' }))).toBe('creator_inactive')
    adminRevokeCreator(env.ctx, OWNER, c.uuid, { reason: 'Pause' })
    expect(code(() => submitProfile(env.ctx, c.uuid, { description: 'x' }))).toBe('creator_inactive')
  })

  it('migration 29 is idempotent and the last one', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    migrate(db)
    migrateCreatorProfiles(db)
    migrateCreatorProfiles(db)
    expect(MIGRATIONS.at(-1)!.version).toBe(29)
    expect(all(db, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'creator_profiles'")).toHaveLength(1)
  })
})

describe('creator profile: public list', () => {
  it('only active creators, only approved data, ordered by tier and fans', async () => {
    const env = makeEnv()
    const top = await creator(env, 'Topper', 'top')
    const part = await creator(env, 'Partna', 'partner')
    const start = await creator(env, 'Starty')
    const gone = await creator(env, 'Gone')
    // Fans: Starty 2, Partna 1.
    const fans = await players(env, 'F1', 'F2', 'F3')
    redeemCreatorCode(env.ctx, fans[0]!.uuid, { code: start.code })
    redeemCreatorCode(env.ctx, fans[1]!.uuid, { code: start.code })
    redeemCreatorCode(env.ctx, fans[2]!.uuid, { code: part.code })
    // Symbol + Fan-Umhang: wartend unsichtbar, freigegeben sichtbar.
    uploadSymbol(env.ctx, part.uuid, solidPng(32, 32), {})
    uploadFanCape(env.ctx, part.uuid, solidPng(64, 32), { name: 'Drache' })
    let p = siteCreators(env.ctx).creators.find((c) => c.uuid === part.uuid)!
    expect(p.symbol).toBeNull()
    expect(p.fanCape).toBeNull()
    decideQueueItem(env.ctx, OWNER, queueId(env, part.uuid, 'symbol'), true)
    decideQueueItem(env.ctx, OWNER, queueId(env, part.uuid, 'fan_cape'), true)
    // Pack gelistet.
    run(
      env.ctx.db,
      `INSERT INTO shared_packs (id, code, owner_uuid, name, summary, pack_version, mc_version, loader, loader_version, index_files,
         own_jars, other_files, revision, bytes, sha256, duration, installs, created_at, updated_at, expires_at, creator_listed)
       VALUES (?, 'ABCD2345', ?, 'Redstone Pack', 'Viel Redstone', '1.0', '1.21.11', 'fabric', NULL, 1, 0, 0, 1, 10, ?, '7d', 12, ?, ?, NULL, 1)`,
      'p'.repeat(22), part.uuid, 'a'.repeat(64), env.clock.t, env.clock.t,
    )
    adminRevokeCreator(env.ctx, OWNER, gone.uuid, { reason: 'Weg' })
    // Nur wartendes Profil bei Topper → öffentlich leer.
    submitProfile(env.ctx, top.uuid, { description: 'Geheim' })

    const list = siteCreators(env.ctx).creators
    expect(list.map((c) => c.name)).toEqual(['Topper', 'Partna', 'Starty'])
    expect(list[0]).toMatchObject({ tier: 'top', description: '', links: [], fanCount: 0, fanCape: null })
    expect(list[2]).toMatchObject({ tier: 'starter', fanCount: 2 })
    p = list[1]!
    expect(p).toMatchObject({ tier: 'partner', fanCount: 1, packs: [{ code: 'TRS-ABCD-2345', name: 'Redstone Pack', summary: 'Viel Redstone', mcVersion: '1.21.11', loader: 'fabric', installs: 12 }] })
    expect(p.symbol?.url).toMatch(/^\/v1\/creator-symbols\/[0-9a-f]{64}\.png$/)
    expect(p.fanCape).toMatchObject({ name: 'Drache', scale: 1, frames: 1, frameTimeMs: null, url: expect.stringMatching(/^\/v1\/capes\/u[0-9a-f]{20}\.png\?v=[0-9a-f]{12}$/) })
    expect(JSON.stringify(list)).not.toContain('Geheim')

    // Einzeln: Name ohne Groß/klein, Creator-Code, sonst 404 (auch entzogene).
    expect(siteCreator(env.ctx, 'topper').creator.uuid).toBe(top.uuid)
    expect(siteCreator(env.ctx, part.code.toLowerCase()).creator.name).toBe('Partna')
    expect(code(() => siteCreator(env.ctx, 'Gone'))).toBe('creator_not_found')
    expect(code(() => siteCreator(env.ctx, 'nobody'))).toBe('creator_not_found')
    expect(code(() => siteCreator(env.ctx, '../etc'))).toBe('creator_not_found')
    // Konto gesperrt → nicht in der Liste.
    run(
      env.ctx.db,
      "INSERT INTO sanctions (uuid, kind, reason_code, created_at, updated_at, created_by, created_role) VALUES (?, 'account_ban', 'other', ?, ?, ?, 'admin')",
      start.uuid, env.clock.t - DAY, env.clock.t - DAY, ADMIN,
    )
    expect(siteCreators(env.ctx).creators.map((c) => c.name)).toEqual(['Topper', 'Partna'])
  })
})

// ---------------------------------------------------------------- HTTP

describe('creator profile: HTTP', () => {
  const open: { close: () => Promise<void> }[] = []
  afterEach(async () => {
    for (const s of open.splice(0)) await s.close()
    setContext(undefined)
  })

  async function serve(env: TestEnv) {
    const app = createApp({
      onError: async (error, event) => {
        const api = isApiError(error) ? error : isApiError((error as { cause?: unknown }).cause) ? (error as { cause: never }).cause : null
        setResponseStatus(event, (api as { status?: number } | null)?.status ?? 500)
        await send(event, JSON.stringify({ error: { code: (api as { code?: string } | null)?.code ?? 'internal_error', details: (api as { details?: unknown } | null)?.details } }), 'application/json')
      },
    })
    const router = createRouter()
    router.get('/v1/site/creators', siteList)
    router.get('/v1/site/creators/:name', siteOne)
    router.get('/v1/creator/dashboard', dashboardRoute)
    router.post('/v1/creator/codes', codesPost)
    router.get('/v1/creator/profile', profileGet)
    router.put('/v1/creator/profile', profilePut)
    router.delete('/v1/creator/profile/pending', profileDelete)
    router.put('/v1/creator/symbol', symbolPut)
    router.get('/v1/me/creator', meCreator)
    router.get('/v1/admin/creators/queue', queueGet)
    router.post('/v1/admin/creators/queue/:id/approve', queueApprove)
    router.post('/v1/admin/creators/queue/:id/reject', queueReject)
    app.use(router)
    const server: Server = createServer(toNodeListener(app))
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
    open.push({ close: () => new Promise<void>((r) => server.close(() => r())) })
    setContext(env.ctx)
    return `http://127.0.0.1:${(server.address() as { port: number }).port}`
  }

  it('cookie session with CSRF on creator routes, Bearer still works, public routes cache + 404', async () => {
    const env = makeEnv()
    const base = await serve(env)
    const c = await creator(env, 'Pixelmaus', 'partner')
    const web = createWebSession(env.ctx, c.uuid)
    const cookie = { cookie: `trs_session=${web.token}` }
    const json = { 'content-type': 'application/json' }

    // Lesen mit Cookie (ohne CSRF ok), mit Bearer wie bisher, ohne beides 401.
    const dash = await fetch(`${base}/v1/creator/dashboard`, { headers: cookie })
    expect(dash.status).toBe(200)
    const dashBody = (await dash.json()) as { uuid: string, profile: unknown, codes: unknown[] }
    expect(dashBody.uuid).toBe(c.uuid)
    expect(dashBody.profile).toEqual({ approved: null, pending: null, rejected: null })
    expect((await fetch(`${base}/v1/creator/dashboard`, { headers: { authorization: `Bearer ${c.token}` } })).status).toBe(200)
    expect((await fetch(`${base}/v1/creator/dashboard`)).status).toBe(401)
    const me = await fetch(`${base}/v1/me/creator`, { headers: cookie })
    expect(((await me.json()) as { creator: { uuid: string } }).creator.uuid).toBe(c.uuid)

    // Ändern mit Cookie nur mit CSRF-Token.
    const body = JSON.stringify({ description: 'Hallo <b>Welt</b>', links: [{ type: 'twitch', url: 'https://twitch.tv/pixel' }] })
    const noCsrf = await fetch(`${base}/v1/creator/profile`, { method: 'PUT', headers: { ...cookie, ...json }, body })
    expect(noCsrf.status).toBe(403)
    expect(((await noCsrf.json()) as { error: { code: string } }).error.code).toBe('csrf_failed')
    const wrong = await fetch(`${base}/v1/creator/profile`, { method: 'PUT', headers: { ...cookie, ...json, 'x-csrf-token': 'nope' }, body })
    expect(wrong.status).toBe(403)
    const put = await fetch(`${base}/v1/creator/profile`, { method: 'PUT', headers: { ...cookie, ...json, 'x-csrf-token': web.csrf }, body })
    expect(put.status).toBe(200)
    expect(((await put.json()) as { pending: { description: string } }).pending.description).toBe('Hallo <b>Welt</b>')
    const bad = await fetch(`${base}/v1/creator/profile`, {
      method: 'PUT', headers: { ...cookie, ...json, 'x-csrf-token': web.csrf }, body: JSON.stringify({ links: [{ type: 'youtube', url: 'https://evil.com/x' }] }),
    })
    expect(bad.status).toBe(400)
    expect(((await bad.json()) as { error: { code: string, details: unknown } }).error).toEqual({ code: 'invalid_profile', details: { field: 'links.0', reason: 'wrong_host' } })

    // Bestehende Creator-Routen: Cookie + CSRF und Bearer.
    const codeCookie = await fetch(`${base}/v1/creator/codes`, { method: 'POST', headers: { ...cookie, ...json, 'x-csrf-token': web.csrf }, body: JSON.stringify({ code: 'PIXEL-WEB' }) })
    expect(codeCookie.status).toBe(201)
    const codeNoCsrf = await fetch(`${base}/v1/creator/codes`, { method: 'POST', headers: { ...cookie, ...json }, body: JSON.stringify({ code: 'PIXEL-WEB2' }) })
    expect(codeNoCsrf.status).toBe(403)
    const codeBearer = await fetch(`${base}/v1/creator/codes`, { method: 'POST', headers: { authorization: `Bearer ${c.token}`, ...json }, body: JSON.stringify({ code: 'PIXEL-APP' }) })
    expect(codeBearer.status).toBe(201)
    const sym = await fetch(`${base}/v1/creator/symbol`, { method: 'PUT', headers: { ...cookie, 'content-type': 'image/png', 'x-csrf-token': web.csrf }, body: solidPng(32, 32) })
    expect(sym.status).toBe(200)
    // Bearer schlägt das Cookie: ein Bearer ohne Gültigkeit wird nicht durch das Cookie gerettet.
    expect((await fetch(`${base}/v1/creator/dashboard`, { headers: { ...cookie, authorization: 'Bearer nope' } })).status).toBe(401)

    // Bearer: Profil lesen und einreichen (Launcher).
    const pb = await fetch(`${base}/v1/creator/profile`, { headers: { authorization: `Bearer ${c.token}` } })
    expect(((await pb.json()) as { pending: { description: string } }).pending.description).toBe('Hallo <b>Welt</b>')
    expect((await fetch(`${base}/v1/creator/profile/pending`, { method: 'DELETE', headers: { authorization: `Bearer ${c.token}` } })).status).toBe(204)
    await fetch(`${base}/v1/creator/profile`, { method: 'PUT', headers: { authorization: `Bearer ${c.token}`, ...json }, body })

    // Kein Creator → 403 creator_inactive (auch über das Cookie).
    const fan = await login(env, 'Fan')
    const fanWeb = createWebSession(env.ctx, fan.user.uuid)
    const notCreator = await fetch(`${base}/v1/creator/dashboard`, { headers: { cookie: `trs_session=${fanWeb.token}` } })
    expect(notCreator.status).toBe(403)
    expect(((await notCreator.json()) as { error: { code: string } }).error.code).toBe('creator_inactive')

    // Team: Prüfung über die Warteschlange (X-Admin-Key = Owner).
    const key = { 'x-admin-key': 'admin-key-0123456789abcdef-0123456789abcdef' }
    await login(env, 'Theredstonee', ADMIN)
    const q = (await (await fetch(`${base}/v1/admin/creators/queue`, { headers: key })).json()) as { items: { id: string, kind: string, profile?: { description: string } }[] }
    const item = q.items.find((i) => i.kind === 'profile')!
    expect(item.profile?.description).toBe('Hallo <b>Welt</b>')
    expect((await fetch(`${base}/v1/admin/creators/queue/${item.id}/approve`, { method: 'POST', headers: key })).status).toBe(204)
    expect((await fetch(`${base}/v1/admin/creators/queue/p999/reject`, { method: 'POST', headers: { ...key, ...json }, body: JSON.stringify({ reason: 'x' }) })).status).toBe(404)

    // Öffentlich: Liste + einzeln, cachebar; 404 für Unbekannte.
    const list = await fetch(`${base}/v1/site/creators`)
    expect(list.headers.get('cache-control')).toBe('public, max-age=300')
    const creators = ((await list.json()) as { creators: { name: string, description: string, links: unknown[] }[] }).creators
    expect(creators).toHaveLength(1)
    expect(creators[0]).toMatchObject({ name: 'Pixelmaus', description: 'Hallo <b>Welt</b>', links: [{ type: 'twitch', url: 'https://twitch.tv/pixel' }] })
    const one = await fetch(`${base}/v1/site/creators/pixelmaus`)
    expect(one.status).toBe(200)
    expect(one.headers.get('cache-control')).toBe('public, max-age=300')
    expect((await fetch(`${base}/v1/site/creators/PIXEL-WEB`)).status).toBe(200)
    const missing = await fetch(`${base}/v1/site/creators/nobody`)
    expect(missing.status).toBe(404)
    expect(((await missing.json()) as { error: { code: string } }).error.code).toBe('creator_not_found')
    expect((await fetch(`${base}/v1/site/creators/${encodeURIComponent('<x>')}`)).status).toBe(404)
  })
})
