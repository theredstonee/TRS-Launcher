import { DatabaseSync } from 'node:sqlite'
import { describe, expect, it } from 'vitest'
import { PET_STATES, validateModel } from '../app/utils/cosmetic-v2/format'
import { cosmeticEntrySchema } from '../server/lib/builtin'
import { cosmeticCatalog, equipCosmetics, equippedView, grantCosmetic, seedBuiltinCosmetics, type AnyBuiltinCosmetic } from '../server/lib/cosmetics'
import { buildV2Cosmetic } from '../server/lib/cosmetics-v2'
import { adminAssignPet, creatorPetState } from '../server/lib/creator-pets'
import { adminCreateCreator, adminRevokeCreator, redeemCreatorCode } from '../server/lib/creators'
import { migrate, one, run } from '../server/lib/db'
import { lookupPlayers } from '../server/lib/lookup'
import { migratePetExtras } from '../server/lib/migrations'
import { adminPetExtrasOverview, adminSetPetExtras, setShowPetExtras } from '../server/lib/pet-extras'
import type { PlayerEvent } from '../server/lib/watch'
import { publicPets } from '../server/lib/site'
import { ownerStaff } from '../server/lib/team'
import adminExtrasGet from '../server/routes/v1/admin/creators/pet-extras/index.get'
import adminExtrasPut from '../server/routes/v1/admin/creators/[uuid]/pet-extras.put'
import petGet from '../server/routes/v1/creator/pet/index.get'
import extrasPut from '../server/routes/v1/creator/pet/extras.put'
import { code, listen } from './chathelpers'
import { callRoute } from './circuithelpers'
import { ADMIN, login, makeEnv, solidPng, type TestEnv } from './helpers'

// Begleiter-Gefolge (§35.13). Nur synthetische Platzhalter-Modelle – die echten Pinguine sind proprietär und nie im Repo.

const OWNER = ownerStaff(ADMIN)
const MAIN = 'test_penguin'
const BIG = 'test_emperor'
const CAP = 'test_beanie'
const SHADES = 'test_shades'
const FIFTH = 'test_fifth'

function pet(id: string, slot: 'pet' | 'hat', petScale?: number): AnyBuiltinCosmetic {
  const card = solidPng(8, 8)
  const model = {
    format: 2,
    id,
    name: id,
    slot,
    attach: slot === 'pet' ? 'ground' : 'head',
    texture: { file: `${id}.png`, width: 16, height: 16, scale: 1 },
    bones: [{ id: 'root', pivot: [0, 0, 0] }],
    cubes: [{ bone: 'root', from: [-1, 0, -1], to: [1, 2, 1], faces: { south: { uv: [0, 0, 2, 2] } } }],
  }
  return buildV2Cosmetic({
    id,
    name: id,
    unlock: slot === 'pet' && id !== MAIN ? 'admin' : 'code',
    hidden: id !== MAIN && slot === 'pet',
    sort: 0,
    petScale,
    frames: 1,
    frameTimeMs: null,
    glowFrames: 0,
    glowFrameTimeMs: null,
    files: { modelJson: Buffer.from(JSON.stringify(model)), texture: solidPng(16, 16), glow: null, card, cardNight: card },
  })
}

function seed(env: TestEnv) {
  const list = [pet(MAIN, 'pet'), pet(BIG, 'pet', 1.25), pet(CAP, 'pet'), pet(SHADES, 'pet'), pet(FIFTH, 'pet'), pet('test_hat', 'hat')]
  seedBuiltinCosmetics(env.ctx, list.map((c, sort) => ({ ...c, sort })))
}

async function creator(env: TestEnv, name: string) {
  const l = await login(env, name)
  const c = adminCreateCreator(env.ctx, OWNER, { player: name, tier: 'top' }).creator
  return { uuid: l.user.uuid, token: l.token, code: c.mainCode! }
}

