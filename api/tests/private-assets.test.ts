import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { createServer, type Server } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createApp, createRouter, send, setResponseStatus, toNodeListener } from 'h3'
import { afterEach, describe, expect, it } from 'vitest'
import { assertBuiltinCape, canUse, getCape, grantCape, seedBuiltins, type BuiltinCape } from '../server/lib/capes'
import { setContext } from '../server/lib/context'
import { canUseCosmetic, cosmeticCatalog, equipCosmetics, getCosmetic, grantCosmetic, readCosmeticTemplate, seedBuiltinCosmetics } from '../server/lib/cosmetics'
import { run, one } from '../server/lib/db'
import { isApiError } from '../server/lib/errors'
import { lookupPlayers } from '../server/lib/lookup'
import { MAX_UPLOAD_BYTES } from '../server/lib/png'
import {
  loadPrivateCapes,
  loadPrivateCosmetics,
  loadPrivateTemplates,
  mergeBuiltins,
  privateAssetsAvailable,
  resolveInside,
  type PrivatePiece,
} from '../server/lib/private-assets'
import { mergeTemplates } from '../server/lib/templates'
import { updateSettings } from '../server/lib/users'
import templateRoute from '../server/routes/v1/cosmetics/[id]/template.json.get'
import { login, makeEnv, seedCosmeticFixtures, solidPng, templatePng } from './helpers'

const CAPE_PUBLIC = ['redstone', 'lamp', 'deepslate', 'lapis', 'emerald', 'amethyst', 'phoenix', 'nether', 'ender', 'ozean', 'wald', 'frost', 'kirschbluete', 'drache', 'sonne', 'halloween']
const CAPE_PRIVATE: [string, number][] = [['trs', 6], ['team', 7], ['tester', 8], ['content-team', 18], ['veteran', 19], ['ideengeber', 20]]
const CAPE_ORDER = ['redstone', 'lamp', 'deepslate', 'lapis', 'emerald', 'amethyst', 'trs', 'team', 'tester', 'phoenix', 'nether', 'ender', 'ozean', 'wald', 'frost', 'kirschbluete', 'drache', 'sonne', 'content-team', 'veteran', 'ideengeber', 'halloween']

const COS_PUBLIC = ['trs_cap', 'lamp_helmet', 'top_hat', 'witch_hat', 'pumpkin_head', 'bat_buddy', 'dragon_wings', 'backpack', 'redstone_aura', 'footprints']
const COS_PRIVATE: [string, number][] = [['redstone_crown', 0], ['team_crown', 1], ['halo', 5], ['redstone_wings', 9], ['rubber_duck', 14]]
const COS_ORDER = ['redstone_crown', 'team_crown', 'trs_cap', 'lamp_helmet', 'top_hat', 'halo', 'witch_hat', 'pumpkin_head', 'bat_buddy', 'redstone_wings', 'dragon_wings', 'backpack', 'redstone_aura', 'footprints', 'rubber_duck']

const item = (id: string) => ({ id, sort: -1 })
const placed = (pairs: [string, number][]): PrivatePiece<{ id: string, sort: number }>[] =>
  pairs.map(([id, at]) => ({ item: item(id), at }))

