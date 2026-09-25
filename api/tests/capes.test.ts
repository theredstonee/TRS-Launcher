import { existsSync, rmSync, statSync } from 'node:fs'
import { IncomingMessage, ServerResponse } from 'node:http'
import { Socket } from 'node:net'
import { join } from 'node:path'
import { createEvent } from 'h3'
import { describe, expect, it } from 'vitest'
import { approveCape, banUser, deleteCapeAdmin, listCapesForReview, rejectCape, stats } from '../server/lib/admin'
import { loadBuiltins } from '../server/lib/builtin'
import {
  catalog,
  deleteOwnUpload,
  getCape,
  grantCape,
  readTexture,
  reportCape,
  revokeCape,
  seedBuiltins,
  setActiveCape,
  uploadCape,
} from '../server/lib/capes'
import { setContext } from '../server/lib/context'
import { block } from '../server/lib/friends'
import { lookupPlayers } from '../server/lib/lookup'
import { deleteUser, getUser, updateSettings } from '../server/lib/users'
import adminSkinRoute from '../server/routes/v1/admin/users/[uuid]/skin.get'
import { ADMIN, fixtureBuiltins, login, makeEnv, seedFixtures, solidPng, type TestEnv } from './helpers'

const NO_COSMETICS = { hat: null, wings: null, back: null, aura: null }

function code(fn: () => unknown): string {
  try {
    fn()
  } catch (e) {
    return (e as { code?: string }).code ?? (e as Error).message
  }
  return 'ok'
}

describe('built-in catalog', () => {
  it('seeds animated strips with frame metadata and scale', () => {
    const env = makeEnv()
    seedFixtures(env)
    const team = getCape(env.ctx, 'team')!
    expect(team).toMatchObject({ width: 128, height: 64, frames: 4, frame_time_ms: 150, unlock: 'admin', status: 'approved' })
    expect(existsSync(join(env.ctx.capeDir, 'team.png'))).toBe(true)
  })

  it('rejects strips whose size does not match scale × frames', () => {
    const env = makeEnv()
    const bad = fixtureBuiltins()
    bad[2] = { ...bad[2]!, frames: 3 }
    expect(code(() => seedBuiltins(env.ctx, bad))).toMatch(/frame count|does not match/)
    const wrongScale = fixtureBuiltins()
    wrongScale[0] = { ...wrongScale[0]!, scale: 1 }
    expect(code(() => seedBuiltins(env.ctx, wrongScale))).toMatch(/does not match scale/)
  })

  it('parses catalog.json as array or object and validates it', async () => {
    const png = solidPng(128, 128)
    const list = [{ id: 'team', name: 'Team', file: 'team.png', unlock: 'admin', animated: true, frames: 2, frameTimeMs: 150, scale: 2, extra: 1 }]
    const read = async () => png
    expect((await loadBuiltins(async () => list, read))[0]).toMatchObject({ id: 'team', frames: 2, frameTimeMs: 150, scale: 2 })
    expect(await loadBuiltins(async () => ({ capes: list }), read)).toHaveLength(1)
    expect(await loadBuiltins(async () => null, read)).toEqual([])
    await expect(loadBuiltins(async () => [{ ...list[0], frameTimeMs: undefined }], read)).rejects.toThrow()
    await expect(loadBuiltins(async () => [{ ...list[0], animated: false }], read)).rejects.toThrow()
    await expect(loadBuiltins(async () => [{ ...list[0], file: '../etc/passwd' }], read)).rejects.toThrow()
    await expect(loadBuiltins(async () => [list[0], list[0]], read)).rejects.toThrow()
  })

  it('catalog shows ownership; locked capes cannot be worn; admins own every built-in', async () => {
    const env = makeEnv()
    seedFixtures(env)
    const u = await login(env, 'Steve')
    const admin = await login(env, 'Theredstonee', ADMIN)
    const c = catalog(env.ctx, u.user.uuid)
    expect(c.map((x) => [x.id, x.owned])).toEqual([['redstone', true], ['emerald', false], ['team', false]])
    expect(c.find((x) => x.id === 'team')).toMatchObject({ animated: true, frames: 4, frameTimeMs: 150, scale: 2 })
    expect(c[0]!.url).toMatch(/^https:\/\/api\.example\.test\/v1\/capes\/redstone\.png\?v=[0-9a-f]{12}$/)
    expect(code(() => setActiveCape(env.ctx, u.user.uuid, 'team'))).toBe('cape_locked')
    expect(setActiveCape(env.ctx, u.user.uuid, 'redstone')!.id).toBe('redstone')
    expect(setActiveCape(env.ctx, admin.user.uuid, 'team')!.animated).toBe(true)
    grantCape(env.ctx, u.user.uuid, 'team', 'admin')
    setActiveCape(env.ctx, u.user.uuid, 'team')
    revokeCape(env.ctx, u.user.uuid, 'team')
    expect(getUser(env.ctx, u.user.uuid)!.active_cape_id).toBeNull()
  })
})

