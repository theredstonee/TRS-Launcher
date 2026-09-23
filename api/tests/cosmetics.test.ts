import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { PNG } from 'pngjs'
import { describe, expect, it } from 'vitest'
import { banUser, listCosmeticsForReview, rejectCosmetic, approveCosmetic, deleteCosmeticAdmin, stats, userInfo } from '../server/lib/admin'
import { loadBuiltinCosmetics } from '../server/lib/builtin'
import { createCodes, redeemCode } from '../server/lib/codes'
import {
  cosmeticCatalog,
  deleteOwnCosmetic,
  equipCosmetics,
  equippedView,
  getCosmetic,
  grantCosmetic,
  ownedEmotes,
  readCosmeticTexture,
  reportCosmetic,
  revokeCosmetic,
  seedBuiltinCosmetics,
  uploadCosmetic,
} from '../server/lib/cosmetics'
import { EMOTES } from '../server/lib/emotes'
import { block } from '../server/lib/friends'
import { normalizeRedeemCode } from '../server/lib/ids'
import { lookupPlayers } from '../server/lib/lookup'
import { deleteUser, getUser, updateSettings } from '../server/lib/users'
import { cubeFaces, parseTemplates, usedMask } from '../server/lib/templates'
import { ADMIN, bundledTemplates, fixtureCosmetics, login, makeEnv, seedCosmeticFixtures, solidPng, templatePng, type TestEnv } from './helpers'

function code(fn: () => unknown): string {
  try {
    fn()
  } catch (e) {
    return (e as { code?: string }).code ?? (e as Error).message
  }
  return 'ok'
}

const ASSETS = join(__dirname, '..', 'assets', 'cosmetics')
const rawTemplates = () => JSON.parse(readFileSync(join(ASSETS, 'templates.json'), 'utf8'))

describe('templates', () => {
  it('the bundled templates.json is valid: nets inside the texture, no overlaps', () => {
    const set = bundledTemplates()
    expect(set.list.map((t) => t.id)).toEqual(
      expect.arrayContaining(['crown', 'cap', 'lamp_helmet', 'tophat', 'wings', 'backpack', 'halo', 'ring', 'orbit', 'trail']),
    )
    expect(set.get('crown')).toMatchObject({ slot: 'hat', kind: 'model', textureWidth: 64, textureHeight: 16 })
    expect(set.get('wings')!.slot).toBe('wings')
    expect(set.get('backpack')!.slot).toBe('back')
    expect(set.get('halo')).toMatchObject({ slot: 'aura', kind: 'model', textureWidth: 32, textureHeight: 8 })
    for (const id of ['ring', 'orbit', 'trail']) expect(set.get(id)).toMatchObject({ slot: 'aura', kind: 'particles' })
    expect(JSON.parse(set.json).templates).toHaveLength(set.list.length)
    expect(set.etag).toMatch(/^"[0-9a-f]{32}"$/)
  })

  it('uses the vanilla box UV net', () => {
    // Wie der Kopf eines Skins: 8×8×8 bei (0, 0)
    const f = cubeFaces({ from: [-4, 0, -4], to: [4, 8, 4], uv: [0, 0] })
    expect(f).toEqual({
      top: { x: 8, y: 0, w: 8, h: 8 },
      bottom: { x: 16, y: 0, w: 8, h: 8 },
      right: { x: 0, y: 8, w: 8, h: 8 },
      front: { x: 8, y: 8, w: 8, h: 8 },
      left: { x: 16, y: 8, w: 8, h: 8 },
      back: { x: 24, y: 8, w: 8, h: 8 },
    })
    // Nicht-würfelförmig: w=10 (x), h=4 (y), d=2 (z) bei (3, 5)
    const g = cubeFaces({ from: [0, 0, 0], to: [10, 4, 2], uv: [3, 5] })
    expect(g.top).toEqual({ x: 5, y: 5, w: 10, h: 2 })
    expect(g.back).toEqual({ x: 17, y: 7, w: 10, h: 4 })
  })

  it('rejects broken template files', () => {
    const base = rawTemplates()
    const mutate = (fn: (f: { templates: Record<string, unknown>[] }) => void) => {
      const f = structuredClone(base)
      fn(f)
      return () => parseTemplates(f)
    }
    const crown = (f: { templates: Record<string, unknown>[] }) => f.templates[0] as { cubes: Record<string, unknown>[], textureWidth: number }
    expect(mutate((f) => { crown(f).textureWidth = 32 })).toThrow(/exceeds/)
    expect(mutate((f) => { crown(f).cubes.push({ ...crown(f).cubes[0], uv: [2, 2] }) })).toThrow(/overlap/)
    expect(mutate((f) => { crown(f).cubes[0]!.to = [5, 11.5, 5] })).toThrow(/whole number/)
    expect(mutate((f) => { crown(f).cubes[0]!.from = [-5, 7.25, -5] })).toThrow(/multiple of 0.5/)
    expect(mutate((f) => { crown(f).cubes[0]!.anim = 'flap' })).toThrow(/pivot/)
    expect(mutate((f) => { crown(f).cubes[0]!.extra = 1 })).toThrow()
    expect(mutate((f) => { f.templates.push(f.templates[0]!) })).toThrow(/duplicate/)
    expect(mutate((f) => { (f.templates.find((t) => t.kind === 'particles') as { slot: string }).slot = 'hat' })).toThrow() // Partikel nur als aura
  })

  it('computes the paintable mask per scale', () => {
    const t = bundledTemplates().get('halo')!
    const m1 = usedMask(t, 1)
    const m2 = usedMask(t, 2)
    expect(m1.length).toBe(32 * 8)
    expect(m2.length).toBe(64 * 16)
    expect(m1.reduce((a, b) => a + b, 0) * 4).toBe(m2.reduce((a, b) => a + b, 0))
    expect(m1[0]).toBe(0) // (0,0) liegt im leeren Eck des ersten Netzes
    expect(m1[1]).toBe(1) // Oberseite des vorderen Stabs
  })
})