const petOf = (env: TestEnv, viewer: string, subject: string) =>
  lookupPlayers(env.ctx, viewer, [subject]).players.find((p) => p.uuid === subject)?.cosmetics.pet ?? null

function watch(env: TestEnv, viewer: string, uuid: string): PlayerEvent[] {
  const got: PlayerEvent[] = []
  env.ctx.watch.subscribe(viewer, [uuid], (e) => got.push(e), () => {})
  return got
}

describe('catalog: petScale, hidden team items', () => {
  it('accepts petScale + hidden with unlock admin, rejects petScale out of range and on other slots', () => {
    const entry = { id: 'penguin_emperor', name: 'Kaiserpinguin', format: 2, unlock: 'admin', frames: 1, glowFrames: 0, hidden: true, petScale: 1.25, sort: 24 }
    expect(cosmeticEntrySchema.safeParse(entry)).toMatchObject({ success: true, data: { petScale: 1.25, hidden: true, unlock: 'admin' } })
    expect(cosmeticEntrySchema.safeParse({ ...entry, petScale: 3 }).success).toBe(false)
    expect(cosmeticEntrySchema.safeParse({ ...entry, unlock: 'free' }).success).toBe(false)
    expect(pet(BIG, 'pet', 1.25)).toMatchObject({ petScale: 1.25 })
    expect(pet(MAIN, 'pet')).toMatchObject({ petScale: 1 })
    expect(() => pet('test_hat', 'hat', 1.25)).toThrow(/petScale only for the slot "pet"/)
  })

  it('views carry petScale for pet-slot items only; hidden team pets stay out of foreign catalogs', async () => {
    const env = makeEnv()
    seed(env)
    const p = await login(env, 'Someone')
    const cat = cosmeticCatalog(env.ctx, p.user.uuid)
    expect(cat.find((c) => c.id === MAIN)).toMatchObject({ petScale: 1 })
    expect(cat.find((c) => c.id === 'test_hat')).not.toHaveProperty('petScale')
    expect(cat.find((c) => c.id === BIG)).toBeUndefined()
    grantCosmetic(env.ctx, p.user.uuid, BIG, 'admin')
    equipCosmetics(env.ctx, p.user.uuid, { pet: BIG })
    expect(equippedView(env.ctx, p.user.uuid).pet).toMatchObject({ id: BIG, petScale: 1.25, count: 1 })
    expect(equippedView(env.ctx, p.user.uuid).pet).not.toHaveProperty('extras')
    expect(petOf(env, '', p.user.uuid)).toMatchObject({ id: BIG, petScale: 1.25, scale: 1, count: 1 })
  })
})

describe('migration 37', () => {
  it('is idempotent; fresh database has the table and the toggle column (default on)', async () => {
    const env = makeEnv()
    migratePetExtras(env.ctx.db as unknown as DatabaseSync)
    migratePetExtras(env.ctx.db as unknown as DatabaseSync)
    const db = new DatabaseSync(':memory:')
    migrate(db as never)
    expect(db.prepare("SELECT 1 AS x FROM sqlite_master WHERE type = 'table' AND name = 'creator_pet_extras'").get()).toBeTruthy()
    const cols = (db.prepare('PRAGMA table_info(creators)').all() as { name: string, dflt_value: string }[])
    expect(cols.find((c) => c.name === 'show_pet_extras')?.dflt_value).toBe('1')
  })
})

