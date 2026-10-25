import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import { encode as jpegEncode } from 'jpeg-js'
import { describe, expect, it } from 'vitest'
import { listCodes, redeemCode } from '../server/lib/codes'
import { equippedView, seedBuiltinCosmetics, type AnyBuiltinCosmetic } from '../server/lib/cosmetics'
import { buildV2Cosmetic } from '../server/lib/cosmetics-v2'
import { petImageDir, syncAllCreatorPets } from '../server/lib/creator-pet'
import {
  adminAssignPet,
  adminDecidePetRequest,
  adminListPetRequests,
  adminPetOverview,
  adminRemovePet,
  adminSetPetLimits,
  createPetCodes,
  createPetRequest,
  creatorPetState,
  deletePetRequestImage,
  readPetRequestImage,
  revokePetCode,
  uploadPetRequestImage,
  withdrawPetRequest,
} from '../server/lib/creator-pets'
import {
  adminCreateCreator,
  adminDeleteCreator,
  adminRestoreCreator,
  adminRevokeCreator,
  answerHelperInvite,
  creatorDashboard,
  inviteHelper,
  redeemCreatorCode,
} from '../server/lib/creators'
import { all, migrate, one, run } from '../server/lib/db'
import { normalizeRedeemCode } from '../server/lib/ids'
import { lookupPlayers } from '../server/lib/lookup'
import { migrateCreatorPets } from '../server/lib/migrations'
import { ownerStaff } from '../server/lib/team'
import { getUser } from '../server/lib/users'
import { createWebSession } from '../server/lib/weblogin'
import adminAssignRoute from '../server/routes/v1/admin/creators/[uuid]/pet.put'
import petCodesPost from '../server/routes/v1/creator/pet/codes/index.post'
import petGet from '../server/routes/v1/creator/pet/index.get'
import petImagePut from '../server/routes/v1/creator/pet/requests/[id]/image.put'
import petImageGet from '../server/routes/v1/creator-pet-images/[file].get'
import { befriend, code, listen } from './chathelpers'
import { callRoute } from './circuithelpers'
import { ADMIN, login, makeEnv, solidPng, type TestEnv } from './helpers'

// Creator-Haustiere (§35.13). Nur synthetische Platzhalter-Modelle – echte Haustiere sind proprietär und nie im Repo.

const OWNER = ownerStaff(ADMIN)
const PENGUIN = 'penguin'
const FOX = 'test_fox'

function pet(id: string, slot: 'pet' | 'hat', unlock: 'code' | 'free' = 'code'): AnyBuiltinCosmetic {
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
    unlock,
    sort: 0,
    frames: 1,
    frameTimeMs: null,
    glowFrames: 0,
    glowFrameTimeMs: null,
    files: { modelJson: Buffer.from(JSON.stringify(model)), texture: solidPng(16, 16), glow: null, card, cardNight: card },
  })
}

function seedPets(env: TestEnv) {
  const list = [pet(PENGUIN, 'pet'), pet(FOX, 'pet'), pet('test_hat', 'hat'), pet('free_pet', 'pet', 'free')]
  seedBuiltinCosmetics(env.ctx, list.map((c, sort) => ({ ...c, sort })))
}

async function creator(env: TestEnv, name: string, tier: 'starter' | 'partner' | 'top' = 'starter') {
  const l = await login(env, name)
  const c = adminCreateCreator(env.ctx, OWNER, { player: name, tier }).creator
  return { uuid: l.user.uuid, token: l.token, code: c.mainCode! }
}

function bulkFans(env: TestEnv, n: number, prefix: string): string[] {
  const out: string[] = []
  for (let i = 0; i < n; i++) {
    const uuid = `${prefix}${i.toString(16).padStart(32 - prefix.length, '0')}`
    run(env.ctx.db, 'INSERT INTO users (uuid, name, name_lower, created_at, last_login_at) VALUES (?, ?, ?, ?, ?)', uuid, `P${prefix}${i}`, `p${prefix}${i}`, env.clock.t, env.clock.t)
    out.push(uuid)
  }
  return out
}

const petOf = (env: TestEnv, viewer: string, subject: string) =>
  lookupPlayers(env.ctx, viewer, [subject]).players.find((p) => p.uuid === subject)?.cosmetics.pet ?? null

