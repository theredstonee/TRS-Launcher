import { createHash } from 'node:crypto'
import { createServer, type Server } from 'node:http'
import { DatabaseSync } from 'node:sqlite'
import { createApp, createRouter, send, setResponseStatus, toNodeListener } from 'h3'
import { afterEach, describe, expect, it } from 'vitest'
import { buildRobots } from '../shared/seo'
import { setContext } from '../server/lib/context'
import { all, migrate, one } from '../server/lib/db'
import { isApiError } from '../server/lib/errors'
import { MIGRATIONS, migratePitchLinks } from '../server/lib/migrations'
import {
  createPitchLink,
  deletePitchLink,
  listPitchLinks,
  MAX_PITCH_LINKS,
  newPitchToken,
  PITCH_TOKEN,
  pitchTokenHash,
  resolvePitchToken,
  revokePitchLink,
} from '../server/lib/pitch'
import {
  buildPitchBundle,
  parseRange,
  PITCH_CSP,
  pitchContentType,
  resolvePitchPath,
  setPitchBundleLoader,
  withBase,
} from '../server/lib/pitch-http'
import { ownerStaff, setMemberRoles, teamOf, type Staff } from '../server/lib/team'
import pitchIndex from '../server/routes/pitch/[token]/index.get'
import pitchFile from '../server/routes/pitch/[token]/[...path].get'
import previewIndex from '../server/routes/admin/pitch/preview/index.get'
import previewFile from '../server/routes/admin/pitch/preview/[...path].get'
import adminList from '../server/routes/v1/admin/pitch-links/index.get'
import adminCreate from '../server/routes/v1/admin/pitch-links/index.post'
import adminRevoke from '../server/routes/v1/admin/pitch-links/[id]/revoke.post'
import adminDelete from '../server/routes/v1/admin/pitch-links/[id].delete'
import { code } from './chathelpers'
import { ADMIN, login, makeEnv, solidPng, type TestEnv } from './helpers'

const OWNER: Staff = ownerStaff(ADMIN)
const DAY = 24 * 60 * 60 * 1000
const ADMIN_KEY = 'admin-key-0123456789abcdef-0123456789abcdef'

