import { describe, expect, it } from 'vitest'
import { authenticate } from '../server/lib/auth'
import { all } from '../server/lib/db'
import { platformOf, pickLatest } from '../server/lib/site'
import {
  WEB_LOGIN_TTL_MS,
  WEB_SESSION_TTL_MS,
  approveWebLogin,
  endWebSession,
  normalizeWebLoginCode,
  pollWebLogin,
  startWebLogin,
  sweepWebLogins,
  webSession,
} from '../server/lib/weblogin'
import { ADMIN, login, makeEnv } from './helpers'

function code(fn: () => unknown): string {
  try {
    fn()
  } catch (e) {
    return (e as { code: string }).code
  }
  return 'ok'
}

async function authed(env: ReturnType<typeof makeEnv>, name: string, uuid?: string) {
  const r = await login(env, name, uuid)
  return authenticate(env.ctx, `Bearer ${r.token}`)
}

describe('website sign-in', () => {
  it('normalises codes the way people type them', () => {
    expect(normalizeWebLoginCode('7k3p qx9m')).toBe('7K3P-QX9M')
    expect(normalizeWebLoginCode('OIL0-ABCD')).toBe('0110-ABCD')
    expect(normalizeWebLoginCode('7K3P-QX9')).toBeNull()
    expect(normalizeWebLoginCode('7K3P-QX9U')).toBeNull()
  })

  it('an admin approves a code, the website gets one session and the code is used up', async () => {
    const env = makeEnv()
    const admin = await authed(env, 'Theredstonee', ADMIN)
    const start = startWebLogin(env.ctx)
    expect(start.code).toMatch(/^[0-9A-Z]{4}-[0-9A-Z]{4}$/)
    // Nur Hashes in der Datenbank.
    expect(JSON.stringify(all(env.ctx.db, 'SELECT * FROM web_logins'))).not.toContain(start.code)
    expect(JSON.stringify(all(env.ctx.db, 'SELECT * FROM web_logins'))).not.toContain(start.pollSecret)

    expect(pollWebLogin(env.ctx, start.pollSecret)).toEqual({ status: 'pending' })
    approveWebLogin(env.ctx, admin, start.code.toLowerCase().replace('-', ' '))
    const done = pollWebLogin(env.ctx, start.pollSecret)
    expect(done.status).toBe('approved')
    if (done.status !== 'approved') return
    expect(done.name).toBe('Theredstonee')
    expect(JSON.stringify(all(env.ctx.db, 'SELECT * FROM web_sessions'))).not.toContain(done.token)

    // Einmalig: zweite Abfrage und zweite Bestätigung gehen nicht.
    expect(pollWebLogin(env.ctx, start.pollSecret)).toEqual({ status: 'expired' })
    expect(code(() => approveWebLogin(env.ctx, admin, start.code))).toBe('invalid_code')

    const s = webSession(env.ctx, done.token, undefined, false)
    expect(s.uuid).toBe(ADMIN)
    expect(code(() => webSession(env.ctx, done.token, undefined, true))).toBe('csrf_failed')
    expect(code(() => webSession(env.ctx, done.token, 'wrong', true))).toBe('csrf_failed')
    expect(webSession(env.ctx, done.token, done.csrf, true).uuid).toBe(ADMIN)

    endWebSession(env.ctx, s.tokenHash)
    expect(code(() => webSession(env.ctx, done.token, undefined, false))).toBe('unauthorized')
  })

  it('only admins can approve, and codes expire', async () => {
    const env = makeEnv()
    const player = await authed(env, 'Steve')
    const admin = await authed(env, 'Theredstonee', ADMIN)
    const start = startWebLogin(env.ctx)
    expect(code(() => approveWebLogin(env.ctx, player, start.code))).toBe('not_admin')
    expect(code(() => approveWebLogin(env.ctx, admin, 'nonsense'))).toBe('invalid_code')
    expect(code(() => approveWebLogin(env.ctx, admin, '0000-0000'))).toBe('invalid_code')
    env.clock.advance(WEB_LOGIN_TTL_MS + 1)
    expect(code(() => approveWebLogin(env.ctx, admin, start.code))).toBe('expired')
    expect(pollWebLogin(env.ctx, start.pollSecret)).toEqual({ status: 'expired' })
    sweepWebLogins(env.ctx)
    expect(all(env.ctx.db, 'SELECT * FROM web_logins')).toHaveLength(0)
  })

  it('sessions end after 8 hours', async () => {
    const env = makeEnv()
    const admin = await authed(env, 'Theredstonee', ADMIN)
    const start = startWebLogin(env.ctx)
    approveWebLogin(env.ctx, admin, start.code)
    const done = pollWebLogin(env.ctx, start.pollSecret)
    if (done.status !== 'approved') throw new Error('not approved')
    env.clock.advance(WEB_SESSION_TTL_MS + 1)
    expect(code(() => webSession(env.ctx, done.token, undefined, false))).toBe('unauthorized')
    expect(code(() => pollWebLogin(env.ctx, 'short'))).toBe('invalid_request')
  })
})

describe('website data', () => {
  const asset = (name: string) => ({ name, browser_download_url: `https://github.com/theredstonee/TRS-Launcher/releases/download/v0.4.3/${name}`, size: 1 })

  it('recognises the download files of a release', () => {
    expect(platformOf('TRS.Launcher_0.4.3_x64-setup.exe')).toBe('windows')
    expect(platformOf('TRS.Launcher_0.4.3_x64-setup.exe.sig')).toBeNull()
    expect(platformOf('TRS.Launcher_0.4.3_amd64.AppImage')).toBe('appimage')
    expect(platformOf('trs-launcher_0.4.3_amd64.deb')).toBe('deb')
    expect(platformOf('trs-launcher-0.4.3-1.x86_64.rpm')).toBe('rpm')
    expect(platformOf('latest.json')).toBeNull()
  })

  it('picks the newest version tag and ignores the fixed channel releases', () => {
    const latest = pickLatest([
      { tag_name: 'updater', html_url: 'u', published_at: '2026-09-24T00:00:00Z', draft: false, assets: [asset('latest.json')] },
      { tag_name: 'v0.4.2', html_url: 'a', published_at: '2026-09-20T00:00:00Z', draft: false, assets: [] },
      { tag_name: 'v0.4.3', html_url: 'b', published_at: '2026-09-22T00:00:00Z', draft: false, assets: [asset('TRS.Launcher_0.4.3_x64-setup.exe'), { ...asset('evil_x64-setup.exe'), browser_download_url: 'https://evil.example/x64-setup.exe' }] },
      { tag_name: 'v0.5.0', html_url: 'c', published_at: '2026-09-25T00:00:00Z', draft: true, assets: [] },
    ])
    expect(latest?.version).toBe('0.4.3')
    expect(latest?.assets.map((a) => a.platform)).toEqual(['windows'])
  })
})
