import { existsSync, readdirSync } from 'node:fs'
import { createServer, type Server } from 'node:http'
import { join } from 'node:path'
import { DatabaseSync } from 'node:sqlite'
import { createApp, createRouter, send, setResponseHeaders, setResponseStatus, toNodeListener } from 'h3'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { renderMarkdown } from '../app/utils/markdown'
import { buildSitemap } from '../shared/seo'
import {
  blogAuthors,
  blogDir,
  createPost,
  deletePost,
  deleteMedia,
  mediaForServe,
  publicNews,
  publicNewsFull,
  publicNewsPost,
  publishPost,
  slugify,
  summaryFromBody,
  unpublishPost,
  updatePost,
  uploadMedia,
} from '../server/lib/blog'
import { setContext } from '../server/lib/context'
import { migrate } from '../server/lib/db'
import { isApiError } from '../server/lib/errors'
import { MIGRATIONS, migrateLauncherLoginBlog } from '../server/lib/migrations'
import { ownerStaff, setMemberRoles, teamOf, type Staff } from '../server/lib/team'
import { createWebSession } from '../server/lib/weblogin'
import mediaRoute from '../server/routes/v1/site/blog/media/[file].get'
import blogIndex from '../server/routes/v1/site/blog/index.get'
import { codeAsync, code } from './chathelpers'
import { ADMIN, login, makeEnv, solidPng, type TestEnv } from './helpers'

const OWNER: Staff = ownerStaff(ADMIN)
const HOUR = 60 * 60 * 1000

const EN = { title: 'Hello world', summary: 'A short text.', body: 'First **news** post.\n\n![Shot](/v1/site/blog/media/AAAAAAAAAAAAAAAAAAAAAA.jpg)' }

async function crew(env: TestEnv) {
  const writer = await login(env, 'Writer')
  const mod = await login(env, 'Moddy')
  await login(env, 'Theredstonee', ADMIN)
  setMemberRoles(env.ctx, OWNER, writer.user.uuid, ['content'])
  setMemberRoles(env.ctx, OWNER, mod.user.uuid, ['moderator'])
  return { writer: teamOf(env.ctx, writer.user.uuid)!, mod: teamOf(env.ctx, mod.user.uuid)!, writerUuid: writer.user.uuid }
}