describe('bundled cosmetics', () => {
  it('catalog.json matches the templates and is seedable', async () => {
    const env = makeEnv()
    const list = await loadBuiltinCosmetics(
      async () => JSON.parse(readFileSync(join(ASSETS, 'catalog.json'), 'utf8')),
      async (name) => readFileSync(join(ASSETS, name)),
    )
    expect(list.map((c) => c.id)).toEqual(expect.arrayContaining([
      'redstone_crown', 'team_crown', 'trs_cap', 'lamp_helmet', 'top_hat', 'redstone_wings', 'dragon_wings',
      'backpack', 'halo', 'redstone_aura', 'footprints',
    ]))
    seedBuiltinCosmetics(env.ctx, list)
    for (const c of list) {
      const t = env.ctx.templates.get(c.template)!
      expect(c.png.readUInt32BE(16)).toBe(t.textureWidth * c.scale)
      expect(c.png.readUInt32BE(20)).toBe(t.textureHeight * c.scale * c.frames)
    }
    expect(list.find((c) => c.id === 'team_crown')!.unlock).toBe('admin')
    expect(list.filter((c) => c.frames > 1).map((c) => c.id)).toEqual(
      expect.arrayContaining(['redstone_crown', 'lamp_helmet', 'redstone_wings', 'halo']),
    )
    const u = await login(env, 'Steve')
    const cat = cosmeticCatalog(env.ctx, u.user.uuid)
    expect(cat.find((c) => c.id === 'team_crown')).toMatchObject({ owned: false, slot: 'hat', unlock: 'admin' })
    expect(cat.find((c) => c.id === 'redstone_wings')!.texture).toMatchObject({ scale: 2, width: 128, height: 64, frames: 8, animated: true })
  })

  it('rejects wrong sizes, unknown templates and id clashes with emotes', () => {
    const env = makeEnv()
    const [crown] = fixtureCosmetics(env)
    expect(code(() => seedBuiltinCosmetics(env.ctx, [{ ...crown!, frames: 2 }]))).toMatch(/does not match/)
    expect(code(() => seedBuiltinCosmetics(env.ctx, [{ ...crown!, scale: 1 }]))).toMatch(/does not match/)
    expect(code(() => seedBuiltinCosmetics(env.ctx, [{ ...crown!, template: 'nope' }]))).toMatch(/unknown template/)
    expect(code(() => seedBuiltinCosmetics(env.ctx, [{ ...crown!, id: 'winken' }]))).toMatch(/emote id/)
  })

  it('retires built-ins missing from a new catalog; wearers keep them', async () => {
    const env = makeEnv()
    seedCosmeticFixtures(env)
    const u = await login(env, 'Steve')
    equipCosmetics(env.ctx, u.user.uuid, { hat: 'free_crown' })
    seedBuiltinCosmetics(env.ctx, fixtureCosmetics(env).filter((c) => c.id !== 'free_crown'))
    expect(getCosmetic(env.ctx, 'free_crown')!.retired).toBe(1)
    expect(equippedView(env.ctx, u.user.uuid).hat?.id).toBe('free_crown')
    const other = await login(env, 'Alex')
    expect(code(() => equipCosmetics(env.ctx, other.user.uuid, { hat: 'free_crown' }))).toBe('cosmetic_locked')
    expect(cosmeticCatalog(env.ctx, other.user.uuid).some((c) => c.id === 'free_crown')).toBe(false)
  })
})

