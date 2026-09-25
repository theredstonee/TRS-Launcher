import { PNG } from 'pngjs'
import { describe, expect, it } from 'vitest'
import { sweepExpired } from '../server/lib/auth'
import { one } from '../server/lib/db'
import { isApiError } from '../server/lib/errors'
import { parseWith } from '../server/lib/http'
import { sha256Hex } from '../server/lib/ids'
import { syncPresetsBody, syncSettingsBody, syncSkinPatchBody, syncSkinPutBody } from '../server/lib/schemas'
import {
  MAX_SYNC_SKINS,
  clientTime,
  decodeBase64Png,
  deleteSkin,
  patchSkin,
  putDoc,
  putSkin,
  readSkinPng,
  syncOverview,
} from '../server/lib/sync'
import { deleteUser } from '../server/lib/users'
import { chunk, login, makeEnv, solidPng, withChunks } from './helpers'

const DAY = 24 * 60 * 60 * 1000

function err(fn: () => unknown): { status: number, code: string, details?: Record<string, unknown> } {
  try {
    fn()
  } catch (e) {
    if (isApiError(e)) return { status: e.status, code: e.code, details: e.details }
    throw e
  }
  throw new Error('expected an error')
}

/** Skin-ID wie in der Launcher-Bibliothek (12 Hex-Zeichen). */
const sid = (n: number) => n.toString(16).padStart(12, '0')
const skin = (png = solidPng(64, 64)) => ({ name: 'Mein Skin', variant: 'classic' as const, png })