describe('mergeBuiltins', () => {
  it('restores the original cape and cosmetic order', () => {
    const warn = () => { throw new Error('unexpected warning') }
    expect(mergeBuiltins(CAPE_PUBLIC.map(item), placed(CAPE_PRIVATE), [], warn).map((c) => c.id)).toEqual(CAPE_ORDER)
    expect(mergeBuiltins(COS_PUBLIC.map(item), placed(COS_PRIVATE), [], warn).map((c) => c.id)).toEqual(COS_ORDER)
  })

  it('keeps a hole when a private item is skipped, and leaves public items unchanged without private ones', () => {
    const warnings: string[] = []
    const warn = (m: string) => warnings.push(m)
    const missing = CAPE_PRIVATE.filter(([id]) => id !== 'trs')
    const ids = mergeBuiltins(CAPE_PUBLIC.map(item), placed(missing), [6], warn).map((c) => c.id)
    expect(ids).not.toContain('trs')
    expect(ids.indexOf('amethyst')).toBe(ids.indexOf('team') - 1)
    expect(ids.at(-1)).toBe('halloween')
    expect(warnings).toEqual([])
    const onlyPublic = mergeBuiltins(CAPE_PUBLIC.map(item), [], [], warn)
    expect(onlyPublic.map((c) => c.id)).toEqual(CAPE_PUBLIC)
    expect(onlyPublic.map((c) => c.sort)).toEqual(CAPE_PUBLIC.map((_, i) => i))
  })

  it('skips a private id that the public catalog already has', () => {
    const warnings: string[] = []
    const out = mergeBuiltins([item('redstone')], placed([['redstone', 3]]), [], (m) => warnings.push(m))
    expect(out.map((c) => c.id)).toEqual(['redstone'])
    expect(warnings).toEqual(['private item redstone skipped: id already in the public catalog'])
  })
})