describe('equip and unlock rules', () => {
  async function setup() {
    const env = makeEnv()
    seedCosmeticFixtures(env)
    const u = await login(env, 'Steve')
    const admin = await login(env, 'Theredstonee', ADMIN)
    return { env, u: u.user.uuid, admin: admin.user.uuid }
  }

  it('free items for everyone, code items need a grant, admins own every built-in', async () => {
    const { env, u, admin } = await setup()
    expect(equipCosmetics(env.ctx, u, { hat: 'free_crown', aura: 'free_aura' })).toMatchObject({
      hat: { id: 'free_crown', slot: 'hat', template: 'crown' },
      aura: { id: 'free_aura', template: 'orbit' },
      wings: null,
      back: null,
    })
    expect(code(() => equipCosmetics(env.ctx, u, { wings: 'code_wings' }))).toBe('cosmetic_locked')
    expect(code(() => equipCosmetics(env.ctx, u, { hat: 'team_crown' }))).toBe('cosmetic_locked')
    expect(equipCosmetics(env.ctx, admin, { hat: 'team_crown', wings: 'code_wings' }).wings?.texture).toMatchObject({
      animated: true, frames: 4, frameTimeMs: 120, scale: 2,
    })
    grantCosmetic(env.ctx, u, 'code_wings', 'admin')
    expect(equipCosmetics(env.ctx, u, { wings: 'code_wings' }).wings?.emissive).toBe(true)
    // Teilweise Änderung lässt andere Plätze in Ruhe; null legt ab.
    expect(equipCosmetics(env.ctx, u, { hat: null })).toMatchObject({ hat: null, wings: { id: 'code_wings' }, aura: { id: 'free_aura' } })
    revokeCosmetic(env.ctx, u, 'code_wings')
    expect(equippedView(env.ctx, u).wings).toBeNull()
  })

  it('checks the slot, rejects unknown ids and emotes, and is all-or-nothing', async () => {
    const { env, u } = await setup()
    expect(code(() => equipCosmetics(env.ctx, u, { wings: 'free_crown' }))).toBe('wrong_slot')
    expect(code(() => equipCosmetics(env.ctx, u, { hat: 'winken' }))).toBe('wrong_slot')
    expect(code(() => equipCosmetics(env.ctx, u, { hat: 'nope' }))).toBe('cosmetic_not_found')
    expect(code(() => equipCosmetics(env.ctx, u, { hat: 'free_crown', wings: 'code_wings' }))).toBe('cosmetic_locked')
    expect(equippedView(env.ctx, u).hat).toBeNull() // nichts halb geschrieben
  })

  it('catalog lists built-ins, emotes and own uploads with owned/equipped', async () => {
    const { env, u } = await setup()
    equipCosmetics(env.ctx, u, { hat: 'free_crown' })
    const cat = cosmeticCatalog(env.ctx, u)
    expect(cat.find((c) => c.id === 'free_crown')).toMatchObject({ owned: true, equipped: true, kind: 'builtin' })
    expect(cat.find((c) => c.id === 'code_wings')).toMatchObject({ owned: false, equipped: false })
    const emotes = cat.filter((c) => c.slot === 'emote')
    expect(emotes).toHaveLength(EMOTES.length)
    expect(emotes.find((e) => e.id === 'winken')).toMatchObject({ owned: true, texture: null, template: null, emote: { durationMs: 2000, loop: false } })
    expect(emotes.find((e) => e.id === 'tanzen')).toMatchObject({ owned: false, unlock: 'code', emote: { loop: true } })
    expect(ownedEmotes(env.ctx, u)).toEqual(EMOTES.filter((e) => e.unlock === 'free').map((e) => e.id))
  })
})

