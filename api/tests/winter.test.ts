import { DatabaseSync } from 'node:sqlite'
import { describe, expect, it } from 'vitest'
import { hiddenBones, resolveOptions, validateModel } from '../app/utils/cosmetic-v2/format'
import { createCodes, redeemCode } from '../server/lib/codes'
import {
  cosmeticCatalog,
  equipCosmetics,
  equippedView,
  getCosmetic,
  grantCosmetic,
  seedBuiltinCosmetics,
  type AnyBuiltinCosmetic,
} from '../server/lib/cosmetics'
import { buildV2Cosmetic } from '../server/lib/cosmetics-v2'
import { petCount, petCountForFans } from '../server/lib/creator-lookup'
import { syncAllCreatorPets, syncCreatorPet } from '../server/lib/creator-pet'
import { adminAssignPet, adminRemovePet } from '../server/lib/creator-pets'
import { adminCreateCreator, adminDeleteCreator, adminRestoreCreator, adminRevokeCreator, endSupport, redeemCreatorCode } from '../server/lib/creators'
import { all, migrate, one, run } from '../server/lib/db'
import { lookupPlayers } from '../server/lib/lookup'
import { migrateWinterCosmetics } from '../server/lib/migrations'
import { equipBody } from '../server/lib/schemas'
import { publicHats, publicPets, publicWings } from '../server/lib/site'
import { ownerStaff } from '../server/lib/team'
import { normalizeRedeemCode } from '../server/lib/ids'
import { deleteUser } from '../server/lib/users'
import type { PlayerEvent } from '../server/lib/watch'
import getCosmetics from '../server/routes/v1/me/cosmetics.get'
import putCosmetics from '../server/routes/v1/me/cosmetics.put'
import { callRoute } from './circuithelpers'
import { ADMIN, login, makeEnv, solidPng, type TestEnv } from './helpers'

// Winter 2026: Optionen je Spieler, Platz `pet` (attach `ground`), Creator-Pinguine nach Fans.
// Nur synthetische Platzhalter-Modelle – die echten Winter-Teile sind proprietär und liegen nie im Repo.

const OWNER = ownerStaff(ADMIN)
/** Der Pinguin (Winter 2026) – seit §35.13 ein normales Creator-Haustier ohne Sonderrolle im Code. */
const CREATOR_PET_ID = 'penguin'
const auth = (token: string) => ({ authorization: `Bearer ${token}`, 'content-type': 'application/json' })

type Slot = 'hat' | 'companion' | 'wings' | 'pet'
const ATTACH: Record<Slot, string> = { hat: 'head', companion: 'head', wings: 'body', pet: 'ground' }

/** Kleines gültiges Modell; `beard` = Option mit Kind-Knochen. */
function model(id: string, slot: Slot, extra: Record<string, unknown> = {}) {
  return {
    format: 2,
    id,
    name: id,
    slot,
    attach: ATTACH[slot],
    texture: { file: `${id}.png`, width: 16, height: 16, scale: 1 },
    bones: [
      { id: 'root', pivot: [0, 0, 0] },
      { id: 'beard', parent: 'root', pivot: [0, 1, 0] },
      { id: 'tip', parent: 'beard', pivot: [0, 0, 0] },
    ],
    cubes: [
      { bone: 'root', from: [-1, 1, -1], to: [1, 2, 1], faces: { south: { uv: [0, 0, 2, 1] } } },
      { bone: 'tip', from: [-1, 0, -1], to: [1, 1, 1], faces: { south: { uv: [0, 2, 2, 3] } } },
    ],
    ...extra,
  }
}

const BEARD = [{ id: 'beard', bones: ['beard'], default: true, name: { de: 'Bart', en: 'Beard', es: 'Barba' } }]

