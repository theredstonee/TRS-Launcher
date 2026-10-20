import { createServer, type Server } from 'node:http'
import { DatabaseSync } from 'node:sqlite'
import { createApp, createRouter, send, setResponseStatus, toNodeListener } from 'h3'
import { afterEach, describe, expect, it } from 'vitest'
import { setContext } from '../server/lib/context'
import { all, migrate } from '../server/lib/db'
import { isApiError } from '../server/lib/errors'
import {
  adminFeatured,
  createFeatured,
  deleteFeatured,
  featuredImage,
  orderFeatured,
  parseFeaturedLink,
  publicFeatured,
  refreshFeatured,
  releaseVersions,
  updateFeatured,
} from '../server/lib/featured'
import { MIGRATIONS, migrateFeaturedPacks } from '../server/lib/migrations'
import { ownerStaff, setMemberRoles, teamOf, type Staff } from '../server/lib/team'
import adminIndex from '../server/routes/v1/admin/featured-packs/index.get'
import adminCreate from '../server/routes/v1/admin/featured-packs/index.post'
import adminOrder from '../server/routes/v1/admin/featured-packs/order.put'
import adminPatch from '../server/routes/v1/admin/featured-packs/[id].patch'
import adminDelete from '../server/routes/v1/admin/featured-packs/[id].delete'
import adminRefresh from '../server/routes/v1/admin/featured-packs/[id]/refresh.post'
import publicIndex from '../server/routes/v1/site/featured-packs/index.get'
import iconRoute from '../server/routes/v1/site/featured-packs/[id]/icon.get'
import bannerRoute from '../server/routes/v1/site/featured-packs/[id]/banner.get'
import { code, codeAsync } from './chathelpers'
import { ADMIN, login, makeEnv, solidPng, type TestEnv } from './helpers'

const OWNER: Staff = ownerStaff(ADMIN)
const HOUR = 60 * 60 * 1000
const ADMIN_KEY = 'admin-key-0123456789abcdef-0123456789abcdef'

const PNG = solidPng(16, 16)
const JPEG = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(64, 1)])
const WEBP = Buffer.concat([Buffer.from('RIFF'), Buffer.alloc(4), Buffer.from('WEBPVP8 '), Buffer.alloc(32, 2)])

// ---------------------------------------------------------------- Attrappen für Modrinth und CurseForge

const MR_FO = {
  id: 'AbCd1234',
  slug: 'fabulously-optimized',
  title: 'Fabulously Optimized',
  description: 'Improved performance and graphics.\nOne line.',
  project_type: 'modpack',
  downloads: 12345678,
  loaders: ['fabric', 'quilt', 'minecraft'],
  game_versions: ['1.20.1', '1.21', '23w31a', '1.21.10', '1.21.1-pre1', '1.16.5'],
  icon_url: 'https://cdn.modrinth.com/data/AbCd1234/icon.png',
  gallery: [
    { url: 'https://cdn.modrinth.com/data/AbCd1234/images/a.webp', featured: false, ordering: 0 },
    { url: 'https://cdn.modrinth.com/data/AbCd1234/images/b.webp', featured: true, ordering: 1 },
  ],
}
const MR_MOD = { ...MR_FO, id: 'Mod00001', slug: 'sodium', project_type: 'mod' }

const CF_ATM = {
  id: 715572,
  gameId: 432,
  classId: 4471,
  name: 'All the Mods 9 - ATM9',
  slug: 'all-the-mods-9',
  summary: 'Kitchen sink pack.',
  downloadCount: 9876543.0,
  authors: [{ name: 'ATMTeam' }],
  logo: { url: 'https://media.forgecdn.net/avatars/1/2/atm.png', thumbnailUrl: 'https://media.forgecdn.net/avatars/thumbnails/1/2/atm.png' },
  screenshots: [{ url: 'https://media.forgecdn.net/attachments/1/2/shot.jpg' }],
  latestFilesIndexes: [{ gameVersion: '1.20.1', modLoader: 1 }, { gameVersion: '1.20.1', modLoader: 6 }, { gameVersion: '1.20' }],
  latestFiles: [{ gameVersions: ['1.20.1', 'Forge', 'NeoForge', 'Client'] }],
}
const CF_JEI = { ...CF_ATM, id: 238222, classId: 6, slug: 'jei', name: 'JEI' }