describe('uploads and moderation', () => {
  it('uploads are pending, only visible to the owner and admins until approved', async () => {
    const env = makeEnv()
    seedFixtures(env)
    const owner = await login(env, 'Owner')
    const other = await login(env, 'Other')
    const cape = uploadCape(env.ctx, owner.user.uuid, solidPng(64, 32, [1, 2, 3, 255]), 'Mein Umhang')
    expect(cape).toMatchObject({ status: 'pending', kind: 'upload', unlock: 'owner', scale: 1, animated: false })
    expect(code(() => readTexture(env.ctx, cape.id, null))).toBe('cape_not_found')
    expect(code(() => readTexture(env.ctx, cape.id, { uuid: other.user.uuid, admin: false }))).toBe('cape_not_found')
    expect(readTexture(env.ctx, cape.id, { uuid: owner.user.uuid, admin: false }).public).toBe(false)
    expect(readTexture(env.ctx, cape.id, { uuid: ADMIN, admin: true }).public).toBe(false)
    expect(code(() => setActiveCape(env.ctx, other.user.uuid, cape.id))).toBe('cape_not_found')
    setActiveCape(env.ctx, owner.user.uuid, cape.id)
    // Beide spielen gerade (Live-Abzeichen).
    env.ctx.presence.set(owner.user.uuid, 'in-game', { version: '1.21.1', loader: 'fabric' }, 'client')
    env.ctx.presence.set(other.user.uuid, 'in-game', { version: '1.21.1', loader: 'fabric' }, 'launcher')

    // Lookup: der Besitzer sieht seinen wartenden Umhang, andere nicht.
    expect(lookupPlayers(env.ctx, owner.user.uuid, [owner.user.uuid]).players[0]!.cape?.id).toBe(cape.id)
    expect(lookupPlayers(env.ctx, other.user.uuid, [owner.user.uuid]).players[0]).toEqual({ uuid: owner.user.uuid, badge: true, cape: null, cosmetics: NO_COSMETICS })

    expect(listCapesForReview(env.ctx, 'pending').map((c) => c.id)).toEqual([cape.id])
    approveCape(env.ctx, ADMIN, cape.id)
    expect(readTexture(env.ctx, cape.id, null).public).toBe(true)
    expect(lookupPlayers(env.ctx, other.user.uuid, [owner.user.uuid]).players[0]!.cape).toMatchObject({ id: cape.id, scale: 1, animated: false, frames: 1 })
  })

  it('reject removes the cape from the owner; reports only for visible foreign uploads', async () => {
    const env = makeEnv()
    const owner = await login(env, 'Owner')
    const other = await login(env, 'Other')
    const cape = uploadCape(env.ctx, owner.user.uuid, solidPng(64, 32), undefined)
    setActiveCape(env.ctx, owner.user.uuid, cape.id)
    expect(code(() => reportCape(env.ctx, other.user.uuid, cape.id, 'inappropriate', undefined))).toBe('cape_not_found')
    approveCape(env.ctx, ADMIN, cape.id)
    expect(code(() => reportCape(env.ctx, owner.user.uuid, cape.id, 'other', undefined))).toBe('cape_not_found')
    reportCape(env.ctx, other.user.uuid, cape.id, 'inappropriate', 'Beleidigung')
    reportCape(env.ctx, other.user.uuid, cape.id, 'copyright', undefined) // gleiche Person → aktualisiert
    expect(listCapesForReview(env.ctx, 'reported')[0]!.reports).toEqual({ count: 1, reasons: { copyright: 1 } })
    rejectCape(env.ctx, ADMIN, cape.id, 'Urheberrecht')
    expect(getUser(env.ctx, owner.user.uuid)!.active_cape_id).toBeNull()
    expect(code(() => setActiveCape(env.ctx, owner.user.uuid, cape.id))).toBe('cape_locked')
    expect(catalog(env.ctx, owner.user.uuid).find((c) => c.id === cape.id)).toMatchObject({ status: 'rejected', rejectReason: 'Urheberrecht' })
  })

  it('limits uploads per user and rejects duplicates', async () => {
    const env = makeEnv({ limits: { maxPendingUploadsPerUser: 2, maxUploadsPerUser: 3 } })
    const u = await login(env, 'Steve')
    const a = uploadCape(env.ctx, u.user.uuid, solidPng(64, 32, [1, 1, 1, 255]), undefined)
    expect(code(() => uploadCape(env.ctx, u.user.uuid, solidPng(64, 32, [1, 1, 1, 255]), undefined))).toBe('duplicate_cape')
    uploadCape(env.ctx, u.user.uuid, solidPng(64, 32, [2, 2, 2, 255]), undefined)
    expect(code(() => uploadCape(env.ctx, u.user.uuid, solidPng(64, 32, [3, 3, 3, 255]), undefined))).toBe('too_many_pending')
    approveCape(env.ctx, ADMIN, a.id)
    const c = uploadCape(env.ctx, u.user.uuid, solidPng(64, 32, [3, 3, 3, 255]), undefined)
    rejectCape(env.ctx, ADMIN, c.id, undefined) // abgelehnte zählen nicht
    uploadCape(env.ctx, u.user.uuid, solidPng(64, 32, [5, 5, 5, 255]), undefined)
    for (const p of listCapesForReview(env.ctx, 'pending')) approveCape(env.ctx, ADMIN, p.id)
    expect(code(() => uploadCape(env.ctx, u.user.uuid, solidPng(64, 32, [4, 4, 4, 255]), undefined))).toBe('upload_limit')
    deleteOwnUpload(env.ctx, u.user.uuid, a.id)
    expect(existsSync(join(env.ctx.capeDir, `${a.id}.png`))).toBe(false)
    expect(code(() => deleteCapeAdmin(env.ctx, ADMIN, 'redstone'))).toBe('cape_not_found')
  })

  it('built-in capes cannot be deleted by admins', () => {
    const env = makeEnv()
    seedFixtures(env)
    expect(code(() => deleteCapeAdmin(env.ctx, ADMIN, 'redstone'))).toBe('builtin_cape')
  })
})