function synthetic(id: string, slot: Slot, unlock: 'free' | 'code' | 'admin', extra: Record<string, unknown> = {}): AnyBuiltinCosmetic {
  const card = solidPng(8, 8)
  return buildV2Cosmetic({
    id,
    name: id,
    unlock,
    sort: 0,
    frames: 1,
    frameTimeMs: null,
    glowFrames: 0,
    glowFrameTimeMs: null,
    files: { modelJson: Buffer.from(JSON.stringify(model(id, slot, extra))), texture: solidPng(16, 16), glow: null, card, cardNight: card },
  })
}

/** Platzhalter für Mütze (mit Bart-Option), Pinguin (pet) und Flügel. */
function seedWinter(env: TestEnv) {
  const list = [
    synthetic('test_hat', 'hat', 'code', { options: BEARD }),
    synthetic(CREATOR_PET_ID, 'pet', 'code', {
      animations: ['idle', 'walk', 'slide', 'swim', 'air'].map((d) => ({
        id: d,
        driver: d,
        lengthMs: 1000,
        tracks: [{ bone: 'root', channel: 'rotation', keys: [{ t: 0, v: [0, 0, 0] }, { t: 1000, v: [0, 10, 0] }] }],
      })),
    }),
    synthetic('test_wings', 'wings', 'code'),
    synthetic('free_pet', 'pet', 'free'),
  ]
  seedBuiltinCosmetics(env.ctx, list.map((c, sort) => ({ ...c, sort })))
}

function code(fn: () => unknown): string {
  try {
    fn()
  } catch (e) {
    return (e as { code?: string }).code ?? (e as Error).message
  }
  return 'ok'
}

function watch(env: TestEnv, viewer: string, uuids: string[]): PlayerEvent[] {
  const got: PlayerEvent[] = []
  env.ctx.watch.subscribe(viewer, uuids, (e) => got.push(e), () => {})
  return got
}

/** Fan-Konten direkt anlegen (ohne Mojang-Ablauf). */
function bulkFans(env: TestEnv, n: number, prefix: string): string[] {
  const out: string[] = []
  for (let i = 0; i < n; i++) {
    const uuid = `${prefix}${i.toString(16).padStart(32 - prefix.length, '0')}`
    run(env.ctx.db, 'INSERT INTO users (uuid, name, name_lower, created_at, last_login_at) VALUES (?, ?, ?, ?, ?)', uuid, `W${prefix}${i}`, `w${prefix}${i}`, env.clock.t, env.clock.t)
    out.push(uuid)
  }
  return out
}

async function creator(env: TestEnv, name: string) {
  const uuid = (await login(env, name)).user.uuid
  const c = adminCreateCreator(env.ctx, OWNER, { player: name, tier: 'starter' }).creator
  return { uuid, code: c.mainCode! }
}

/** Ein frischer Admin-Code (1 Einlösung) für den Pinguin, schon normalisiert. */
const penguinCode = (env: TestEnv) => normalizeRedeemCode(createCodes(env.ctx, ADMIN, { cosmeticId: CREATOR_PET_ID, maxUses: 1, count: 1 }).codes[0]!.code)!

const petOf = (env: TestEnv, viewer: string, subject: string) =>
  lookupPlayers(env.ctx, viewer, [subject]).players.find((p) => p.uuid === subject)?.cosmetics.pet ?? null