interface Calls {
  urls: string[]
  cfKeys: (string | null)[]
}

function mockFetch(calls: Calls, opts: { mrDown?: boolean, evilRedirect?: boolean } = {}): typeof fetch {
  const json = (v: unknown, status = 200) => new Response(JSON.stringify(v), { status, headers: { 'content-type': 'application/json' } })
  return (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(input))
    calls.urls.push(url.href)
    const headers = new Headers(init?.headers)
    if (url.hostname === 'api.curseforge.com') calls.cfKeys.push(headers.get('x-api-key'))
    if (url.hostname === 'api.modrinth.com') {
      if (opts.mrDown) return new Response('down', { status: 500 })
      const p = url.pathname
      if (p === '/v2/project/fabulously-optimized' || p === '/v2/project/AbCd1234') return json(MR_FO)
      if (p === '/v2/project/AbCd1234/members') return json([{ role: 'Member', ordering: 1, user: { username: 'Helper' } }, { role: 'Owner', is_owner: true, ordering: 0, user: { username: 'Madis' } }])
      if (p === '/v2/project/sodium') return json(MR_MOD)
      return json({ error: 'not_found' }, 404)
    }
    if (url.hostname === 'cdn.modrinth.com') {
      if (url.pathname.endsWith('icon.png')) {
        if (opts.evilRedirect) return new Response(null, { status: 302, headers: { location: 'https://evil.example/x.png' } })
        return new Response(PNG, { status: 200 })
      }
      if (url.pathname.endsWith('b.webp')) return new Response(WEBP, { status: 200 })
      return new Response('no', { status: 404 })
    }
    if (url.hostname === 'api.curseforge.com') {
      if (url.pathname === '/v1/mods/search') {
        const slug = url.searchParams.get('slug')
        const cls = url.searchParams.get('classId')
        expect(url.searchParams.get('gameId')).toBe('432')
        if (slug === 'all-the-mods-9' && (cls === '4471' || cls === null)) return json({ data: [CF_ATM] })
        if (slug === 'jei' && cls === null) return json({ data: [CF_JEI] })
        return json({ data: [] })
      }
      if (url.pathname === '/v1/mods/715572') return json({ data: CF_ATM })
      if (url.pathname === '/v1/mods/238222') return json({ data: CF_JEI })
      return json({}, 404)
    }
    if (url.hostname === 'media.forgecdn.net') {
      if (url.pathname.endsWith('atm.png')) return new Response(PNG, { status: 200 })
      // Zu groß angekündigt → kein Banner.
      if (url.pathname.endsWith('shot.jpg')) return new Response(JPEG, { status: 200 })
      return new Response('no', { status: 404 })
    }
    if (url.hostname === 'evil.example') return new Response(PNG, { status: 200 })
    throw new Error(`unexpected fetch ${url.href}`)
  }) as typeof fetch
}

function setup(opts: { cfKey?: boolean, mrDown?: boolean, evilRedirect?: boolean } = {}): TestEnv & { calls: Calls } {
  const env = makeEnv({ env: { TRUST_PROXY: 'none', SITE_URL: 'https://site.test', ...(opts.cfKey === false ? {} : { CURSEFORGE_API_KEY: 'cf-test-key' }) } })
  const calls: Calls = { urls: [], cfKeys: [] }
  env.ctx.modrinthFetch = mockFetch(calls, opts)
  env.ctx.curseforgeFetch = mockFetch(calls, opts)
  return { ...env, calls }
}

// ---------------------------------------------------------------- Links

