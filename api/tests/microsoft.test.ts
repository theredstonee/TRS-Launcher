import { createServer, type Server } from 'node:http'
import { createApp, createRouter, toNodeListener } from 'h3'
import { afterEach, describe, expect, it } from 'vitest'
import { startMsMock, type MsMock } from '../scripts/ms-mock.mjs'
import { createSanction } from '../server/lib/sanctions'
import { all, one } from '../server/lib/db'
import { setContext } from '../server/lib/context'
import { OAuthStateStore, safeReturnTo, xstsErrorCode, MS_STATE_TTL_MS } from '../server/lib/microsoft'
import { ownerStaff } from '../server/lib/team'
import { WEB_SESSION_TTL_MS, webSession } from '../server/lib/weblogin'
import callback from '../server/routes/auth/microsoft/callback.get'
import loginRoute from '../server/routes/auth/microsoft/login.get'
import webMe from '../server/routes/v1/web/me.get'
import { ADMIN, makeEnv, type TestEnv } from './helpers'

const CLIENT_ID = 'ac3d320e-d0a2-4910-8e3c-b425883984a9'
const SECRET = 'test-client-secret'
const STEVE = '0123456789abcdef0123456789abcdef'

const ACCOUNTS = [
  { name: 'Steve', uuid: STEVE },
  { name: 'Kiddo', uuid: 'c'.repeat(32), xerr: 2148916238 },
  { name: 'NoXbox', uuid: 'd'.repeat(32), xerr: 2148916233 },
  { name: 'NoGame', uuid: 'e'.repeat(32), noGame: true },
  { name: 'Nope', uuid: 'f'.repeat(32), denied: true },
  { name: 'Theredstonee', uuid: ADMIN },
]

interface Harness {
  env: TestEnv
  mock: MsMock
  base: string
  close: () => Promise<void>
}

const open: Harness[] = []
afterEach(async () => {
  for (const h of open.splice(0)) await h.close()
  setContext(undefined)
})