function jpeg(w: number, h: number): Buffer {
  const data = Buffer.alloc(w * h * 4, 0)
  for (let i = 0; i < data.length; i += 4) {
    data[i] = 30
    data[i + 1] = 120
    data[i + 2] = 200
    data[i + 3] = 255
  }
  return Buffer.from(jpegEncode({ width: w, height: h, data }, 90).data)
}

describe('migration 35', () => {
  it('is idempotent and makes earlier automatic penguins permanent (source admin)', async () => {
    const env = makeEnv()
    seedPets(env)
    const c = await creator(env, 'OldCreator')
    run(env.ctx.db, "INSERT INTO user_cosmetics (uuid, cosmetic_id, source, granted_at) VALUES (?, ?, 'creator', 1)", c.uuid, PENGUIN)
    run(env.ctx.db, 'UPDATE creators SET pet_count = 3 WHERE uuid = ?', c.uuid)
    migrateCreatorPets(env.ctx.db as unknown as DatabaseSync)
    migrateCreatorPets(env.ctx.db as unknown as DatabaseSync)
    expect(one(env.ctx.db, 'SELECT source FROM user_cosmetics WHERE uuid = ? AND cosmetic_id = ?', c.uuid, PENGUIN)).toEqual({ source: 'admin' })
    expect(all(env.ctx.db, 'SELECT tier, monthly FROM creator_pet_limits ORDER BY monthly')).toEqual([
      { tier: 'starter', monthly: 10 }, { tier: 'partner', monthly: 50 }, { tier: 'top', monthly: 200 },
    ])
    // Der Abgleich nimmt den alten Pinguin nicht mehr weg; ohne eigenes Haustier zählt er als 1.
    syncAllCreatorPets(env.ctx)
    expect(one(env.ctx.db, 'SELECT source FROM user_cosmetics WHERE uuid = ? AND cosmetic_id = ?', c.uuid, PENGUIN)).toEqual({ source: 'admin' })
    expect(one<{ n: number }>(env.ctx.db, 'SELECT pet_count AS n FROM creators WHERE uuid = ?', c.uuid)!.n).toBe(0)
    run(env.ctx.db, "INSERT INTO equipped_cosmetics (uuid, slot, cosmetic_id) VALUES (?, 'pet', ?)", c.uuid, PENGUIN)
    for (const f of bulkFans(env, 12, 'm')) redeemCreatorCode(env.ctx, f, { code: c.code })
    expect(petOf(env, c.uuid, c.uuid)).toMatchObject({ id: PENGUIN, count: 1 })
  })

  it('fresh database: all migrations, tables and the codes column exist', () => {
    const db = new DatabaseSync(':memory:')
    migrate(db as never)
    const cols = (db.prepare('PRAGMA table_info(codes)').all() as { name: string }[]).map((c) => c.name)
    expect(cols).toContain('creator_uuid')
    for (const t of ['creator_pets', 'creator_pet_requests', 'creator_pet_limits']) {
      expect(db.prepare("SELECT 1 AS x FROM sqlite_master WHERE type = 'table' AND name = ?").get(t)).toBeTruthy()
    }
  })
})

