import { randomBytes } from 'node:crypto'
import { readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { z } from 'zod'
import { writeFileAtomic } from './attachments'
import { audit } from './audit'
import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { ApiError, badRequest, conflict, notFound } from './errors'
import { sha256Hex } from './ids'
import { processImage, type ImageLimits, type OutputMime } from './images'
import { assertCan, can, rankOf, type Staff } from './team'

/**
 * Blog (§30): eigene News-Beiträge des Teams, gemischt mit den automatischen Update-Beiträgen aus CHANGELOG.md
 * (die bleiben unverändert in `site.ts`).
 *
 * - Texte je Sprache (EN Pflicht zum Veröffentlichen, DE/ES optional – fehlt eine Sprache, gilt Englisch):
 *   Titel, Kurztext (Karten/SEO) und Markdown. Markdown wird nur über `app/utils/markdown.ts` gerendert (kein rohes
 *   HTML, Links nur http(s)/eigene, Bilder nur aus dem eigenen Blog-Speicher).
 * - Zustand: Entwurf, geplant (veröffentlicht mit Zeitpunkt in der Zukunft) oder veröffentlicht. Öffentlich ist ein
 *   Beitrag, sobald `status = 'published' AND publish_at <= jetzt` – ohne Hintergrund-Job.
 * - Bilder: PNG/JPEG/WebP hoch, immer neu kodiert (keine Metadaten), in `<DATA_DIR>/blog/<xx>/<id>.<ext>`; öffentlich
 *   ausgeliefert, sobald ihr Beitrag öffentlich ist – vorher nur für das Team.
 * - Rechte: `blog.write` (Entwürfe schreiben, Bilder) und `blog.publish` (veröffentlichen, planen, zurückziehen,
 *   veröffentlichte Beiträge ändern/löschen).
 */

export const BLOG_LANGS = ['en', 'de', 'es'] as const
export type BlogLang = (typeof BLOG_LANGS)[number]

export const BLOG_ID = /^[A-Za-z0-9_-]{22}$/
/** Kleinbuchstaben/Ziffern mit Bindestrichen, mindestens ein Buchstabe – so nie mit einer Version (`0.6.5`) verwechselbar. */
export const BLOG_SLUG = /^(?=[a-z0-9-]*[a-z])[a-z0-9]+(?:-[a-z0-9]+)*$/
export const MAX_TITLE = 120
export const MAX_SUMMARY = 300
export const MAX_BODY = 40_000
export const MAX_MEDIA_PER_POST = 40
export const MAX_BLOG_POSTS = 2000
export const MAX_BLOG_IMAGE_BYTES = 8 * 1024 * 1024
/** Gesamter Blog-Bildspeicher. */
export const BLOG_STORAGE_MAX_BYTES = 2 * 1024 * 1024 * 1024
/** Weiter als so weit in der Zukunft lässt sich nicht planen. */
const MAX_SCHEDULE_MS = 366 * 24 * 60 * 60 * 1000

export const BLOG_IMAGE_LIMITS: ImageLimits = {
  maxBytes: MAX_BLOG_IMAGE_BYTES,
  maxOutputEdge: 2400,
  quality: 86,
  thumbEdge: 720,
  thumbQuality: 78,
}

/** Öffentlicher Pfad der Bilder: `/v1/site/blog/media/<id>.<jpg|png>` (Vorschau `<id>.t.<ext>`). */
export const BLOG_MEDIA_PATH = '/v1/site/blog/media/'
export const BLOG_MEDIA_FILE = /^([A-Za-z0-9_-]{22})(\.t)?\.(jpg|png)$/

const MAX_PARALLEL = 2
let running = 0

// ---------------------------------------------------------------- Eingaben (zod)

// eslint-disable-next-line no-control-regex -- Steuerzeichen außer Tab/Zeilenumbruch fliegen raus
const CONTROL = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f\u200b-\u200f\u202a-\u202e\u2066-\u2069]/g
const oneLine = (s: string) => s.replace(CONTROL, '').replace(/[\r\n\t]+/g, ' ').replace(/\s+/g, ' ').trim()
const multiLine = (s: string) => s.replace(/\r\n?/g, '\n').replace(CONTROL, '').replace(/\s+$/, '')