describe('player lookup privacy', () => {
  it('returns only TRS users who show something, respecting their settings', async () => {
    const env = makeEnv()
    seedFixtures(env)
    const viewer = await login(env, 'Viewer')
    const a = await login(env, 'Alpha')
    const b = await login(env, 'Beta')
    const c = await login(env, 'Gamma')
    setActiveCape(env.ctx, a.user.uuid, 'redstone')
    setActiveCape(env.ctx, b.user.uuid, 'redstone')
    updateSettings(env.ctx, b.user.uuid, { showCapeToOthers: false })
    updateSettings(env.ctx, c.user.uuid, { showBadge: false })
    for (const u of [viewer, a, b, c]) env.ctx.presence.set(u.user.uuid, 'in-game', { version: '1.21.1', loader: 'fabric' }, 'client')
    const notTrs = 'f'.repeat(32)
    const r = lookupPlayers(env.ctx, viewer.user.uuid, [a.user.uuid, b.user.uuid, c.user.uuid, notTrs, a.user.uuid])
    const by = Object.fromEntries(r.players.map((p) => [p.uuid, p]))
    expect(by[a.user.uuid]).toMatchObject({ badge: true, cape: { id: 'redstone', scale: 2, animated: false, frames: 1, frameTimeMs: null } })
    expect(by[b.user.uuid]).toEqual({ uuid: b.user.uuid, badge: true, cape: null, cosmetics: NO_COSMETICS })
    expect(by[c.user.uuid]).toBeUndefined() // kein Abzeichen, kein Umhang
    expect(by[notTrs]).toBeUndefined()
    expect(r.players).toHaveLength(2)
    // Selbst sieht man den eigenen Umhang auch mit showCapeToOthers=false.
    expect(lookupPlayers(env.ctx, b.user.uuid, [b.user.uuid]).players[0]!.cape?.id).toBe('redstone')
  })

  it('animated capes carry frame info; banned users and blockers are hidden', async () => {
    const env = makeEnv()
    seedFixtures(env)
    const viewer = await login(env, 'Viewer')
    const staff = await login(env, 'Theredstonee', ADMIN)
    const banned = await login(env, 'Banned')
    const blocker = await login(env, 'Blocker')
    setActiveCape(env.ctx, staff.user.uuid, 'team')
    const r = lookupPlayers(env.ctx, viewer.user.uuid, [staff.user.uuid])
    expect(r.players[0]!.cape).toMatchObject({ id: 'team', animated: true, frames: 4, frameTimeMs: 150, scale: 2 })
    banUser(env.ctx, 'api-key', banned.user.uuid, undefined)
    block(env.ctx, blocker.user.uuid, { uuid: viewer.user.uuid })
    const r2 = lookupPlayers(env.ctx, viewer.user.uuid, [banned.user.uuid, blocker.user.uuid])
    expect(r2.players).toEqual([])
  })
})

