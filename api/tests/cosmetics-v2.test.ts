import { readFileSync } from 'node:fs'
import { createServer, type Server } from 'node:http'
import { join } from 'node:path'
import { createApp, createRouter, send, setResponseStatus, toNodeListener } from 'h3'
import { afterEach, describe, expect, it } from 'vitest'
import { validateModel } from '../app/utils/cosmetic-v2/format'
import { loadBuiltinCosmetics } from '../server/lib/builtin'
import { setContext } from '../server/lib/context'
import {
  cosmeticCatalog,
  equipCosmetics,
  getCosmetic,
  grantCosmetic,
  readCosmeticTexture,
  readCosmeticV2File,
  seedBuiltinCosmetics,
  type AnyBuiltinCosmetic,
} from '../server/lib/cosmetics'
import { buildV2Cosmetic, v2Hash } from '../server/lib/cosmetics-v2'
import { isApiError } from '../server/lib/errors'
import { lookupPlayers } from '../server/lib/lookup'
import { publicHats } from '../server/lib/site'
import { updateSettings } from '../server/lib/users'
import cardNightRoute from '../server/routes/v1/cosmetics/[id]/card-night.png.get'
import cardRoute from '../server/routes/v1/cosmetics/[id]/card.png.get'
import glowRoute from '../server/routes/v1/cosmetics/[id]/glow.png.get'
import modelRoute from '../server/routes/v1/cosmetics/[id]/model.json.get'
import { ADMIN, fixtureCosmetics, login, makeEnv, solidPng, templatePng, type TestEnv } from './helpers'

const ASSETS = join(__dirname, '..', 'assets', 'cosmetics')
/** Öffentliche Hüte ohne Event (Event aus → nicht in der Website-Liste). */
const V2_IDS = ['trs_cap', 'lamp_helmet', 'top_hat']
/** Alle öffentlichen Format-2-Teile, in Katalogreihenfolge. */
const V2_ALL = [...V2_IDS, 'witch_hat', 'pumpkin_head', 'bat_buddy']
const readCatalog = async () => JSON.parse(readFileSync(join(ASSETS, 'catalog.json'), 'utf8'))
const readAsset = async (name: string) => {
  try {
    return readFileSync(join(ASSETS, name))
  } catch {
    return null
  }
}

function code(fn: () => unknown): string {
  try {
    fn()
  } catch (e) {
    return (e as { code?: string }).code ?? (e as Error).message
  }
  return 'ok'
}

async function seeded(env: TestEnv) {
  const list = await loadBuiltinCosmetics(readCatalog, readAsset)
  seedBuiltinCosmetics(env.ctx, list)
  return list
}

/** Kleines gültiges Format-2-Teil, ohne die proprietären Studio-Dateien. */
function syntheticV2(id: string, unlock: 'free' | 'code' | 'admin'): AnyBuiltinCosmetic {
  const model = {
    format: 2,
    id,
    name: id,
    slot: 'hat',
    attach: 'head',
    texture: { file: `${id}.png`, width: 16, height: 16, scale: 1 },
    bones: [{ id: 'root', pivot: [0, 8, 0] }],
    cubes: [{ bone: 'root', from: [-1, 8, -1], to: [1, 9, 1], faces: { south: { uv: [0, 0, 2, 1] } } }],
  }
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
    files: { modelJson: Buffer.from(JSON.stringify(model)), texture: solidPng(16, 16), glow: null, card, cardNight: card },
  })
}