const textInput = z
  .object({
    title: z.string().max(MAX_TITLE * 2).transform(oneLine).pipe(z.string().max(MAX_TITLE)),
    summary: z.string().max(MAX_SUMMARY * 2).transform(oneLine).pipe(z.string().max(MAX_SUMMARY)).default(''),
    body: z.string().max(MAX_BODY + 2000).transform(multiLine).pipe(z.string().max(MAX_BODY)).default(''),
  })
  .strict()
/** Texte einer Sprache (schlichtes Interface statt zod-Typ – hält die Routen-Typen von Nitro flach). */
export interface BlogText {
  title: string
  summary: string
  body: string
}
export type BlogTexts = Partial<Record<BlogLang, BlogText>>

const textsInput = z.object({ en: textInput.optional(), de: textInput.optional(), es: textInput.optional() }).strict()
const slugInput = z.string().trim().toLowerCase().min(3).max(80).regex(BLOG_SLUG)
const mediaRef = z.string().regex(BLOG_ID)
const uuidRef = z.string().regex(/^[0-9a-f]{32}$/)

export const blogCreateBody = z
  .object({
    slug: slugInput.optional(),
    texts: textsInput.optional(),
    author: uuidRef.nullable().optional(),
  })
  .strict()

export const blogPatchBody = z
  .object({
    rev: z.number().int().min(1),
    slug: slugInput.optional(),
    texts: textsInput.optional(),
    coverId: mediaRef.nullable().optional(),
    author: uuidRef.nullable().optional(),
  })
  .strict()

export const blogPublishBody = z
  .object({
    rev: z.number().int().min(1),
    /** Zeitpunkt; fehlt/`null`/Vergangenheit = sofort. */
    at: z.iso.datetime({ offset: true }).nullable().optional(),
  })
  .strict()

export const blogRevBody = z.object({ rev: z.number().int().min(1) }).strict()

// ---------------------------------------------------------------- Zeilen + Ansichten

export interface BlogPostRow {
  id: string
  slug: string
  status: 'draft' | 'published'
  publish_at: number | null
  texts: string
  cover_id: string | null
  author_uuid: string | null
  rev: number
  created_at: number
  created_by: string
  updated_at: number
  updated_by: string
}

export interface BlogMediaRow {
  id: string
  post_id: string
  mime: OutputMime
  width: number
  height: number
  bytes: number
  thumb_mime: OutputMime
  thumb_width: number
  thumb_height: number
  thumb_bytes: number
  sha256: string
  uploaded_by: string | null
  created_at: number
}

export type BlogState = 'draft' | 'scheduled' | 'published'

export interface BlogMediaView {
  id: string
  /** Relativ zur Website (gleiche Herkunft). */
  url: string
  thumbUrl: string
  width: number
  height: number
  bytes: number
  createdAt: string
}

export interface NewsAuthor {
  uuid: string
  name: string
  /** Letzte bekannte Skin-Adresse (textures.minecraft.net) für den Kopf, sonst `null`. */
  skin: string | null
}

export interface NewsCover {
  url: string
  thumbUrl: string
  width: number
  height: number
}

/** Karte in der Blog-Liste / im Launcher. Texte nur für vorhandene Sprachen (Englisch immer). */
export interface NewsSummary {
  kind: 'news'
  slug: string
  /** ISO – Zeitpunkt der Veröffentlichung. */
  publishedAt: string
  updatedAt: string
  langs: BlogLang[]
  title: Partial<Record<BlogLang, string>>
  summary: Partial<Record<BlogLang, string>>
  cover: NewsCover | null
  author: NewsAuthor | null
}

export interface NewsPost extends NewsSummary {
  markdown: Partial<Record<BlogLang, string>>
}

const iso = (t: number) => new Date(t).toISOString()
const ext = (m: OutputMime) => (m === 'image/png' ? 'png' : 'jpg')

export function blogDir(ctx: AppContext): string {
  return join(ctx.config.dataDir, 'blog')
}