describe('blog: rights and life cycle', () => {
  it('content role writes drafts, only publishers publish; drafts and scheduled posts stay hidden', async () => {
    const env = makeEnv()
    const { writer, mod } = await crew(env)
    expect(code(() => createPost(env.ctx, mod, { texts: { en: EN } }))).toBe('missing_permission')
    const p = createPost(env.ctx, writer, { texts: { en: EN } })
    expect(p).toMatchObject({ slug: 'hello-world', state: 'draft', rev: 1, author: { name: 'Writer' } })
    expect(publicNews(env.ctx)).toEqual([])
    expect(code(() => publishPost(env.ctx, writer, p.id, { rev: 1 }))).toBe('missing_permission')

    // Geplant: erst nach dem Zeitpunkt öffentlich (ohne Hintergrund-Job).
    const at = new Date(env.clock.t + 2 * HOUR).toISOString()
    const s = publishPost(env.ctx, OWNER, p.id, { rev: 1, at })
    expect(s.state).toBe('scheduled')
    expect(publicNews(env.ctx)).toEqual([])
    expect(publicNewsPost(env.ctx, 'hello-world')).toBeNull()
    // Geplante/veröffentlichte Beiträge ändern nur Publisher.
    expect(code(() => updatePost(env.ctx, writer, p.id, { rev: s.rev, texts: { en: { ...EN, title: 'Changed' } } }))).toBe('missing_permission')
    env.clock.advance(2 * HOUR + 1)
    expect(publicNews(env.ctx).map((n) => n.slug)).toEqual(['hello-world'])
    expect(publicNewsPost(env.ctx, 'hello-world')).toMatchObject({ kind: 'news', langs: ['en'], title: { en: 'Hello world' }, summary: { en: 'A short text.' } })

    const back = unpublishPost(env.ctx, OWNER, p.id, s.rev)
    expect(back.state).toBe('draft')
    expect(publicNews(env.ctx)).toEqual([])
    expect(code(() => unpublishPost(env.ctx, OWNER, p.id, s.rev))).toBe('stale')
  })

  it('English is required; translations fall back to English and must be complete', async () => {
    const env = makeEnv()
    await crew(env)
    const p = createPost(env.ctx, OWNER, { texts: { de: { title: 'Hallo', summary: '', body: 'Text' } } })
    expect(code(() => publishPost(env.ctx, OWNER, p.id, { rev: p.rev }))).toBe('english_required')
    const q = updatePost(env.ctx, OWNER, p.id, { rev: p.rev, texts: { en: EN, es: { title: 'Hola', summary: '', body: '' } } })
    expect(code(() => publishPost(env.ctx, OWNER, p.id, { rev: q.rev }))).toBe('translation_incomplete')
    // Ganz leere Sprache = entfernt.
    const r = updatePost(env.ctx, OWNER, p.id, { rev: q.rev, texts: { es: { title: '', summary: '', body: '' } } })
    expect(Object.keys(r.texts).sort()).toEqual(['de', 'en'])
    const cur = publishPost(env.ctx, OWNER, p.id, { rev: r.rev })
    const post = publicNewsPost(env.ctx, r.slug)!
    expect(post.langs).toEqual(['en', 'de'])
    expect(post.markdown).toEqual({ en: EN.body, de: 'Text' })
    // Kein Kurztext → aus dem Markdown (ohne Bilder/Formatierung).
    expect(post.summary.de).toBe('Text')
    expect(summaryFromBody('# Head\n\nSome **bold** [link](https://x.test) and ![img](/a.png) text')).toBe('Head Some bold link and text')
    // Veröffentlicht: Englisch kann nicht mehr entfernt werden.
    expect(code(() => updatePost(env.ctx, OWNER, p.id, { rev: cur.rev, texts: { en: { title: '', summary: '', body: '' } } }))).toBe('english_required')
  })

  it('slugs: from the title, unique, never version-like; stale revisions are refused', async () => {
    const env = makeEnv()
    await crew(env)
    expect(slugify('Größer, schöner – über 9000!')).toBe('grosser-schoner-uber-9000')
    expect(slugify('0.6.5')).toBe('')
    const a = createPost(env.ctx, OWNER, { texts: { en: EN } })
    const b = createPost(env.ctx, OWNER, { texts: { en: EN } })
    expect([a.slug, b.slug]).toEqual(['hello-world', 'hello-world-2'])
    expect(code(() => createPost(env.ctx, OWNER, { slug: 'hello-world', texts: { en: EN } }))).toBe('slug_taken')
    expect(code(() => updatePost(env.ctx, OWNER, b.id, { rev: b.rev, slug: 'hello-world' }))).toBe('slug_taken')
    const c = createPost(env.ctx, OWNER, {})
    expect(c.slug).toMatch(/^post-[0-9a-f]{6}$/)
    const b2 = updatePost(env.ctx, OWNER, b.id, { rev: b.rev, slug: 'second-post' })
    expect(b2.rev).toBe(2)
    expect(code(() => updatePost(env.ctx, OWNER, b.id, { rev: 1, slug: 'x-post' }))).toBe('stale')
    // Autor nur aus dem Team.
    const p = await login(env, 'Player')
    expect(code(() => updatePost(env.ctx, OWNER, b.id, { rev: b2.rev, author: p.user.uuid }))).toBe('invalid_author')
    expect(blogAuthors(env.ctx).map((x) => x.name)).toEqual(['Moddy', 'Theredstonee', 'Writer'])
  })

  it('images: re-encoded, hidden until the post is public, cover, deleted with the post', async () => {
    const env = makeEnv()
    const { writer } = await crew(env)
    const p = createPost(env.ctx, writer, { texts: { en: EN } })
    const img = await uploadMedia(env.ctx, writer, p.id, solidPng(3000, 1000), 'image/png')
    expect(img).toMatchObject({ width: 2400, height: 800 })
    expect(img.url).toMatch(/^\/v1\/site\/blog\/media\/[A-Za-z0-9_-]{22}\.jpg$/)
    expect(img.thumbUrl).toMatch(/\.t\.jpg$/)
    expect(await codeAsync(() => uploadMedia(env.ctx, writer, p.id, solidPng(8, 8), 'image/jpeg'))).toBe('unsupported_media_type')
    expect(await codeAsync(() => uploadMedia(env.ctx, writer, p.id, Buffer.from('not an image'), 'image/png'))).toBe('unsupported_media_type')
    expect(mediaForServe(env.ctx, img.id)!.public).toBe(false)
    const withCover = updatePost(env.ctx, writer, p.id, { rev: p.rev, coverId: img.id })
    expect(withCover.cover?.id).toBe(img.id)
    expect(code(() => updatePost(env.ctx, writer, p.id, { rev: withCover.rev, coverId: 'B'.repeat(22) }))).toBe('invalid_cover')
    const pub = publishPost(env.ctx, OWNER, p.id, { rev: withCover.rev })
    expect(mediaForServe(env.ctx, img.id)!.public).toBe(true)
    expect(publicNews(env.ctx)[0]!.cover).toMatchObject({ url: img.url, width: 2400 })
    // Veröffentlicht: Bilder ändern nur Publisher.
    expect(code(() => deleteMedia(env.ctx, writer, p.id, img.id))).toBe('missing_permission')
    const files = () => readdirSync(join(blogDir(env.ctx), img.id.slice(0, 2)))
    expect(files()).toHaveLength(2)
    deletePost(env.ctx, OWNER, pub.id)
    expect(existsSync(join(blogDir(env.ctx), img.id.slice(0, 2))) ? files() : []).toHaveLength(0)
    expect(mediaForServe(env.ctx, img.id)).toBeNull()
  })

  it('launcher feed: newest first with text; the sitemap lists news only in their languages', async () => {
    const env = makeEnv()
    await crew(env)
    const a = createPost(env.ctx, OWNER, { texts: { en: EN } })
    publishPost(env.ctx, OWNER, a.id, { rev: a.rev })
    env.clock.advance(HOUR)
    const b = createPost(env.ctx, OWNER, { slug: 'second', texts: { en: { ...EN, title: 'Second' }, es: { title: 'Segundo', summary: '', body: 'Hola' } } })
    publishPost(env.ctx, OWNER, b.id, { rev: b.rev })
    const feed = publicNewsFull(env.ctx, 10)
    expect(feed.map((p) => p.slug)).toEqual(['second', 'hello-world'])
    expect(feed[0]!.markdown.es).toBe('Hola')
    const xml = buildSitemap('https://site.test', [], null, [], [], publicNews(env.ctx).map((n) => ({ slug: n.slug, updatedAt: n.updatedAt, langs: n.langs })))
    expect(xml).toContain('<loc>https://site.test/blog/second</loc>')
    expect(xml).toContain('<loc>https://site.test/blog/second?lang=es</loc>')
    expect(xml).not.toContain('<loc>https://site.test/blog/second?lang=de</loc>')
    expect(xml).not.toContain('<loc>https://site.test/blog/hello-world?lang=es</loc>')
  })
})