describe('account deletion (Art. 17)', () => {
  it('removes the user, uploads and files, keeps only the ban record', async () => {
    const env = makeEnv()
    seedFixtures(env)
    const u = await login(env, 'Steve')
    const cape = uploadCape(env.ctx, u.user.uuid, solidPng(64, 32), undefined)
    grantCape(env.ctx, u.user.uuid, 'team', 'admin')
    env.ctx.presence.set(u.user.uuid, 'online', null)
    deleteUser(env.ctx, u.user.uuid)
    expect(getUser(env.ctx, u.user.uuid)).toBeUndefined()
    expect(getCape(env.ctx, cape.id)).toBeUndefined()
    expect(existsSync(join(env.ctx.capeDir, `${cape.id}.png`))).toBe(false)
    expect(env.ctx.presence.get(u.user.uuid)).toBeNull()
    const s = stats(env.ctx)
    expect(s.users.total).toBe(0)
    expect(s.sessions).toBe(0)
  })
})

describe('HD and animated uploads', () => {
  it('stores one frame size, frame count and frame time; static capes ignore frameTimeMs', async () => {
    const env = makeEnv()
    const u = await login(env, 'Animator')
    const strip = uploadCape(env.ctx, u.user.uuid, solidPng(512, 4096, [9, 9, 9, 255]), 'Blitz', { frames: 16, frameTimeMs: 120 })
    expect(strip).toMatchObject({ width: 512, height: 256, scale: 8, animated: true, frames: 16, frameTimeMs: 120 })
    const row = getCape(env.ctx, strip.id)!
    expect(row).toMatchObject({ width: 512, height: 256, frames: 16, frame_time_ms: 120 })

    const still = uploadCape(env.ctx, u.user.uuid, solidPng(512, 256, [8, 8, 8, 255]), undefined, { frameTimeMs: 300 })
    expect(still).toMatchObject({ width: 512, height: 256, scale: 8, animated: false, frames: 1, frameTimeMs: null })
    expect(getCape(env.ctx, still.id)!.frame_time_ms).toBeNull()

    const small = uploadCape(env.ctx, u.user.uuid, solidPng(22, 17 * 4, [7, 7, 7, 255]), undefined, { frameTimeMs: 50 })
    expect(small).toMatchObject({ width: 64, height: 32, scale: 1, frames: 4, frameTimeMs: 50 })
  })

  it('animated uploads need frameTimeMs; frames must match; nothing is stored on errors', async () => {
    const env = makeEnv()
    const u = await login(env, 'Animator')
    expect(code(() => uploadCape(env.ctx, u.user.uuid, solidPng(64, 128), undefined))).toBe('frame_time_required')
    expect(code(() => uploadCape(env.ctx, u.user.uuid, solidPng(64, 128), undefined, { frames: 3, frameTimeMs: 100 }))).toBe('invalid_dimensions')
    expect(code(() => uploadCape(env.ctx, u.user.uuid, solidPng(64, 32 * 17), undefined, { frameTimeMs: 100 }))).toBe('invalid_dimensions')
    expect(listCapesForReview(env.ctx, 'pending')).toEqual([])
  })
})