describe('uploads', () => {
  async function setup(limits = {}) {
    const env = makeEnv({ limits })
    const owner = await login(env, 'Owner')
    const other = await login(env, 'Other')
    return { env, owner: owner.user.uuid, other: other.user.uuid }
  }

  it('accepts static textures in scale 1 and 2 and animated strips', async () => {
    const { env, owner } = await setup({ maxPendingCosmeticUploadsPerUser: 5 })
    const a = uploadCosmetic(env.ctx, owner, templatePng(env, 'crown', 1), { template: 'crown', name: 'Meine Krone' })
    expect(a).toMatchObject({ kind: 'upload', unlock: 'owner', status: 'pending', slot: 'hat', template: 'crown', emissive: false,
      texture: { width: 64, height: 16, scale: 1, frames: 1, animated: false, frameTimeMs: null } })
    expect(a.id).toMatch(/^c[0-9a-f]{20}$/)
    const b = uploadCosmetic(env.ctx, owner, templatePng(env, 'wings', 2, 1, [1, 2, 3, 255]), { template: 'wings' })
    expect(b.texture).toMatchObject({ width: 128, height: 64, scale: 2 })
    expect(b.name).toBe('Flügel (eigene)')
    const c = uploadCosmetic(env.ctx, owner, templatePng(env, 'halo', 2, 16), { template: 'halo', frameTimeMs: 80 })
    expect(c.texture).toMatchObject({ width: 64, height: 16, frames: 16, animated: true, frameTimeMs: 80 })
    // Statisch: frameTimeMs wird ignoriert
    const d = uploadCosmetic(env.ctx, owner, templatePng(env, 'orbit', 1), { template: 'orbit', frameTimeMs: 100 })
    expect(d.texture!.frameTimeMs).toBeNull()
  })

  it('rejects wrong sizes, missing frame time, unknown templates and empty textures', async () => {
    const { env, owner } = await setup()
    const up = (png: Buffer, template = 'crown', frameTimeMs?: number) =>
      code(() => uploadCosmetic(env.ctx, owner, png, { template, frameTimeMs }))
    expect(up(solidPng(22, 17))).toBe('invalid_dimensions') // Umhang-Format, keine Krone
    expect(up(solidPng(64, 32))).toBe('frame_time_required') // = 2 Frames Krone
    expect(up(solidPng(65, 16))).toBe('invalid_dimensions')
    expect(up(solidPng(64, 24))).toBe('invalid_dimensions') // kein ganzzahliges Frame
    expect(up(solidPng(192, 48))).toBe('invalid_dimensions') // scale 3 nur für mitgelieferte
    expect(up(templatePng(env, 'crown', 1, 17), 'crown', 100)).toBe('invalid_dimensions') // > 16 Frames
    expect(up(templatePng(env, 'crown', 1, 2))).toBe('frame_time_required')
    expect(up(templatePng(env, 'crown'), 'nope')).toBe('unknown_template')
    expect(up(solidPng(64, 16, [0, 0, 0, 0]))).toBe('empty_cosmetic')
    // Nur Pixel außerhalb der benutzten Bereiche → leer
    const outside = new PNG({ width: 64, height: 16 })
    outside.data.set([255, 0, 0, 255], (15 * 64 + 63) * 4)
    expect(up(PNG.sync.write(outside))).toBe('empty_cosmetic')
    expect(up(Buffer.from('not a png at all'))).toBe('invalid_png')
  })

  it('re-encodes: pixels outside the template net and invisible pixels are zeroed', async () => {
    const { env, owner } = await setup()
    const img = new PNG({ width: 64, height: 16 })
    img.data.fill(255) // alles deckend weiß, auch der ungenutzte Bereich rechts (x ≥ 40)
    img.data.set([9, 9, 9, 0], (2 * 64 + 12) * 4) // unsichtbarer Pixel mit Farbe
    const c = uploadCosmetic(env.ctx, owner, PNG.sync.write(img), { template: 'crown' })
    const out = PNG.sync.read(readCosmeticTexture(env.ctx, c.id, { uuid: owner, admin: false }).png)
    const px = (x: number, y: number) => [...out.data.subarray((y * 64 + x) * 4, (y * 64 + x) * 4 + 4)]
    expect(px(50, 5)).toEqual([0, 0, 0, 0]) // außerhalb des Netzes
    expect(px(0, 0)).toEqual([0, 0, 0, 0]) // leeres Eck des Netzes
    expect(px(12, 2)).toEqual([0, 0, 0, 0]) // unsichtbar → genullt
    expect(px(12, 3)).toEqual([255, 255, 255, 255]) // Oberseite der Krone bleibt
  })

  it('model textures get binary alpha, particle textures keep soft alpha', async () => {
    const { env, owner } = await setup()
    const soft = (w: number, h: number) => {
      const img = new PNG({ width: w, height: h })
      for (let p = 0; p < w * h; p++) img.data.set([200, 100, 50, p % 2 === 0 ? 100 : 200], p * 4)
      return PNG.sync.write(img)
    }
    const read = (id: string, w: number, x: number, y: number) => {
      const d = PNG.sync.read(readCosmeticTexture(env.ctx, id, { uuid: owner, admin: false }).png).data
      return [...d.subarray((y * w + x) * 4, (y * w + x) * 4 + 4)]
    }
    const model = uploadCosmetic(env.ctx, owner, soft(64, 16), { template: 'crown' })
    expect(read(model.id, 64, 12, 3)).toEqual([0, 0, 0, 0]) // Alpha 100 → weg
    expect(read(model.id, 64, 13, 3)).toEqual([200, 100, 50, 255]) // Alpha 200 → deckend
    const particles = uploadCosmetic(env.ctx, owner, soft(16, 8), { template: 'orbit' })
    expect(read(particles.id, 16, 0, 0)).toEqual([200, 100, 50, 100])
  })

  it('limits pending and total uploads, rejects duplicates', async () => {
    const { env, owner } = await setup({ maxPendingCosmeticUploadsPerUser: 2, maxCosmeticUploadsPerUser: 3 })
    const png = (n: number) => templatePng(env, 'crown', 1, 1, [n, n, n, 255])
    const a = uploadCosmetic(env.ctx, owner, png(1), { template: 'crown' })
    expect(code(() => uploadCosmetic(env.ctx, owner, png(1), { template: 'crown' }))).toBe('duplicate_cosmetic')
    uploadCosmetic(env.ctx, owner, png(2), { template: 'crown' })
    expect(code(() => uploadCosmetic(env.ctx, owner, png(3), { template: 'crown' }))).toBe('too_many_pending')
    approveCosmetic(env.ctx, ADMIN, a.id)
    const c = uploadCosmetic(env.ctx, owner, png(3), { template: 'crown' })
    rejectCosmetic(env.ctx, ADMIN, c.id, undefined) // abgelehnte zählen nicht
    uploadCosmetic(env.ctx, owner, png(4), { template: 'crown' })
    for (const p of listCosmeticsForReview(env.ctx, 'pending')) approveCosmetic(env.ctx, ADMIN, p.id)
    expect(code(() => uploadCosmetic(env.ctx, owner, png(5), { template: 'crown' }))).toBe('upload_limit')
    deleteOwnCosmetic(env.ctx, owner, a.id)
    expect(existsSync(join(env.ctx.cosmeticDir, `${a.id}.png`))).toBe(false)
  })

  it('pending uploads: the owner sees and wears them, others see nothing until approved', async () => {
    const { env, owner, other } = await setup()
    const c = uploadCosmetic(env.ctx, owner, templatePng(env, 'crown'), { template: 'crown' })
    expect(code(() => readCosmeticTexture(env.ctx, c.id, null))).toBe('cosmetic_not_found')
    expect(code(() => readCosmeticTexture(env.ctx, c.id, { uuid: other, admin: false }))).toBe('cosmetic_not_found')
    expect(readCosmeticTexture(env.ctx, c.id, { uuid: owner, admin: false }).public).toBe(false)
    expect(readCosmeticTexture(env.ctx, c.id, { uuid: ADMIN, admin: true }).public).toBe(false)
    expect(code(() => equipCosmetics(env.ctx, other, { hat: c.id }))).toBe('cosmetic_not_found')
    equipCosmetics(env.ctx, owner, { hat: c.id })

    expect(lookupPlayers(env.ctx, owner, [owner]).players[0]!.cosmetics.hat).toMatchObject({ id: c.id, template: 'crown', scale: 1 })
    expect(lookupPlayers(env.ctx, other, [owner]).players[0]!.cosmetics.hat).toBeNull()
    expect(code(() => reportCosmetic(env.ctx, other, c.id, 'inappropriate', undefined))).toBe('cosmetic_not_found')

    approveCosmetic(env.ctx, ADMIN, c.id)
    expect(readCosmeticTexture(env.ctx, c.id, null).public).toBe(true)
    expect(lookupPlayers(env.ctx, other, [owner]).players[0]!.cosmetics.hat).toMatchObject({
      id: c.id, template: 'crown', url: expect.stringMatching(/^https:\/\/api\.example\.test\/v1\/cosmetics\/c[0-9a-f]{20}\.png\?v=[0-9a-f]{12}$/),
      scale: 1, animated: false, frames: 1, frameTimeMs: null, emissive: false,
    })

    // Melden → Ablehnen: abgelegt, im Katalog mit Grund, nicht mehr anlegbar
    expect(code(() => reportCosmetic(env.ctx, owner, c.id, 'other', undefined))).toBe('cosmetic_not_found')
    reportCosmetic(env.ctx, other, c.id, 'copyright', 'Kopie')
    expect(listCosmeticsForReview(env.ctx, 'reported')[0]!.reports).toEqual({ count: 1, reasons: { copyright: 1 } })
    rejectCosmetic(env.ctx, ADMIN, c.id, 'Urheberrecht')
    expect(equippedView(env.ctx, owner).hat).toBeNull()
    expect(code(() => equipCosmetics(env.ctx, owner, { hat: c.id }))).toBe('cosmetic_locked')
    expect(cosmeticCatalog(env.ctx, owner).find((x) => x.id === c.id)).toMatchObject({ status: 'rejected', rejectReason: 'Urheberrecht', owned: false })
    expect(code(() => deleteCosmeticAdmin(env.ctx, ADMIN, 'winken'))).toBe('builtin_cosmetic')
    deleteCosmeticAdmin(env.ctx, ADMIN, c.id)
    expect(getCosmetic(env.ctx, c.id)).toBeUndefined()
  })
})