describe('sync skins', () => {
  it('uploads, lists, downloads, renames and re-encodes without metadata', async () => {
    const env = makeEnv()
    const u = await login(env, 'Steve')
    const withMeta = withChunks(solidPng(64, 64), [chunk('tEXt', Buffer.from('Comment\0secret'))])
    const view = putSkin(env.ctx, u.user.uuid, 'a1b2c3d4e5f6', { name: 'Mein Skin', variant: 'slim', png: withMeta })
    expect(view).toMatchObject({ id: 'a1b2c3d4e5f6', name: 'Mein Skin', variant: 'slim' })
    expect(view.updatedAt).toBe(new Date(env.clock.t).toISOString())

    const img = readSkinPng(env.ctx, u.user.uuid, 'a1b2c3d4e5f6')
    expect(img.png.includes(Buffer.from('secret'))).toBe(false)
    expect(sha256Hex(img.png)).toBe(view.sha256)
    const decoded = PNG.sync.read(img.png)
    expect([decoded.width, decoded.height]).toEqual([64, 64])

    // 64×32 (altes Format) bleibt 64×32.
    const legacy = putSkin(env.ctx, u.user.uuid, sid(2), skin(solidPng(64, 32)))
    expect(PNG.sync.read(readSkinPng(env.ctx, u.user.uuid, sid(2)).png).height).toBe(32)

    env.clock.advance(1000)
    const renamed = patchSkin(env.ctx, u.user.uuid, 'a1b2c3d4e5f6', { name: 'Neu' })
    expect(renamed).toMatchObject({ name: 'Neu', variant: 'slim', sha256: view.sha256 })
    expect(Date.parse(renamed.updatedAt)).toBe(env.clock.t)
    expect(patchSkin(env.ctx, u.user.uuid, 'a1b2c3d4e5f6', { variant: 'classic' }).variant).toBe('classic')
    expect(err(() => patchSkin(env.ctx, u.user.uuid, sid(99), { name: 'x' })).code).toBe('skin_not_found')

    const o = syncOverview(env.ctx, u.user.uuid)
    expect(o.skins.map((s) => s.id).sort()).toEqual([sid(2), 'a1b2c3d4e5f6'].sort())
    expect(o.skins.find((s) => s.id === sid(2))).toEqual(legacy)
    expect(o).toMatchObject({ deletedSkins: [], presets: null, settings: null, client: null, wardrobe: null })
    expect(JSON.stringify(o)).not.toContain('png')
  })

  it('replaces idempotently and keeps the sha256 stable for the same pixels', async () => {
    const env = makeEnv()
    const u = await login(env, 'Steve')
    const a = putSkin(env.ctx, u.user.uuid, sid(1), skin())
    const b = putSkin(env.ctx, u.user.uuid, sid(1), skin())
    expect(b.sha256).toBe(a.sha256)
    const c = putSkin(env.ctx, u.user.uuid, sid(1), skin(solidPng(64, 64, [1, 2, 3, 255])))
    expect(c.sha256).not.toBe(a.sha256)
    expect(syncOverview(env.ctx, u.user.uuid).skins).toHaveLength(1)
  })

  it('deletes with a tombstone, also for unknown ids; upload removes it; sweep drops it after 30 days', async () => {
    const env = makeEnv()
    const u = await login(env, 'Steve')
    putSkin(env.ctx, u.user.uuid, sid(1), skin())
    deleteSkin(env.ctx, u.user.uuid, sid(1))
    deleteSkin(env.ctx, u.user.uuid, sid(2))
    let o = syncOverview(env.ctx, u.user.uuid)
    expect(o.skins).toEqual([])
    expect(o.deletedSkins).toEqual([
      { id: sid(1), deletedAt: new Date(env.clock.t).toISOString() },
      { id: sid(2), deletedAt: new Date(env.clock.t).toISOString() },
    ])
    expect(err(() => readSkinPng(env.ctx, u.user.uuid, sid(1))).code).toBe('skin_not_found')

    putSkin(env.ctx, u.user.uuid, sid(2), skin())
    o = syncOverview(env.ctx, u.user.uuid)
    expect(o.deletedSkins.map((d) => d.id)).toEqual([sid(1)])
    expect(o.skins.map((s) => s.id)).toEqual([sid(2)])

    env.clock.advance(30 * DAY + 1)
    expect(syncOverview(env.ctx, u.user.uuid).deletedSkins).toEqual([])
    sweepExpired(env.ctx)
    expect(one(env.ctx.db, 'SELECT COUNT(*) AS n FROM sync_skin_tombstones')).toEqual({ n: 0 })
  })

  it('allows at most 60 skins; replacing an existing one still works at the limit', async () => {
    const env = makeEnv()
    const u = await login(env, 'Steve')
    const png = solidPng(64, 64)
    for (let i = 0; i < MAX_SYNC_SKINS; i++) putSkin(env.ctx, u.user.uuid, sid(i), skin(png))
    const e = err(() => putSkin(env.ctx, u.user.uuid, sid(1000), skin(png)))
    expect([e.status, e.code]).toEqual([409, 'skin_limit'])
    expect(putSkin(env.ctx, u.user.uuid, sid(5), { ...skin(png), name: 'Ersetzt' }).name).toBe('Ersetzt')
    deleteSkin(env.ctx, u.user.uuid, sid(0))
    expect(putSkin(env.ctx, u.user.uuid, sid(1000), skin(png)).id).toBe(sid(1000))
  })

  it('rejects invalid PNGs, wrong sizes and files over 128 KB', async () => {
    const env = makeEnv()
    const u = await login(env, 'Steve')
    const put = (png: Buffer) => err(() => putSkin(env.ctx, u.user.uuid, sid(1), skin(png)))
    expect(put(Buffer.from('not a png at all, just some text')).code).toBe('invalid_png')
    expect(put(solidPng(64, 64).subarray(0, 60)).code).toBe('invalid_png')
    expect(put(Buffer.concat([solidPng(64, 64), Buffer.from('trailing')])).code).toBe('invalid_png')
    expect(put(solidPng(128, 128)).code).toBe('invalid_dimensions')
    expect(put(solidPng(32, 64)).code).toBe('invalid_dimensions')
    expect(put(withChunks(solidPng(64, 64), [chunk('acTL', Buffer.alloc(8))], true)).code).toBe('animated_png')
    const big = put(withChunks(solidPng(64, 64), [chunk('tEXt', Buffer.alloc(130 * 1024, 0x41))]))
    expect([big.status, big.code]).toEqual([413, 'payload_too_large'])
    expect(syncOverview(env.ctx, u.user.uuid).skins).toEqual([])
  })

  it('validates the request bodies', () => {
    const png = solidPng(64, 64).toString('base64')
    const body = parseWith(syncSkinPutBody, { name: '  Mein Skin 🎨 ', variant: 'slim', png })
    expect(body.name).toBe('Mein Skin 🎨')
    expect(decodeBase64Png(body.png).equals(solidPng(64, 64))).toBe(true)
    const bad = (v: unknown) => err(() => parseWith(syncSkinPutBody, v)).code
    expect(bad({ name: 'x', variant: 'slim', png: 'nicht base64!' })).toBe('invalid_request')
    expect(bad({ name: 'x', variant: 'wide', png })).toBe('invalid_request')
    expect(bad({ name: 'a'.repeat(49), variant: 'slim', png })).toBe('invalid_request')
    expect(bad({ name: 'a\nb', variant: 'slim', png })).toBe('invalid_request')
    expect(bad({ name: '   ', variant: 'slim', png })).toBe('invalid_request')
    expect(bad({ name: 'x', variant: 'slim', png, updatedAt: '2026-01-01T00:00:00Z' })).toBe('invalid_request')
    expect(err(() => parseWith(syncSkinPatchBody, {})).code).toBe('invalid_request')
    expect(parseWith(syncSkinPatchBody, { variant: 'classic' })).toEqual({ variant: 'classic' })
  })
})