describe('format v2: bundled models', () => {
  it('every public format-2 item is valid against its images and matches the catalog', async () => {
    const list = await loadBuiltinCosmetics(readCatalog, readAsset)
    const v2 = list.filter((c) => c.format === 2)
    expect(v2.map((c) => c.id)).toEqual(V2_ALL)
    for (const c of v2) {
      const json = JSON.parse(c.files.modelJson.toString('utf8'))
      const png = (b: Buffer) => ({ width: b.readUInt32BE(16), height: b.readUInt32BE(20) })
      const r = validateModel(json, { texture: png(c.files.texture), glow: c.files.glow ? png(c.files.glow) : null })
      expect(r.errors).toEqual([])
      expect(json.id).toBe(c.id)
      // Karten höchstens 512 px
      for (const card of [c.files.card, c.files.cardNight]) expect(Math.max(png(card).width, png(card).height)).toBeLessThanOrEqual(512)
      expect(c.hash).toBe(v2Hash(c.files))
      // Mitgelieferte Dateien = unveränderte Studio-Exporte
      expect(c.files.modelJson.equals(readFileSync(join(ASSETS, 'v2', `${c.id}.json`)))).toBe(true)
    }
    expect(list.find((c) => c.id === 'rubber_duck')).toBeUndefined()
    expect(list.find((c) => c.id === 'dragon_wings')).toMatchObject({ template: 'wings' })
    expect(list.find((c) => c.id === 'dragon_wings')!.format).toBeUndefined()
  })

  it('the v1 textures of the replaced items are gone', () => {
    for (const id of V2_ALL) expect(readFileSync.bind(null, join(ASSETS, `${id}.png`))).toThrow()
  })

  it('an invalid model stops the start (loader throws)', async () => {
    const cat = [{ id: 'trs_cap', name: 'TRS-Cap', format: 2, unlock: 'free', frames: 1, glowFrames: 12, glowFrameTimeMs: 150 }]
    const broken = (patch: (m: Record<string, unknown>) => void) => async (name: string) => {
      if (name === 'v2/trs_cap.json') {
        const m = JSON.parse(readFileSync(join(ASSETS, 'v2', 'trs_cap.json'), 'utf8'))
        patch(m)
        return Buffer.from(JSON.stringify(m))
      }
      return readAsset(name)
    }
    await expect(loadBuiltinCosmetics(async () => cat, readAsset)).resolves.toHaveLength(1)
    await expect(loadBuiltinCosmetics(async () => cat, broken((m) => { m.id = 'other_cap' }))).rejects.toThrow(/does not match/)
    await expect(loadBuiltinCosmetics(async () => cat, broken((m) => { (m.cubes as { bone: string }[])[0]!.bone = 'nope' }))).rejects.toThrow(/unbekannter Knochen/)
    await expect(loadBuiltinCosmetics(async () => cat, broken((m) => { (m.texture as { width: number }).width = 40 }))).rejects.toThrow(/Bild ist/)
    await expect(loadBuiltinCosmetics(async () => cat, broken((m) => { (m.glow as { frames: number }).frames = 6 }))).rejects.toThrow(/glowFrames|Bild ist/)
    await expect(loadBuiltinCosmetics(async () => [{ ...cat[0], glowFrames: 4 }], readAsset)).rejects.toThrow(/glowFrames/)
    await expect(loadBuiltinCosmetics(async () => cat, async (n) => (n.endsWith('-card.png') ? null : readAsset(n)))).rejects.toThrow(/file missing/)
    // Unbekannte Felder im v2-Eintrag (z. B. template) sind ein Fehler – kein halber v1/v2-Mischmasch.
    await expect(loadBuiltinCosmetics(async () => [{ ...cat[0], template: 'cap' }], readAsset)).rejects.toThrow()
  })
})