describe('format: options, slot pet, attach ground, drivers slide/swim', () => {
  it('accepts options with existing bones and the new slot/attach/driver values', () => {
    const m = model('p', 'pet', {
      options: BEARD,
      animations: [{ id: 's', driver: 'slide', lengthMs: 500, tracks: [{ bone: 'root', channel: 'position', keys: [{ t: 0, v: [0, 0, 0] }] }] },
        { id: 'w', driver: 'swim', lengthMs: 500, tracks: [{ bone: 'root', channel: 'position', keys: [{ t: 0, v: [0, 0, 0] }] }] }],
    })
    expect(validateModel(m).errors).toEqual([])
  })

  it('rejects wrong slot/attach pairs and bad options', () => {
    const errs = (m: unknown) => validateModel(m).errors.join(' | ')
    expect(errs({ ...model('p', 'pet'), attach: 'head' })).toMatch(/slot pet braucht attach ground/)
    expect(errs({ ...model('h', 'hat'), attach: 'ground' })).toMatch(/slot hat braucht attach head/)
    expect(errs(model('h', 'hat', { options: [{ id: 'beard', bones: ['nope'], default: true }] }))).toMatch(/options\[0\]\.bones/)
    expect(errs(model('h', 'hat', { options: [{ id: 'Beard', bones: ['beard'], default: true }] }))).toMatch(/options\[0\]\.id/)
    expect(errs(model('h', 'hat', { options: [{ id: 'a', bones: ['beard'], default: 'yes' }] }))).toMatch(/options\[0\]\.default/)
    expect(errs(model('h', 'hat', { options: [{ id: 'a', bones: ['beard'], default: true, name: null }] }))).toMatch(/options\[0\]\.name/)
    expect(errs(model('h', 'hat', { options: [1, 2, 3, 4, 5].map((i) => ({ id: `o${i}`, bones: ['beard'], default: true })) }))).toMatch(/höchstens 4/)
    expect(errs(model('h', 'hat', { options: [{ id: 'a', bones: ['beard'], default: true }, { id: 'a', bones: ['root'], default: false }] }))).toMatch(/eindeutig/)
    expect(errs(model('h', 'hat', { animations: [{ id: 'x', driver: 'fly', lengthMs: 500, tracks: [{ bone: 'root', channel: 'position', keys: [{ t: 0, v: [0, 0, 0] }] }] }] }))).toMatch(/driver/)
  })

  it('an option that is off hides its bones and all child bones; missing choice = default', () => {
    const m = model('h', 'hat', { options: BEARD }) as never
    expect(resolveOptions(m, null)).toEqual({ beard: true })
    expect(resolveOptions(m, { beard: false, other: true })).toEqual({ beard: false })
    expect([...hiddenBones(m, null)]).toEqual([])
    expect([...hiddenBones(m, { beard: false })].sort()).toEqual(['beard', 'tip'])
  })

  it('the server build accepts pet + ground and refuses a pet hung on the head', () => {
    expect(() => synthetic('ok_pet', 'pet', 'code')).not.toThrow()
    const card = solidPng(8, 8)
    const bad = { ...model('bad_pet', 'pet'), attach: 'head' }
    expect(() =>
      buildV2Cosmetic({
        id: 'bad_pet', name: 'x', unlock: 'code', sort: 0, frames: 1, glowFrames: 0,
        files: { modelJson: Buffer.from(JSON.stringify(bad)), texture: solidPng(16, 16), glow: null, card, cardNight: card },
      }),
    ).toThrow(/attach/)
  })
})