const INDEX = '<!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="assets/app.css"></head><body><script type="module" src="assets/app.js"></script></body></html>'
const PNG = solidPng(4, 4)
const VIDEO = Buffer.from(Array.from({ length: 100 }, (_, i) => i))
const FILES = new Map<string, Buffer>([
  ['index.html', Buffer.from(INDEX)],
  ['assets/app.js', Buffer.from('console.log("pitch")')],
  ['assets/app.css', Buffer.from('body{margin:0}')],
  ['assets/logo.png', PNG],
  ['assets/font.woff2', Buffer.from('wOF2xxxx')],
  ['assets/intro.mp4', VIDEO],
  ['assets/icon.svg', Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"/>')],
])

function setup(): TestEnv {
  const env = makeEnv({ env: { TRUST_PROXY: 'none', SITE_URL: 'https://site.test' } })
  setPitchBundleLoader(async () => FILES)
  return env
}

afterEach(() => {
  setPitchBundleLoader(null)
})

// ---------------------------------------------------------------- Token

describe('pitch: tokens', () => {
  it('32 base62 characters, random, stored only as SHA-256', () => {
    const seen = new Set<string>()
    for (let i = 0; i < 200; i++) {
      const t = newPitchToken()
      expect(t).toMatch(PITCH_TOKEN)
      seen.add(t)
    }
    expect(seen.size).toBe(200)
    // Alle 62 Zeichen kommen vor (gleichverteilt, nicht nur Hex o. Ä.).
    const chars = new Set([...seen].join(''))
    expect(chars.size).toBe(62)
    expect(pitchTokenHash('abc')).toBe(createHash('sha256').update('abc').digest('hex'))
  })

  it('create: plain token only in the answer, DB holds hash + last 4 characters', () => {
    const env = setup()
    const r = createPitchLink(env.ctx, OWNER, { label: 'Investor A', mode: 'manual', lang: 'en', expiresInDays: 7 })
    expect(r.token).toMatch(PITCH_TOKEN)
    expect(r.url).toBe(`https://site.test/pitch/${r.token}?mode=manual&lang=en`)
    expect(r.link).toMatchObject({ label: 'Investor A', mode: 'manual', lang: 'en', tokenHint: r.token.slice(-4), status: 'active', views: 0, lastViewAt: null, createdBy: ADMIN })
    expect(r.link.expiresAt).toBe(new Date(env.clock.t + 7 * DAY).toISOString())
    const row = one<Record<string, unknown>>(env.ctx.db, 'SELECT * FROM pitch_links WHERE id = ?', r.link.id)!
    expect(row.token_hash).toBe(pitchTokenHash(r.token))
    expect(JSON.stringify(row)).not.toContain(r.token)
    expect(JSON.stringify(listPitchLinks(env.ctx, OWNER))).not.toContain(r.token)
    expect(all(env.ctx.db, "SELECT action, target FROM admin_log WHERE action LIKE 'pitch.%'")).toEqual([{ action: 'pitch.create', target: String(r.link.id) }])
    // Ohne Ablauf + Standardwerte
    const n = createPitchLink(env.ctx, OWNER, { label: '  Messe  ', mode: 'loop', lang: 'de', expiresInDays: null })
    expect(n.link).toMatchObject({ label: 'Messe', expiresAt: null })
    expect(createPitchLink(env.ctx, OWNER, { label: 'Zeile\u0000eins\nzwei‮', mode: 'loop', lang: 'de' }).link.label).toBe('Zeileeins zwei')
    expect(code(() => createPitchLink(env.ctx, OWNER, { label: '​', mode: 'loop', lang: 'de' }))).toBe('invalid_request')
  })

  it('resolve: valid only while active (revoked, expired, unknown, malformed → null)', () => {
    const env = setup()
    const a = createPitchLink(env.ctx, OWNER, { label: 'A', mode: 'loop', lang: 'de', expiresInDays: 1 })
    const b = createPitchLink(env.ctx, OWNER, { label: 'B', mode: 'loop', lang: 'de' })
    expect(resolvePitchToken(env.ctx, a.token)).toEqual({ id: a.link.id })
    expect(resolvePitchToken(env.ctx, newPitchToken())).toBeNull()
    expect(resolvePitchToken(env.ctx, `${a.token}x`)).toBeNull()
    expect(resolvePitchToken(env.ctx, a.token.toLowerCase() === a.token ? a.token.toUpperCase() : a.token.toLowerCase())).toBeNull()
    expect(resolvePitchToken(env.ctx, '../../etc/passwd')).toBeNull()
    revokePitchLink(env.ctx, OWNER, b.link.id)
    expect(resolvePitchToken(env.ctx, b.token)).toBeNull()
    expect(revokePitchLink(env.ctx, OWNER, b.link.id).status).toBe('revoked')
    expect(all(env.ctx.db, "SELECT action FROM admin_log WHERE action = 'pitch.revoke'")).toHaveLength(1)
    env.clock.advance(DAY)
    expect(resolvePitchToken(env.ctx, a.token)).toBeNull()
    expect(listPitchLinks(env.ctx, OWNER).map((l) => [l.label, l.status])).toEqual([['B', 'revoked'], ['A', 'expired']])
    deletePitchLink(env.ctx, OWNER, a.link.id)
    expect(code(() => deletePitchLink(env.ctx, OWNER, a.link.id))).toBe('not_found')
    expect(code(() => revokePitchLink(env.ctx, OWNER, 999))).toBe('not_found')
    expect(listPitchLinks(env.ctx, OWNER)).toHaveLength(1)
  })

  it('limit: at most MAX_PITCH_LINKS links', () => {
    const env = setup()
    const ins = env.ctx.db.prepare("INSERT INTO pitch_links (token_hash, token_hint, label, mode, lang, created_by, created_at) VALUES (?, 'abcd', 'x', 'loop', 'de', 'api-key', 1)")
    for (let i = 0; i < MAX_PITCH_LINKS; i++) ins.run(pitchTokenHash(String(i)))
    expect(code(() => createPitchLink(env.ctx, OWNER, { label: 'one more', mode: 'loop', lang: 'de' }))).toBe('pitch_limit')
  })

  it('rights: only pitch.manage (admin role yes, content team and moderator no)', async () => {
    const env = setup()
    const admin = await login(env, 'Admina')
    const content = await login(env, 'Content')
    const mod = await login(env, 'Moddy')
    await login(env, 'Theredstonee', ADMIN)
    setMemberRoles(env.ctx, OWNER, admin.user.uuid, ['admin'])
    setMemberRoles(env.ctx, OWNER, content.user.uuid, ['content'])
    setMemberRoles(env.ctx, OWNER, mod.user.uuid, ['moderator'])
    for (const u of [content, mod]) {
      const s = teamOf(env.ctx, u.user.uuid)!
      expect(code(() => listPitchLinks(env.ctx, s))).toBe('missing_permission')
      expect(code(() => createPitchLink(env.ctx, s, { label: 'x', mode: 'loop', lang: 'de' }))).toBe('missing_permission')
    }
    const r = createPitchLink(env.ctx, teamOf(env.ctx, admin.user.uuid)!, { label: 'by admin', mode: 'loop', lang: 'de' })
    expect(listPitchLinks(env.ctx, OWNER)[0]).toMatchObject({ id: r.link.id, createdBy: admin.user.uuid, createdByName: 'Admina' })
  })

  it('migration 30 is idempotent, the last one, and grants pitch.manage to owner + admin only', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    migrate(db)
    migratePitchLinks(db)
    migratePitchLinks(db)
    expect(MIGRATIONS.at(-1)!.version).toBe(30)
    expect(all(db, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'pitch_links'")).toHaveLength(1)
    const roles = all<{ id: string, permissions: string }>(db, "SELECT id, permissions FROM team_roles WHERE id IN ('owner', 'admin', 'content', 'moderator') ORDER BY id")
    const has = Object.fromEntries(roles.map((r) => [r.id, (JSON.parse(r.permissions) as string[]).includes('pitch.manage')]))
    expect(has).toEqual({ admin: true, content: false, moderator: false, owner: true })
  })
})

// ---------------------------------------------------------------- Bundle

describe('pitch: bundle helpers', () => {
  const bundle = buildPitchBundle(FILES)

  it('content types', () => {
    expect(bundle.get('index.html')!.type).toBe('text/html; charset=utf-8')
    expect(bundle.get('assets/app.js')!.type).toBe('text/javascript; charset=utf-8')
    expect(bundle.get('assets/app.css')!.type).toBe('text/css; charset=utf-8')
    expect(bundle.get('assets/logo.png')!.type).toBe('image/png')
    expect(bundle.get('assets/font.woff2')!.type).toBe('font/woff2')
    expect(bundle.get('assets/intro.mp4')!.type).toBe('video/mp4')
    expect(bundle.get('assets/icon.svg')!.type).toBe('image/svg+xml')
    expect(pitchContentType('x.webm')).toBe('video/webm')
    expect(pitchContentType('x.unknown')).toBe('application/octet-stream')
  })

  it('path lookup only in the table – traversal and odd names are refused', () => {
    expect(resolvePitchPath(bundle, '')).toMatchObject({ index: true, path: 'index.html' })
    expect(resolvePitchPath(bundle, '/')).toMatchObject({ index: true })
    expect(resolvePitchPath(bundle, 'index.html')).toMatchObject({ index: true })
    expect(resolvePitchPath(bundle, 'assets/app.js')).toMatchObject({ index: false, path: 'assets/app.js' })
    expect(resolvePitchPath(bundle, 'assets/app%2Ejs')).toMatchObject({ path: 'assets/app.js' })
    for (const bad of ['../package.json', 'assets/../index.html', '%2e%2e/x', 'assets/..%2F..%2Fx', 'assets%2Fapp.js', 'assets\\app.js', 'assets/app.js%00', 'assets//app.js', './index.html', '.env', 'assets/%E0%A4%A', 'assets/app.js:x', 'assets/missing.js']) {
      expect(resolvePitchPath(bundle, bad), bad).toBeNull()
    }
    // Unsaubere Namen schaffen es gar nicht erst ins Bundle.
    const odd = buildPitchBundle(new Map([['../evil.js', Buffer.from('x')], ['a/.hidden', Buffer.from('x')], ['ok.js', Buffer.from('x')]]))
    expect([...odd.keys()]).toEqual(['ok.js'])
  })

  it('base tag goes right after <head>', () => {
    expect(withBase('<html><head><title>x</title></head></html>', '/pitch/T/')).toBe('<html><head><base href="/pitch/T/"><title>x</title></head></html>')
    expect(withBase('<HTML><HEAD lang="x"></HEAD>', '/b/')).toBe('<HTML><HEAD lang="x"><base href="/b/"></HEAD>')
    expect(withBase('<p>no head</p>', '/b/')).toBe('<base href="/b/"><p>no head</p>')
    // <header> ist kein <head>
    expect(withBase('<header></header><head></head>', '/b/')).toBe('<header></header><head><base href="/b/"></head>')
  })

  it('range parsing', () => {
    expect(parseRange(undefined, 100)).toBeNull()
    expect(parseRange('bytes=0-9', 100)).toEqual({ start: 0, end: 9 })
    expect(parseRange('bytes=90-', 100)).toEqual({ start: 90, end: 99 })
    expect(parseRange('bytes=-10', 100)).toEqual({ start: 90, end: 99 })
    expect(parseRange('bytes=50-500', 100)).toEqual({ start: 50, end: 99 })
    expect(parseRange('bytes=100-', 100)).toBe('invalid')
    expect(parseRange('bytes=9-1', 100)).toBe('invalid')
    expect(parseRange('bytes=0-1,5-6', 100)).toBeNull()
  })

  it('robots.txt keeps crawlers out of /pitch/', () => {
    expect(buildRobots('https://site.test').split('\n')).toContain('Disallow: /pitch/')
  })
})

// ---------------------------------------------------------------- HTTP

describe('pitch: HTTP', () => {
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
        await send(event, JSON.stringify({ error: { code: (api as { code?: string } | null)?.code ?? 'internal_error' } }), 'application/json')
      },
    })
    const router = createRouter()
    router.get('/pitch/:token', pitchIndex)
    router.get('/pitch/:token/**:path', pitchFile)
    router.get('/admin/pitch/preview', previewIndex)
    router.get('/admin/pitch/preview/**:path', previewFile)
    router.get('/v1/admin/pitch-links', adminList)
    router.post('/v1/admin/pitch-links', adminCreate)
    router.post('/v1/admin/pitch-links/:id/revoke', adminRevoke)
    router.delete('/v1/admin/pitch-links/:id', adminDelete)
    app.use(router)
    const server: Server = createServer(toNodeListener(app))
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
    open.push({ close: () => new Promise<void>((r) => server.close(() => r())) })
    setContext(env.ctx)
    return `http://127.0.0.1:${(server.address() as { port: number }).port}`
  }

  const key = { 'x-admin-key': ADMIN_KEY, 'content-type': 'application/json' }

  async function newLink(base: string, body: Record<string, unknown> = { label: 'Pitch A', mode: 'loop', lang: 'de' }) {
    const res = await fetch(`${base}/v1/admin/pitch-links`, { method: 'POST', headers: key, body: JSON.stringify(body) })
    expect(res.status).toBe(201)
    const json = (await res.json()) as { link: { id: number, tokenHint: string }, url: string }
    expect(Object.keys(json).sort()).toEqual(['link', 'url'])
    const token = new URL(json.url).pathname.split('/')[2]!
    return { ...json, token }
  }

  function expectPitchHeaders(res: Response) {
    expect(res.headers.get('content-security-policy')).toBe(PITCH_CSP)
    expect(res.headers.get('x-robots-tag')).toBe('noindex, nofollow')
    expect(res.headers.get('referrer-policy')).toBe('no-referrer')
    expect(res.headers.get('x-content-type-options')).toBe('nosniff')
    expect(res.headers.get('x-frame-options')).toBe('DENY')
  }

  it('valid link: index with base tag + headers, assets with types/cache/ETag/Range, views only for the index', async () => {
    const env = setup()
    const base = await serve(env)
    const l = await newLink(base, { label: 'Pitch A', mode: 'manual', lang: 'en', expiresInDays: 30 })
    expect(l.token).toMatch(PITCH_TOKEN)
    expect(l.url).toBe(`https://site.test/pitch/${l.token}?mode=manual&lang=en`)

    const page = await fetch(`${base}/pitch/${l.token}?mode=manual&lang=en`)
    expect(page.status).toBe(200)
    expect(page.headers.get('content-type')).toBe('text/html; charset=utf-8')
    expect(page.headers.get('cache-control')).toBe('no-store')
    expectPitchHeaders(page)
    const html = await page.text()
    expect(html).toContain(`<head><base href="/pitch/${l.token}/"><meta charset="utf-8">`)
    expect(html).toContain('src="assets/app.js"')

    expect((await fetch(`${base}/pitch/${l.token}/`)).status).toBe(200)

    const js = await fetch(`${base}/pitch/${l.token}/assets/app.js`)
    expect(js.status).toBe(200)
    expect(js.headers.get('content-type')).toBe('text/javascript; charset=utf-8')
    expect(js.headers.get('cache-control')).toBe('private, max-age=3600')
    expectPitchHeaders(js)
    expect(await js.text()).toBe('console.log("pitch")')
    const css = await fetch(`${base}/pitch/${l.token}/assets/app.css`)
    expect(css.headers.get('content-type')).toBe('text/css; charset=utf-8')
    const png = await fetch(`${base}/pitch/${l.token}/assets/logo.png`)
    expect(png.headers.get('content-type')).toBe('image/png')
    expect(Buffer.from(await png.arrayBuffer()).equals(PNG)).toBe(true)
    const etag = png.headers.get('etag')!
    expect((await fetch(`${base}/pitch/${l.token}/assets/logo.png`, { headers: { 'if-none-match': etag } })).status).toBe(304)
    const part = await fetch(`${base}/pitch/${l.token}/assets/intro.mp4`, { headers: { range: 'bytes=10-19' } })
    expect(part.status).toBe(206)
    expect(part.headers.get('content-type')).toBe('video/mp4')
    expect(part.headers.get('content-range')).toBe('bytes 10-19/100')
    expect([...Buffer.from(await part.arrayBuffer())]).toEqual([10, 11, 12, 13, 14, 15, 16, 17, 18, 19])
    expect((await fetch(`${base}/pitch/${l.token}/assets/intro.mp4`, { headers: { range: 'bytes=500-' } })).status).toBe(416)

    // 2 Seitenaufrufe, Dateien zählen nicht
    const list = (await (await fetch(`${base}/v1/admin/pitch-links`, { headers: key })).json()) as { links: { id: number, views: number, lastViewAt: string | null }[] }
    expect(list.links[0]).toMatchObject({ id: l.link.id, views: 2, lastViewAt: new Date(env.clock.t).toISOString() })
    expect(JSON.stringify(list)).not.toContain(l.token)
  })

  it('invalid, unknown, revoked and expired tokens + traversal → the same generic 404 page', async () => {
    const env = setup()
    const base = await serve(env)
    const a = await newLink(base)
    const b = await newLink(base, { label: 'B', mode: 'loop', lang: 'de', expiresInDays: 1 })
    const ref = await fetch(`${base}/pitch/${newPitchToken()}`)
    expect(ref.status).toBe(404)
    expectPitchHeaders(ref)
    expect(ref.headers.get('content-type')).toBe('text/html; charset=utf-8')
    const body404 = await ref.text()
    expect(body404).not.toMatch(/revoked|expired|token|widerrufen|abgelaufen/i)

    const expect404 = async (path: string) => {
      const res = await fetch(`${base}${path}`)
      expect(res.status, path).toBe(404)
      expect(await res.text(), path).toBe(body404)
    }
    await expect404('/pitch/short')
    await expect404(`/pitch/${a.token}x`)
    await expect404(`/pitch/${newPitchToken()}/assets/app.js`)
    await expect404(`/pitch/${a.token}/assets/missing.js`)
    await expect404(`/pitch/${a.token}/assets/..%2F..%2Fpackage.json`)
    await expect404(`/pitch/${a.token}/assets/%2e%2e%2f%2e%2e%2fpackage.json`)
    await expect404(`/pitch/${a.token}/assets%5Capp.js`)
    await expect404(`/pitch/${a.token}/.env`)

    expect((await fetch(`${base}/v1/admin/pitch-links/${a.link.id}/revoke`, { method: 'POST', headers: key })).status).toBe(200)
    await expect404(`/pitch/${a.token}`)
    await expect404(`/pitch/${a.token}/assets/app.js`)

    expect((await fetch(`${base}/pitch/${b.token}`)).status).toBe(200)
    env.clock.advance(DAY)
    await expect404(`/pitch/${b.token}`)

    const del = await fetch(`${base}/v1/admin/pitch-links/${b.link.id}`, { method: 'DELETE', headers: key })
    expect(del.status).toBe(204)
    expect((await fetch(`${base}/v1/admin/pitch-links/${b.link.id}`, { method: 'DELETE', headers: key })).status).toBe(404)
    expect((await fetch(`${base}/v1/admin/pitch-links/abc/revoke`, { method: 'POST', headers: key })).status).toBe(404)
    // Aufrufe nur für gültige Links gezählt
    expect(one<{ v: number }>(env.ctx.db, 'SELECT SUM(views) AS v FROM pitch_links')!.v).toBe(0)
  })

  it('team API: validation, 401 without login, 403 without pitch.manage; preview only for the team', async () => {
    const env = setup()
    const base = await serve(env)
    for (const body of [{ label: '' }, { label: 'x'.repeat(81) }, { label: 'x', mode: 'auto' }, { label: 'x', lang: 'fr' }, { label: 'x', expiresInDays: 3 }, { label: 'x', token: 'mine' }]) {
      const res = await fetch(`${base}/v1/admin/pitch-links`, { method: 'POST', headers: key, body: JSON.stringify(body) })
      expect(res.status, JSON.stringify(body)).toBe(400)
    }
    const d = await newLink(base, { label: 'Defaults' })
    expect(d.url).toMatch(/\?mode=loop&lang=de$/)

    expect((await fetch(`${base}/v1/admin/pitch-links`)).status).toBe(401)
    const mod = await login(env, 'Moddy')
    await login(env, 'Theredstonee', ADMIN)
    setMemberRoles(env.ctx, OWNER, mod.user.uuid, ['moderator'])
    const bearer = { authorization: `Bearer ${mod.token}` }
    expect((await fetch(`${base}/v1/admin/pitch-links`, { headers: bearer })).status).toBe(403)
    expect((await fetch(`${base}/v1/admin/pitch-links`, { method: 'POST', headers: { ...bearer, 'content-type': 'application/json' }, body: '{"label":"x"}' })).status).toBe(403)

    // Vorschau: ohne Anmeldung / ohne Recht → 404; Team mit Recht → Bundle mit eigener Basis, ohne Zählung.
    const anon = await fetch(`${base}/admin/pitch/preview?mode=manual`)
    expect(anon.status).toBe(404)
    expectPitchHeaders(anon)
    expect((await fetch(`${base}/admin/pitch/preview`, { headers: bearer })).status).toBe(404)
    expect((await fetch(`${base}/admin/pitch/preview/assets/app.js`)).status).toBe(404)
    const prev = await fetch(`${base}/admin/pitch/preview?mode=manual&lang=en`, { headers: { 'x-admin-key': ADMIN_KEY } })
    expect(prev.status).toBe(200)
    expectPitchHeaders(prev)
    expect(await prev.text()).toContain('<base href="/admin/pitch/preview/">')
    const asset = await fetch(`${base}/admin/pitch/preview/assets/app.css`, { headers: { 'x-admin-key': ADMIN_KEY } })
    expect(asset.headers.get('content-type')).toBe('text/css; charset=utf-8')
    expect((await fetch(`${base}/admin/pitch/preview/assets/..%2F..%2Fpackage.json`, { headers: { 'x-admin-key': ADMIN_KEY } })).status).toBe(404)
    expect(one<{ v: number }>(env.ctx.db, 'SELECT SUM(views) AS v FROM pitch_links')!.v).toBe(0)
  })

  it('no bundle loaded → 404 instead of a crash', async () => {
    const env = setup()
    setPitchBundleLoader(async () => new Map())
    const base = await serve(env)
    const l = await newLink(base)
    expect((await fetch(`${base}/pitch/${l.token}`)).status).toBe(404)
  })
})