describe('extra companions (team)', () => {
  it('validates, replaces/reorders, shows extras only for the creator, with scale; count stays for the main pet', async () => {
    const env = makeEnv()
    seed(env)
    const c = await creator(env, 'PixelCST')
    const other = await creator(env, 'Other')
    const viewer = await login(env, 'Viewer')
    adminAssignPet(env.ctx, OWNER, c.uuid, { cosmeticId: MAIN })
    const ev = listen(env, c.uuid)
    const seen = watch(env, viewer.user.uuid, c.uuid)

    expect(code(() => adminSetPetExtras(env.ctx, OWNER, c.uuid, { cosmeticIds: ['test_hat'] }))).toBe('not_a_pet')
    expect(code(() => adminSetPetExtras(env.ctx, OWNER, c.uuid, { cosmeticIds: ['nope'] }))).toBe('cosmetic_not_found')
    expect(code(() => adminSetPetExtras(env.ctx, OWNER, c.uuid, { cosmeticIds: [BIG, BIG] }))).toBe('invalid_request')
    expect(code(() => adminSetPetExtras(env.ctx, OWNER, c.uuid, { cosmeticIds: [BIG, CAP, SHADES, FIFTH, MAIN] }))).toBe('invalid_request')
    expect(code(() => adminSetPetExtras(env.ctx, OWNER, '0'.repeat(32), { cosmeticIds: [BIG] }))).toBe('creator_not_found')

    const set = adminSetPetExtras(env.ctx, OWNER, c.uuid, { cosmeticIds: [BIG, CAP, SHADES] })
    expect(set).toMatchObject({ showExtras: true, max: 4 })
    expect(set.extras.map((x) => [x.cosmeticId, x.petScale, x.available])).toEqual([[BIG, 1.25, true], [CAP, 1, true], [SHADES, 1, true]])
    expect(ev.of('creator_updated')).toContainEqual({ type: 'creator_updated', kind: 'pet_extras_changed' })

    const p = petOf(env, viewer.user.uuid, c.uuid)!
    expect(p).toMatchObject({ id: MAIN, count: 1, petScale: 1 })
    expect(p.extras!.map((x) => [x.id, x.petScale])).toEqual([[BIG, 1.25], [CAP, 1], [SHADES, 1]])
    expect(p.extras![0]).toMatchObject({ format: 2, model: expect.stringContaining(`/v1/cosmetics/${BIG}/model.json?v=`), url: expect.stringContaining(`/v1/cosmetics/${BIG}.png`), hash: expect.any(String) })
    expect(p.extras![0]).not.toHaveProperty('count')
    // Ereignis an Beobachter (Lookup-Form mit extras).
    const last = seen.filter((e) => e.type === 'cosmetics').at(-1) as Extract<PlayerEvent, { type: 'cosmetics' }>
    expect(last.cosmetics.pet?.extras?.map((x) => x.id)).toEqual([BIG, CAP, SHADES])
    // Eigene Ausrüstung (Launcher-Vorschau) und Dashboard.
    expect(equippedView(env.ctx, c.uuid).pet?.extras?.map((x) => [x.id, x.petScale])).toEqual([[BIG, 1.25], [CAP, 1], [SHADES, 1]])
    expect(creatorPetState(env.ctx, c.uuid)).toMatchObject({ showExtras: true, extras: [{ cosmeticId: BIG }, { cosmeticId: CAP }, { cosmeticId: SHADES }] })

    // Fans mit demselben Pinguin bekommen kein Gefolge; andere Creator ohne Zuordnung auch nicht.
    const fan = await login(env, 'Fan')
    redeemCreatorCode(env.ctx, fan.user.uuid, { code: c.code })
    grantCosmetic(env.ctx, fan.user.uuid, MAIN, 'code')
    equipCosmetics(env.ctx, fan.user.uuid, { pet: MAIN })
    expect(petOf(env, viewer.user.uuid, fan.user.uuid)).not.toHaveProperty('extras')
    grantCosmetic(env.ctx, other.uuid, MAIN, 'code')
    equipCosmetics(env.ctx, other.uuid, { pet: MAIN })
    expect(petOf(env, viewer.user.uuid, other.uuid)).not.toHaveProperty('extras')
    // Dasselbe Extra darf auch ein anderer Creator haben.
    adminSetPetExtras(env.ctx, OWNER, other.uuid, { cosmeticIds: [SHADES] })
    expect(petOf(env, viewer.user.uuid, other.uuid)!.extras!.map((x) => x.id)).toEqual([SHADES])

    // Sortieren = neues Ereignis; gleiche Liste = keins.
    const before = seen.length
    adminSetPetExtras(env.ctx, OWNER, c.uuid, { cosmeticIds: [SHADES, BIG, CAP] })
    expect(seen.length).toBe(before + 1)
    expect(petOf(env, viewer.user.uuid, c.uuid)!.extras!.map((x) => x.id)).toEqual([SHADES, BIG, CAP])
    adminSetPetExtras(env.ctx, OWNER, c.uuid, { cosmeticIds: [SHADES, BIG, CAP] })
    expect(seen.length).toBe(before + 1)

    const o = adminPetExtrasOverview(env.ctx, OWNER)
    expect(o.max).toBe(4)
    expect(o.creators.map((x) => [x.creator.name, x.extras.map((e) => e.cosmeticId)])).toEqual(expect.arrayContaining([
      ['PixelCST', [SHADES, BIG, CAP]], ['Other', [SHADES]],
    ]))
    expect(o.cosmetics.map((x) => x.id)).toEqual([MAIN, BIG, CAP, SHADES, FIFTH])

    // Entfernen.
    adminSetPetExtras(env.ctx, OWNER, other.uuid, { cosmeticIds: [] })
    expect(petOf(env, viewer.user.uuid, other.uuid)).not.toHaveProperty('extras')
    expect(one<{ n: number }>(env.ctx.db, "SELECT COUNT(*) AS n FROM admin_log WHERE action = 'creator.pet.extras'")!.n).toBe(4)
  })

  it('respects the toggle, show_cosmetics, inactive creators, no pet worn and unloaded items', async () => {
    const env = makeEnv()
    seed(env)
    const c = await creator(env, 'PixelCST')
    const viewer = await login(env, 'Viewer')
    adminAssignPet(env.ctx, OWNER, c.uuid, { cosmeticId: MAIN })
    adminSetPetExtras(env.ctx, OWNER, c.uuid, { cosmeticIds: [BIG, CAP, SHADES] })
    const seen = watch(env, viewer.user.uuid, c.uuid)

    // Schalter „Gefolge zeigen“.
    expect(setShowPetExtras(env.ctx, c.uuid, { show: false })).toMatchObject({ showExtras: false, extras: [{ cosmeticId: BIG }, { cosmeticId: CAP }, { cosmeticId: SHADES }] })
    expect(seen.filter((e) => e.type === 'cosmetics')).toHaveLength(1)
    expect(petOf(env, viewer.user.uuid, c.uuid)).not.toHaveProperty('extras')
    expect(equippedView(env.ctx, c.uuid).pet).not.toHaveProperty('extras')
    setShowPetExtras(env.ctx, c.uuid, { show: false })
    expect(seen.filter((e) => e.type === 'cosmetics')).toHaveLength(1)
    setShowPetExtras(env.ctx, c.uuid, { show: true })
    expect(petOf(env, viewer.user.uuid, c.uuid)!.extras).toHaveLength(3)
    expect(code(() => setShowPetExtras(env.ctx, c.uuid, { show: 'yes' }))).toBe('invalid_request')

    // Kosmetik ausgeblendet: andere sehen nichts, er selbst schon.
    run(env.ctx.db, 'UPDATE users SET show_cosmetics = 0 WHERE uuid = ?', c.uuid)
    expect(petOf(env, viewer.user.uuid, c.uuid)).toBeNull()
    expect(petOf(env, c.uuid, c.uuid)!.extras).toHaveLength(3)
    run(env.ctx.db, 'UPDATE users SET show_cosmetics = 1 WHERE uuid = ?', c.uuid)

    // Teil nicht geladen (private Dateien fehlen): wird übersprungen, im Dashboard als nicht verfügbar.
    const saved = env.ctx.cosmeticsV2.get(CAP)!
    env.ctx.cosmeticsV2.delete(CAP)
    expect(petOf(env, viewer.user.uuid, c.uuid)!.extras!.map((x) => x.id)).toEqual([BIG, SHADES])
    expect(creatorPetState(env.ctx, c.uuid).extras.find((x) => x.cosmeticId === CAP)).toMatchObject({ available: false })
    env.ctx.cosmeticsV2.set(CAP, saved)

    // Ohne Begleiter im Platz `pet` kein Gefolge.
    equipCosmetics(env.ctx, c.uuid, { pet: null })
    expect(petOf(env, viewer.user.uuid, c.uuid)).toBeNull()
    equipCosmetics(env.ctx, c.uuid, { pet: MAIN })

    // Creator entzogen: kein Gefolge mehr (Zuordnung bleibt für eine Wiederherstellung).
    adminRevokeCreator(env.ctx, OWNER, c.uuid, { reason: 'test' })
    expect(petOf(env, viewer.user.uuid, c.uuid)?.extras).toBeUndefined()
    expect(one<{ n: number }>(env.ctx.db, 'SELECT COUNT(*) AS n FROM creator_pet_extras WHERE creator_uuid = ?', c.uuid)!.n).toBe(3)
  })
})