describe('migration 34', () => {
  it('is idempotent on a fresh database and allows pet, options and source creator', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    migrate(db)
    migrateWinterCosmetics(db)
    migrateWinterCosmetics(db)
    const sql = (n: string) => (db.prepare("SELECT sql FROM sqlite_master WHERE name = ?").get(n) as { sql: string }).sql
    expect(sql('equipped_cosmetics')).toContain("'pet'")
    expect(sql('equipped_cosmetics')).toContain('options')
    expect(sql('user_cosmetics')).toContain("'creator'")
    const cols = (db.prepare('PRAGMA table_info(creators)').all() as { name: string }[]).map((c) => c.name)
    expect(cols).toContain('pet_count')
    expect(db.prepare('PRAGMA foreign_key_check').all()).toEqual([])
  })

  it('keeps existing rows when rebuilding the old tables', () => {
    const db = new DatabaseSync(':memory:')
    db.exec(`
CREATE TABLE users (uuid TEXT PRIMARY KEY);
CREATE TABLE cosmetics (id TEXT PRIMARY KEY);
CREATE TABLE creators (uuid TEXT PRIMARY KEY);
CREATE TABLE user_cosmetics (uuid TEXT NOT NULL REFERENCES users(uuid), cosmetic_id TEXT NOT NULL REFERENCES cosmetics(id),
  source TEXT NOT NULL CHECK (source IN ('code', 'admin', 'event', 'reward')), granted_at INTEGER NOT NULL, PRIMARY KEY (uuid, cosmetic_id));
CREATE TABLE equipped_cosmetics (uuid TEXT NOT NULL REFERENCES users(uuid), slot TEXT NOT NULL CHECK (slot IN ('hat', 'wings', 'back', 'aura', 'companion')),
  cosmetic_id TEXT NOT NULL REFERENCES cosmetics(id), PRIMARY KEY (uuid, slot));
INSERT INTO users VALUES ('u'); INSERT INTO cosmetics VALUES ('a'); INSERT INTO creators VALUES ('u');
INSERT INTO user_cosmetics VALUES ('u', 'a', 'code', 5);
INSERT INTO equipped_cosmetics VALUES ('u', 'hat', 'a');
`)
    migrateWinterCosmetics(db)
    expect(all(db, 'SELECT * FROM equipped_cosmetics')).toEqual([{ uuid: 'u', slot: 'hat', cosmetic_id: 'a', options: null }])
    expect(all(db, 'SELECT * FROM user_cosmetics')).toEqual([{ uuid: 'u', cosmetic_id: 'a', source: 'code', granted_at: 5 }])
    run(db, "INSERT INTO equipped_cosmetics VALUES ('u', 'pet', 'a', '{\"beard\":false}')")
    run(db, "UPDATE user_cosmetics SET source = 'creator'")
    expect(() => run(db, "UPDATE equipped_cosmetics SET options = '[1]' WHERE slot = 'pet'")).toThrow()
    expect(one<{ n: number }>(db, 'SELECT pet_count AS n FROM creators')!.n).toBe(0)
  })
})