describe('private asset files', () => {
  const dirs: string[] = []
  afterEach(() => {
    for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
  })
  const scratch = () => {
    const d = mkdtempSync(join(tmpdir(), 'trs-private-'))
    dirs.push(d)
    return d
  }

  it('warns once when the directory is missing or not a directory', () => {
    const root = scratch()
    const warnings: string[] = []
    expect(privateAssetsAvailable(join(root, 'missing'), (m) => warnings.push(m))).toBe(false)
    writeFileSync(join(root, 'file'), 'x')
    expect(privateAssetsAvailable(join(root, 'file'), (m) => warnings.push(m))).toBe(false)
    expect(warnings).toHaveLength(2)
    expect(warnings[0]).toMatch(/private assets dir missing/)
    expect(warnings[1]).toMatch(/not a directory/)
    expect(privateAssetsAvailable(root, () => { throw new Error('no warning') })).toBe(true)
  })

  it('rejects traversal and a symlink that leaves the root', () => {
    const root = scratch()
    mkdirSync(join(root, 'capes'))
    const outside = scratch()
    writeFileSync(join(outside, 'secret.png'), 'nope')
    expect(resolveInside(root, ['..', 'secret.png'])).toBeNull()
    expect(resolveInside(root, ['capes', '..', 'secret.png'])).toBeNull()
    expect(resolveInside(root, ['capes/secret.png'])).toBeNull()
    expect(resolveInside(root, ['capes', 'ok.png'])).toBeTruthy()
    let linked = false
    try {
      symlinkSync(join(outside, 'secret.png'), join(root, 'capes', 'evil.png'), 'file')
      linked = true
    } catch (err) {
      const code = (err as NodeJS.ErrnoException).code
      if (code !== 'EPERM' && code !== 'EACCES') throw err
    }
    if (linked) expect(resolveInside(root, ['capes', 'evil.png'])).toBeNull()
  })

  it('loads a synthetic cape and skips a missing or oversized file with one warning', () => {
    const root = scratch()
    mkdirSync(join(root, 'capes'))
    const png = solidPng(64, 32)
    writeFileSync(join(root, 'capes', 'priv_cape.png'), png)
    writeFileSync(join(root, 'capes', 'huge.png'), Buffer.alloc(MAX_UPLOAD_BYTES + 1))
    writeFileSync(join(root, 'capes', 'catalog.private.json'), JSON.stringify([
      { id: 'priv_cape', name: 'Priv', unlock: 'code', file: 'priv_cape.png', scale: 1, frames: 1, sort: 2 },
      { id: 'priv_gone', name: 'Gone', unlock: 'admin', file: 'gone.png', scale: 1, frames: 1, sort: 4 },
      { id: 'priv_huge', name: 'Huge', unlock: 'code', file: 'huge.png', scale: 1, frames: 1, sort: 5 },
      { id: 'not safe', name: 'X', unlock: 'code', file: '../x.png' },
    ]))
    const warnings: string[] = []
    const priv = loadPrivateCapes(root, (m) => warnings.push(m))
    expect(priv.loaded.map((p) => p.item.id)).toEqual(['priv_cape'])
    expect(priv.loaded[0]!.item.png.equals(png)).toBe(true)
    expect(assertBuiltinCape(priv.loaded[0]!.item)).toBeUndefined()
    expect(priv.reserved).toEqual([4, 5])
    expect(warnings).toEqual([
      'private cape priv_gone skipped: file missing (gone.png)',
      'private cape priv_huge skipped: file too large (huge.png)',
      'private cape not safe skipped: invalid catalog entry',
    ])
    const merged = mergeBuiltins(
      [{ id: 'redstone', sort: 0 }, { id: 'lamp', sort: 0 }, { id: 'phoenix', sort: 0 }],
      priv.loaded,
      priv.reserved,
      () => { throw new Error('merge warning') },
    )
    expect(merged.map((c) => c.id)).toEqual(['redstone', 'lamp', 'priv_cape', 'phoenix'])
  })

  it('loads a synthetic v1 and v2 cosmetic and skips one broken item', () => {
    const env = makeEnv()
    const root = join(env.dir, 'private')
    mkdirSync(join(root, 'cosmetics', 'v2'), { recursive: true })
    const wings = templatePng(env, 'wings', 1)
    writeFileSync(join(root, 'cosmetics', 'priv_wings.png'), wings)
    const model = {
      format: 2,
      id: 'priv_hat',
      name: 'Priv Hat',
      slot: 'hat',
      attach: 'head',
      texture: { file: 'priv_hat.png', width: 16, height: 16, scale: 1 },
      bones: [{ id: 'root', pivot: [0, 8, 0] }],
      cubes: [{ bone: 'root', from: [-1, 8, -1], to: [1, 9, 1], faces: { south: { uv: [0, 0, 2, 1] } } }],
    }
    writeFileSync(join(root, 'cosmetics', 'v2', 'priv_hat.json'), JSON.stringify(model))
    writeFileSync(join(root, 'cosmetics', 'v2', 'priv_hat.png'), solidPng(16, 16))
    writeFileSync(join(root, 'cosmetics', 'v2', 'priv_hat-card.png'), solidPng(8, 8))
    writeFileSync(join(root, 'cosmetics', 'v2', 'priv_hat-card-night.png'), solidPng(8, 8))
    writeFileSync(join(root, 'cosmetics', 'catalog.private.json'), JSON.stringify({
      cosmetics: [
        { id: 'priv_hat', name: 'Priv Hat', format: 2, unlock: 'code', frames: 1, glowFrames: 0, sort: 0 },
        { id: 'priv_wings', name: 'Priv Wings', template: 'wings', unlock: 'code', file: 'priv_wings.png', scale: 1, frames: 1, sort: 3 },
        { id: 'priv_broken', name: 'Broken', format: 2, unlock: 'admin', frames: 1, glowFrames: 0, sort: 1 },
      ],
    }))
    const warnings: string[] = []
    const priv = loadPrivateCosmetics(root, env.ctx, (m) => warnings.push(m))
    expect(priv.loaded.map((p) => [p.item.id, p.at])).toEqual([['priv_hat', 0], ['priv_wings', 3]])
    expect(warnings).toHaveLength(1)
    expect(warnings[0]).toMatch(/private cosmetic priv_broken skipped: file missing/)
    expect(warnings[0]).toMatch(/v2\/priv_broken\.json/)
    const hat = priv.loaded[0]!.item
    expect(hat.format).toBe(2)
    if (hat.format !== 2) return
    expect(hat.unlock).toBe('code')
    expect(hat.files.texture.readUInt32BE(16)).toBe(16)
    expect(hat.files.texture.readUInt32BE(20)).toBe(16)
  })

  it('warns once and loads nothing when the private catalog is missing', () => {
    const root = scratch()
    const warnings: string[] = []
    expect(loadPrivateCapes(root, (m) => warnings.push(m)).loaded).toEqual([])
    expect(warnings).toEqual(['private cape catalog missing – private capes skipped'])
  })
})