describe('assigning pets (team)', () => {
  it('only pet-slot items, one per creator and one per pet; replacing; overview lists owners', async () => {
    const env = makeEnv()
    seedPets(env)
    const a = await creator(env, 'PixelCST', 'top')
    const b = await creator(env, 'Other')
    const ev = listen(env, a.uuid)
    expect(code(() => adminAssignPet(env.ctx, OWNER, a.uuid, { cosmeticId: 'test_hat' }))).toBe('not_a_pet')
    expect(code(() => adminAssignPet(env.ctx, OWNER, a.uuid, { cosmeticId: 'nope' }))).toBe('cosmetic_not_found')
    expect(code(() => adminAssignPet(env.ctx, OWNER, a.uuid, { cosmeticId: 'bad id!' }))).toBe('invalid_request')
    expect(code(() => adminAssignPet(env.ctx, OWNER, '0'.repeat(32), { cosmeticId: PENGUIN }))).toBe('creator_not_found')
    expect(adminAssignPet(env.ctx, OWNER, a.uuid, { cosmeticId: PENGUIN }).pet).toMatchObject({ cosmeticId: PENGUIN, count: 1, available: true })
    expect(ev.of('creator_updated')).toEqual([{ type: 'creator_updated', kind: 'pet_assigned' }])
    expect(equippedView(env.ctx, a.uuid).pet?.id).toBe(PENGUIN)
    let err: unknown
    try {
      adminAssignPet(env.ctx, OWNER, b.uuid, { cosmeticId: PENGUIN })
    } catch (e) {
      err = e
    }
    expect(err).toMatchObject({ status: 409, code: 'creator_pet_taken', details: { owner: { uuid: a.uuid, name: 'PixelCST' } } })

    const o = adminPetOverview(env.ctx, OWNER)
    expect(o.limits).toEqual({ starter: 10, partner: 50, top: 200 })
    expect(o.pets).toMatchObject([{ creator: { uuid: a.uuid, name: 'PixelCST', tier: 'top', state: 'active' }, pet: { cosmeticId: PENGUIN }, assignedBy: { uuid: ADMIN } }])
    expect(o.cosmetics.map((c) => [c.id, c.owner?.name ?? null])).toEqual([[PENGUIN, 'PixelCST'], [FOX, null], ['free_pet', null]])
    expect(o.cosmetics[0]!.card).toMatch(/^https:\/\/api\.example\.test\/v1\/cosmetics\/penguin\/card\.png/)

    // Ersetzen: der Pinguin-Besitz fällt weg (nicht ausgerüstet weiter), der Fuchs kommt.
    adminAssignPet(env.ctx, OWNER, a.uuid, { cosmeticId: FOX })
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM user_cosmetics WHERE uuid = ? AND cosmetic_id = ?', a.uuid, PENGUIN)).toBeUndefined()
    expect(one(env.ctx.db, 'SELECT source FROM user_cosmetics WHERE uuid = ? AND cosmetic_id = ?', a.uuid, FOX)).toEqual({ source: 'creator' })
    expect(equippedView(env.ctx, a.uuid).pet?.id).toBe(FOX)
    expect(adminAssignPet(env.ctx, OWNER, b.uuid, { cosmeticId: PENGUIN }).pet.cosmeticId).toBe(PENGUIN)
    expect(creatorDashboard(env.ctx, b.uuid).pet).toMatchObject({ cosmeticId: PENGUIN })

    adminRemovePet(env.ctx, OWNER, a.uuid)
    expect(petOf(env, a.uuid, a.uuid)).toBeNull()
    expect(code(() => adminRemovePet(env.ctx, OWNER, a.uuid))).toBe('creator_pet_missing')
    expect(all<{ action: string }>(env.ctx.db, "SELECT action FROM admin_log WHERE action LIKE 'creator.pet.%' ORDER BY rowid").map((r) => r.action))
      .toEqual(['creator.pet.assign', 'creator.pet.assign', 'creator.pet.assign', 'creator.pet.remove'])
  })
})