describe('lookup with cosmetics', () => {
  it('shows only approved cosmetics to others, respects showCosmeticsToOthers, blocks and bans', async () => {
    const env = makeEnv()
    seedCosmeticFixtures(env)
    const viewer = (await login(env, 'Viewer')).user.uuid
    const a = (await login(env, 'Alpha')).user.uuid
    const b = (await login(env, 'Beta')).user.uuid
    const c = (await login(env, 'Gamma')).user.uuid
    for (const u of [a, b, c]) {
      updateSettings(env.ctx, u, { showBadge: false })
      equipCosmetics(env.ctx, u, { hat: 'free_crown', aura: 'free_aura' })
    }
    updateSettings(env.ctx, b, { showCosmeticsToOthers: false })
    block(env.ctx, c, { uuid: viewer })
    const r = lookupPlayers(env.ctx, viewer, [a, b, c])
    // Beta zeigt nichts (kein Abzeichen, Kosmetik verborgen), Gamma hat den Fragenden blockiert.
    expect(r.players.map((p) => p.uuid)).toEqual([a])
    expect(r.players[0]!.cosmetics).toMatchObject({
      hat: { id: 'free_crown', template: 'crown', scale: 2 },
      aura: { id: 'free_aura', template: 'orbit' },
      wings: null,
      back: null,
    })
    expect(r.players[0]!.badge).toBe(false)
    // Selbst sieht man die eigene Kosmetik auch mit showCosmeticsToOthers=false.
    expect(lookupPlayers(env.ctx, b, [b]).players[0]!.cosmetics.hat?.id).toBe('free_crown')
    banUser(env.ctx, 'api-key', a, undefined)
    expect(lookupPlayers(env.ctx, viewer, [a]).players).toEqual([])
    expect(getUser(env.ctx, b)!.show_cosmetics).toBe(0)
  })
})