describe('routes', () => {
  it('admin GET/PUT, creator PUT /v1/creator/pet/extras, GET /v1/creator/pet carries extras', async () => {
    const env = makeEnv()
    seed(env)
    const c = await creator(env, 'PixelCST')
    const admin = await login(env, 'Theredstonee', ADMIN)
    const json = { 'content-type': 'application/json' }
    const put = await callRoute(env, adminExtrasPut, {
      method: 'PUT', url: `/v1/admin/creators/${c.uuid}/pet-extras`, params: { uuid: c.uuid },
      headers: { authorization: `Bearer ${admin.token}`, ...json }, body: JSON.stringify({ cosmeticIds: [BIG, CAP, SHADES] }),
    })
    expect(put.status).toBe(200)
    expect(put.body).toMatchObject({ showExtras: true, max: 4, extras: [{ cosmeticId: BIG, petScale: 1.25 }, { cosmeticId: CAP }, { cosmeticId: SHADES }] })
    const denied = await callRoute(env, adminExtrasPut, {
      method: 'PUT', url: `/v1/admin/creators/${c.uuid}/pet-extras`, params: { uuid: c.uuid },
      headers: { authorization: `Bearer ${c.token}`, ...json }, body: JSON.stringify({ cosmeticIds: [] }),
    })
    expect(denied.status).toBe(403)
    const list = await callRoute(env, adminExtrasGet, { url: '/v1/admin/creators/pet-extras', headers: { authorization: `Bearer ${admin.token}` } })
    expect(list.body).toMatchObject({ creators: [{ creator: { uuid: c.uuid, name: 'PixelCST' }, extras: [{ cosmeticId: BIG }, { cosmeticId: CAP }, { cosmeticId: SHADES }] }] })

    const off = await callRoute(env, extrasPut, {
      method: 'PUT', url: '/v1/creator/pet/extras', headers: { authorization: `Bearer ${c.token}`, ...json }, body: JSON.stringify({ show: false }),
    })
    expect(off.status).toBe(200)
    expect(off.body).toMatchObject({ showExtras: false })
    const state = await callRoute(env, petGet, { url: '/v1/creator/pet', headers: { authorization: `Bearer ${c.token}` } })
    expect(state.body).toMatchObject({ showExtras: false, extras: [{ cosmeticId: BIG }, { cosmeticId: CAP }, { cosmeticId: SHADES }] })
    // Team-Modus (§35.9): ein Team-Mitglied mit creators.manage schaltet für den Creator um (protokolliert).
    const asTeam = await callRoute(env, extrasPut, {
      method: 'PUT', url: `/v1/creator/pet/extras?as=${c.uuid}`, headers: { authorization: `Bearer ${admin.token}`, ...json }, body: JSON.stringify({ show: true }),
    })
    expect(asTeam.status).toBe(200)
    expect(asTeam.body).toMatchObject({ showExtras: true })
    expect(one<{ n: number }>(env.ctx.db, "SELECT COUNT(*) AS n FROM admin_log WHERE action LIKE '%pet.extras.show' AND target = ?", c.uuid)!.n).toBe(1)
    const nobody = await login(env, 'Nobody')
    const notCreator = await callRoute(env, extrasPut, {
      method: 'PUT', url: '/v1/creator/pet/extras', headers: { authorization: `Bearer ${nobody.token}`, ...json }, body: JSON.stringify({ show: true }),
    })
    expect(notCreator.status).toBe(403)
  })
})