describe('pet codes by the owning creator', () => {
  it('normal codes with monthly limit per tier, revoke, month reset, redeem via /v1/redeem, origin in the team list', async () => {
    const env = makeEnv()
    seedPets(env)
    const c = await creator(env, 'PixelCST')
    expect(code(() => createPetCodes(env.ctx, c.uuid, { count: 1 }))).toBe('creator_pet_missing')
    adminAssignPet(env.ctx, OWNER, c.uuid, { cosmeticId: PENGUIN })
    expect(code(() => createPetCodes(env.ctx, c.uuid, { count: 21 }))).toBe('invalid_request')
    expect(code(() => createPetCodes(env.ctx, c.uuid, { maxUses: 101 }))).toBe('invalid_request')
    expect(code(() => createPetCodes(env.ctx, c.uuid, { count: 1, expiresAt: '2020-01-01T00:00:00Z' }))).toBe('invalid_request')

    const r = createPetCodes(env.ctx, c.uuid, { count: 2, maxUses: 3, note: 'Stream' })
    expect(r.codes).toHaveLength(2)
    expect(r.codes[0]).toMatchObject({ maxUses: 3, uses: 0, note: 'Stream', revokedAt: null, createdBy: { uuid: c.uuid, name: 'PixelCST' } })
    expect(r.codes[0]!.code).toMatch(/^[A-Z0-9-]+$/)
    expect(r.quota).toEqual({ month: '2026-09', limit: 10, used: 6, remaining: 4, resetsAt: '2026-10-01T00:00:00.000Z' })
    let err: unknown
    try {
      createPetCodes(env.ctx, c.uuid, { count: 1, maxUses: 5 })
    } catch (e) {
      err = e
    }
    expect(err).toMatchObject({ status: 403, code: 'creator_pet_limit', details: { limit: 10, used: 6, remaining: 4 } })

    // Einlösen wie jeder Team-Code; der Fan besitzt den Pinguin dauerhaft.
    const fan = (await login(env, 'Fan')).user.uuid
    const red = redeemCode(env.ctx, fan, normalizeRedeemCode(r.codes[0]!.code)!)
    expect(red).toMatchObject({ kind: 'cosmetic', alreadyOwned: false, cosmetic: { id: PENGUIN } })
    expect(one(env.ctx.db, 'SELECT source FROM user_cosmetics WHERE uuid = ? AND cosmetic_id = ?', fan, PENGUIN)).toEqual({ source: 'code' })

    // Widerrufen: nur tatsächliche Einlösungen zählen weiter (1 statt 3).
    const rev = revokePetCode(env.ctx, c.uuid, r.codes[0]!.id)
    expect(rev.quota.used).toBe(4)
    expect(rev.codes.find((k) => k.id === r.codes[0]!.id)!.revokedAt).not.toBeNull()
    const other = (await login(env, 'Fan2')).user.uuid
    expect(code(() => redeemCode(env.ctx, other, normalizeRedeemCode(r.codes[0]!.code)!))).toBe('invalid_code')
    expect(code(() => revokePetCode(env.ctx, c.uuid, 999_999))).toBe('code_not_found')

    // Team-Liste zeigt die Herkunft.
    const team = listCodes(env.ctx).filter((k) => k.cosmeticId === PENGUIN)
    expect(team.every((k) => k.creator?.uuid === c.uuid && k.creator.name === 'PixelCST')).toBe(true)

    // Neuer Monat: wieder volle Grenze. Team ändert die Grenze.
    env.clock.t = Date.UTC(2026, 9, 1, 0, 0, 1)
    expect(creatorPetState(env.ctx, c.uuid).quota).toMatchObject({ month: '2026-10', used: 0, remaining: 10 })
    expect(code(() => adminSetPetLimits(env.ctx, OWNER, { starter: -1, partner: 1, top: 1 }))).toBe('invalid_request')
    expect(adminSetPetLimits(env.ctx, OWNER, { starter: 25, partner: 60, top: 300 }).limits).toEqual({ starter: 25, partner: 60, top: 300 })
    expect(createPetCodes(env.ctx, c.uuid, { count: 20, maxUses: 1 }).quota).toMatchObject({ limit: 25, used: 20, remaining: 5 })
  })

  it('codes stop working while the creator is revoked or after the pet is taken away', async () => {
    const env = makeEnv()
    seedPets(env)
    const c = await creator(env, 'PixelCST', 'partner')
    adminAssignPet(env.ctx, OWNER, c.uuid, { cosmeticId: PENGUIN })
    const [k1, k2] = createPetCodes(env.ctx, c.uuid, { count: 2, maxUses: 5 }).codes
    const fan = (await login(env, 'Fan')).user.uuid
    adminRevokeCreator(env.ctx, OWNER, c.uuid, { reason: 'pause', ban: false })
    expect(code(() => redeemCode(env.ctx, fan, normalizeRedeemCode(k1!.code)!))).toBe('invalid_code')
    expect(code(() => createPetCodes(env.ctx, c.uuid, { count: 1 }))).toBe('creator_inactive')
    adminRestoreCreator(env.ctx, OWNER, c.uuid)
    expect(redeemCode(env.ctx, fan, normalizeRedeemCode(k1!.code)!).alreadyOwned).toBe(false)
    adminRemovePet(env.ctx, OWNER, c.uuid)
    const fan2 = (await login(env, 'Fan2')).user.uuid
    expect(code(() => redeemCode(env.ctx, fan2, normalizeRedeemCode(k2!.code)!))).toBe('invalid_code')
    // Gelöschter Creator: Codes bleiben in der Team-Liste, sind aber widerrufen.
    adminAssignPet(env.ctx, OWNER, c.uuid, { cosmeticId: PENGUIN })
    createPetCodes(env.ctx, c.uuid, { count: 1 })
    adminDeleteCreator(env.ctx, OWNER, c.uuid)
    expect(listCodes(env.ctx).filter((k) => k.creator?.uuid === c.uuid).every((k) => k.revokedAt !== null)).toBe(true)
  })

  it('helpers with the "codes" right create and revoke; without it 403; requests stay owner-only', async () => {
    const env = makeEnv()
    seedPets(env)
    const c = await creator(env, 'PixelCST', 'partner')
    adminAssignPet(env.ctx, OWNER, c.uuid, { cosmeticId: PENGUIN })
    const ed = await login(env, 'Editor')
    const st = await login(env, 'Stats')
    befriend(env, getUser(env.ctx, c.uuid)!, ed.user)
    befriend(env, getUser(env.ctx, c.uuid)!, st.user)
    inviteHelper(env.ctx, c.uuid, { uuid: ed.user.uuid, rights: ['codes'] })
    inviteHelper(env.ctx, c.uuid, { uuid: st.user.uuid, rights: ['stats'] })
    answerHelperInvite(env.ctx, ed.user.uuid, c.uuid, { accept: true })
    answerHelperInvite(env.ctx, st.user.uuid, c.uuid, { accept: true })

    const made = createPetCodes(env.ctx, ed.user.uuid, { count: 1, maxUses: 2 }, c.uuid)
    expect(made.codes[0]!.createdBy).toEqual({ uuid: ed.user.uuid, name: 'Editor' })
    expect(revokePetCode(env.ctx, ed.user.uuid, made.codes[0]!.id, c.uuid).codes).toHaveLength(1)
    expect(code(() => createPetCodes(env.ctx, st.user.uuid, { count: 1 }, c.uuid))).toBe('creator_helper_right')
    const view = creatorPetState(env.ctx, st.user.uuid, c.uuid)
    expect(view).toMatchObject({ pet: { cosmeticId: PENGUIN }, codes: null, requests: [], canRequest: false })
    expect(creatorPetState(env.ctx, ed.user.uuid, c.uuid).codes).toHaveLength(1)
    expect(code(() => createPetRequest(env.ctx, ed.user.uuid, { species: 'Fuchs', description: 'x' }, c.uuid))).toBe('creator_helper_right')
  })
})

