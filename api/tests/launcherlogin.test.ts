import { createServer, type Server } from 'node:http'
import { createApp, createRouter, send, setResponseHeaders, setResponseStatus, toNodeListener, type EventHandler } from 'h3'
import { afterEach, describe, expect, it } from 'vitest'
import { all, one } from '../server/lib/db'
import { setContext } from '../server/lib/context'
import { isApiError } from '../server/lib/errors'
import {
  LAUNCHER_LOGIN_TTL_MS,
  browserLabel,
  formatLoginCode,
  normalizeLoginCode,
} from '../server/lib/launcherlogin'
import { createSanction } from '../server/lib/sanctions'
import { ownerStaff, setMemberRoles } from '../server/lib/team'
import approveRoute from '../server/routes/v1/launcher-login/approve.post'
import denyRoute from '../server/routes/v1/launcher-login/deny.post'
import lookupRoute from '../server/routes/v1/launcher-login/lookup.post'
import cancelRoute from '../server/routes/v1/web/launcher-login/cancel.post'
import createRoute from '../server/routes/v1/web/launcher-login/index.post'
import pollRoute from '../server/routes/v1/web/launcher-login/poll.post'
import webMe from '../server/routes/v1/web/me.get'
import { ADMIN, login, makeEnv, type TestEnv } from './helpers'

interface Harness {
  env: TestEnv
  base: string
  close: () => Promise<void>
}
const open: Harness[] = []
afterEach(async () => {
  for (const h of open.splice(0)) await h.close()
  setContext(undefined)
})

/** Echter HTTP-Server mit den Routen von §29 + JSON-Fehlern wie in server/error-handler.ts. */
async function harness(): Promise<Harness> {
  const app = createApp({
    onError: async (error, event) => {
      const cause = (error as { cause?: unknown }).cause
      const api = isApiError(error) ? error : isApiError(cause) ? cause : null
      setResponseStatus(event, api?.status ?? 500)
      if (api?.headers) setResponseHeaders(event, api.headers)
      await send(event, JSON.stringify({ error: api ? { code: api.code, message: api.message, ...api.details } : { code: 'internal_error' } }), 'application/json')
    },
  })
  const router = createRouter()
  const routes: [string, EventHandler][] = [
    ['/v1/web/launcher-login', createRoute],
    ['/v1/web/launcher-login/poll', pollRoute],
    ['/v1/web/launcher-login/cancel', cancelRoute],
    ['/v1/launcher-login/lookup', lookupRoute],
    ['/v1/launcher-login/approve', approveRoute],
    ['/v1/launcher-login/deny', denyRoute],
  ]
  for (const [p, h] of routes) router.post(p, h)
  router.get('/v1/web/me', webMe)
  app.use(router)
  const server: Server = createServer(toNodeListener(app))
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
  const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`
  const env = makeEnv({ env: { TRUST_PROXY: 'none', SITE_URL: 'https://site.test' } })
  setContext(env.ctx)
  const h = { env, base, close: () => new Promise<void>((r) => server.close(() => r())) }
  open.push(h)
  return h
}

function cookies(res: Response): Map<string, { value: string, attrs: string }> {
  const out = new Map<string, { value: string, attrs: string }>()
  for (const line of res.headers.getSetCookie()) {
    const [pair, ...rest] = line.split(';')
    const i = pair!.indexOf('=')
    out.set(pair!.slice(0, i).trim(), { value: pair!.slice(i + 1), attrs: rest.join(';').toLowerCase() })
  }
  return out
}

const FIREFOX = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0'

async function post(h: Harness, path: string, body: unknown, headers: Record<string, string> = {}) {
  const res = await fetch(`${h.base}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...headers },
    body: JSON.stringify(body),
  })
  const text = await res.text()
  return { res, status: res.status, // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Test: beliebige JSON-Antworten
    json: text ? JSON.parse(text) as Record<string, any> : null }
}

/** Website: Anfrage anlegen (wie der Browser) → Token, Code, Cookie. */
async function start(h: Harness, extra: Record<string, string> = {}, returnTo?: string) {
  const r = await post(h, '/v1/web/launcher-login', returnTo ? { return: returnTo } : {}, { 'user-agent': FIREFOX, 'sec-fetch-site': 'same-origin', ...extra })
  expect(r.status).toBe(201)
  const cookie = cookies(r.res).get('trs_llogin')!
  return { token: r.json!.token as string, code: r.json!.code as string, link: r.json!.link as string, cookie, raw: r }
}

const poll = (h: Harness, token: string, browser?: string, extra: Record<string, string> = {}) =>
  post(h, '/v1/web/launcher-login/poll', { token }, { ...(browser ? { cookie: `trs_llogin=${browser}${extra.session ? `; trs_session=${extra.session}` : ''}` } : {}) })