function mediaFile(ctx: AppContext, r: Pick<BlogMediaRow, 'id' | 'mime' | 'thumb_mime'>, thumb: boolean): string {
  return join(blogDir(ctx), r.id.slice(0, 2), `${r.id}${thumb ? '.t' : ''}.${ext(thumb ? r.thumb_mime : r.mime)}`)
}

export function mediaView(r: BlogMediaRow): BlogMediaView {
  return {
    id: r.id,
    url: `${BLOG_MEDIA_PATH}${r.id}.${ext(r.mime)}`,
    thumbUrl: `${BLOG_MEDIA_PATH}${r.id}.t.${ext(r.thumb_mime)}`,
    width: r.width,
    height: r.height,
    bytes: r.bytes,
    createdAt: iso(r.created_at),
  }
}

export function stateOf(r: Pick<BlogPostRow, 'status' | 'publish_at'>, now: number): BlogState {
  if (r.status === 'draft' || r.publish_at === null) return 'draft'
  return r.publish_at > now ? 'scheduled' : 'published'
}

export function isPublic(r: Pick<BlogPostRow, 'status' | 'publish_at'>, now: number): boolean {
  return stateOf(r, now) === 'published'
}

/** Gespeicherte Texte (JSON) lesen – kaputte/fremde Einträge fallen weg. */
export function parseTexts(raw: string): BlogTexts {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return {}
  }
  const out: BlogTexts = {}
  if (!parsed || typeof parsed !== 'object') return out
  for (const l of BLOG_LANGS) {
    const r = textInput.safeParse((parsed as Record<string, unknown>)[l])
    if (r.success) out[l] = r.data
  }
  return out
}

/** Eine Sprache zählt, wenn sie Titel und Text hat. */
function complete(t: BlogText | undefined): t is BlogText {
  return !!t && t.title.length > 0 && t.body.trim().length > 0
}

/** Sprachen, die öffentlich erscheinen (Englisch vorne). */
export function publicLangs(texts: BlogTexts): BlogLang[] {
  return BLOG_LANGS.filter((l) => complete(texts[l]))
}

