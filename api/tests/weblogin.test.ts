import { describe, expect, it } from 'vitest'
import { createSanction } from '../server/lib/sanctions'
import { all } from '../server/lib/db'
import { platformOf, pickLatest } from '../server/lib/site'
import { ownerStaff } from '../server/lib/team'
import { MAX_WEB_SESSIONS, WEB_SESSION_TTL_MS, createWebSession, endWebSession, sweepWebLogins, webSession } from '../server/lib/weblogin'
import { ADMIN, login, makeEnv } from './helpers'

function code(fn: () => unknown): string {
  try {
    fn()
  } catch (e) {
    return (e as { code: string }).code
  }
  return 'ok'
}

describe('website sessions (Microsoft sign-in)', () => {
  it('stores only hashes, needs CSRF for changes, rotates and ends', async () => {
    const env = makeEnv()
    const u = (await login(env, 'Steve')).user.uuid
    const s = createWebSession(env.ctx, u)
    expect(JSON.stringify(all(env.ctx.db, 'SELECT * FROM web_sessions'))).not.toContain(s.token)
    expect(webSession(env.ctx, s.token, undefined, false)).toMatchObject({ uuid: u, name: 'Steve', csrf: s.csrf })
    expect(code(() => webSession(env.ctx, s.token, undefined, true))).toBe('csrf_failed')
    expect(code(() => webSession(env.ctx, s.token, 'wrong', true))).toBe('csrf_failed')
    expect(webSession(env.ctx, s.token, s.csrf, true).uuid).toBe(u)
    // Rotation: die alte Sitzung (Cookie vor der Anmeldung) ist danach ungültig.
    const next = createWebSession(env.ctx, u, s.token)
    expect(code(() => webSession(env.ctx, s.token, undefined, false))).toBe('unauthorized')
    endWebSession(env.ctx, webSession(env.ctx, next.token, undefined, false).tokenHash)
    expect(code(() => webSession(env.ctx, next.token, undefined, false))).toBe('unauthorized')
    expect(code(() => webSession(env.ctx, 'short', undefined, false))).toBe('unauthorized')
  })

  it('expires after 8 hours, keeps at most 5 per account, ends on a ban', async () => {
    const env = makeEnv()
    const u = (await login(env, 'Steve')).user.uuid
    const first = createWebSession(env.ctx, u)
    for (let i = 0; i < MAX_WEB_SESSIONS; i++) {
      env.clock.advance(1000)
      createWebSession(env.ctx, u)
    }
    expect(all(env.ctx.db, 'SELECT * FROM web_sessions WHERE uuid = ?', u)).toHaveLength(MAX_WEB_SESSIONS)
    expect(code(() => webSession(env.ctx, first.token, undefined, false))).toBe('unauthorized')
    const s = createWebSession(env.ctx, u)
    env.clock.advance(WEB_SESSION_TTL_MS + 1)
    expect(code(() => webSession(env.ctx, s.token, undefined, false))).toBe('unauthorized')
    sweepWebLogins(env.ctx)
    expect(all(env.ctx.db, 'SELECT * FROM web_sessions')).toHaveLength(0)
    const t = createWebSession(env.ctx, u)
    createSanction(env.ctx, ownerStaff(ADMIN), { uuid: u, kind: 'account_ban', minutes: 60, reasonCode: 'cheating' })
    expect(code(() => webSession(env.ctx, t.token, undefined, false))).toBe('unauthorized')
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