describe('codes for cosmetics and emotes', () => {
  it('unlocks cosmetics and emotes; free items need no code', async () => {
    const env = makeEnv()
    seedCosmeticFixtures(env)
    const u = (await login(env, 'Steve')).user.uuid
    const wings = normalizeRedeemCode(createCodes(env.ctx, ADMIN, { cosmeticId: 'code_wings', maxUses: 1, count: 1 }).codes[0]!.code)!
    const r = redeemCode(env.ctx, u, wings)
    expect(r).toMatchObject({ kind: 'cosmetic', cape: null, alreadyOwned: false, cosmetic: { id: 'code_wings', slot: 'wings' } })
    expect(equipCosmetics(env.ctx, u, { wings: 'code_wings' }).wings?.id).toBe('code_wings')
    expect(redeemCode(env.ctx, u, wings).alreadyOwned).toBe(true)

    const dance = createCodes(env.ctx, ADMIN, { cosmeticId: 'tanzen', maxUses: 5, count: 1 }).codes[0]!
    expect(dance).toMatchObject({ capeId: null, cosmeticId: 'tanzen' })
    expect(ownedEmotes(env.ctx, u)).not.toContain('tanzen')
    redeemCode(env.ctx, u, normalizeRedeemCode(dance.code)!)
    expect(ownedEmotes(env.ctx, u)).toContain('tanzen')

    expect(code(() => createCodes(env.ctx, ADMIN, { cosmeticId: 'free_crown', maxUses: 1, count: 1 }))).toBe('cosmetic_is_free')
    expect(code(() => createCodes(env.ctx, ADMIN, { cosmeticId: 'winken', maxUses: 1, count: 1 }))).toBe('cosmetic_is_free')
    expect(code(() => createCodes(env.ctx, ADMIN, { cosmeticId: 'nope', maxUses: 1, count: 1 }))).toBe('cosmetic_not_found')
    const info = userInfo(env.ctx, u)
    expect(info.grantedCosmetics.map((g) => g.cosmeticId).sort()).toEqual(['code_wings', 'tanzen'])
    expect(info.equippedCosmetics).toEqual({ wings: 'code_wings' })
  })
})

describe('account deletion and stats', () => {
  it('removes cosmetic uploads, files, grants and equipment', async () => {
    const env: TestEnv = makeEnv()
    seedCosmeticFixtures(env)
    const u = (await login(env, 'Steve')).user.uuid
    const c = uploadCosmetic(env.ctx, u, templatePng(env, 'crown'), { template: 'crown' })
    grantCosmetic(env.ctx, u, 'code_wings', 'admin')
    equipCosmetics(env.ctx, u, { hat: c.id, wings: 'code_wings' })
    expect(stats(env.ctx).cosmetics).toMatchObject({ builtin: 4, emotes: EMOTES.length, pending: 1, equippedUsers: 1 })
    deleteUser(env.ctx, u)
    expect(getCosmetic(env.ctx, c.id)).toBeUndefined()
    expect(existsSync(join(env.ctx.cosmeticDir, `${c.id}.png`))).toBe(false)
    expect(stats(env.ctx).cosmetics).toMatchObject({ pending: 0, equippedUsers: 0 })
  })
})