describe('ownership survives a missing private item', () => {
  const cape = (id: string, unlock: 'free' | 'code'): BuiltinCape => ({
    id, name: id, unlock, sort: 0, scale: 1, frames: 1, frameTimeMs: null, png: solidPng(64, 32),
  })

  it('retires the cape without deleting the grant, then gives it back', async () => {
    const env = makeEnv()
    const pub = cape('redstone', 'free')
    const priv = cape('priv_cape', 'code')
    seedBuiltins(env.ctx, [pub, priv])
    const u = (await login(env, 'Steve')).user.uuid
    expect(grantCape(env.ctx, u, 'priv_cape', 'code')).toBe(true)
    expect(canUse(env.ctx, u, getCape(env.ctx, 'priv_cape')!)).toBe(true)

    seedBuiltins(env.ctx, [pub])
    expect(getCape(env.ctx, 'priv_cape')!.retired).toBe(1)
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM user_capes WHERE uuid = ? AND cape_id = ?', u, 'priv_cape')).toBeDefined()
    expect(canUse(env.ctx, u, getCape(env.ctx, 'priv_cape')!)).toBe(false)

    seedBuiltins(env.ctx, [pub, priv])
    expect(getCape(env.ctx, 'priv_cape')!.retired).toBe(0)
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM user_capes WHERE uuid = ? AND cape_id = ?', u, 'priv_cape')).toBeDefined()
    expect(canUse(env.ctx, u, getCape(env.ctx, 'priv_cape')!)).toBe(true)
  })

  it('retires the cosmetic without deleting the grant, then gives it back', async () => {
    const env = makeEnv()
    const pub = { id: 'free_crown', name: 'Krone', template: 'crown', unlock: 'free' as const, sort: 0, scale: 1, frames: 1, frameTimeMs: null, emissive: false, png: templatePng(env, 'crown', 1) }
    const priv = { id: 'priv_duck', name: 'Ente', template: 'crown', unlock: 'code' as const, hidden: true, sort: 1, scale: 1, frames: 1, frameTimeMs: null, emissive: false, png: templatePng(env, 'crown', 1) }
    seedBuiltinCosmetics(env.ctx, [pub, priv])
    const u = (await login(env, 'Steve')).user.uuid
    expect(grantCosmetic(env.ctx, u, 'priv_duck', 'code')).toBe(true)
    expect(canUseCosmetic(env.ctx, u, getCosmetic(env.ctx, 'priv_duck')!)).toBe(true)

    seedBuiltinCosmetics(env.ctx, [pub])
    expect(getCosmetic(env.ctx, 'priv_duck')!.retired).toBe(1)
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM user_cosmetics WHERE uuid = ? AND cosmetic_id = ?', u, 'priv_duck')).toBeDefined()
    expect(canUseCosmetic(env.ctx, u, getCosmetic(env.ctx, 'priv_duck')!)).toBe(false)

    seedBuiltinCosmetics(env.ctx, [pub, priv])
    expect(getCosmetic(env.ctx, 'priv_duck')!.retired).toBe(0)
    expect(canUseCosmetic(env.ctx, u, getCosmetic(env.ctx, 'priv_duck')!)).toBe(true)
  })
})

const privHat = {
  id: 'priv_hat',
  name: 'Priv',
  kind: 'model',
  slot: 'hat',
  textureWidth: 16,
  textureHeight: 8,
  cubes: [{ from: [-2, 8, -2], to: [2, 10, 2], uv: [0, 0], attach: 'head' }],
}