/** Kurztext aus dem Markdown, wenn keiner eingetragen ist (Karten, SEO). */
export function summaryFromBody(body: string, max = 200): string {
  const text = body
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/^>\s?/gm, '')
    .replace(/^\s*[-*+]\s+/gm, '')
    .replace(/[*_`~]+/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  if (text.length <= max) return text
  const cut = text.slice(0, max - 1)
  const space = cut.lastIndexOf(' ')
  return `${(space > max * 0.6 ? cut.slice(0, space) : cut).replace(/[\s,.;:–-]+$/, '')}…`
}

function authorOf(ctx: AppContext, uuid: string | null): NewsAuthor | null {
  if (!uuid) return null
  const u = one<{ name: string, skin_url: string | null }>(ctx.db, 'SELECT name, skin_url FROM users WHERE uuid = ?', uuid)
  if (!u) return null
  return { uuid, name: u.name, skin: u.skin_url && /^https:\/\/textures\.minecraft\.net\/texture\/[0-9a-f]{32,64}$/.test(u.skin_url) ? u.skin_url : null }
}

function coverOf(ctx: AppContext, r: BlogPostRow): NewsCover | null {
  if (!r.cover_id) return null
  const m = one<BlogMediaRow>(ctx.db, 'SELECT * FROM blog_media WHERE id = ? AND post_id = ?', r.cover_id, r.id)
  if (!m) return null
  const v = mediaView(m)
  return { url: v.url, thumbUrl: v.thumbUrl, width: v.width, height: v.height }
}

export function newsSummary(ctx: AppContext, r: BlogPostRow): NewsSummary {
  const texts = parseTexts(r.texts)
  const langs = publicLangs(texts)
  const title: NewsSummary['title'] = {}
  const summary: NewsSummary['summary'] = {}
  for (const l of langs) {
    const t = texts[l]!
    title[l] = t.title
    summary[l] = t.summary || summaryFromBody(t.body)
  }
  return {
    kind: 'news',
    slug: r.slug,
    publishedAt: iso(r.publish_at ?? r.updated_at),
    updatedAt: iso(Math.max(r.updated_at, r.publish_at ?? 0)),
    langs,
    title,
    summary,
    cover: coverOf(ctx, r),
    author: authorOf(ctx, r.author_uuid),
  }
}

export function newsPost(ctx: AppContext, r: BlogPostRow): NewsPost {
  const s = newsSummary(ctx, r)
  const texts = parseTexts(r.texts)
  const markdown: NewsPost['markdown'] = {}
  for (const l of s.langs) markdown[l] = texts[l]!.body
  return { ...s, markdown }
}

// ---------------------------------------------------------------- öffentlich

/** Öffentliche Beiträge, neueste zuerst. */
export function publicNews(ctx: AppContext, limit = 200): NewsSummary[] {
  return all<BlogPostRow>(
    ctx.db,
    "SELECT * FROM blog_posts WHERE status = 'published' AND publish_at <= ? ORDER BY publish_at DESC, id LIMIT ?",
    ctx.now(), Math.max(1, Math.min(limit, 500)),
  ).map((r) => newsSummary(ctx, r)).filter((s) => s.langs.includes('en'))
}

/** Die neuesten öffentlichen Beiträge mit Text (Launcher-Neuigkeiten). */
export function publicNewsFull(ctx: AppContext, limit = 10): NewsPost[] {
  return all<BlogPostRow>(
    ctx.db,
    "SELECT * FROM blog_posts WHERE status = 'published' AND publish_at <= ? ORDER BY publish_at DESC, id LIMIT ?",
    ctx.now(), Math.max(1, Math.min(limit, 20)),
  ).map((r) => newsPost(ctx, r)).filter((p) => p.langs.includes('en'))
}

export function publicNewsPost(ctx: AppContext, slug: string): NewsPost | null {
  if (!BLOG_SLUG.test(slug) || slug.length > 80) return null
  const r = one<BlogPostRow>(ctx.db, "SELECT * FROM blog_posts WHERE slug = ? AND status = 'published' AND publish_at <= ?", slug, ctx.now())
  if (!r) return null
  const p = newsPost(ctx, r)
  return p.langs.includes('en') ? p : null
}

/** Bild zum Ausliefern: `public` = der Beitrag ist öffentlich (sonst nur für das Team). */
export function mediaForServe(ctx: AppContext, id: string): { row: BlogMediaRow, public: boolean } | null {
  if (!BLOG_ID.test(id)) return null
  const row = one<BlogMediaRow>(ctx.db, 'SELECT * FROM blog_media WHERE id = ?', id)
  if (!row) return null
  const post = one<Pick<BlogPostRow, 'status' | 'publish_at'>>(ctx.db, 'SELECT status, publish_at FROM blog_posts WHERE id = ?', row.post_id)
  return { row, public: !!post && isPublic(post, ctx.now()) }
}

export function readMedia(ctx: AppContext, row: BlogMediaRow, thumb: boolean): Buffer {
  return readFileSync(mediaFile(ctx, row, thumb))
}

// ---------------------------------------------------------------- Team

export interface StaffRef {
  uuid: string
  name: string | null
}

export interface AdminBlogPost {
  id: string
  slug: string
  state: BlogState
  publishAt: string | null
  texts: BlogTexts
  cover: BlogMediaView | null
  author: NewsAuthor | null
  rev: number
  createdAt: string
  createdBy: StaffRef
  updatedAt: string
  updatedBy: StaffRef
  media: BlogMediaView[]
  /** Öffentlicher Pfad (`/blog/<slug>`). */
  path: string
}

export interface AdminBlogListItem {
  id: string
  slug: string
  state: BlogState
  publishAt: string | null
  titles: Partial<Record<BlogLang, string>>
  langs: BlogLang[]
  cover: BlogMediaView | null
  author: NewsAuthor | null
  updatedAt: string
  updatedBy: StaffRef
  path: string
}

function staffRef(ctx: AppContext, uuid: string): StaffRef {
  const name = /^[0-9a-f]{32}$/.test(uuid) ? one<{ name: string }>(ctx.db, 'SELECT name FROM users WHERE uuid = ?', uuid)?.name ?? null : null
  return { uuid, name }
}

function mediaOf(ctx: AppContext, postId: string): BlogMediaRow[] {
  return all<BlogMediaRow>(ctx.db, 'SELECT * FROM blog_media WHERE post_id = ? ORDER BY created_at, id', postId)
}

export function adminView(ctx: AppContext, r: BlogPostRow): AdminBlogPost {
  const media = mediaOf(ctx, r.id)
  const cover = r.cover_id ? media.find((m) => m.id === r.cover_id) : undefined
  return {
    id: r.id,
    slug: r.slug,
    state: stateOf(r, ctx.now()),
    publishAt: r.publish_at === null ? null : iso(r.publish_at),
    texts: parseTexts(r.texts),
    cover: cover ? mediaView(cover) : null,
    author: authorOf(ctx, r.author_uuid),
    rev: r.rev,
    createdAt: iso(r.created_at),
    createdBy: staffRef(ctx, r.created_by),
    updatedAt: iso(r.updated_at),
    updatedBy: staffRef(ctx, r.updated_by),
    media: media.map(mediaView),
    path: `/blog/${r.slug}`,
  }
}

export function adminList(ctx: AppContext): AdminBlogListItem[] {
  const rows = all<BlogPostRow>(
    ctx.db,
    'SELECT * FROM blog_posts ORDER BY CASE WHEN status = \'draft\' THEN 0 ELSE 1 END, COALESCE(publish_at, updated_at) DESC, id LIMIT 500',
  )
  return rows.map((r) => {
    const texts = parseTexts(r.texts)
    const titles: AdminBlogListItem['titles'] = {}
    for (const l of BLOG_LANGS) if (texts[l]?.title) titles[l] = texts[l]!.title
    const cover = r.cover_id ? one<BlogMediaRow>(ctx.db, 'SELECT * FROM blog_media WHERE id = ? AND post_id = ?', r.cover_id, r.id) : undefined
    return {
      id: r.id,
      slug: r.slug,
      state: stateOf(r, ctx.now()),
      publishAt: r.publish_at === null ? null : iso(r.publish_at),
      titles,
      langs: publicLangs(texts),
      cover: cover ? mediaView(cover) : null,
      author: authorOf(ctx, r.author_uuid),
      updatedAt: iso(r.updated_at),
      updatedBy: staffRef(ctx, r.updated_by),
      path: `/blog/${r.slug}`,
    }
  })
}

export function getPostRow(ctx: AppContext, id: string): BlogPostRow {
  const r = BLOG_ID.test(id) ? one<BlogPostRow>(ctx.db, 'SELECT * FROM blog_posts WHERE id = ?', id) : undefined
  if (!r) throw notFound('post_not_found', 'No such post')
  return r
}

/** Autoren zur Auswahl: Team-Mitglieder und Owner mit Konto. */
export function blogAuthors(ctx: AppContext): NewsAuthor[] {
  const uuids = new Set(all<{ uuid: string }>(ctx.db, 'SELECT DISTINCT uuid FROM team_members').map((r) => r.uuid))
  for (const u of ctx.config.adminUuids) uuids.add(u)
  return [...uuids]
    .map((u) => authorOf(ctx, u))
    .filter((a): a is NewsAuthor => a !== null)
    .sort((a, b) => a.name.localeCompare(b.name))
}

function assertAuthor(ctx: AppContext, uuid: string | null | undefined): void {
  if (!uuid) return
  if (rankOf(ctx, uuid) <= 0 || !one(ctx.db, 'SELECT 1 AS x FROM users WHERE uuid = ?', uuid)) {
    throw badRequest('invalid_author', 'The author must be a member of the team')
  }
}

/** „Hallo, Welt! Über uns“ → „hallo-welt-uber-uns“ (Umlaute ohne Punkte, höchstens 60 Zeichen). */
export function slugify(title: string): string {
  const base = title
    .normalize('NFKD')
    .replace(/ß/g, 'ss')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60)
    .replace(/-+$/g, '')
  return BLOG_SLUG.test(base) && base.length >= 3 ? base : ''
}

/** Freien Slug finden (`name`, `name-2`, …). */
function freeSlug(ctx: AppContext, wanted: string, exceptId?: string): string {
  for (let i = 1; i < 1000; i++) {
    const s = i === 1 ? wanted : `${wanted.slice(0, 75)}-${i}`
    const hit = one<{ id: string }>(ctx.db, 'SELECT id FROM blog_posts WHERE slug = ?', s)
    if (!hit || hit.id === exceptId) return s
  }
  throw conflict('slug_taken', 'This address is already used by another post')
}

function mergeTexts(current: BlogTexts, patch: z.output<typeof textsInput> | undefined): BlogTexts {
  if (!patch) return current
  const out: BlogTexts = { ...current }
  for (const l of BLOG_LANGS) {
    const t = patch[l]
    if (t === undefined) continue
    // Ganz leere Sprache = entfernen.
    if (!t.title && !t.summary && !t.body.trim()) delete out[l]
    else out[l] = t
  }
  return out
}

export function createPost(ctx: AppContext, staff: Staff, input: z.output<typeof blogCreateBody>): AdminBlogPost {
  assertCan(staff, 'blog.write')
  const n = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM blog_posts')!.n
  if (n >= MAX_BLOG_POSTS) throw conflict('blog_full', 'The blog has reached its maximum number of posts')
  assertAuthor(ctx, input.author)
  const texts = mergeTexts({}, input.texts)
  const wanted = input.slug ?? (slugify(texts.en?.title ?? '') || `post-${randomBytes(3).toString('hex')}`)
  const t = ctx.now()
  const id = randomBytes(16).toString('base64url')
  const author = input.author === undefined ? (/^[0-9a-f]{32}$/.test(staff.uuid) && one(ctx.db, 'SELECT 1 AS x FROM users WHERE uuid = ?', staff.uuid) ? staff.uuid : null) : input.author
  tx(ctx.db, () => {
    const slug = input.slug ? (one(ctx.db, 'SELECT 1 AS x FROM blog_posts WHERE slug = ?', wanted) ? null : wanted) : freeSlug(ctx, wanted)
    if (!slug) throw conflict('slug_taken', 'This address is already used by another post')
    run(
      ctx.db,
      `INSERT INTO blog_posts (id, slug, status, publish_at, texts, cover_id, author_uuid, rev, created_at, created_by, updated_at, updated_by)
       VALUES (?, ?, 'draft', NULL, ?, NULL, ?, 1, ?, ?, ?, ?)`,
      id, slug, JSON.stringify(texts), author, t, staff.uuid, t, staff.uuid,
    )
    audit(ctx, staff.uuid, 'blog.create', id, slug)
  })
  return adminView(ctx, getPostRow(ctx, id))
}

function assertRev(r: BlogPostRow, rev: number): void {
  if (r.rev !== rev) throw new ApiError(409, 'stale', 'Someone else changed this post in the meantime – reload it first', { rev: r.rev })
}

/** Veröffentlichte/geplante Beiträge ändern braucht `blog.publish`. */
function assertCanEdit(ctx: AppContext, staff: Staff, r: BlogPostRow): void {
  assertCan(staff, 'blog.write')
  if (stateOf(r, ctx.now()) !== 'draft') assertCan(staff, 'blog.publish')
}

export function updatePost(ctx: AppContext, staff: Staff, id: string, input: z.output<typeof blogPatchBody>): AdminBlogPost {
  const r = getPostRow(ctx, id)
  assertCanEdit(ctx, staff, r)
  assertRev(r, input.rev)
  assertAuthor(ctx, input.author)
  const texts = mergeTexts(parseTexts(r.texts), input.texts)
  if (r.status === 'published' && !complete(texts.en)) {
    throw badRequest('english_required', 'A published post needs an English title and text')
  }
  if (input.coverId && !one(ctx.db, 'SELECT 1 AS x FROM blog_media WHERE id = ? AND post_id = ?', input.coverId, r.id)) {
    throw badRequest('invalid_cover', 'The cover image must be uploaded to this post first')
  }
  const t = ctx.now()
  tx(ctx.db, () => {
    const slug = input.slug ?? r.slug
    if (slug !== r.slug && one(ctx.db, 'SELECT 1 AS x FROM blog_posts WHERE slug = ? AND id <> ?', slug, r.id)) {
      throw conflict('slug_taken', 'This address is already used by another post')
    }
    const changed = run(
      ctx.db,
      `UPDATE blog_posts SET slug = ?, texts = ?, cover_id = ?, author_uuid = ?, rev = rev + 1, updated_at = ?, updated_by = ?
       WHERE id = ? AND rev = ?`,
      slug, JSON.stringify(texts), input.coverId === undefined ? r.cover_id : input.coverId,
      input.author === undefined ? r.author_uuid : input.author, t, staff.uuid, r.id, r.rev,
    )
    if (changed !== 1) throw new ApiError(409, 'stale', 'Someone else changed this post in the meantime – reload it first')
    audit(ctx, staff.uuid, 'blog.update', r.id, slug !== r.slug ? `${r.slug} → ${slug}` : slug)
  })
  return adminView(ctx, getPostRow(ctx, id))
}

/** Veröffentlichen (sofort) oder planen (`at` in der Zukunft). Englisch ist Pflicht, weitere Sprachen nur vollständig. */
export function publishPost(ctx: AppContext, staff: Staff, id: string, input: z.output<typeof blogPublishBody>): AdminBlogPost {
  assertCan(staff, 'blog.publish')
  const r = getPostRow(ctx, id)
  assertRev(r, input.rev)
  const texts = parseTexts(r.texts)
  if (!complete(texts.en)) throw badRequest('english_required', 'Write an English title and text before publishing')
  for (const l of BLOG_LANGS) {
    const x = texts[l]
    if (x && !complete(x)) throw badRequest('translation_incomplete', `The ${l.toUpperCase()} translation needs a title and text (or leave it empty)`, { lang: l })
  }
  const now = ctx.now()
  let at = input.at ? Date.parse(input.at) : now
  if (!Number.isFinite(at) || at < now) at = now
  if (at > now + MAX_SCHEDULE_MS) throw badRequest('invalid_time', 'Posts can be scheduled at most one year ahead')
  tx(ctx.db, () => {
    run(
      ctx.db,
      "UPDATE blog_posts SET status = 'published', publish_at = ?, rev = rev + 1, updated_at = ?, updated_by = ? WHERE id = ?",
      at, now, staff.uuid, r.id,
    )
    audit(ctx, staff.uuid, at > now ? 'blog.schedule' : 'blog.publish', r.id, at > now ? `${r.slug} @ ${iso(at)}` : r.slug)
  })
  return adminView(ctx, getPostRow(ctx, id))
}

/** Zurück zum Entwurf (nicht mehr öffentlich, Planung aufgehoben). */
export function unpublishPost(ctx: AppContext, staff: Staff, id: string, rev: number): AdminBlogPost {
  assertCan(staff, 'blog.publish')
  const r = getPostRow(ctx, id)
  assertRev(r, rev)
  tx(ctx.db, () => {
    run(ctx.db, "UPDATE blog_posts SET status = 'draft', publish_at = NULL, rev = rev + 1, updated_at = ?, updated_by = ? WHERE id = ?", ctx.now(), staff.uuid, r.id)
    audit(ctx, staff.uuid, 'blog.unpublish', r.id, r.slug)
  })
  return adminView(ctx, getPostRow(ctx, id))
}

export function deletePost(ctx: AppContext, staff: Staff, id: string): void {
  const r = getPostRow(ctx, id)
  assertCanEdit(ctx, staff, r)
  const media = mediaOf(ctx, r.id)
  tx(ctx.db, () => {
    run(ctx.db, 'DELETE FROM blog_posts WHERE id = ?', r.id)
    audit(ctx, staff.uuid, 'blog.delete', r.id, r.slug)
  })
  removeMediaFiles(ctx, media)
}

// ---------------------------------------------------------------- Bilder

export function blogStorageUsed(ctx: AppContext): number {
  return one<{ n: number | null }>(ctx.db, 'SELECT SUM(bytes + thumb_bytes) AS n FROM blog_media')!.n ?? 0
}

function assertMediaRoom(ctx: AppContext, postId: string): void {
  const n = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM blog_media WHERE post_id = ?', postId)!.n
  if (n >= MAX_MEDIA_PER_POST) throw conflict('media_limit', `A post can have at most ${MAX_MEDIA_PER_POST} images`)
  if (blogStorageUsed(ctx) >= BLOG_STORAGE_MAX_BYTES) throw new ApiError(507, 'storage_full', 'Image storage is full')
}

/** Bild zu einem Beitrag hochladen (neu kodiert). Bei veröffentlichten Beiträgen braucht es `blog.publish`. */
export async function uploadMedia(ctx: AppContext, staff: Staff, postId: string, body: Buffer, contentType: string): Promise<BlogMediaView> {
  const r = getPostRow(ctx, postId)
  assertCanEdit(ctx, staff, r)
  assertMediaRoom(ctx, r.id)
  if (running >= MAX_PARALLEL) {
    throw new ApiError(503, 'busy', 'The server is busy processing images, try again in a few seconds', { retryAfter: 3 }, { 'Retry-After': '3' })
  }
  running++
  let img: Awaited<ReturnType<typeof processImage>>
  try {
    img = await processImage(body, contentType, BLOG_IMAGE_LIMITS)
  } finally {
    running--
  }
  // Während des Neukodierens könnte der Beitrag gelöscht oder voll geworden sein.
  getPostRow(ctx, postId)
  assertMediaRoom(ctx, r.id)
  const row: BlogMediaRow = {
    id: randomBytes(16).toString('base64url'),
    post_id: r.id,
    mime: img.full.mime,
    width: img.full.width,
    height: img.full.height,
    bytes: img.full.data.length,
    thumb_mime: img.thumb.mime,
    thumb_width: img.thumb.width,
    thumb_height: img.thumb.height,
    thumb_bytes: img.thumb.data.length,
    sha256: sha256Hex(img.full.data),
    uploaded_by: staff.uuid,
    created_at: ctx.now(),
  }
  writeFileAtomic(mediaFile(ctx, row, false), img.full.data)
  writeFileAtomic(mediaFile(ctx, row, true), img.thumb.data)
  try {
    run(
      ctx.db,
      `INSERT INTO blog_media (id, post_id, mime, width, height, bytes, thumb_mime, thumb_width, thumb_height, thumb_bytes, sha256, uploaded_by, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      row.id, row.post_id, row.mime, row.width, row.height, row.bytes, row.thumb_mime, row.thumb_width, row.thumb_height,
      row.thumb_bytes, row.sha256, row.uploaded_by, row.created_at,
    )
  } catch (err) {
    removeMediaFiles(ctx, [row])
    throw err
  }
  return mediaView(row)
}