describe('slot pet and options per player', () => {
  it('PUT body: pet and options are accepted, bad option ids / values / slots are refused', () => {
    expect(equipBody.safeParse({ pet: 'penguin' }).success).toBe(true)
    expect(equipBody.safeParse({ options: { hat: { beard: false } } }).success).toBe(true)
    expect(equipBody.safeParse({ options: { hat: { Beard: false } } }).success).toBe(false)
    expect(equipBody.safeParse({ options: { hat: { beard: 'no' } } }).success).toBe(false)
    expect(equipBody.safeParse({ options: { cape: { beard: false } } }).success).toBe(false)
    expect(equipBody.safeParse({ options: {} }).success).toBe(false)
    expect(equipBody.safeParse({ options: { hat: {} } }).success).toBe(false)
  })

  it('equips a pet only in slot pet; options round-trip via PUT/GET /v1/me/cosmetics and the lookup', async () => {
    const env = makeEnv()
    seedWinter(env)
    const s = await login(env, 'Steve')
    const u = s.user.uuid
    grantCosmetic(env.ctx, u, 'test_hat', 'code')
    grantCosmetic(env.ctx, u, CREATOR_PET_ID, 'code')
    const put = (body: unknown) => callRoute(env, putCosmetics, { method: 'PUT', url: '/v1/me/cosmetics', body: JSON.stringify(body), headers: auth(s.token) })
    expect((await put({ hat: CREATOR_PET_ID })).error?.code).toBe('wrong_slot')
    expect((await put({ pet: 'test_hat' })).error?.code).toBe('wrong_slot')
    expect((await put({ options: { hat: { beard: false } } })).error?.code).toBe('unknown_option')

    const on = await put({ hat: 'test_hat', pet: CREATOR_PET_ID })
    expect(on.body).toMatchObject({ equipped: { hat: { id: 'test_hat', options: { beard: true } }, pet: { id: CREATOR_PET_ID, slot: 'pet', format: 2, count: 1 } } })
    expect((on.body as { equipped: { pet: object } }).equipped.pet).not.toHaveProperty('options')
    expect((await put({ options: { hat: { mustache: false } } })).error?.code).toBe('unknown_option')
    expect((await put({ options: { pet: { beard: false } } })).error?.code).toBe('unknown_option')

    const seen = watch(env, u, [u])
    const off = await put({ options: { hat: { beard: false } } })
    expect(off.body).toMatchObject({ equipped: { hat: { id: 'test_hat', options: { beard: false } } } })
    expect(seen.filter((e) => e.type === 'cosmetics')).toHaveLength(1)
    // Gleiche Wahl erneut: kein Ereignis.
    await put({ options: { hat: { beard: false } } })
    expect(seen.filter((e) => e.type === 'cosmetics')).toHaveLength(1)
    const got = await callRoute(env, getCosmetics, { url: '/v1/me/cosmetics', headers: auth(s.token) })
    expect(got.body).toMatchObject({ equipped: { hat: { options: { beard: false } } } })

    const l = lookupPlayers(env.ctx, u, [u]).players[0]!
    expect(l.cosmetics.hat).toMatchObject({ id: 'test_hat', format: 2, options: { beard: false } })
    expect(l.cosmetics.pet).toMatchObject({ id: CREATOR_PET_ID, format: 2, count: 1 })
    expect(l.cosmetics.pet).not.toHaveProperty('options')

    // Erneut dasselbe Teil anlegen: Wahl bleibt. Ablegen + wieder anlegen: Standardwerte.
    expect((await put({ hat: 'test_hat' })).body).toMatchObject({ equipped: { hat: { options: { beard: false } } } })
    await put({ hat: null })
    expect((await put({ hat: 'test_hat' })).body).toMatchObject({ equipped: { hat: { options: { beard: true } } } })
    // Anlegen und Option in einem Aufruf.
    await put({ hat: null })
    expect((await put({ hat: 'test_hat', options: { hat: { beard: false } } })).body).toMatchObject({ equipped: { hat: { options: { beard: false } } } })
    // Option an einem leeren Platz in demselben Aufruf, der ihn leert.
    expect((await put({ hat: null, options: { hat: { beard: true } } })).error?.code).toBe('unknown_option')
    expect(equippedView(env.ctx, u).hat?.id).toBe('test_hat')
  })

  it('others see options and count through the lookup like the wearer', async () => {
    const env = makeEnv()
    seedWinter(env)
    const a = (await login(env, 'Alex')).user.uuid
    const b = (await login(env, 'Bob')).user.uuid
    grantCosmetic(env.ctx, a, 'test_hat', 'code')
    equipCosmetics(env.ctx, a, { hat: 'test_hat', pet: 'free_pet', options: { hat: { beard: false } } })
    const other = lookupPlayers(env.ctx, b, [a]).players[0]!
    expect(other.cosmetics.hat?.options).toEqual({ beard: false })
    expect(other.cosmetics.pet).toMatchObject({ id: 'free_pet', count: 1 })
  })

  it('the catalog lists pets with slot pet; the website lists wings and pets with attach and options', async () => {
    const env = makeEnv()
    seedWinter(env)
    const u = (await login(env, 'Steve')).user.uuid
    const cat = cosmeticCatalog(env.ctx, u)
    expect(cat.find((c) => c.id === CREATOR_PET_ID)).toMatchObject({ slot: 'pet', unlock: 'code', owned: false })
    expect(publicPets(env.ctx).map((p) => [p.id, p.attach, p.unlock])).toEqual([[CREATOR_PET_ID, 'ground', 'code'], ['free_pet', 'ground', 'free']])
    expect(publicWings(env.ctx).map((p) => [p.id, p.attach])).toEqual([['test_wings', 'body']])
    expect(publicHats(env.ctx).find((h) => h.id === 'test_hat')?.options).toEqual([{ id: 'beard', default: true, name: { de: 'Bart', en: 'Beard', es: 'Barba' } }])
    expect(publicPets(env.ctx)[0]!.model).toMatch(/^\/v1\/cosmetics\/penguin\/model\.json\?v=/)
  })

  it('admin codes work for a pet: redeem unlocks it, it can be equipped', async () => {
    const env = makeEnv()
    seedWinter(env)
    const u = (await login(env, 'Steve')).user.uuid
    const r = redeemCode(env.ctx, u, penguinCode(env))
    expect(r).toMatchObject({ kind: 'cosmetic', alreadyOwned: false, cosmetic: { id: CREATOR_PET_ID, slot: 'pet' } })
    expect(equipCosmetics(env.ctx, u, { pet: CREATOR_PET_ID }).pet?.id).toBe(CREATOR_PET_ID)
  })
})

