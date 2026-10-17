import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { setMemberRoles, ownerStaff } from '../server/lib/team'
import { SCARE_KINDS } from '../server/lib/scare'
import { MIYAKO_UUID } from '../server/lib/miyako'
import bundleRoute from '../server/routes/v1/me/miyako/bundle.get'
import introDoneRoute from '../server/routes/v1/me/miyako/intro-done.post'
import miyakoRoute from '../server/routes/v1/me/miyako/index.get'
import scareRoute from '../server/routes/v1/admin/halloween/scare.post'
import { listen } from './chathelpers'
import { callRoute } from './circuithelpers'
import { ADMIN, login, makeEnv, type TestEnv } from './helpers'

const OWNER = ownerStaff(ADMIN)
const bearer = (token: string) => ({ authorization: `Bearer ${token}` })
const json = (token: string) => ({ authorization: `Bearer ${token}`, 'content-type': 'application/json' })

const privateDirs: string[] = []
afterEach(() => {
  for (const d of privateDirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

/** Legt einen privaten Asset-Ordner mit `miyako/`-Medien an und liefert den Pfad (für PRIVATE_ASSETS_DIR). */
function seedPrivate(frames = 3): string {
  const root = mkdtempSync(join(tmpdir(), 'trs-private-'))
  privateDirs.push(root)
  const framesDir = join(root, 'miyako', 'frames')
  mkdirSync(framesDir, { recursive: true })
  for (let i = 1; i <= frames; i++) writeFileSync(join(framesDir, `${String(i).padStart(4, '0')}.jpg`), Buffer.from(`frame-${i}`))
  writeFileSync(join(root, 'miyako', 'intro.ogg'), Buffer.from('ogg-audio'))
  writeFileSync(join(root, 'miyako', 'jumpscare.png'), Buffer.from('png-image'))
  return root
}

describe('MiyakoMC private gag (Part A)', () => {
  it('intro flag get/set is gated to MiyakoMC, 404 for everyone else', async () => {
    const env = makeEnv()
    const miyako = await login(env, 'MiyakoMC', MIYAKO_UUID)
    const other = await login(env, 'Steve')

    expect((await callRoute(env, miyakoRoute, { url: '/v1/me/miyako', headers: bearer(miyako.token) })).body)
      .toEqual({ introDone: false, timerMaxMs: 300000 })
    // Anonym → 401, fremdes Konto → 404 (nichts sickert durch).
    expect((await callRoute(env, miyakoRoute, { url: '/v1/me/miyako' })).error?.status).toBe(401)
    expect((await callRoute(env, miyakoRoute, { url: '/v1/me/miyako', headers: bearer(other.token) })).error?.status).toBe(404)

    // Setzen nur für MiyakoMC.
    expect((await callRoute(env, introDoneRoute, { method: 'POST', url: '/v1/me/miyako/intro-done', headers: bearer(other.token) })).error?.status).toBe(404)
    expect((await callRoute(env, introDoneRoute, { method: 'POST', url: '/v1/me/miyako/intro-done', headers: bearer(miyako.token) })).body)
      .toEqual({ introDone: true })
    expect((await callRoute(env, miyakoRoute, { url: '/v1/me/miyako', headers: bearer(miyako.token) })).body)
      .toEqual({ introDone: true, timerMaxMs: 300000 })
  })

  it('bundle streams a zip for MiyakoMC and 404s for everyone else', async () => {
    const env = makeEnv({ env: { PRIVATE_ASSETS_DIR: seedPrivate(4) } })
    const miyako = await login(env, 'MiyakoMC', MIYAKO_UUID)
    const other = await login(env, 'Steve')

    const r = await callRoute(env, bundleRoute, { url: '/v1/me/miyako/bundle', headers: bearer(miyako.token) })
    expect(r.status).toBe(200)
    expect(String(r.headers['content-type'])).toBe('application/zip')
    const zip = r.body as Buffer
    expect(Buffer.isBuffer(zip)).toBe(true)
    expect(zip.subarray(0, 4)).toEqual(Buffer.from([0x50, 0x4b, 0x03, 0x04])) // PK\x03\x04
    // End-of-central-directory: 4 frames + intro.ogg + jumpscare.png = 6 entries.
    const eocd = zip.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]))
    expect(zip.readUInt16LE(eocd + 10)).toBe(6)
    expect(zip.includes(Buffer.from('frames/0001.jpg'))).toBe(true)
    expect(zip.includes(Buffer.from('intro.ogg'))).toBe(true)

    expect((await callRoute(env, bundleRoute, { url: '/v1/me/miyako/bundle', headers: bearer(other.token) })).error?.status).toBe(404)
  })

  it('bundle 404s (does not crash) when the private folder is absent', async () => {
    const env = makeEnv() // default PRIVATE_ASSETS_DIR = <DATA_DIR>/private-assets, never created
    const miyako = await login(env, 'MiyakoMC', MIYAKO_UUID)
    const r = await callRoute(env, bundleRoute, { url: '/v1/me/miyako/bundle', headers: bearer(miyako.token) })
    expect(r.error?.status).toBe(404)
  })
})

describe('owner-triggered scare (Part C)', () => {
  /** Owner (adminUuids), ein Team-Admin (kein Owner) und ein Zielspieler. */
  async function crew(env: TestEnv) {
    const owner = await login(env, 'Owner', ADMIN)
    const admin = await login(env, 'Alice')
    const target = await login(env, 'Target')
    setMemberRoles(env.ctx, OWNER, admin.user.uuid, ['admin'])
    return { owner, admin, target }
  }
  const scare = (env: TestEnv, token: string, target: string) =>
    callRoute(env, scareRoute, { method: 'POST', url: '/v1/admin/halloween/scare', body: JSON.stringify({ target }), headers: json(token) })

  it('is owner-only: 403 not_owner for a team admin, 403 for a plain player', async () => {
    const env = makeEnv()
    const { admin, target } = await crew(env)
    const l = listen(env, target.user.uuid)
    expect((await scare(env, admin.token, 'Target')).error?.code).toBe('not_owner')
    const player = await login(env, 'Random')
    expect((await scare(env, player.token, 'Target')).error?.status).toBe(403)
    expect(l.of('jumpscare')).toEqual([])
  })

  it('emits a jumpscare on the target stream and returns { sent: true }, by name or uuid', async () => {
    const env = makeEnv()
    const { owner, target } = await crew(env)
    const l = listen(env, target.user.uuid)
    const r = await scare(env, owner.token, 'Target')
    expect(r.body).toEqual({ sent: true })
    const got = l.of('jumpscare')
    expect(got).toHaveLength(1)
    expect(SCARE_KINDS).toContain(got[0]!.kind)

    // Auch per UUID.
    l.clear()
    expect((await scare(env, owner.token, target.user.uuid)).body).toEqual({ sent: true })
    expect(l.of('jumpscare')).toHaveLength(1)
  })

  it('404 player_offline when the target has no open stream', async () => {
    const env = makeEnv()
    const { owner, target } = await crew(env)
    // kein listen() → nicht online
    expect((await scare(env, owner.token, 'Target')).error?.code).toBe('player_offline')
    expect((await scare(env, owner.token, target.user.uuid)).error?.code).toBe('player_offline')
  })

  it('404 player_not_found for an unknown name (Mojang miss)', async () => {
    const env = makeEnv()
    const { owner } = await crew(env)
    expect((await scare(env, owner.token, 'Nobody')).error?.code).toBe('player_not_found')
  })
})