describe('sync presets and settings', () => {
  it('last writer wins by updatedAt; older writes get 409 stale with the current state', async () => {
    const env = makeEnv()
    const u = await login(env, 'Steve')
    const t1 = env.clock.t - 60_000
    const first = putDoc(env.ctx, u.user.uuid, 'presets', { presets: [{ name: 'PvP', mods: ['sodium'] }] }, t1)
    expect(first).toEqual({ data: { presets: [{ name: 'PvP', mods: ['sodium'] }] }, updatedAt: new Date(t1).toISOString() })

    const e = err(() => putDoc(env.ctx, u.user.uuid, 'presets', { presets: [] }, t1 - 1))
    expect([e.status, e.code]).toEqual([409, 'stale'])
    expect(e.details).toEqual({ current: first })

    // Gleich alt = Wiederholung desselben Geräts → überschreiben; neuer → gewinnt.
    expect(putDoc(env.ctx, u.user.uuid, 'presets', { presets: [] }, t1).data).toEqual({ presets: [] })
    const newer = putDoc(env.ctx, u.user.uuid, 'presets', { presets: [{ name: 'B' }] }, t1 + 5)
    expect(syncOverview(env.ctx, u.user.uuid).presets).toEqual(newer)

    // Einstellungen unabhängig von Presets.
    const s = putDoc(env.ctx, u.user.uuid, 'settings', { theme: 'dark', accent: 'lapis', language: 'pt-BR' }, t1 - 10)
    expect(syncOverview(env.ctx, u.user.uuid).settings).toEqual(s)
    expect(err(() => putDoc(env.ctx, u.user.uuid, 'settings', { theme: 'light' }, t1 - 11)).code).toBe('stale')
  })

  it('limits presets to 64 KB and rejects clocks far in the future', async () => {
    const env = makeEnv()
    const u = await login(env, 'Steve')
    const big = err(() => putDoc(env.ctx, u.user.uuid, 'presets', { x: 'a'.repeat(64 * 1024) }, env.clock.t))
    expect([big.status, big.code]).toEqual([413, 'payload_too_large'])
    expect(clientTime(env.ctx, new Date(env.clock.t + 60_000).toISOString())).toBe(env.clock.t + 60_000)
    expect(err(() => clientTime(env.ctx, new Date(env.clock.t + 25 * 60 * 60_000).toISOString())).code).toBe('invalid_request')
  })

  it('keeps TRS client settings and the wardrobe as their own documents (64 KB each)', async () => {
    const env = makeEnv()
    const u = await login(env, 'Steve')
    const t = env.clock.t - 1000
    const c = putDoc(env.ctx, u.user.uuid, 'client', { modules: { zoom: { enabled: true } }, intro: { done: true } }, t)
    const w = putDoc(env.ctx, u.user.uuid, 'wardrobe', { outfits: [{ name: 'PvP', skin: 'a1b2c3d4e5f6' }], favorites: [] }, t)
    const o = syncOverview(env.ctx, u.user.uuid)
    expect(o.client).toEqual(c)
    expect(o.wardrobe).toEqual(w)
    expect(o.presets).toBeNull()
    expect(err(() => putDoc(env.ctx, u.user.uuid, 'client', {}, t - 1)).code).toBe('stale')
    for (const kind of ['client', 'wardrobe'] as const) {
      const big = err(() => putDoc(env.ctx, u.user.uuid, kind, { x: 'a'.repeat(64 * 1024) }, env.clock.t))
      expect([big.status, big.code]).toEqual([413, 'payload_too_large'])
    }
  })

  it('only accepts theme, accent and language in settings', () => {
    const at = '2026-09-25T10:00:00.000Z'
    expect(parseWith(syncSettingsBody, { data: { theme: 'dark', accent: 'redstone', language: 'de' }, updatedAt: at }).data)
      .toEqual({ theme: 'dark', accent: 'redstone', language: 'de' })
    expect(parseWith(syncSettingsBody, { data: {}, updatedAt: at }).data).toEqual({})
    const bad = (v: unknown) => err(() => parseWith(syncSettingsBody, v)).code
    expect(bad({ data: { theme: 'dark', maxMemoryMb: 4096 }, updatedAt: at })).toBe('invalid_request')
    expect(bad({ data: { javaPath: 'C:/java' }, updatedAt: at })).toBe('invalid_request')
    expect(bad({ data: { theme: 'a'.repeat(33) }, updatedAt: at })).toBe('invalid_request')
    expect(bad({ data: { language: 'a'.repeat(17) }, updatedAt: at })).toBe('invalid_request')
    expect(bad({ data: { theme: 'da rk' }, updatedAt: at })).toBe('invalid_request')
    expect(bad({ data: { theme: 'dark' }, updatedAt: 'gestern' })).toBe('invalid_request')
    expect(bad({ data: { theme: 'dark' } })).toBe('invalid_request')

    expect(parseWith(syncPresetsBody, { data: { a: [1, { b: null }] }, updatedAt: at }).data).toEqual({ a: [1, { b: null }] })
    expect(err(() => parseWith(syncPresetsBody, { data: [1, 2], updatedAt: at })).code).toBe('invalid_request')
    expect(err(() => parseWith(syncPresetsBody, { data: 'x', updatedAt: at })).code).toBe('invalid_request')
  })
})