describe('pet requests', () => {
  it('one open request, image PNG/JPEG checked by magic bytes, team status + reply, done assigns the pet', async () => {
    const env = makeEnv()
    seedPets(env)
    const c = await creator(env, 'Pixelmaus')
    const ev = listen(env, c.uuid)
    expect(code(() => createPetRequest(env.ctx, c.uuid, { species: '', description: 'x' }))).toBe('invalid_request')
    expect(code(() => createPetRequest(env.ctx, c.uuid, { species: 'Fuchs', description: 'a\u0007b' }))).toBe('invalid_request')
    const r = createPetRequest(env.ctx, c.uuid, { species: 'Fuchs', description: 'Rot mit\r\nweißem Schwanz', colors: 'rot, weiß' }).requests[0]!
    expect(r).toMatchObject({ status: 'open', species: 'Fuchs', description: 'Rot mit\nweißem Schwanz', colors: 'rot, weiß', image: null, reply: null })
    expect(code(() => createPetRequest(env.ctx, c.uuid, { species: 'Hund', description: 'x' }))).toBe('creator_request_open')
    expect(creatorPetState(env.ctx, c.uuid).canRequest).toBe(false)

    // Bild: PNG ok, JPEG ersetzt es (alte Datei weg), WebP/Unsinn → 415, falscher Typ → 415, zu groß → 413.
    // Mit Transparenz bleibt es PNG (undurchsichtige Bilder werden JPEG).
    const png = await uploadPetRequestImage(env.ctx, c.uuid, r.id, solidPng(40, 30, [10, 200, 30, 128]), 'image/png')
    expect(png.request.image).toMatchObject({ mime: 'image/png', width: 40, height: 30 })
    expect(png.request.image!.url).toMatch(/^\/v1\/creator-pet-images\/[0-9a-f]{64}\.png$/)
    const pngFile = join(petImageDir(env.ctx), png.request.image!.url.split('/').pop()!)
    expect(existsSync(pngFile)).toBe(true)
    const jpg = await uploadPetRequestImage(env.ctx, c.uuid, r.id, jpeg(32, 32), 'image/jpeg')
    expect(jpg.request.image).toMatchObject({ mime: 'image/jpeg', width: 32, height: 32 })
    expect(existsSync(pngFile)).toBe(false)
    const webp = Buffer.concat([Buffer.from('RIFF'), Buffer.alloc(4), Buffer.from('WEBPVP8 '), Buffer.alloc(16)])
    await expect(uploadPetRequestImage(env.ctx, c.uuid, r.id, webp, 'image/png')).rejects.toMatchObject({ status: 415 })
    await expect(uploadPetRequestImage(env.ctx, c.uuid, r.id, Buffer.from('<svg></svg>'), 'image/png')).rejects.toMatchObject({ status: 415 })
    await expect(uploadPetRequestImage(env.ctx, c.uuid, r.id, solidPng(8, 8), 'image/jpeg')).rejects.toMatchObject({ status: 415 })
    await expect(uploadPetRequestImage(env.ctx, c.uuid, r.id, Buffer.concat([solidPng(8, 8), Buffer.alloc(2 * 1024 * 1024)]), 'image/png')).rejects.toMatchObject({ status: 413 })

    // Bild sehen: Creator selbst + Team, sonst 404.
    const [sha, ext] = jpg.request.image!.url.split('/').pop()!.split('.') as [string, 'png' | 'jpg']
    expect(readPetRequestImage(env.ctx, sha, ext, { uuid: c.uuid, admin: false }).mime).toBe('image/jpeg')
    expect(readPetRequestImage(env.ctx, sha, ext, { uuid: ADMIN, admin: true }).body.length).toBeGreaterThan(0)
    const stranger = (await login(env, 'Stranger')).user.uuid
    expect(code(() => readPetRequestImage(env.ctx, sha, ext, { uuid: stranger, admin: false }))).toBe('image_not_found')
    expect(code(() => readPetRequestImage(env.ctx, sha, ext, null))).toBe('image_not_found')

    // Team: in Arbeit + Antwort → Creator kann nicht mehr ändern/zurückziehen.
    const list = adminDecidePetRequest(env.ctx, OWNER, r.id, { status: 'in_progress', reply: 'Wir bauen ihn!' }).requests
    expect(list[0]).toMatchObject({ id: r.id, status: 'in_progress', reply: 'Wir bauen ihn!', creator: { uuid: c.uuid, name: 'Pixelmaus', tier: 'starter', state: 'active' } })
    expect(ev.of('creator_updated')).toEqual([{ type: 'creator_updated', kind: 'pet_request_answered', reason: 'Wir bauen ihn!' }])
    expect(code(() => withdrawPetRequest(env.ctx, c.uuid, r.id))).toBe('request_closed')
    expect(code(() => deletePetRequestImage(env.ctx, c.uuid, r.id))).toBe('request_closed')
    expect(code(() => adminDecidePetRequest(env.ctx, OWNER, r.id, { status: 'in_progress', cosmeticId: FOX }))).toBe('invalid_request')

    // Fertig + zuordnen in einem Schritt.
    const done = adminDecidePetRequest(env.ctx, OWNER, r.id, { status: 'done', reply: 'Fertig', cosmeticId: FOX }).requests
    expect(done[0]).toMatchObject({ status: 'done', cosmeticId: FOX, reply: 'Fertig' })
    expect(done[0]!.decidedAt).not.toBeNull()
    expect(creatorPetState(env.ctx, c.uuid)).toMatchObject({ pet: { cosmeticId: FOX }, canRequest: false, requests: [{ status: 'done' }] })
    expect(ev.of('creator_updated').map((e) => e.kind)).toEqual(['pet_request_answered', 'pet_assigned', 'pet_request_answered'])
    expect(code(() => createPetRequest(env.ctx, c.uuid, { species: 'Hund', description: 'x' }))).toBe('creator_pet_owned')
    expect(adminListPetRequests(env.ctx, OWNER, { status: 'open' }).requests).toEqual([])

    // Löschen des Creators räumt die Bilddatei weg.
    const jpgFile = join(petImageDir(env.ctx), `${sha}.${ext}`)
    expect(existsSync(jpgFile)).toBe(true)
    adminDeleteCreator(env.ctx, OWNER, c.uuid)
    expect(existsSync(jpgFile)).toBe(false)
  })

  it('withdraw removes the request and its image; assigning closes open requests', async () => {
    const env = makeEnv()
    seedPets(env)
    const c = await creator(env, 'Pixelmaus')
    const r = createPetRequest(env.ctx, c.uuid, { species: 'Eule', description: 'Klein' }).requests[0]!
    const up = await uploadPetRequestImage(env.ctx, c.uuid, r.id, solidPng(16, 16), 'image/png')
    const file = join(petImageDir(env.ctx), up.request.image!.url.split('/').pop()!)
    withdrawPetRequest(env.ctx, c.uuid, r.id)
    expect(existsSync(file)).toBe(false)
    expect(creatorPetState(env.ctx, c.uuid)).toMatchObject({ requests: [], canRequest: true })
    const r2 = createPetRequest(env.ctx, c.uuid, { species: 'Eule', description: 'Klein' }).requests[0]!
    adminAssignPet(env.ctx, OWNER, c.uuid, { cosmeticId: FOX })
    expect(creatorPetState(env.ctx, c.uuid).requests).toMatchObject([{ id: r2.id, status: 'done', cosmeticId: FOX }])
  })
})