const bearer = (token: string) => ({ authorization: `Bearer ${token}` })

describe('launcher sign-in: building blocks', () => {
  it('codes are six easy characters, shown as XXX-XXX, typed in any form', () => {
    expect(normalizeLoginCode('k7q-2mx')).toBe('K7Q2MX')
    expect(normalizeLoginCode(' K7Q 2MX ')).toBe('K7Q2MX')
    for (const bad of ['K7Q2M', 'K7Q2MXX', 'K0Q2MX', 'K1Q2MX', 'KIQ2MX', 'KLQ2MX', 'KOQ2MX', 'KUQ2MX', '', 'x'.repeat(30)]) expect(normalizeLoginCode(bad)).toBeNull()
    expect(formatLoginCode('K7Q2MX')).toBe('K7Q-2MX')
  })

  it('browser label is coarse and only from a fixed list', () => {
    expect(browserLabel(FIREFOX)).toBe('Firefox · Windows')
    expect(browserLabel('Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15')).toBe('Safari · macOS')
    expect(browserLabel('Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36 Edg/140.0')).toBe('Edge · Linux')
    expect(browserLabel('curl/8.0')).toBeNull()
    expect(browserLabel('<script>alert(1)</script>')).toBeNull()
    expect(browserLabel(undefined)).toBeNull()
  })
})