describe('sync privacy', () => {
  it('other accounts see nothing of mine', async () => {
    const env = makeEnv()
    const a = await login(env, 'Alice')
    const b = await login(env, 'Bob')
    putSkin(env.ctx, a.user.uuid, sid(1), skin())
    deleteSkin(env.ctx, a.user.uuid, sid(2))
    putDoc(env.ctx, a.user.uuid, 'presets', { p: 1 }, env.clock.t)
    putDoc(env.ctx, a.user.uuid, 'settings', { theme: 'dark' }, env.clock.t)

    putDoc(env.ctx, a.user.uuid, 'client', { modules: {} }, env.clock.t)
    putDoc(env.ctx, a.user.uuid, 'wardrobe', { outfits: [] }, env.clock.t)

    expect(syncOverview(env.ctx, b.user.uuid)).toEqual({
      skins: [], deletedSkins: [], presets: null, settings: null, client: null, wardrobe: null,
    })
    expect(err(() => readSkinPng(env.ctx, b.user.uuid, sid(1))).code).toBe('skin_not_found')
    expect(err(() => patchSkin(env.ctx, b.user.uuid, sid(1), { name: 'x' })).code).toBe('skin_not_found')
    // Bob „löscht“ Alices ID nur bei sich selbst.
    deleteSkin(env.ctx, b.user.uuid, sid(1))
    expect(syncOverview(env.ctx, a.user.uuid).skins.map((s) => s.id)).toEqual([sid(1)])
    // Gleiche ID bei Bob ist ein eigener Eintrag.
    putSkin(env.ctx, b.user.uuid, sid(1), { ...skin(), name: 'Bobs' })
    expect(syncOverview(env.ctx, a.user.uuid).skins[0]!.name).toBe('Mein Skin')
  })

  it('account deletion removes every sync record', async () => {
    const env = makeEnv()
    const a = await login(env, 'Alice')
    const b = await login(env, 'Bob')
    for (const u of [a, b]) {
      putSkin(env.ctx, u.user.uuid, sid(1), skin())
      deleteSkin(env.ctx, u.user.uuid, sid(2))
      putDoc(env.ctx, u.user.uuid, 'presets', { p: 1 }, env.clock.t)
      putDoc(env.ctx, u.user.uuid, 'settings', { theme: 'dark' }, env.clock.t)
    }
    deleteUser(env.ctx, a.user.uuid)
    const count = (table: string, uuid: string) =>
      one<{ n: number }>(env.ctx.db, `SELECT COUNT(*) AS n FROM ${table} WHERE uuid = ?`, uuid)!.n
    for (const table of ['sync_skins', 'sync_skin_tombstones', 'sync_docs']) {
      expect(count(table, a.user.uuid)).toBe(0)
      expect(count(table, b.user.uuid)).toBeGreaterThan(0)
    }
  })
})