describe('private templates', () => {
  const dirs: string[] = []
  const open: { close: () => Promise<void> }[] = []
  afterEach(async () => {
    for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
    for (const s of open.splice(0)) await s.close()
    setContext(undefined)
  })
  const scratch = () => {
    const d = mkdtempSync(join(tmpdir(), 'trs-private-'))
    dirs.push(d)
    return d
  }

  it('warns once and loads nothing when the private template file is missing or invalid', () => {
    const root = scratch()
    const warnings: string[] = []
    expect(loadPrivateTemplates(root, (m) => warnings.push(m))).toEqual([])
    mkdirSync(join(root, 'cosmetics'))
    writeFileSync(join(root, 'cosmetics', 'templates.private.json'), '{')
    expect(loadPrivateTemplates(root, (m) => warnings.push(m))).toEqual([])
    writeFileSync(join(root, 'cosmetics', 'templates.private.json'), JSON.stringify({ version: 2, templates: [privHat] }))
    expect(loadPrivateTemplates(root, (m) => warnings.push(m))).toEqual([])
    writeFileSync(join(root, 'cosmetics', 'templates.private.json'), Buffer.alloc(MAX_UPLOAD_BYTES + 1))
    expect(loadPrivateTemplates(root, (m) => warnings.push(m))).toEqual([])
    expect(warnings).toEqual([
      'private template file missing – private templates skipped',
      'private template file invalid – private templates skipped',
      'private template file invalid – private templates skipped',
      'private templates file too large (templates.private.json) – private templates skipped',
    ])
  })

  it('merges a valid private template and skips a broken entry and a public id', () => {
    const root = scratch()
    mkdirSync(join(root, 'cosmetics'))
    writeFileSync(join(root, 'cosmetics', 'templates.private.json'), JSON.stringify({
      version: 1,
      templates: [
        privHat,
        { ...privHat, id: 'priv_hat' },
        { ...privHat, id: 'crown' },
        { ...privHat, id: 'priv_wide', textureWidth: 8 },
        { id: 'nope', name: 'X' },
      ],
    }))
    const warnings: string[] = []
    const loaded = loadPrivateTemplates(root, (m) => warnings.push(m))
    expect(loaded.map((t) => t.id)).toEqual(['priv_hat', 'crown'])
    expect(warnings[0]).toBe('private template priv_hat skipped: duplicate id')
    expect(warnings.some((w) => w.startsWith('private template priv_wide skipped:') && w.includes('exceeds'))).toBe(true)
    expect(warnings.some((w) => w.startsWith('private template nope skipped:'))).toBe(true)
    const env = makeEnv()
    const publicCrown = env.ctx.templates.get('crown')!
    const merged = mergeTemplates(env.ctx.templates, loaded, (m) => warnings.push(m))
    expect(merged.get('priv_hat')).toMatchObject({ slot: 'hat', textureWidth: 16 })
    expect(merged.get('crown')).toBe(publicCrown)
    expect(merged.list[0]!.id).toBe(env.ctx.templates.list[0]!.id)
    expect(merged.list.at(-1)!.id).toBe('priv_hat')
    expect(merged.get('duck')).toBeUndefined()
    expect(warnings.at(-1)).toBe('private template crown skipped: id already public')
    writeFileSync(join(root, 'cosmetics', 'templates.private.json'), JSON.stringify([privHat]))
    expect(loadPrivateTemplates(root, () => { throw new Error('unexpected warning') }).map((t) => t.id)).toEqual(['priv_hat'])
  })

  it('puts templateUrl on catalog and lookup, hashed over the template JSON', async () => {
    const env = makeEnv()
    seedCosmeticFixtures(env)
    const served = env.ctx.templates.jsonOf('crown')!
    expect(served.sha256).toHaveLength(64)
    expect(served.json).toBe(JSON.stringify(env.ctx.templates.get('crown')))
    const u = (await login(env, 'Steve')).user.uuid
    const cat = cosmeticCatalog(env.ctx, u)
    const crown = cat.find((c) => c.id === 'free_crown')!
    const v = served.sha256.slice(0, 12)
    expect(crown.template).toBe('crown')
    expect(crown.templateUrl).toBe(`/v1/cosmetics/free_crown/template.json?v=${v}`)
    expect(cat.find((c) => c.id === 'winken')!.templateUrl).toBeUndefined()
    equipCosmetics(env.ctx, u, { hat: 'free_crown' })
    updateSettings(env.ctx, u, { showCosmeticsToOthers: true })
    const other = (await login(env, 'Alex')).user.uuid
    expect(lookupPlayers(env.ctx, other, [u]).players[0]!.cosmetics.hat).toMatchObject({
      id: 'free_crown', template: 'crown', templateUrl: crown.templateUrl,
    })
    const file = readCosmeticTemplate(env.ctx, 'free_crown')
    expect(file.sha256).toBe(served.sha256)
    expect(file.body.toString('utf8')).toBe(served.json)
    expect(file.public).toBe(true)
    run(env.ctx.db,
      `INSERT INTO cosmetics (id, kind, slot, template, name, status, unlock, sha256, width, height, scale, frames, emissive, sort, retired, created_at, format)
       VALUES ('v2_hat', 'builtin', 'hat', '@v2', 'V2', 'approved', 'free', 'abc', 16, 16, 1, 1, 0, 0, 0, 0, 2)`)
    expect(thrown(() => readCosmeticTemplate(env.ctx, 'v2_hat'))).toMatchObject({ status: 404, code: 'cosmetic_not_found' })
    expect(thrown(() => readCosmeticTemplate(env.ctx, 'winken'))).toMatchObject({ code: 'cosmetic_not_found' })
    expect(thrown(() => readCosmeticTemplate(env.ctx, 'nope'))).toMatchObject({ code: 'cosmetic_not_found' })
  })

  it('serves template.json with ETag, immutable cache and 404', async () => {
    const env = makeEnv()
    seedCosmeticFixtures(env)
    run(env.ctx.db,
      `INSERT INTO cosmetics (id, kind, slot, template, name, status, unlock, sha256, width, height, scale, frames, emissive, sort, retired, created_at, format)
       VALUES ('v2_hat', 'builtin', 'hat', '@v2', 'V2', 'approved', 'free', 'abc', 16, 16, 1, 1, 0, 0, 0, 0, 2)`)
    const app = createApp({
      onError: async (error, event) => {
        const cause: unknown = (error as { cause?: unknown }).cause
        const api = isApiError(error) ? error : isApiError(cause) ? cause : null
        setResponseStatus(event, (api as { status?: number } | null)?.status ?? 500)
        await send(event, JSON.stringify({ error: { code: (api as { code?: string } | null)?.code ?? 'internal_error' } }), 'application/json')
      },
    })
    const router = createRouter()
    router.get('/v1/cosmetics/:id/template.json', templateRoute)
    app.use(router)
    const server: Server = createServer(toNodeListener(app))
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
    open.push({ close: () => new Promise<void>((r) => server.close(() => r())) })
    setContext(env.ctx)
    const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`
    const served = env.ctx.templates.jsonOf('crown')!
    const v = served.sha256.slice(0, 12)

    const res = await fetch(`${base}/v1/cosmetics/free_crown/template.json?v=${v}`)
    expect(res.status).toBe(200)
    expect(res.headers.get('content-type')).toContain('application/json')
    expect(res.headers.get('etag')).toBe(`"${served.sha256}"`)
    expect(res.headers.get('cache-control')).toBe('public, max-age=31536000, immutable')
    expect(await res.text()).toBe(served.json)

    const stale = await fetch(`${base}/v1/cosmetics/free_crown/template.json?v=000000000000`)
    expect(stale.headers.get('cache-control')).toBe('public, max-age=300')
    const again = await fetch(`${base}/v1/cosmetics/free_crown/template.json`, { headers: { 'if-none-match': `"${served.sha256}"` } })
    expect(again.status).toBe(304)

    expect((await fetch(`${base}/v1/cosmetics/nope/template.json`)).status).toBe(404)
    expect((await fetch(`${base}/v1/cosmetics/winken/template.json`)).status).toBe(404)
    expect((await fetch(`${base}/v1/cosmetics/v2_hat/template.json`)).status).toBe(404)
    expect((await fetch(`${base}/v1/cosmetics/..%2Fx/template.json`)).status).toBe(404)
  })
})

function thrown(fn: () => unknown): unknown {
  try {
    fn()
  } catch (e) {
    return e
  }
  return undefined
}