describe('launcher sign-in: full flow', () => {
  it('link → confirm in the launcher → the browser gets a normal strict session, exactly once', async () => {
    const h = await harness()
    const steve = await login(h.env, 'Steve')
    const s = await start(h, {}, '/team')
    expect(s.code).toMatch(/^[2-9A-HJKMNP-TV-Z]{3}-[2-9A-HJKMNP-TV-Z]{3}$/)
    expect(s.link).toBe(`trs-launcher://web-login/${s.token}`)
    expect(s.token).toMatch(/^[A-Za-z0-9_-]{43}$/)
    // Browser-Wert: httpOnly, Secure, Strict, nur auf den Anmelde-Routen.
    expect(s.cookie.attrs).toContain('httponly')
    expect(s.cookie.attrs).toContain('secure')
    expect(s.cookie.attrs).toContain('samesite=strict')
    expect(s.cookie.attrs).toContain('path=/v1/web/launcher-login')
    // Gespeichert werden nur Hashes – weder Token noch Browser-Wert im Klartext.
    const row = one<Record<string, unknown>>(h.env.ctx.db, 'SELECT * FROM launcher_logins')!
    expect(JSON.stringify(row)).not.toContain(s.token)
    expect(JSON.stringify(row)).not.toContain(s.cookie.value)
    expect(row.browser).toBe('Firefox · Windows')

    expect((await poll(h, s.token, s.cookie.value)).json).toMatchObject({ status: 'pending' })

    const look = await post(h, '/v1/launcher-login/lookup', { token: s.token }, bearer(steve.token))
    expect(look.status).toBe(200)
    expect(look.json!.request).toMatchObject({ code: s.code, site: 'site.test', browser: 'Firefox · Windows' })
    expect(look.json!.request.id).toMatch(/^[A-Za-z0-9_-]{22}$/)

    // Nicht bestätigt → nichts passiert.
    expect((await poll(h, s.token, s.cookie.value)).json).toMatchObject({ status: 'pending' })
    const ok = await post(h, '/v1/launcher-login/approve', { id: look.json!.request.id, code: s.code }, bearer(steve.token))
    expect(ok.status).toBe(204)

    const done = await poll(h, s.token, s.cookie.value)
    expect(done.json).toEqual({ status: 'approved', returnTo: '/team' })
    const session = cookies(done.res).get('trs_session')!
    expect(session.attrs).toContain('httponly')
    expect(session.attrs).toContain('samesite=strict')
    expect(session.attrs).toContain('secure')
    expect(cookies(done.res).get('trs_llogin')?.value).toBe('')
    const me = await fetch(`${h.base}/v1/web/me`, { headers: { cookie: `trs_session=${session.value}` } })
    expect(me.status).toBe(200)
    expect(await me.json()).toMatchObject({ uuid: steve.user.uuid, name: 'Steve', team: null })

    // Einmalig: danach ist die Anfrage weg.
    expect((await poll(h, s.token, s.cookie.value)).json).toEqual({ status: 'expired' })
    expect(all(h.env.ctx.db, 'SELECT * FROM launcher_logins')).toHaveLength(0)
    expect((await post(h, '/v1/launcher-login/approve', { id: look.json!.request.id, code: s.code }, bearer(steve.token))).status).toBe(404)
  })

  it('only the browser that started it can redeem it; an older session cookie is replaced (rotation)', async () => {
    const h = await harness()
    const steve = await login(h.env, 'Steve')
    const alex = await login(h.env, 'Alex')
    // Alex war in diesem Browser schon angemeldet (altes Sitzungs-Cookie).
    const a = await start(h)
    const la = await post(h, '/v1/launcher-login/lookup', { token: a.token }, bearer(alex.token))
    await post(h, '/v1/launcher-login/approve', { id: la.json!.request.id, code: a.code }, bearer(alex.token))
    const old = cookies((await poll(h, a.token, a.cookie.value)).res).get('trs_session')!.value

    const s = await start(h)
    const look = await post(h, '/v1/launcher-login/lookup', { token: s.token }, bearer(steve.token))
    await post(h, '/v1/launcher-login/approve', { id: look.json!.request.id, code: s.code }, bearer(steve.token))
    // Ohne Cookie / mit fremdem Cookie: nichts.
    expect((await poll(h, s.token)).json).toEqual({ status: 'expired' })
    expect((await poll(h, s.token, a.cookie.value)).json).toEqual({ status: 'expired' })
    expect((await poll(h, s.token, 'x'.repeat(43))).json).toEqual({ status: 'expired' })
    // Der richtige Browser schon – und das alte Sitzungs-Token gilt danach nicht mehr.
    const done = await poll(h, s.token, s.cookie.value, { session: old })
    expect(done.json!.status).toBe('approved')
    expect((await fetch(`${h.base}/v1/web/me`, { headers: { cookie: `trs_session=${old}` } })).status).toBe(401)
  })

  it('expires after two minutes: lookup, approve and poll all fail', async () => {
    const h = await harness()
    const steve = await login(h.env, 'Steve')
    const s = await start(h)
    const look = await post(h, '/v1/launcher-login/lookup', { token: s.token }, bearer(steve.token))
    h.env.clock.advance(LAUNCHER_LOGIN_TTL_MS + 1)
    const late = await post(h, '/v1/launcher-login/lookup', { token: s.token }, bearer(steve.token))
    expect(late.status).toBe(404)
    expect(late.json!.error.code).toBe('login_request_expired')
    expect((await post(h, '/v1/launcher-login/approve', { id: look.json!.request.id, code: s.code }, bearer(steve.token))).json!.error.code).toBe('login_request_expired')
    expect((await poll(h, s.token, s.cookie.value)).json).toEqual({ status: 'expired' })
  })

  it('wrong code, double answer and deny', async () => {
    const h = await harness()
    const steve = await login(h.env, 'Steve')
    const s = await start(h)
    const { id } = (await post(h, '/v1/launcher-login/lookup', { token: s.token }, bearer(steve.token))).json!.request
    const other = s.code === 'AAA-AAA' ? 'BBB-BBB' : 'AAA-AAA'
    expect((await post(h, '/v1/launcher-login/approve', { id, code: other }, bearer(steve.token))).status).toBe(404)
    expect((await post(h, '/v1/launcher-login/deny', { id, code: s.code }, bearer(steve.token))).status).toBe(204)
    const again = await post(h, '/v1/launcher-login/approve', { id, code: s.code }, bearer(steve.token))
    expect(again.status).toBe(409)
    expect(again.json!.error.code).toBe('login_request_used')
    const denied = await poll(h, s.token, s.cookie.value)
    expect(denied.json).toEqual({ status: 'denied', reason: 'denied' })
    expect(cookies(denied.res).has('trs_session')).toBe(false)
  })

  it('manual code (other PC): works in any spelling, wrong codes are limited strictly', async () => {
    const h = await harness()
    const steve = await login(h.env, 'Steve')
    const s = await start(h)
    const typed = s.code.toLowerCase().replace('-', ' ')
    const hit = await post(h, '/v1/launcher-login/lookup', { code: typed }, bearer(steve.token))
    expect(hit.status).toBe(200)
    expect(hit.json!.request.code).toBe(s.code)
    expect((await post(h, '/v1/launcher-login/lookup', { code: 'K0Q-2MX' }, bearer(steve.token))).json!.error.code).toBe('invalid_code')
    const wrong = s.code.startsWith('Z') ? 'YYY-YYY' : 'ZZZ-ZZZ'
    for (let i = 0; i < 5; i++) expect((await post(h, '/v1/launcher-login/lookup', { code: wrong }, bearer(steve.token))).status).toBe(404)
    // Danach ist Schluss – auch der richtige Code wird erst nach einer Pause wieder angenommen.
    const blocked = await post(h, '/v1/launcher-login/lookup', { code: s.code }, bearer(steve.token))
    expect(blocked.status).toBe(429)
    expect(Number(blocked.res.headers.get('retry-after'))).toBeGreaterThan(0)
  })

  it('banned accounts cannot confirm; a ban before redeeming stops the session', async () => {
    const h = await harness()
    const steve = await login(h.env, 'Steve')
    const alex = await login(h.env, 'Alex')
    const s = await start(h)
    const { id } = (await post(h, '/v1/launcher-login/lookup', { token: s.token }, bearer(alex.token))).json!.request
    createSanction(h.env.ctx, ownerStaff(ADMIN), { uuid: alex.user.uuid, kind: 'account_ban', minutes: 60, reasonCode: 'cheating' })
    // Die Sperre beendet die Sitzungen des Kontos (401) bzw. meldet `banned` (403) – bestätigt wird nie.
    const r = await post(h, '/v1/launcher-login/approve', { id, code: s.code }, bearer(alex.token))
    expect([401, 403]).toContain(r.status)
    expect((await poll(h, s.token, s.cookie.value)).json).toMatchObject({ status: 'pending' })

    // Freigegeben, dann gesperrt → keine Sitzung.
    await post(h, '/v1/launcher-login/approve', { id, code: s.code }, bearer(steve.token))
    createSanction(h.env.ctx, ownerStaff(ADMIN), { uuid: steve.user.uuid, kind: 'account_ban', minutes: 60, reasonCode: 'cheating' })
    const p = await poll(h, s.token, s.cookie.value)
    expect(p.json).toEqual({ status: 'denied', reason: 'banned' })
    expect(cookies(p.res).has('trs_session')).toBe(false)
  })

  it('team members get their rights like after a Microsoft sign-in (audit entry “launcher”)', async () => {
    const h = await harness()
    const mod = await login(h.env, 'Moddy')
    setMemberRoles(h.env.ctx, ownerStaff(ADMIN), mod.user.uuid, ['moderator'])
    const s = await start(h, {}, '/admin')
    const { id } = (await post(h, '/v1/launcher-login/lookup', { token: s.token }, bearer(mod.token))).json!.request
    await post(h, '/v1/launcher-login/approve', { id, code: s.code }, bearer(mod.token))
    const done = await poll(h, s.token, s.cookie.value)
    expect(done.json).toEqual({ status: 'approved', returnTo: '/admin' })
    const me = await (await fetch(`${h.base}/v1/web/me`, { headers: { cookie: `trs_session=${cookies(done.res).get('trs_session')!.value}` } })).json()
    expect(me.team.permissions).toContain('reports.view')
    expect(one(h.env.ctx.db, "SELECT detail FROM admin_log WHERE action = 'web.login' AND actor = ?", mod.user.uuid)).toEqual({ detail: 'launcher' })
  })

  it('protects the website routes: same origin only, rate limit, cancel, bad return paths', async () => {
    const h = await harness()
    expect((await post(h, '/v1/web/launcher-login', {}, { 'sec-fetch-site': 'cross-site' })).status).toBe(403)
    expect((await post(h, '/v1/web/launcher-login', {}, { origin: 'https://evil.example' })).json!.error.code).toBe('cross_site')
    expect((await post(h, '/v1/web/launcher-login', {}, { origin: 'https://site.test' })).status).toBe(201)
    // Offene Weiterleitung ist nicht möglich.
    const steve = await login(h.env, 'Steve')
    const s = await start(h, {}, '//evil.example')
    const { id } = (await post(h, '/v1/launcher-login/lookup', { token: s.token }, bearer(steve.token))).json!.request
    await post(h, '/v1/launcher-login/approve', { id, code: s.code }, bearer(steve.token))
    expect((await poll(h, s.token, s.cookie.value)).json!.returnTo).toBe('/applications')
    // Zurück: Anfrage verschwindet.
    const c = await start(h)
    expect((await post(h, '/v1/web/launcher-login/cancel', { token: c.token }, { cookie: `trs_llogin=${c.cookie.value}` })).status).toBe(204)
    expect((await post(h, '/v1/launcher-login/lookup', { token: c.token }, bearer(steve.token))).status).toBe(404)
    // Ein neuer Start im selben Browser ersetzt die alte Anfrage.
    const d1 = await start(h)
    await post(h, '/v1/web/launcher-login', {}, { cookie: `trs_llogin=${d1.cookie.value}` })
    expect((await post(h, '/v1/launcher-login/lookup', { token: d1.token }, bearer(steve.token))).status).toBe(404)
    // 10 je IP und 10 Minuten (hier schon 6 verbraucht).
    let last = 0
    for (let i = 0; i < 6; i++) last = (await post(h, '/v1/web/launcher-login', {})).status
    expect(last).toBe(429)
  })

  it('without a Bearer token the launcher routes answer 401', async () => {
    const h = await harness()
    const s = await start(h)
    expect((await post(h, '/v1/launcher-login/lookup', { token: s.token })).status).toBe(401)
    expect((await post(h, '/v1/launcher-login/approve', { id: 'a'.repeat(22), code: s.code })).status).toBe(401)
  })
})