describe('featured: link parsing', () => {
  it('accepts Modrinth and CurseForge links in all documented forms', () => {
    expect(parseFeaturedLink('https://modrinth.com/modpack/fabulously-optimized')).toEqual({ source: 'modrinth', ref: 'fabulously-optimized' })
    expect(parseFeaturedLink('  https://modrinth.com/modpack/fabulously-optimized/versions?l=fabric#x ')).toEqual({ source: 'modrinth', ref: 'fabulously-optimized' })
    expect(parseFeaturedLink('https://modrinth.com/project/AbCd1234')).toEqual({ source: 'modrinth', ref: 'AbCd1234' })
    expect(parseFeaturedLink('https://www.modrinth.com/modpack/x_y.z')).toEqual({ source: 'modrinth', ref: 'x_y.z' })
    expect(parseFeaturedLink('modrinth.com/modpack/fo')).toEqual({ source: 'modrinth', ref: 'fo' })
    expect(parseFeaturedLink('modrinth:AbCd1234')).toEqual({ source: 'modrinth', ref: 'AbCd1234' })
    expect(parseFeaturedLink('https://www.curseforge.com/minecraft/modpacks/all-the-mods-9')).toEqual({ source: 'curseforge', slug: 'all-the-mods-9' })
    expect(parseFeaturedLink('https://www.curseforge.com/minecraft/modpacks/All-The-Mods-9/files/123')).toEqual({ source: 'curseforge', slug: 'all-the-mods-9' })
    expect(parseFeaturedLink('https://curseforge.com/minecraft/modpacks/atm9')).toEqual({ source: 'curseforge', slug: 'atm9' })
    expect(parseFeaturedLink('curseforge:715572')).toEqual({ source: 'curseforge', id: 715572 })
    expect(parseFeaturedLink('CurseForge:715572')).toEqual({ source: 'curseforge', id: 715572 })
  })

  it('rejects everything else', () => {
    for (const bad of [
      '',
      'hello',
      'http://modrinth.com/modpack/fo',
      'https://modrinth.com/mod/sodium',
      'https://modrinth.com/modpack/',
      'https://modrinth.com:8443/modpack/fo',
      'https://user@modrinth.com/modpack/fo',
      'https://evil.example/modpack/fo',
      'https://modrinth.com.evil.example/modpack/fo',
      'https://www.curseforge.com/minecraft/mc-mods/jei',
      'https://www.curseforge.com/minecraft/modpacks/',
      'https://www.curseforge.com/minecraft/modpacks/a%2Fb',
      'https://www.curseforge.com/wow/modpacks/x',
      'curseforge:abc',
      'curseforge:0',
      'modrinth:',
      'modrinth:../x',
      'javascript:alert(1)',
      `https://modrinth.com/modpack/${'a'.repeat(600)}`,
    ]) expect(parseFeaturedLink(bad), bad).toBeNull()
  })

  it('keeps only release versions, newest first, at most 8', () => {
    expect(releaseVersions(['1.20.1', '1.21', '23w31a', '1.21.10', '1.21.1-pre1', '1.16.5', '1.21.2', 7])).toEqual(['1.21.10', '1.21.2', '1.21', '1.20.1', '1.16.5'])
    expect(releaseVersions(Array.from({ length: 12 }, (_, i) => `1.${i}`))).toHaveLength(8)
  })
})

// ---------------------------------------------------------------- Anlegen