describe('creator penguins: only the owning creator (§35.13)', () => {
  it('fan tiers: 1 / 10 / 100 / 1000 → 1 / 2 / 3 / 4; only the own pet of a creator counts by fans', () => {
    expect([0, 1, 9, 10, 99, 100, 999, 1000, 5000].map(petCountForFans)).toEqual([0, 1, 1, 2, 2, 3, 3, 4, 4])
    expect(petCount(CREATOR_PET_ID, undefined)).toBe(1)
    expect(petCount(CREATOR_PET_ID, { cosmeticId: CREATOR_PET_ID, fans: 0 })).toBe(1)
    expect(petCount(CREATOR_PET_ID, { cosmeticId: CREATOR_PET_ID, fans: 150 })).toBe(3)
    expect(petCount('free_pet', { cosmeticId: CREATOR_PET_ID, fans: 150 })).toBe(1)
  })

  it('a creator WITHOUT an own pet gets nothing from fans any more', async () => {
    const env = makeEnv()
    seedWinter(env)
    const c = await creator(env, 'Pixelmaus')
    for (const f of bulkFans(env, 10, 'a')) redeemCreatorCode(env.ctx, f, { code: c.code })
    expect(all(env.ctx.db, 'SELECT * FROM user_cosmetics WHERE uuid = ?', c.uuid)).toEqual([])
    expect(petOf(env, c.uuid, c.uuid)).toBeNull()
  })

  it('the owner gets and wears it at once, 10 fans → 2, revoke/restore/delete follow the state', async () => {
    const env = makeEnv()
    seedWinter(env)
    const c = await creator(env, 'Pixelmaus')
    const viewer = (await login(env, 'Viewer')).user.uuid
    const seen = watch(env, viewer, [c.uuid])
    adminAssignPet(env.ctx, OWNER, c.uuid, { cosmeticId: CREATOR_PET_ID })
    expect(one(env.ctx.db, 'SELECT source FROM user_cosmetics WHERE uuid = ? AND cosmetic_id = ?', c.uuid, CREATOR_PET_ID)).toEqual({ source: 'creator' })
    expect(petOf(env, viewer, c.uuid)).toMatchObject({ id: CREATOR_PET_ID, count: 1 })
    const afterAssign = seen.filter((e) => e.type === 'cosmetics').length
    expect(afterAssign).toBeGreaterThanOrEqual(1)
    const fans = bulkFans(env, 10, 'f')
    for (const f of fans.slice(0, 9)) redeemCreatorCode(env.ctx, f, { code: c.code })
    expect(seen.filter((e) => e.type === 'cosmetics').length).toBe(afterAssign) // 9 Fans: Stufe bleibt 1
    redeemCreatorCode(env.ctx, fans[9]!, { code: c.code })
    expect(petOf(env, viewer, c.uuid)).toMatchObject({ count: 2 })
    expect(equippedView(env.ctx, c.uuid).pet).toMatchObject({ id: CREATOR_PET_ID, count: 2 })
    expect(one<{ n: number }>(env.ctx.db, 'SELECT pet_count AS n FROM creators WHERE uuid = ?', c.uuid)!.n).toBe(2)
    // Ohne Fans bleibt das eigene Haustier (1 Tier).
    for (const f of fans) endSupport(env.ctx, f)
    expect(petOf(env, viewer, c.uuid)).toMatchObject({ count: 1 })
    adminRevokeCreator(env.ctx, OWNER, c.uuid, { reason: 'test', ban: false })
    expect(petOf(env, c.uuid, c.uuid)).toBeNull()
    expect(code(() => equipCosmetics(env.ctx, c.uuid, { pet: CREATOR_PET_ID }))).toBe('cosmetic_locked')
    adminRestoreCreator(env.ctx, OWNER, c.uuid)
    expect(petOf(env, c.uuid, c.uuid)).toMatchObject({ count: 1 })
    adminDeleteCreator(env.ctx, OWNER, c.uuid)
    expect(petOf(env, c.uuid, c.uuid)).toBeNull()
  })

  it('1000 fans → 4; does not auto-equip over another pet; a code penguin stays after the pet is taken away', async () => {
    const env = makeEnv()
    seedWinter(env)
    const c = await creator(env, 'Pixelmaus')
    equipCosmetics(env.ctx, c.uuid, { pet: 'free_pet' })
    for (const f of bulkFans(env, 1000, 'h')) {
      run(env.ctx.db, 'INSERT INTO creator_supports (fan_uuid, creator_uuid, last_creator_uuid, last_started_at, updated_at) VALUES (?, ?, ?, ?, ?)', f, c.uuid, c.uuid, 1, 1)
    }
    adminAssignPet(env.ctx, OWNER, c.uuid, { cosmeticId: CREATOR_PET_ID })
    expect(equippedView(env.ctx, c.uuid).pet?.id).toBe('free_pet')
    expect(equipCosmetics(env.ctx, c.uuid, { pet: CREATOR_PET_ID }).pet?.id).toBe(CREATOR_PET_ID)
    expect(petOf(env, c.uuid, c.uuid)).toMatchObject({ count: 4 })
    // Code auf das eigene Haustier: wird dauerhaft (Code wird verbraucht).
    expect(redeemCode(env.ctx, c.uuid, penguinCode(env)).alreadyOwned).toBe(false)
    adminRemovePet(env.ctx, OWNER, c.uuid)
    expect(one(env.ctx.db, 'SELECT source FROM user_cosmetics WHERE uuid = ? AND cosmetic_id = ?', c.uuid, CREATOR_PET_ID)).toEqual({ source: 'code' })
    expect(petOf(env, c.uuid, c.uuid)).toMatchObject({ id: CREATOR_PET_ID, count: 1 })
  })

  it('the periodic sync catches deleted accounts; without the item loaded nothing is granted', async () => {
    const env = makeEnv()
    seedWinter(env)
    const c = await creator(env, 'Pixelmaus')
    adminAssignPet(env.ctx, OWNER, c.uuid, { cosmeticId: CREATOR_PET_ID })
    const fans = bulkFans(env, 10, 'k')
    for (const f of fans) redeemCreatorCode(env.ctx, f, { code: c.code })
    expect(petOf(env, c.uuid, c.uuid)).toMatchObject({ count: 2 })
    for (const f of fans.slice(0, 5)) deleteUser(env.ctx, f)
    syncAllCreatorPets(env.ctx)
    expect(one<{ n: number }>(env.ctx.db, 'SELECT pet_count AS n FROM creators WHERE uuid = ?', c.uuid)!.n).toBe(1)

    const env2 = makeEnv()
    seedBuiltinCosmetics(env2.ctx, [synthetic('test_hat', 'hat', 'code')])
    expect(getCosmetic(env2.ctx, CREATOR_PET_ID)).toBeUndefined()
    const c2 = await creator(env2, 'Pixelmaus')
    const [fan] = bulkFans(env2, 1, 'k')
    redeemCreatorCode(env2.ctx, fan!, { code: c2.code })
    syncCreatorPet(env2.ctx, c2.uuid)
    expect(all(env2.ctx.db, 'SELECT * FROM user_cosmetics WHERE uuid = ?', c2.uuid)).toEqual([])
  })
})