/** Echter HTTP-Server mit den Anmelde-Routen + Microsoft-/Xbox-/Minecraft-Attrappe. */
async function harness(opts: { secret?: string, disabled?: boolean } = {}): Promise<Harness> {
  const app = createApp()
  const router = createRouter()
  router.get('/auth/microsoft/login', loginRoute)
  router.get('/auth/microsoft/callback', callback)
  router.get('/v1/web/me', webMe)
  app.use(router)
  const server: Server = createServer(toNodeListener(app))
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
  const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`
  const mock = await startMsMock({ clientId: CLIENT_ID, clientSecret: SECRET, accounts: ACCOUNTS })
  const env = makeEnv({
    env: opts.disabled ? {} : {
      MS_CLIENT_ID: CLIENT_ID,
      MS_CLIENT_SECRET: opts.secret ?? SECRET,
      MS_REDIRECT_URI: `${base}/auth/microsoft/callback`,
      MS_AUTHORITY_URL: mock.authority,
      XBOX_USER_AUTH_URL: mock.xbl,
      XBOX_XSTS_URL: mock.xsts,
      MINECRAFT_SERVICES_URL: mock.minecraft,
      ALLOW_INSECURE_MS_URLS: 'true',
      TRUST_PROXY: 'none',
    },
  })
  setContext(env.ctx)
  const h = { env, mock, base, close: async () => { await mock.close(); await new Promise((r) => server.close(r)) } }
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

/** Einmal komplett durchklicken: Login → Microsoft (Attrappe) → Callback. */
async function signIn(h: Harness, account: string, opts: { returnTo?: string, sessionCookie?: string, tamperState?: boolean } = {}) {
  const start = await fetch(`${h.base}/auth/microsoft/login${opts.returnTo ? `?return=${encodeURIComponent(opts.returnTo)}` : ''}`, { redirect: 'manual' })
  const stateCookie = cookies(start).get('trs_oauth')
  const authorize = start.headers.get('location')!
  h.mock.next = account
  const ms = await fetch(authorize, { redirect: 'manual' })
  let back = ms.headers.get('location')!
  if (opts.tamperState) back = back.replace(/state=[^&]+/, 'state=AAAA')
  const cookieHeader = [stateCookie ? `trs_oauth=${stateCookie.value}` : '', opts.sessionCookie ? `trs_session=${opts.sessionCookie}` : ''].filter(Boolean).join('; ')
  const cb = await fetch(back, { redirect: 'manual', headers: cookieHeader ? { cookie: cookieHeader } : {} })
  return { start, stateCookie, authorize, cb, back, body: await cb.text() }
}

describe('Microsoft sign-in: building blocks', () => {
  it('state store: one use, must match, expires', () => {
    let t = 1000
    const s = new OAuthStateStore(() => t)
    const a = s.create('/team')
    expect(a.challenge).toMatch(/^[A-Za-z0-9_-]{43}$/)
    expect(s.take(a.handle, 'wrong')).toBeNull()
    // Falscher state verbraucht den Eintrag trotzdem (kein Durchprobieren).
    expect(s.take(a.handle, a.state)).toBeNull()
    const b = s.create('/x')
    expect(s.take(b.handle, b.state)?.returnTo).toBe('/x')
    expect(s.take(b.handle, b.state)).toBeNull()
    const c = s.create('/y')
    t += MS_STATE_TTL_MS + 1
    expect(s.take(c.handle, c.state)).toBeNull()
    expect(s.take(undefined, 'x')).toBeNull()
  })

  it('return paths stay on the site', () => {
    expect(safeReturnTo('/team/moderator?lang=de')).toBe('/team/moderator?lang=de')
    for (const bad of ['//evil.example', '/\\evil.example', 'https://evil.example', '/auth/microsoft/login', '/v1/web/me', 'javascript:alert(1)', 42, undefined, '/a b']) {
      expect(safeReturnTo(bad)).toBe('/applications')
    }
  })

  it('maps XSTS errors to readable codes', () => {
    expect(xstsErrorCode(2148916238)).toBe('child_account')
    expect(xstsErrorCode(2148916233)).toBe('no_xbox')
    expect(xstsErrorCode(2148916235)).toBe('xbox_region')
    expect(xstsErrorCode(2148916236)).toBe('xbox_verification')
    expect(xstsErrorCode(1)).toBe('ms_failed')
  })
})

describe('Microsoft sign-in: full flow against test doubles', () => {
  it('signs in, creates the account, sets a strict session cookie and never stores tokens', async () => {
    const h = await harness()
    const r = await signIn(h, 'Steve', { returnTo: '/team/moderator' })
    // Weiterleitung zu Microsoft mit PKCE S256 + state, Cookie nur für /auth/microsoft, Lax, httpOnly, kurz.
    const url = new URL(r.authorize)
    expect(url.origin + url.pathname).toBe(`${h.mock.authority}/authorize`)
    expect(url.searchParams.get('client_id')).toBe(CLIENT_ID)
    expect(url.searchParams.get('code_challenge_method')).toBe('S256')
    expect(url.searchParams.get('scope')).toBe('XboxLive.signin')
    expect(url.searchParams.get('client_secret')).toBeNull()
    expect(r.stateCookie?.attrs).toContain('httponly')
    expect(r.stateCookie?.attrs).toContain('samesite=lax')
    expect(r.stateCookie?.attrs).toContain('path=/auth/microsoft')
    expect(r.stateCookie?.attrs).toContain('max-age=600')
    // Rücksprung: Seite mit Meta-Refresh (damit das Strict-Cookie beim nächsten Aufruf mitkommt).
    expect(r.cb.status).toBe(200)
    expect(r.body).toContain('url=/team/moderator')
    const set = cookies(r.cb)
    const session = set.get('trs_session')!
    expect(session.attrs).toContain('httponly')
    expect(session.attrs).toContain('secure')
    expect(session.attrs).toContain('samesite=strict')
    expect(session.attrs).toContain(`max-age=${WEB_SESSION_TTL_MS / 1000}`)
    expect(set.get('trs_oauth')?.value).toBe('')
    // Konto + Sitzung da, nur Hashes gespeichert, keine Microsoft-/Xbox-/Minecraft-Tokens irgendwo.
    expect(one<{ name: string }>(h.env.ctx.db, 'SELECT name FROM users WHERE uuid = ?', STEVE)?.name).toBe('Steve')
    expect(webSession(h.env.ctx, session.value, undefined, false).uuid).toBe(STEVE)
    const dump = JSON.stringify([all(h.env.ctx.db, 'SELECT * FROM web_sessions'), all(h.env.ctx.db, 'SELECT * FROM users'), all(h.env.ctx.db, 'SELECT * FROM admin_log')])
    for (const secret of [session.value, 'ms.Steve', 'xbl.Steve', 'xsts.Steve', 'mc.Steve']) expect(dump).not.toContain(secret)
    // /v1/web/me mit Cookie.
    const me = await fetch(`${h.base}/v1/web/me`, { headers: { cookie: `trs_session=${session.value}` } })
    expect(await me.json()).toMatchObject({ uuid: STEVE, name: 'Steve', team: null })
    expect(h.mock.calls).toEqual([
      'GET /consumers/oauth2/v2.0/authorize',
      'POST /consumers/oauth2/v2.0/token',
      'POST /xbl',
      'POST /xsts',
      'POST /mc/authentication/login_with_xbox',
      'GET /mc/minecraft/profile',
    ])
  })

  it('rotates the session on sign-in and shows team rights for owners', async () => {
    const h = await harness()
    const first = cookies((await signIn(h, 'Theredstonee')).cb).get('trs_session')!.value
    const second = cookies((await signIn(h, 'Theredstonee', { sessionCookie: first })).cb).get('trs_session')!.value
    expect(second).not.toBe(first)
    expect(() => webSession(h.env.ctx, first, undefined, false)).toThrow()
    const me = await (await fetch(`${h.base}/v1/web/me`, { headers: { cookie: `trs_session=${second}` } })).json()
    expect(me.team).toMatchObject({ owner: true, rank: 1000 })
    expect(me.team.permissions).toContain('roles.manage')
    // Anmeldung von Team-Mitgliedern steht im Audit-Log.
    expect(all<{ action: string }>(h.env.ctx.db, "SELECT action FROM admin_log WHERE action = 'web.login'")).toHaveLength(2)
  })

  it('rejects tampered or replayed state, and missing cookies', async () => {
    const h = await harness()
    const tampered = await signIn(h, 'Steve', { tamperState: true })
    expect(tampered.cb.headers.get('location')).toBe('/login?error=ms_state')
    // Wiederholen desselben Rücksprungs (gleiche Parameter + Cookie) geht nicht.
    const ok = await signIn(h, 'Steve')
    expect(ok.cb.status).toBe(200)
    const replay = await fetch(ok.back, { redirect: 'manual', headers: { cookie: `trs_oauth=${ok.stateCookie!.value}` } })
    expect(replay.headers.get('location')).toBe('/login?error=ms_state')
    // Ohne Cookie (z. B. anderer Browser) → state passt nicht.
    const start = await fetch(`${h.base}/auth/microsoft/login`, { redirect: 'manual' })
    h.mock.next = 'Steve'
    const back = (await fetch(start.headers.get('location')!, { redirect: 'manual' })).headers.get('location')!
    expect((await fetch(back, { redirect: 'manual' })).headers.get('location')).toBe('/login?error=ms_state')
  })

  it('explains the usual failures: cancelled, child account, no Xbox profile, no Minecraft, banned, wrong secret', async () => {
    const h = await harness()
    expect((await signIn(h, 'Nope')).cb.headers.get('location')).toBe('/login?error=ms_cancelled')
    expect((await signIn(h, 'Kiddo')).cb.headers.get('location')).toBe('/login?error=child_account')
    expect((await signIn(h, 'NoXbox')).cb.headers.get('location')).toBe('/login?error=no_xbox')
    expect((await signIn(h, 'NoGame')).cb.headers.get('location')).toBe('/login?error=no_minecraft')
    // Keine Sitzung, kein Konto bei Fehlern.
    expect(one(h.env.ctx.db, 'SELECT 1 AS x FROM users WHERE uuid = ?', 'c'.repeat(32))).toBeUndefined()
    expect(all(h.env.ctx.db, 'SELECT * FROM web_sessions')).toHaveLength(0)
    // Gesperrt: kein Login.
    await signIn(h, 'Steve')
    createSanction(h.env.ctx, ownerStaff(ADMIN), { uuid: STEVE, kind: 'account_ban', minutes: 60, reasonCode: 'cheating' })
    expect(all(h.env.ctx.db, 'SELECT * FROM web_sessions WHERE uuid = ?', STEVE)).toHaveLength(0)
    expect((await signIn(h, 'Steve')).cb.headers.get('location')).toBe('/login?error=banned')

    const wrong = await harness({ secret: 'not-the-secret' })
    expect((await signIn(wrong, 'Steve')).cb.headers.get('location')).toBe('/login?error=ms_failed')
  })

  it('is off without MS_CLIENT_ID/MS_CLIENT_SECRET', async () => {
    const h = await harness({ disabled: true })
    expect(h.env.ctx.config.microsoft).toBeNull()
    const start = await fetch(`${h.base}/auth/microsoft/login`, { redirect: 'manual' })
    expect(start.headers.get('location')).toBe('/login?error=ms_disabled')
  })
})