describe('format v2: catalog, equip, lookup', () => {
  it('catalog views carry the v2 fields, no template, texture = v2 texture', async () => {
    const env = makeEnv()
    await seeded(env)
    const u = (await login(env, 'Steve')).user.uuid
    const cap = cosmeticCatalog(env.ctx, u).find((c) => c.id === 'trs_cap')!
    const a = env.ctx.cosmeticsV2.get('trs_cap')!
    const v = a.hash.slice(0, 12)
    expect(cap).toMatchObject({
      slot: 'hat', format: 2, template: null, emissive: false, owned: true, unlock: 'free',
      model: `https://api.example.test/v1/cosmetics/trs_cap/model.json?v=${v}`,
      glow: `https://api.example.test/v1/cosmetics/trs_cap/glow.png?v=${v}`,
      card: `https://api.example.test/v1/cosmetics/trs_cap/card.png?v=${a.cardHash.slice(0, 12)}`,
      cardNight: `https://api.example.test/v1/cosmetics/trs_cap/card-night.png?v=${a.cardNightHash.slice(0, 12)}`,
      frames: 1, frameTimeMs: null, glowFrames: 12, glowFrameTimeMs: 150, hash: v,
      texture: { url: `https://api.example.test/v1/cosmetics/trs_cap.png?v=${v}`, width: 256, height: 256, scale: 8, frames: 1, animated: false },
    })
    // v1-Teile ohne v2-Felder
    const wings = cosmeticCatalog(env.ctx, u).find((c) => c.id === 'dragon_wings')!
    expect(wings.template).toBe('wings')
    expect('format' in wings).toBe(false)
    // Textur-Datei = v2-Textur, ETag = v2-Hash
    const tex = readCosmeticTexture(env.ctx, 'trs_cap', null)
    expect(tex.png.equals(a.files.texture)).toBe(true)
    expect(tex.sha256).toBe(a.hash)
  })

  it('PUT hat accepts v2 ids with the usual unlock rules; lookup has v2 fields without template/emissive', async () => {
    const env = makeEnv()
    const list = await seeded(env)
    const u = (await login(env, 'Steve')).user.uuid
    const admin = (await login(env, 'Theredstonee', ADMIN)).user.uuid
    seedBuiltinCosmetics(env.ctx, [...list, syntheticV2('priv_code', 'code'), syntheticV2('priv_admin', 'admin')])
    expect(equipCosmetics(env.ctx, u, { hat: 'top_hat' }).hat).toMatchObject({ id: 'top_hat', format: 2, template: null })
    expect(code(() => equipCosmetics(env.ctx, u, { hat: 'priv_code' }))).toBe('cosmetic_locked')
    expect(code(() => equipCosmetics(env.ctx, u, { aura: 'trs_cap' }))).toBe('wrong_slot')
    expect(code(() => equipCosmetics(env.ctx, u, { hat: 'priv_admin' }))).toBe('cosmetic_locked')
    expect(equipCosmetics(env.ctx, admin, { hat: 'priv_admin' }).hat?.id).toBe('priv_admin')
    grantCosmetic(env.ctx, u, 'priv_code', 'code')
    expect(equipCosmetics(env.ctx, u, { hat: 'priv_code' }).hat?.id).toBe('priv_code')

    const other = (await login(env, 'Alex')).user.uuid
    updateSettings(env.ctx, u, { showCosmeticsToOthers: true })
    const hat = lookupPlayers(env.ctx, other, [u]).players[0]!.cosmetics.hat!
    const a = env.ctx.cosmeticsV2.get('priv_code')!
    expect(hat).toEqual({
      id: 'priv_code', format: 2,
      model: `https://api.example.test/v1/cosmetics/priv_code/model.json?v=${a.hash.slice(0, 12)}`,
      url: `https://api.example.test/v1/cosmetics/priv_code.png?v=${a.hash.slice(0, 12)}`,
      scale: 1, animated: false, frames: 1, frameTimeMs: null,
      glow: null, glowFrames: 0, glowFrameTimeMs: null, hash: a.hash.slice(0, 12),
    })
    // Ältere Mods: kein template → HatInfo.of(...) liefert null (nichts zeichnen, kein Absturz).
    expect(hat.template).toBeUndefined()
  })

  it('replaces the v1 items with the same id: owners keep them, the aura item moves to hat', async () => {
    const env = makeEnv()
    // Alter Stand: v1-Heiligenschein (Vorlage halo, Platz aura) + v1-Krone im Hut-Platz.
    // Die Modelle sind synthetisch – die echten Dateien liegen nicht im Repository.
    const old: AnyBuiltinCosmetic[] = [
      { id: 'halo', name: 'Heiligenschein', template: 'halo', unlock: 'code', sort: 0, scale: 2, frames: 1, frameTimeMs: null, emissive: true, png: templatePng(env, 'halo', 2) },
      { id: 'redstone_crown', name: 'Redstone-Krone', template: 'crown', unlock: 'code', sort: 1, scale: 2, frames: 1, frameTimeMs: null, emissive: true, png: templatePng(env, 'crown', 2) },
      ...fixtureCosmetics(env),
    ]
    seedBuiltinCosmetics(env.ctx, old)
    const a = (await login(env, 'A')).user.uuid
    const b = (await login(env, 'B')).user.uuid
    for (const u of [a, b]) {
      grantCosmetic(env.ctx, u, 'halo', 'code')
      grantCosmetic(env.ctx, u, 'redstone_crown', 'code')
    }
    equipCosmetics(env.ctx, a, { aura: 'halo' })
    equipCosmetics(env.ctx, b, { aura: 'halo', hat: 'redstone_crown' })
    expect(getCosmetic(env.ctx, 'halo')).toMatchObject({ slot: 'aura', format: 1 })

    seedBuiltinCosmetics(env.ctx, [syntheticV2('halo', 'code'), syntheticV2('redstone_crown', 'code')])
    expect(getCosmetic(env.ctx, 'halo')).toMatchObject({ slot: 'hat', format: 2, template: '@v2', retired: 0 })
    // a: Hut-Platz frei → Heiligenschein wandert nach hat; b: Hut belegt → abgelegt, Krone (jetzt v2) bleibt.
    expect(equipCosmetics(env.ctx, a, {})).toMatchObject({ hat: { id: 'halo', format: 2 }, aura: null })
    expect(equipCosmetics(env.ctx, b, {})).toMatchObject({ hat: { id: 'redstone_crown', format: 2 }, aura: null })
    // Besitz bleibt
    expect(cosmeticCatalog(env.ctx, b).find((c) => c.id === 'halo')).toMatchObject({ owned: true, slot: 'hat' })
  })

  it('without its files a v2 row is not renderable (catalog, lookup, routes)', async () => {
    const env = makeEnv()
    await seeded(env)
    const u = (await login(env, 'Steve')).user.uuid
    equipCosmetics(env.ctx, u, { hat: 'trs_cap' })
    env.ctx.cosmeticsV2.delete('trs_cap')
    expect(cosmeticCatalog(env.ctx, u).some((c) => c.id === 'trs_cap')).toBe(false)
    expect(lookupPlayers(env.ctx, u, [u]).players[0]?.cosmetics.hat ?? null).toBeNull()
    expect(code(() => readCosmeticV2File(env.ctx, 'trs_cap', 'model'))).toBe('cosmetic_not_found')
  })

  it('website list: v2 hats only, relative URLs, never hidden items', async () => {
    const env = makeEnv()
    await seeded(env)
    const hats = publicHats(env.ctx)
    expect(hats.map((h) => h.id)).toEqual(V2_IDS)
    const cap = hats.find((h) => h.id === 'trs_cap')!
    expect(cap).toMatchObject({ unlock: 'free', achievement: null, format: 2, glowFrames: 12 })
    for (const k of ['model', 'texture', 'glow', 'card', 'cardNight'] as const) expect(cap[k]).toMatch(/^\/v1\/cosmetics\//)
    expect(hats.some((h) => h.id === 'rubber_duck')).toBe(false)
    // Ein verstecktes v2-Teil taucht nicht auf.
    const list = await loadBuiltinCosmetics(readCatalog, readAsset)
    const secret = list.map((c) => (c.id === 'top_hat' ? { ...c, unlock: 'code' as const, hidden: true } : c))
    seedBuiltinCosmetics(env.ctx, secret)
    expect(publicHats(env.ctx).some((h) => h.id === 'top_hat')).toBe(false)
  })
})

describe('format v2: HTTP routes', () => {
  const open: { close: () => Promise<void> }[] = []
  afterEach(async () => {
    for (const s of open.splice(0)) await s.close()
    setContext(undefined)
  })

  async function serve(env: TestEnv) {
    const app = createApp({
      onError: async (error, event) => {
        const cause: unknown = (error as { cause?: unknown }).cause
        const api = isApiError(error) ? error : isApiError(cause) ? cause : null
        setResponseStatus(event, (api as { status?: number } | null)?.status ?? 500)
        await send(event, JSON.stringify({ error: { code: (api as { code?: string } | null)?.code ?? 'internal_error' } }), 'application/json')
      },
    })
    const router = createRouter()
    router.get('/v1/cosmetics/:id/model.json', modelRoute)
    router.get('/v1/cosmetics/:id/glow.png', glowRoute)
    router.get('/v1/cosmetics/:id/card.png', cardRoute)
    router.get('/v1/cosmetics/:id/card-night.png', cardNightRoute)
    app.use(router)
    const server: Server = createServer(toNodeListener(app))
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
    open.push({ close: () => new Promise<void>((r) => server.close(() => r())) })
    setContext(env.ctx)
    return `http://127.0.0.1:${(server.address() as { port: number }).port}`
  }

  it('serves model, glow and cards with ETag, immutable on matching ?v=, 304 and 404', async () => {
    const env = makeEnv()
    await seeded(env)
    const base = await serve(env)
    const a = env.ctx.cosmeticsV2.get('trs_cap')!
    const v = a.hash.slice(0, 12)

    const model = await fetch(`${base}/v1/cosmetics/trs_cap/model.json?v=${v}`)
    expect(model.status).toBe(200)
    expect(model.headers.get('content-type')).toContain('application/json')
    expect(model.headers.get('etag')).toBe(`"${a.hash}"`)
    expect(model.headers.get('cache-control')).toBe('public, max-age=31536000, immutable')
    expect(Buffer.from(await model.arrayBuffer()).equals(a.files.modelJson)).toBe(true)

    const stale = await fetch(`${base}/v1/cosmetics/trs_cap/model.json?v=000000000000`)
    expect(stale.headers.get('cache-control')).toBe('public, max-age=300')
    const again = await fetch(`${base}/v1/cosmetics/trs_cap/model.json`, { headers: { 'if-none-match': `"${a.hash}"` } })
    expect(again.status).toBe(304)

    const glow = await fetch(`${base}/v1/cosmetics/trs_cap/glow.png?v=${v}`)
    expect(glow.headers.get('content-type')).toBe('image/png')
    expect(Buffer.from(await glow.arrayBuffer()).equals(a.files.glow!)).toBe(true)

    const card = await fetch(`${base}/v1/cosmetics/trs_cap/card.png?v=${a.cardHash.slice(0, 12)}`)
    expect(card.headers.get('etag')).toBe(`"${a.cardHash}"`)
    expect(card.headers.get('cache-control')).toContain('immutable')
    const night = await fetch(`${base}/v1/cosmetics/trs_cap/card-night.png`)
    expect(Buffer.from(await night.arrayBuffer()).equals(a.files.cardNight)).toBe(true)

    // v1-Teil, unbekannt, ungültige ID → 404
    expect((await fetch(`${base}/v1/cosmetics/dragon_wings/model.json`)).status).toBe(404)
    expect((await fetch(`${base}/v1/cosmetics/nope/card.png`)).status).toBe(404)
    expect((await fetch(`${base}/v1/cosmetics/..%2Fx/model.json`)).status).toBe(404)
  })
})