export function deleteMedia(ctx: AppContext, staff: Staff, postId: string, mediaId: string): void {
  const r = getPostRow(ctx, postId)
  assertCanEdit(ctx, staff, r)
  const m = BLOG_ID.test(mediaId) ? one<BlogMediaRow>(ctx.db, 'SELECT * FROM blog_media WHERE id = ? AND post_id = ?', mediaId, r.id) : undefined
  if (!m) throw notFound('media_not_found', 'No such image')
  tx(ctx.db, () => {
    run(ctx.db, 'DELETE FROM blog_media WHERE id = ?', m.id)
    if (r.cover_id === m.id) run(ctx.db, 'UPDATE blog_posts SET cover_id = NULL, rev = rev + 1, updated_at = ?, updated_by = ? WHERE id = ?', ctx.now(), staff.uuid, r.id)
  })
  removeMediaFiles(ctx, [m])
}

export function removeMediaFiles(ctx: AppContext, rows: Pick<BlogMediaRow, 'id' | 'mime' | 'thumb_mime'>[]): void {
  for (const r of rows) {
    rmSync(mediaFile(ctx, r, false), { force: true })
    rmSync(mediaFile(ctx, r, true), { force: true })
  }
}

/** Darf diese Person Entwürfe (samt Bildern) sehen? */
export function canSeeDrafts(staff: Staff | null): boolean {
  return !!staff && (can(staff, 'blog.write') || can(staff, 'blog.publish'))
}