describe('featured: create via the platforms', () => {
  it('Modrinth: metadata, owner as author, icon + featured gallery image stored', async () => {
    const env = setup()
    const p = await createFeatured(env.ctx, OWNER, { url: 'https://modrinth.com/modpack/fabulously-optimized' })
    expect(p).toMatchObject({
      source: 'modrinth',
      projectId: 'AbCd1234',
      slug: 'fabulously-optimized',
      url: 'https://modrinth.com/modpack/fabulously-optimized',
      title: 'Fabulously Optimized',
      summary: 'Improved performance and graphics. One line.',
      author: 'Madis',
      downloads: 12345678,
      loaders: ['fabric', 'quilt'],
      gameVersions: ['1.21.10', '1.21', '1.20.1', '1.16.5'],
      badge: null,
      pinned: false,
      startsAt: null,
      endsAt: null,
      active: true,
      createdBy: ADMIN,
      texts: { en: { title: null, text: null }, de: { title: null, text: null } },
    })
    expect(p.id).toMatch(/^fp_[a-z0-9]{12}$/)
    expect(p.iconUrl).toBe(`https://site.test/v1/site/featured-packs/${p.id}/icon?v=1`)
    expect(p.bannerUrl).toBe(`https://site.test/v1/site/featured-packs/${p.id}/banner?v=1`)
    expect(featuredImage(env.ctx, p.id, 'icon')!.mime).toBe('image/png')
    expect(featuredImage(env.ctx, p.id, 'banner')!.mime).toBe('image/webp')
    expect(env.calls.urls).toContain('https://cdn.modrinth.com/data/AbCd1234/images/b.webp')
    expect(all(env.ctx.db, "SELECT action FROM admin_log WHERE action LIKE 'featured.%'")).toEqual([{ action: 'featured.create' }])
  })

  it('CurseForge by slug and id: key sent, loaders and versions from the file index', async () => {
    const env = setup()
    const p = await createFeatured(env.ctx, OWNER, { url: 'https://www.curseforge.com/minecraft/modpacks/all-the-mods-9' })
    expect(p).toMatchObject({
      source: 'curseforge',
      projectId: '715572',
      slug: 'all-the-mods-9',
      url: 'https://www.curseforge.com/minecraft/modpacks/all-the-mods-9',
      title: 'All the Mods 9 - ATM9',
      author: 'ATMTeam',
      downloads: 9876543,
      loaders: ['forge', 'neoforge'],
      gameVersions: ['1.20.1', '1.20'],
    })
    expect(featuredImage(env.ctx, p.id, 'icon')!.mime).toBe('image/png')
    expect(featuredImage(env.ctx, p.id, 'banner')!.mime).toBe('image/jpeg')
    expect(env.calls.cfKeys.every((k) => k === 'cf-test-key')).toBe(true)
    // Gleiche Zahl als Kurzform → schon da (ohne Anfrage bei CurseForge).
    const before = env.calls.urls.length
    expect(await codeAsync(() => createFeatured(env.ctx, OWNER, { url: 'curseforge:715572' }))).toBe('already_featured')
    expect(env.calls.urls.length).toBe(before)
  })

  it('errors: invalid link, not found, not a modpack, already featured, no CurseForge key, upstream down', async () => {
    const env = setup()
    expect(await codeAsync(() => createFeatured(env.ctx, OWNER, { url: 'https://example.com' }))).toBe('invalid_link')
    expect(await codeAsync(() => createFeatured(env.ctx, OWNER, { url: 'https://modrinth.com/modpack/missing' }))).toBe('project_not_found')
    expect(await codeAsync(() => createFeatured(env.ctx, OWNER, { url: 'https://modrinth.com/project/sodium' }))).toBe('not_a_modpack')
    expect(await codeAsync(() => createFeatured(env.ctx, OWNER, { url: 'https://www.curseforge.com/minecraft/modpacks/jei' }))).toBe('not_a_modpack')
    expect(await codeAsync(() => createFeatured(env.ctx, OWNER, { url: 'https://www.curseforge.com/minecraft/modpacks/nope' }))).toBe('project_not_found')
    expect(await codeAsync(() => createFeatured(env.ctx, OWNER, { url: 'curseforge:238222' }))).toBe('not_a_modpack')
    await createFeatured(env.ctx, OWNER, { url: 'modrinth:AbCd1234' })
    // Gleiches Projekt über den Slug → erst nach dem Nachschlagen erkannt.
    expect(await codeAsync(() => createFeatured(env.ctx, OWNER, { url: 'https://modrinth.com/modpack/fabulously-optimized' }))).toBe('already_featured')

    const noKey = setup({ cfKey: false })
    expect(await codeAsync(() => createFeatured(noKey.ctx, OWNER, { url: 'curseforge:715572' }))).toBe('curseforge_unavailable')
    expect(noKey.calls.urls).toEqual([])

    const down = setup({ mrDown: true })
    expect(await codeAsync(() => createFeatured(down.ctx, OWNER, { url: 'modrinth:AbCd1234' }))).toBe('upstream_failed')
    expect(adminFeatured(down.ctx, OWNER)).toEqual([])
  })

  it('images: never follows redirects to other hosts', async () => {
    const env = setup({ evilRedirect: true })
    const p = await createFeatured(env.ctx, OWNER, { url: 'modrinth:AbCd1234' })
    expect(p.iconUrl).toBeNull()
    expect(p.bannerUrl).not.toBeNull()
    expect(env.calls.urls.some((u) => u.includes('evil.example'))).toBe(false)
  })
})