describe('blog: markdown stays safe', () => {
  it('only own blog images, no raw HTML, no script links', () => {
    const md = [
      '![ok](/v1/site/blog/media/AAAAAAAAAAAAAAAAAAAAAA.jpg)',
      '![evil](https://evil.example/x.png)',
      '![rel](/v1/site/blog/media/../../x.png)',
      '<script>alert(1)</script><iframe src="https://evil.example"></iframe>',
      '[click](javascript:alert(1)) [web](https://example.com)',
      '![q"x](/v1/site/blog/media/AAAAAAAAAAAAAAAAAAAAAA.t.png "t<i>")',
    ].join('\n\n')
    const html = renderMarkdown(md, { blogImages: true })
    expect(html).toContain('<img src="/v1/site/blog/media/AAAAAAAAAAAAAAAAAAAAAA.jpg" alt="ok" loading="lazy" decoding="async">')
    expect(html).not.toContain('evil.example/x.png')
    expect(html).not.toContain('<script')
    expect(html).not.toContain('<iframe')
    expect(html).not.toContain('javascript:')
    expect(html).toContain('<a href="https://example.com" rel="noopener nofollow" target="_blank">web</a>')
    expect(html).toContain('alt="q&quot;x" title="t&lt;i&gt;"')
    // Ohne blogImages (Update-Beiträge, Datenschutz) bleiben Bilder Text.
    expect(renderMarkdown(md)).not.toContain('<img')
  })
})