describe('routes (Bearer, website session + CSRF)', () => {
  it('GET /v1/creator/pet, POST codes with cookie needs CSRF, image upload checks the type, admin assign', async () => {
    const env = makeEnv()
    seedPets(env)
    const c = await creator(env, 'PixelCST', 'top')
    const bearer = { authorization: `Bearer ${c.token}` }
    const json = { 'content-type': 'application/json' }
    const admin = await login(env, 'Theredstonee', ADMIN)
    const assign = await callRoute(env, adminAssignRoute, {
      method: 'PUT', url: `/v1/admin/creators/${c.uuid}/pet`, params: { uuid: c.uuid },
      headers: { authorization: `Bearer ${admin.token}`, ...json }, body: JSON.stringify({ cosmeticId: PENGUIN }),
    })
    expect(assign.status).toBe(200)
    expect(assign.body).toMatchObject({ pet: { cosmeticId: PENGUIN } })

    const state = await callRoute(env, petGet, { url: '/v1/creator/pet', headers: bearer })
    expect(state.body).toMatchObject({ pet: { cosmeticId: PENGUIN, count: 1 }, quota: { limit: 200, used: 0 }, codes: [], requests: [], canRequest: false })

    const web = createWebSession(env.ctx, c.uuid)
    const cookie = { cookie: `trs_session=${web.token}` }
    const body = JSON.stringify({ count: 2, maxUses: 10 })
    const noCsrf = await callRoute(env, petCodesPost, { method: 'POST', url: '/v1/creator/pet/codes', headers: { ...cookie, ...json }, body })
    expect(noCsrf).toMatchObject({ status: 403, error: { code: 'csrf_failed' } })
    const ok = await callRoute(env, petCodesPost, { method: 'POST', url: '/v1/creator/pet/codes', headers: { ...cookie, ...json, 'x-csrf-token': web.csrf }, body })
    expect(ok.status).toBe(201)
    expect((ok.body as { quota: { used: number } }).quota.used).toBe(20)

    // Bild-Upload: falscher Content-Type → 415; ein Fremder sieht das Bild nicht.
    const other = await creator(env, 'Other')
    const r = createPetRequest(env.ctx, other.uuid, { species: 'Eule', description: 'Klein' }).requests[0]!
    const bad = await callRoute(env, petImagePut, {
      method: 'PUT', url: `/v1/creator/pet/requests/${r.id}/image`, params: { id: String(r.id) },
      headers: { authorization: `Bearer ${other.token}`, 'content-type': 'image/webp' }, body: solidPng(8, 8),
    })
    expect(bad.status).toBe(415)
    const up = await callRoute(env, petImagePut, {
      method: 'PUT', url: `/v1/creator/pet/requests/${r.id}/image`, params: { id: String(r.id) },
      headers: { authorization: `Bearer ${other.token}`, 'content-type': 'image/png' }, body: solidPng(20, 20),
    })
    expect(up.status).toBe(200)
    const file = (up.body as { request: { image: { url: string } } }).request.image.url.split('/').pop()!
    const mine = await callRoute(env, petImageGet, { url: `/v1/creator-pet-images/${file}`, params: { file }, headers: { authorization: `Bearer ${other.token}` } })
    expect(mine.status).toBe(200)
    expect(mine.headers['cache-control']).toBe('private, no-store')
    const foreign = await callRoute(env, petImageGet, { url: `/v1/creator-pet-images/${file}`, params: { file }, headers: bearer })
    expect(foreign.status).toBe(404)
  })
})