// ---------------------------------------------------------------- Liste, Änderungen, Reihenfolge

describe('featured: list, patch, order', () => {
  async function three(env: TestEnv) {
    const a = await createFeatured(env.ctx, OWNER, { url: 'modrinth:AbCd1234' })
    const b = await createFeatured(env.ctx, OWNER, { url: 'curseforge:715572' })
    return { a, b }
  }

  it('public list: only active packs, pinned first, then manual order', async () => {
    const env = setup()
    const { a, b } = await three(env)
    expect(publicFeatured(env.ctx).map((p) => p.id)).toEqual([a.id, b.id])
    updateFeatured(env.ctx, OWNER, b.id, { pinned: true })
    expect(publicFeatured(env.ctx).map((p) => p.id)).toEqual([b.id, a.id])
    updateFeatured(env.ctx, OWNER, b.id, { pinned: false })
    orderFeatured(env.ctx, OWNER, [b.id, a.id])
    expect(publicFeatured(env.ctx).map((p) => p.id)).toEqual([b.id, a.id])

    // Zeitfenster: noch nicht gestartet / abgelaufen → nur im Team-Bereich.
    const now = env.clock.t
    updateFeatured(env.ctx, OWNER, a.id, { startsAt: new Date(now + HOUR).toISOString() })
    updateFeatured(env.ctx, OWNER, b.id, { endsAt: new Date(now + 2 * HOUR).toISOString() })
    expect(publicFeatured(env.ctx).map((p) => p.id)).toEqual([b.id])
    expect(adminFeatured(env.ctx, OWNER).map((p) => [p.id, p.active])).toEqual([[b.id, true], [a.id, false]])
    env.clock.advance(HOUR)
    expect(publicFeatured(env.ctx).map((p) => p.id)).toEqual([b.id, a.id])
    env.clock.advance(HOUR)
    expect(publicFeatured(env.ctx).map((p) => p.id)).toEqual([a.id])
    // Öffentliche Ansicht ohne Team-Felder.
    expect(Object.keys(publicFeatured(env.ctx)[0]!).sort()).toEqual([
      'author', 'badge', 'bannerUrl', 'downloads', 'endsAt', 'gameVersions', 'iconUrl', 'id', 'loaders', 'pinned', 'projectId',
      'slug', 'source', 'startsAt', 'summary', 'texts', 'title', 'updatedAt', 'url',
    ])
  })

  it('patch: texts trimmed to one line, null clears, badge, invalid window', async () => {
    const env = setup()
    const { a } = await three(env)
    const p = updateFeatured(env.ctx, OWNER, a.id, {
      texts: { en: { title: 'Fast & pretty', text: 'Line one\nline\u0007 two\u202e' }, de: { title: 'Schnell' } },
      badge: 'recommended',
    })
    expect(p.texts).toEqual({ en: { title: 'Fast & pretty', text: 'Line one line two' }, de: { title: 'Schnell', text: null } })
    expect(p.badge).toBe('recommended')
    const q = updateFeatured(env.ctx, OWNER, a.id, { texts: { en: { title: null, text: '' } }, badge: null })
    expect(q.texts.en).toEqual({ title: null, text: null })
    expect(q.texts.de.title).toBe('Schnell')
    expect(q.badge).toBeNull()

    const now = env.clock.t
    expect(code(() => updateFeatured(env.ctx, OWNER, a.id, { startsAt: new Date(now + 2 * HOUR).toISOString(), endsAt: new Date(now + HOUR).toISOString() }))).toBe('invalid_window')
    updateFeatured(env.ctx, OWNER, a.id, { endsAt: new Date(now + HOUR).toISOString() })
    expect(code(() => updateFeatured(env.ctx, OWNER, a.id, { startsAt: new Date(now + HOUR).toISOString() }))).toBe('invalid_window')
    expect(code(() => updateFeatured(env.ctx, OWNER, 'fp_aaaaaaaaaaaa', { pinned: true }))).toBe('not_found')
  })

  it('order: must be exactly the current set', async () => {
    const env = setup()
    const { a, b } = await three(env)
    expect(code(() => orderFeatured(env.ctx, OWNER, [a.id]))).toBe('invalid_order')
    expect(code(() => orderFeatured(env.ctx, OWNER, [a.id, a.id]))).toBe('invalid_order')
    expect(code(() => orderFeatured(env.ctx, OWNER, [a.id, b.id, 'fp_aaaaaaaaaaaa']))).toBe('invalid_order')
    expect(code(() => orderFeatured(env.ctx, OWNER, [a.id, 'fp_aaaaaaaaaaaa']))).toBe('invalid_order')
    expect(orderFeatured(env.ctx, OWNER, [b.id, a.id]).map((p) => p.id)).toEqual([b.id, a.id])
  })

  it('refresh bumps rev (new image URLs); delete removes images', async () => {
    const env = setup()
    const { a } = await three(env)
    env.clock.advance(HOUR)
    const r = await refreshFeatured(env.ctx, OWNER, a.id)
    expect(r.iconUrl).toMatch(/\?v=2$/)
    expect(r.refreshedAt).toBe(new Date(env.clock.t).toISOString())
    deleteFeatured(env.ctx, OWNER, a.id)
    expect(featuredImage(env.ctx, a.id, 'icon')).toBeNull()
    expect(all(env.ctx.db, 'SELECT * FROM featured_pack_images WHERE pack_id = ?', a.id)).toEqual([])
    expect(code(() => deleteFeatured(env.ctx, OWNER, a.id))).toBe('not_found')
  })

  it('rights: only roles with featured.manage (content team yes, moderator no)', async () => {
    const env = setup()
    const content = await login(env, 'Content')
    const mod = await login(env, 'Moddy')
    await login(env, 'Theredstonee', ADMIN)
    setMemberRoles(env.ctx, OWNER, content.user.uuid, ['content'])
    setMemberRoles(env.ctx, OWNER, mod.user.uuid, ['moderator'])
    const c = teamOf(env.ctx, content.user.uuid)!
    const m = teamOf(env.ctx, mod.user.uuid)!
    expect(await codeAsync(() => createFeatured(env.ctx, m, { url: 'modrinth:AbCd1234' }))).toBe('missing_permission')
    expect(code(() => adminFeatured(env.ctx, m))).toBe('missing_permission')
    const p = await createFeatured(env.ctx, c, { url: 'modrinth:AbCd1234' })
    expect(p.createdBy).toBe(content.user.uuid)
  })

  it('migration 28 is idempotent and the last one', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    migrate(db)
    migrateFeaturedPacks(db)
    migrateFeaturedPacks(db)
    expect(MIGRATIONS.at(-1)!.version).toBe(28)
    const roles = all<{ id: string, permissions: string }>(db, "SELECT id, permissions FROM team_roles WHERE id IN ('owner', 'admin', 'content', 'moderator') ORDER BY id")
    const has = Object.fromEntries(roles.map((r) => [r.id, (JSON.parse(r.permissions) as string[]).includes('featured.manage')]))
    expect(has).toEqual({ admin: true, content: true, moderator: false, owner: true })
  })
})