describe('pet states sneak/grab/flee/handsup (format, website)', () => {
  const anim = (id: string, driver: string) => ({ id, driver, lengthMs: 1000, tracks: [{ bone: 'root', channel: 'rotation', keys: [{ t: 0, v: [0, 0, 0] }, { t: 1000, v: [0, 10, 0] }] }] })
  const model = (anims: unknown[]) => ({
    format: 2,
    id: 'test_heist',
    name: 'test_heist',
    slot: 'pet',
    attach: 'ground',
    texture: { file: 'test_heist.png', width: 16, height: 16, scale: 1 },
    bones: [{ id: 'root', pivot: [0, 0, 0] }],
    cubes: [{ bone: 'root', from: [-1, 0, -1], to: [1, 2, 1], faces: { south: { uv: [0, 0, 2, 2] } } }],
    animations: anims,
  })

  it('accepts the new drivers and up to 12 animations, rejects 13 and unknown drivers', () => {
    expect(PET_STATES).toEqual(['idle', 'walk', 'slide', 'swim', 'air', 'sneak', 'grab', 'flee', 'handsup'])
    const nine = PET_STATES.map((d) => anim(d, d))
    expect(validateModel(model(nine), { texture: { width: 16, height: 16 } })).toMatchObject({ ok: true })
    const twelve = [...nine, anim('x1', 'idle'), anim('x2', 'walk'), anim('x3', 'grab')]
    expect(validateModel(model(twelve), { texture: { width: 16, height: 16 } }).ok).toBe(true)
    expect(validateModel(model([...twelve, anim('x4', 'idle')]), { texture: { width: 16, height: 16 } }).ok).toBe(false)
    expect(validateModel(model([anim('a', 'dance')]), { texture: { width: 16, height: 16 } }).ok).toBe(false)
  })

  it('website pets list their states (idle first, only those with an animation) and petScale; code pets are listed', () => {
    const env = makeEnv()
    const card = solidPng(8, 8)
    const heist = buildV2Cosmetic({
      id: 'test_heist', name: 'Schwarzes Schaf', unlock: 'code', sort: 0, frames: 1, frameTimeMs: null, glowFrames: 0, glowFrameTimeMs: null,
      files: { modelJson: Buffer.from(JSON.stringify(model([anim('w', 'walk'), anim('h', 'handsup'), anim('g', 'grab'), anim('s', 'sneak')]))), texture: solidPng(16, 16), glow: null, card, cardNight: card },
    })
    seedBuiltinCosmetics(env.ctx, [heist, { ...pet(BIG, 'pet', 1.25), sort: 1 }])
    const list = publicPets(env.ctx)
    expect(list.find((p) => p.id === 'test_heist')).toMatchObject({ unlock: 'code', states: ['idle', 'walk', 'sneak', 'grab', 'handsup'], petScale: 1 })
    // Versteckte Team-Begleiter erscheinen nicht.
    expect(list.find((p) => p.id === BIG)).toBeUndefined()
  })
})