describe('admin review details', () => {
  it('lists file size and upload counts of the owner', async () => {
    const env = makeEnv()
    const a = await login(env, 'Alice')
    const b = await login(env, 'Bob')
    const a1 = uploadCape(env.ctx, a.user.uuid, solidPng(64, 32, [1, 1, 1, 255]), undefined)
    const a2 = uploadCape(env.ctx, a.user.uuid, solidPng(64, 32, [2, 2, 2, 255]), undefined)
    const a3 = uploadCape(env.ctx, a.user.uuid, solidPng(64, 32, [3, 3, 3, 255]), undefined)
    approveCape(env.ctx, ADMIN, a1.id)
    rejectCape(env.ctx, ADMIN, a2.id, 'nope')
    const b1 = uploadCape(env.ctx, b.user.uuid, solidPng(128, 64, [4, 4, 4, 255]), undefined)
    const list = listCapesForReview(env.ctx, 'pending')
    expect(list.map((c) => c.id)).toEqual([a3.id, b1.id])
    expect(list[0]!.ownerStats).toEqual({ uploads: 3, approved: 1, pending: 1, rejected: 1 })
    expect(list[1]!.ownerStats).toEqual({ uploads: 1, approved: 0, pending: 1, rejected: 0 })
    expect(list[0]!.bytes).toBe(statSync(join(env.ctx.capeDir, `${a3.id}.png`)).size)
    expect(list[0]!.bytes).toBeGreaterThan(0)
    rmSync(join(env.ctx.capeDir, `${b1.id}.png`))
    expect(listCapesForReview(env.ctx, 'pending')[1]!.bytes).toBe(0)
    expect(listCapesForReview(env.ctx, 'rejected')[0]).toMatchObject({ id: a2.id, rejectReason: 'nope', ownerStats: { uploads: 3 } })
  })

  /** Ruft die Admin-Skin-Route direkt auf (ohne Nitro), mit dem Admin-Schlüssel aus den Test-Einstellungen. */
  async function adminSkin(env: TestEnv, uuid: string, key = env.ctx.config.adminApiKey!) {
    setContext(env.ctx)
    const req = new IncomingMessage(new Socket())
    req.method = 'GET'
    req.url = `/v1/admin/users/${uuid}/skin`
    req.headers = { 'x-admin-key': key }
    const event = createEvent(req, new ServerResponse(req))
    event.context.params = { uuid }
    try {
      return await adminSkinRoute(event)
    } catch (e) {
      return { error: (e as { status: number, code: string }) }
    } finally {
      setContext(undefined)
    }
  }

  it('admin skin route returns the SkinView and 404 for invalid UUIDs', async () => {
    const env = makeEnv()
    const STEVE = '0'.repeat(31) + '1'
    env.mojang.accounts.set('steve', {
      uuid: STEVE, name: 'Steve', model: 'default', skinUrl: 'https://textures.minecraft.net/texture/abc', capeUrl: null,
    })
    expect(await adminSkin(env, STEVE)).toEqual({ uuid: STEVE, name: 'Steve', model: 'default', textureUrl: 'https://textures.minecraft.net/texture/abc', capeUrl: null })
    expect(await adminSkin(env, 'not-a-uuid')).toMatchObject({ error: { status: 404, code: 'not_found' } })
    expect(await adminSkin(env, 'f'.repeat(32))).toMatchObject({ error: { status: 404, code: 'player_not_found' } })
    expect(await adminSkin(env, STEVE, 'wrong-key')).toMatchObject({ error: { status: 401 } })
    env.mojang.fail = true
    expect(await adminSkin(env, 'e'.repeat(32))).toMatchObject({ error: { status: 502, code: 'upstream_unavailable' } })
  })
})