// ---------------------------------------------------------------- HTTP

describe('featured: HTTP', () => {
  const open: { close: () => Promise<void> }[] = []
  afterEach(async () => {
    for (const s of open.splice(0)) await s.close()
    setContext(undefined)
  })

  async function serve(env: TestEnv) {
    const app = createApp({
      onError: async (error, event) => {
        const api = isApiError(error) ? error : isApiError((error as { cause?: unknown }).cause) ? (error as { cause: never }).cause : null
        setResponseStatus(event, (api as { status?: number } | null)?.status ?? 500)
        await send(event, JSON.stringify({ error: { code: (api as { code?: string } | null)?.code ?? 'internal_error' } }), 'application/json')
      },
    })
    const router = createRouter()
    router.get('/v1/site/featured-packs', publicIndex)
    router.get('/v1/site/featured-packs/:id/icon', iconRoute)
    router.get('/v1/site/featured-packs/:id/banner', bannerRoute)
    router.get('/v1/admin/featured-packs', adminIndex)
    router.post('/v1/admin/featured-packs', adminCreate)
    router.put('/v1/admin/featured-packs/order', adminOrder)
    router.patch('/v1/admin/featured-packs/:id', adminPatch)
    router.delete('/v1/admin/featured-packs/:id', adminDelete)
    router.post('/v1/admin/featured-packs/:id/refresh', adminRefresh)
    app.use(router)
    const server: Server = createServer(toNodeListener(app))
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
    open.push({ close: () => new Promise<void>((r) => server.close(() => r())) })
    setContext(env.ctx)
    return `http://127.0.0.1:${(server.address() as { port: number }).port}`
  }

  it('team routes with X-Admin-Key; public list + images with the right headers; 403 without the permission', async () => {
    const env = setup()
    const base = await serve(env)
    const key = { 'x-admin-key': ADMIN_KEY, 'content-type': 'application/json' }

    const bad = await fetch(`${base}/v1/admin/featured-packs`, { method: 'POST', headers: key, body: JSON.stringify({ url: 'nope' }) })
    expect(bad.status).toBe(400)
    expect(((await bad.json()) as { error: { code: string } }).error.code).toBe('invalid_link')

    const res = await fetch(`${base}/v1/admin/featured-packs`, { method: 'POST', headers: key, body: JSON.stringify({ url: 'https://modrinth.com/modpack/fabulously-optimized' }) })
    expect(res.status).toBe(201)
    const { pack } = (await res.json()) as { pack: { id: string, createdBy: string } }
    expect(pack.createdBy).toBe('api-key')
    const cf = await fetch(`${base}/v1/admin/featured-packs`, { method: 'POST', headers: key, body: JSON.stringify({ url: 'curseforge:715572' }) })
    const cfPack = ((await cf.json()) as { pack: { id: string } }).pack

    const patch = await fetch(`${base}/v1/admin/featured-packs/${pack.id}`, { method: 'PATCH', headers: key, body: JSON.stringify({ badge: 'event', texts: { de: { text: 'Hallo' } } }) })
    expect(patch.status).toBe(200)
    const badPatch = await fetch(`${base}/v1/admin/featured-packs/${pack.id}`, { method: 'PATCH', headers: key, body: JSON.stringify({ badge: 'hot' }) })
    expect(badPatch.status).toBe(400)
    const window = await fetch(`${base}/v1/admin/featured-packs/${pack.id}`, { method: 'PATCH', headers: key, body: JSON.stringify({ startsAt: '2030-01-02T00:00:00Z', endsAt: '2030-01-01T00:00:00Z' }) })
    expect(((await window.json()) as { error: { code: string } }).error.code).toBe('invalid_window')
    const order = await fetch(`${base}/v1/admin/featured-packs/order`, { method: 'PUT', headers: key, body: JSON.stringify({ ids: [cfPack.id] }) })
    expect(((await order.json()) as { error: { code: string } }).error.code).toBe('invalid_order')
    const ok = await fetch(`${base}/v1/admin/featured-packs/order`, { method: 'PUT', headers: key, body: JSON.stringify({ ids: [cfPack.id, pack.id] }) })
    expect(((await ok.json()) as { packs: { id: string }[] }).packs.map((p) => p.id)).toEqual([cfPack.id, pack.id])
    expect((await fetch(`${base}/v1/admin/featured-packs/fp_aaaaaaaaaaaa`, { method: 'PATCH', headers: key, body: '{}' })).status).toBe(404)

    const pub = await fetch(`${base}/v1/site/featured-packs`)
    expect(pub.headers.get('cache-control')).toBe('public, max-age=300')
    const list = (await pub.json()) as { packs: { id: string, badge: string | null, texts: { de: { text: string | null } } }[] }
    expect(list.packs.map((p) => p.id)).toEqual([cfPack.id, pack.id])
    expect(list.packs[1]).toMatchObject({ badge: 'event', texts: { de: { text: 'Hallo' } } })

    const icon = await fetch(`${base}/v1/site/featured-packs/${pack.id}/icon?v=1`)
    expect(icon.status).toBe(200)
    expect(icon.headers.get('content-type')).toBe('image/png')
    expect(icon.headers.get('cache-control')).toBe('public, max-age=86400')
    expect(icon.headers.get('x-content-type-options')).toBe('nosniff')
    expect(Buffer.from(await icon.arrayBuffer()).equals(PNG)).toBe(true)
    const etag = icon.headers.get('etag')!
    expect((await fetch(`${base}/v1/site/featured-packs/${pack.id}/icon`, { headers: { 'if-none-match': etag } })).status).toBe(304)
    const banner = await fetch(`${base}/v1/site/featured-packs/${pack.id}/banner`)
    expect(banner.headers.get('content-type')).toBe('image/webp')
    expect((await fetch(`${base}/v1/site/featured-packs/${cfPack.id}/banner`)).headers.get('content-type')).toBe('image/jpeg')
    expect((await fetch(`${base}/v1/site/featured-packs/fp_aaaaaaaaaaaa/icon`)).status).toBe(404)
    expect((await fetch(`${base}/v1/site/featured-packs/..%2Fx/icon`)).status).toBe(404)

    // Ohne featured.manage: 403; anonym: 401.
    const mod = await login(env, 'Moddy')
    await login(env, 'Theredstonee', ADMIN)
    setMemberRoles(env.ctx, OWNER, mod.user.uuid, ['moderator'])
    const denied = await fetch(`${base}/v1/admin/featured-packs`, { headers: { authorization: `Bearer ${mod.token}` } })
    expect(denied.status).toBe(403)
    expect((await fetch(`${base}/v1/admin/featured-packs`)).status).toBe(401)

    const refresh = await fetch(`${base}/v1/admin/featured-packs/${pack.id}/refresh`, { method: 'POST', headers: { 'x-admin-key': ADMIN_KEY } })
    expect(((await refresh.json()) as { pack: { iconUrl: string } }).pack.iconUrl).toMatch(/\?v=2$/)
    expect((await fetch(`${base}/v1/admin/featured-packs/${pack.id}`, { method: 'DELETE', headers: { 'x-admin-key': ADMIN_KEY } })).status).toBe(204)
    expect((await fetch(`${base}/v1/site/featured-packs/${pack.id}/icon`)).status).toBe(404)
    const actions = all<{ action: string }>(env.ctx.db, "SELECT action FROM admin_log WHERE action LIKE 'featured.%' ORDER BY id").map((r) => r.action)
    expect(actions).toEqual(['featured.create', 'featured.create', 'featured.update', 'featured.order', 'featured.refresh', 'featured.delete'])
  })
})