describe('blog: HTTP', () => {
  const open: { close: () => Promise<void> }[] = []
  afterEach(async () => {
    for (const s of open.splice(0)) await s.close()
    setContext(undefined)
    vi.unstubAllGlobals()
  })

  async function serve(env: TestEnv) {
    const app = createApp({
      onError: async (error, event) => {
        const api = isApiError(error) ? error : isApiError((error as { cause?: unknown }).cause) ? (error as { cause: never }).cause : null
        setResponseStatus(event, (api as { status?: number } | null)?.status ?? 500)
        if ((api as { headers?: Record<string, string> } | null)?.headers) setResponseHeaders(event, (api as { headers: Record<string, string> }).headers)
        await send(event, JSON.stringify({ error: { code: (api as { code?: string } | null)?.code ?? 'internal_error' } }), 'application/json')
      },
    })
    const router = createRouter()
    router.get('/v1/site/blog/media/:file', mediaRoute)
    router.get('/v1/site/blog', blogIndex)
    app.use(router)
    const server: Server = createServer(toNodeListener(app))
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
    open.push({ close: () => new Promise<void>((r) => server.close(() => r())) })
    setContext(env.ctx)
    return `http://127.0.0.1:${(server.address() as { port: number }).port}`
  }

  it('draft images only for the blog team; public ones are cacheable; mixed list has updates + news', async () => {
    const env = makeEnv({ env: { TRUST_PROXY: 'none' } })
    const { writer, writerUuid } = await crew(env)
    const base = await serve(env)
    const p = createPost(env.ctx, writer, { texts: { en: EN } })
    const img = await uploadMedia(env.ctx, writer, p.id, solidPng(64, 36), 'image/png')
    expect((await fetch(`${base}${img.url}`)).status).toBe(404)
    const staffCookie = `trs_session=${createWebSession(env.ctx, writerUuid).token}`
    const player = await login(env, 'Player')
    const playerCookie = `trs_session=${createWebSession(env.ctx, player.user.uuid).token}`
    expect((await fetch(`${base}${img.url}`, { headers: { cookie: playerCookie } })).status).toBe(404)
    const draft = await fetch(`${base}${img.url}`, { headers: { cookie: staffCookie } })
    expect(draft.status).toBe(200)
    expect(draft.headers.get('cache-control')).toBe('private, no-store')
    // Falsche Endung / Pfad-Tricks → 404.
    expect((await fetch(`${base}${img.url.replace('.jpg', '.png')}`, { headers: { cookie: staffCookie } })).status).toBe(404)
    expect((await fetch(`${base}/v1/site/blog/media/..%2F..%2Fsecret.jpg`)).status).toBe(404)

    publishPost(env.ctx, OWNER, p.id, { rev: p.rev })
    const pub = await fetch(`${base}${img.thumbUrl}`)
    expect(pub.status).toBe(200)
    expect(pub.headers.get('content-type')).toBe('image/jpeg')
    expect(pub.headers.get('cache-control')).toContain('immutable')
    expect(pub.headers.get('x-content-type-options')).toBe('nosniff')

    // GitHub (CHANGELOG) nicht erreichbar → Updates leer, News trotzdem da.
    const realFetch = globalThis.fetch
    vi.stubGlobal('fetch', async (url: string | URL, init?: RequestInit) => {
      if (String(url).startsWith(base)) return realFetch(url, init)
      throw new Error('offline')
    })
    const list = await (await fetch(`${base}/v1/site/blog`)).json() as { posts: unknown[], news: { slug: string, kind: string }[] }
    expect(list.posts).toEqual([])
    expect(list.news.map((n) => [n.kind, n.slug])).toEqual([['news', 'hello-world']])
  })
})

describe('migration 18', () => {
  it('creates the tables, grants blog rights to owner/admin/content only and is idempotent', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    db.exec('CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)')
    // Stand 16 herstellen; danach eine Standardrolle von Hand anpassen (muss erhalten bleiben).
    for (const m of MIGRATIONS.filter((x) => x.version <= 16)) {
      if (m.sql) db.exec(m.sql)
      m.run?.(db)
      db.prepare('INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)').run(m.version, 1)
    }
    const perms = (id: string) => JSON.parse((db.prepare('SELECT permissions FROM team_roles WHERE id = ?').get(id) as { permissions: string }).permissions) as string[]
    // Ältere Datenbank: Rechte ohne blog.* (so wie vor diesem Update gespeichert).
    for (const id of ['owner', 'admin', 'content', 'moderator']) {
      db.prepare('UPDATE team_roles SET permissions = ? WHERE id = ?').run(JSON.stringify(perms(id).filter((p) => !p.startsWith('blog.'))), id)
    }
    db.prepare('UPDATE team_roles SET permissions = ? WHERE id = ?').run(JSON.stringify(['dashboard.view']), 'content')
    expect(migrate(db)).toBe(MIGRATIONS.filter((x) => x.version > 16).length)
    for (const t of ['launcher_logins', 'blog_posts', 'blog_media']) {
      expect(db.prepare("SELECT 1 AS x FROM sqlite_master WHERE type = 'table' AND name = ?").get(t)).toBeTruthy()
    }
    expect(perms('admin')).toEqual(expect.arrayContaining(['blog.write', 'blog.publish']))
    expect(perms('owner')).toEqual(expect.arrayContaining(['blog.write', 'blog.publish']))
    expect(perms('content')).toEqual(['dashboard.view', 'blog.write'])
    expect(perms('moderator')).not.toContain('blog.write')
    migrateLauncherLoginBlog(db)
    migrateLauncherLoginBlog(db)
    expect(perms('admin').filter((p) => p === 'blog.write')).toHaveLength(1)
    expect(perms('content')).toEqual(['dashboard.view', 'blog.write'])
  })
})
