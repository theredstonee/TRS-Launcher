import { randomBytes } from 'node:crypto'
import { z } from 'zod'
import { audit } from './audit'
import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { ApiError, badRequest, conflict, notFound, unavailable } from './errors'
import { sha256Hex } from './ids'
import { imageType } from './modrinth'
import { assertCan, type Staff } from './team'

/**
 * Empfohlene Modpacks (§37): Das Team fügt einen Modrinth- oder CurseForge-Link ein; der Server holt Titel, Kurztext,
 * Autor, Downloads, Loader und Versionen von der Plattform und legt Symbol + Banner als Kopie (BLOB) ab – Besucher
 * verbinden sich so nie mit Modrinth/CurseForge, die CSP der Website bleibt streng. Je Pack: eigene Texte (EN/DE),
 * Abzeichen, Anheften, Reihenfolge (Drag & Drop) und ein optionales Zeitfenster.
 *
 * Öffentlich: Startseite des Launchers (Karussell) und der Website. Aktiv = (Start leer oder ≤ jetzt) und (Ende leer
 * oder > jetzt) – ohne Hintergrund-Job. Plattform-Daten älter als 24 h frischt eine öffentliche Abfrage still im
 * Hintergrund auf (nie blockierend, Fehler verschluckt).
 */

export const FEATURED_SOURCES = ['modrinth', 'curseforge'] as const
export type FeaturedSource = (typeof FEATURED_SOURCES)[number]
export const FEATURED_BADGES = ['recommended', 'new', 'event'] as const
export type FeaturedBadge = (typeof FEATURED_BADGES)[number]
export const FEATURED_LANGS = ['en', 'de'] as const
export type FeaturedLang = (typeof FEATURED_LANGS)[number]

export const FEATURED_ID = /^fp_[a-z0-9]{12}$/
export const MAX_TITLE = 60
export const MAX_TEXT = 200
export const MAX_SUMMARY = 300
/** Öffentlich höchstens so viele Packs. */
export const MAX_PUBLIC = 20
/** Insgesamt (auch geplante/abgelaufene) höchstens so viele Packs. */
export const MAX_FEATURED = 100
export const MAX_IMAGE_BYTES = 2 * 1024 * 1024
const MAX_VERSIONS = 8
const STALE_MS = 24 * 60 * 60 * 1000
/** Nach einem Fehlschlag beim stillen Auffrischen so lange warten. */
const RETRY_MS = 60 * 60 * 1000
const TIMEOUT_MS = 6000
const MAX_REDIRECTS = 3
/** Weiter als so weit in der Zukunft lässt sich kein Zeitfenster setzen. */
const MAX_WINDOW_MS = 2 * 366 * 24 * 60 * 60 * 1000

const MODRINTH_API = 'https://api.modrinth.com/v2'
const MODRINTH_API_V3 = 'https://api.modrinth.com/v3'
const CURSEFORGE_API = 'https://api.curseforge.com/v1'
const UA = 'theredstonee/TRS-Launcher (trs-launcher.theredstonee.de)'
/** CurseForge: Spiel Minecraft und Klasse „Modpacks“. */
const CF_GAME = 432
const CF_MODPACK_CLASS = 4471
/** CurseForge `modLoader` → Name (nur echte Mod-Loader). */
const CF_LOADERS: Record<number, string> = { 1: 'forge', 4: 'fabric', 5: 'quilt', 6: 'neoforge' }
const MOD_LOADERS = ['fabric', 'forge', 'neoforge', 'quilt'] as const
/** Bilder nur von diesen Hosts (auch nach Weiterleitungen). */
const IMAGE_HOSTS: Record<FeaturedSource, string> = { modrinth: 'cdn.modrinth.com', curseforge: 'media.forgecdn.net' }

// ---------------------------------------------------------------- Links

export type FeaturedRef =
  | { source: 'modrinth', ref: string }
  | { source: 'curseforge', slug: string }
  | { source: 'curseforge', id: number }

const MR_REF = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/
const CF_SLUG = /^[a-z0-9][a-z0-9_-]{0,127}$/i
const CF_NUM = /^[1-9][0-9]{0,9}$/

/**
 * Eingefügten Link lesen (§37.2): `https://modrinth.com/modpack|project/<slugOrId>[/…]`,
 * `https://www.curseforge.com/minecraft/modpacks/<slug>[/…]`, `modrinth:<id>`, `curseforge:<zahl>`. Ohne `https://`
 * geht es auch, `http://` nicht. Alles andere → `null`.
 */
export function parseFeaturedLink(input: string): FeaturedRef | null {
  const s = input.trim()
  if (!s || s.length > 500) return null
  const short = /^(modrinth|curseforge):(.+)$/i.exec(s)
  if (short) {
    const value = short[2]!.trim()
    if (short[1]!.toLowerCase() === 'modrinth') return MR_REF.test(value) ? { source: 'modrinth', ref: value } : null
    return CF_NUM.test(value) ? { source: 'curseforge', id: Number(value) } : null
  }
  let url: URL
  try {
    url = new URL(/^[a-z][a-z0-9+.-]*:\/\//i.test(s) ? s : `https://${s}`)
  } catch {
    return null
  }
  if (url.protocol !== 'https:' || url.username || url.password || url.port) return null
  const host = url.hostname.toLowerCase()
  const parts = url.pathname.split('/').filter(Boolean).map((p) => {
    try {
      return decodeURIComponent(p)
    } catch {
      return ''
    }
  })
  if (host === 'modrinth.com' || host === 'www.modrinth.com') {
    const [kind, ref] = parts
    if ((kind === 'modpack' || kind === 'project') && ref && MR_REF.test(ref)) return { source: 'modrinth', ref }
    return null
  }
  if (host === 'www.curseforge.com' || host === 'curseforge.com') {
    const [game, kind, slug] = parts
    if (game === 'minecraft' && kind === 'modpacks' && slug && CF_SLUG.test(slug)) return { source: 'curseforge', slug: slug.toLowerCase() }
    return null
  }
  return null
}

// ---------------------------------------------------------------- Ansichten

export interface FeaturedText {
  title: string | null
  text: string | null
}
export type FeaturedTexts = Record<FeaturedLang, FeaturedText>

/** Öffentliche Ansicht (Vertrag API.md §37.1 – Launcher und Website bauen darauf). */
export interface FeaturedPack {
  id: string
  source: FeaturedSource
  projectId: string
  slug: string
  url: string
  title: string
  summary: string
  author: string | null
  downloads: number
  loaders: string[]
  gameVersions: string[]
  iconUrl: string | null
  bannerUrl: string | null
  badge: FeaturedBadge | null
  texts: FeaturedTexts
  pinned: boolean
  startsAt: string | null
  endsAt: string | null
  updatedAt: string
}

export interface AdminFeaturedPack extends FeaturedPack {
  active: boolean
  createdBy: string
  createdAt: string
  refreshedAt: string
}

export interface FeaturedRow {
  id: string
  source: FeaturedSource
  project_id: string
  slug: string
  title: string
  summary: string
  author: string | null
  downloads: number
  loaders: string
  game_versions: string
  badge: FeaturedBadge | null
  texts: string
  pinned: number
  sort: number
  starts_at: number | null
  ends_at: number | null
  rev: number
  has_icon: number
  has_banner: number
  created_at: number
  created_by: string
  updated_at: number
  refreshed_at: number
}

export interface FeaturedImage {
  mime: string
  data: Buffer
  sha256: string
}

const iso = (t: number) => new Date(t).toISOString()
const ORDER = 'ORDER BY pinned DESC, sort ASC, created_at ASC, id ASC'
const SELECT = `SELECT p.*,
  EXISTS (SELECT 1 FROM featured_pack_images i WHERE i.pack_id = p.id AND i.kind = 'icon') AS has_icon,
  EXISTS (SELECT 1 FROM featured_pack_images i WHERE i.pack_id = p.id AND i.kind = 'banner') AS has_banner
FROM featured_packs p`

export function packUrl(source: FeaturedSource, slug: string): string {
  return source === 'modrinth'
    ? `https://modrinth.com/modpack/${encodeURIComponent(slug)}`
    : `https://www.curseforge.com/minecraft/modpacks/${encodeURIComponent(slug)}`
}

function stringList(raw: string): string[] {
  try {
    const v = JSON.parse(raw) as unknown
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []
  } catch {
    return []
  }
}

const emptyTexts = (): FeaturedTexts => ({ en: { title: null, text: null }, de: { title: null, text: null } })

/** Gespeicherte Texte lesen – kaputte/fremde Einträge fallen weg. */
export function parseFeaturedTexts(raw: string): FeaturedTexts {
  const out = emptyTexts()
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return out
  }
  if (!parsed || typeof parsed !== 'object') return out
  for (const l of FEATURED_LANGS) {
    const t = (parsed as Record<string, unknown>)[l]
    if (!t || typeof t !== 'object') continue
    const { title, text } = t as Record<string, unknown>
    out[l] = {
      title: typeof title === 'string' && title ? title.slice(0, MAX_TITLE) : null,
      text: typeof text === 'string' && text ? text.slice(0, MAX_TEXT) : null,
    }
  }
  return out
}

export function isActive(r: Pick<FeaturedRow, 'starts_at' | 'ends_at'>, now: number): boolean {
  return (r.starts_at === null || r.starts_at <= now) && (r.ends_at === null || r.ends_at > now)
}

function imageUrl(ctx: AppContext, r: FeaturedRow, kind: 'icon' | 'banner'): string {
  return `${ctx.config.siteUrl}/v1/site/featured-packs/${r.id}/${kind}?v=${r.rev}`
}

export function featuredView(ctx: AppContext, r: FeaturedRow): FeaturedPack {
  return {
    id: r.id,
    source: r.source,
    projectId: r.project_id,
    slug: r.slug,
    url: packUrl(r.source, r.slug),
    title: r.title,
    summary: r.summary,
    author: r.author,
    downloads: r.downloads,
    loaders: stringList(r.loaders),
    gameVersions: stringList(r.game_versions),
    iconUrl: r.has_icon ? imageUrl(ctx, r, 'icon') : null,
    bannerUrl: r.has_banner ? imageUrl(ctx, r, 'banner') : null,
    badge: r.badge,
    texts: parseFeaturedTexts(r.texts),
    pinned: r.pinned === 1,
    startsAt: r.starts_at === null ? null : iso(r.starts_at),
    endsAt: r.ends_at === null ? null : iso(r.ends_at),
    updatedAt: iso(r.updated_at),
  }
}

export function adminFeaturedView(ctx: AppContext, r: FeaturedRow): AdminFeaturedPack {
  return {
    ...featuredView(ctx, r),
    active: isActive(r, ctx.now()),
    createdBy: r.created_by,
    createdAt: iso(r.created_at),
    refreshedAt: iso(r.refreshed_at),
  }
}

function rowOf(ctx: AppContext, id: string): FeaturedRow {
  const r = FEATURED_ID.test(id) ? one<FeaturedRow>(ctx.db, `${SELECT} WHERE p.id = ?`, id) : undefined
  if (!r) throw notFound('not_found', 'No such featured pack')
  return r
}

// ---------------------------------------------------------------- öffentlich

/** Aktive Packs: angeheftet zuerst, dann nach Reihenfolge; höchstens 20. */
export function publicFeatured(ctx: AppContext): FeaturedPack[] {
  const t = ctx.now()
  return all<FeaturedRow>(
    ctx.db,
    `${SELECT} WHERE (p.starts_at IS NULL OR p.starts_at <= ?) AND (p.ends_at IS NULL OR p.ends_at > ?) ${ORDER} LIMIT ?`,
    t, t, MAX_PUBLIC,
  ).map((r) => featuredView(ctx, r))
}

/** Gespeichertes Symbol/Banner (oder `null`). */
export function featuredImage(ctx: AppContext, id: string, kind: 'icon' | 'banner'): FeaturedImage | null {
  if (!FEATURED_ID.test(id)) return null
  const r = one<{ mime: string, data: Uint8Array, sha256: string }>(
    ctx.db,
    'SELECT mime, data, sha256 FROM featured_pack_images WHERE pack_id = ? AND kind = ?',
    id, kind,
  )
  return r ? { mime: r.mime, data: Buffer.from(r.data), sha256: r.sha256 } : null
}

// ---------------------------------------------------------------- Plattformen

/** Was der Server von der Plattform übernimmt. */
export interface PackMeta {
  source: FeaturedSource
  projectId: string
  slug: string
  title: string
  summary: string
  author: string | null
  downloads: number
  loaders: string[]
  gameVersions: string[]
  iconSrc: string | null
  bannerSrc: string | null
}

const upstream = (what: string) => new ApiError(502, 'upstream_failed', `${what} is not reachable, try again later`)
const projectNotFound = () => notFound('project_not_found', 'No such project')
const notAModpack = () => new ApiError(422, 'not_a_modpack', 'This project is not a modpack')

function cfKey(ctx: AppContext): string {
  const key = ctx.config.curseforgeApiKey
  if (!key) throw unavailable('curseforge_unavailable', 'CurseForge is not configured on this server')
  return key
}

/** JSON von Modrinth/CurseForge: 404 → `null`, sonst Fehler → 502 `upstream_failed`. */
async function getJson(ctx: AppContext, source: FeaturedSource, url: string, headers: Record<string, string> = {}): Promise<unknown> {
  const f = (source === 'modrinth' ? ctx.modrinthFetch : ctx.curseforgeFetch) ?? fetch
  const name = source === 'modrinth' ? 'Modrinth' : 'CurseForge'
  let res: Response
  try {
    res = await f(url, {
      headers: { 'User-Agent': UA, Accept: 'application/json', ...headers },
      redirect: 'error',
      signal: AbortSignal.timeout(TIMEOUT_MS),
    })
  } catch (err) {
    console.warn(`[trs-api] ${name} request failed: ${(err as Error).message}`)
    throw upstream(name)
  }
  if (res.status === 404) return null
  if (!res.ok) {
    console.warn(`[trs-api] ${name} answered HTTP ${res.status}`)
    throw upstream(name)
  }
  try {
    return JSON.parse(await res.text()) as unknown
  } catch {
    throw upstream(name)
  }
}

/** Ein Bild (≤ 2 MB, nur Raster) von erlaubtem CDN-Host laden; Weiterleitungen nur auf denselben Host. `null` = keins. */
export async function downloadImage(ctx: AppContext, source: FeaturedSource, src: string | null): Promise<FeaturedImage | null> {
  if (!src) return null
  const f = (source === 'modrinth' ? ctx.modrinthFetch : ctx.curseforgeFetch) ?? fetch
  const allowed = (u: URL) => u.protocol === 'https:' && u.hostname.toLowerCase() === IMAGE_HOSTS[source] && !u.username && !u.password && !u.port
  let url: URL
  try {
    url = new URL(src)
  } catch {
    return null
  }
  try {
    for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
      if (!allowed(url)) return null
      const res = await f(url.href, { headers: { 'User-Agent': UA }, redirect: 'manual', signal: AbortSignal.timeout(TIMEOUT_MS) })
      if (res.status >= 300 && res.status < 400) {
        const loc = res.headers.get('location')
        if (!loc) return null
        url = new URL(loc, url)
        continue
      }
      if (!res.ok) return null
      const len = Number(res.headers.get('content-length') ?? '0')
      if (len > MAX_IMAGE_BYTES) return null
      const data = await readCapped(res, MAX_IMAGE_BYTES)
      if (!data) return null
      const mime = imageType(data)
      if (!mime) return null
      return { mime, data, sha256: sha256Hex(data) }
    }
    return null
  } catch (err) {
    console.warn(`[trs-api] featured image ${url.hostname} failed: ${(err as Error).message}`)
    return null
  }
}

async function readCapped(res: Response, max: number): Promise<Buffer | null> {
  if (!res.body) {
    const b = Buffer.from(await res.arrayBuffer())
    return b.length > max ? null : b
  }
  const reader = res.body.getReader()
  const chunks: Buffer[] = []
  let size = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    size += value.byteLength
    if (size > max) {
      await reader.cancel().catch(() => {})
      return null
    }
    chunks.push(Buffer.from(value))
  }
  return Buffer.concat(chunks, size)
}

// eslint-disable-next-line no-control-regex -- Steuer- und Richtungszeichen fliegen raus
const CONTROL = /[\u0000-\u001f\u007f-\u009f​-‏‪-‮⁦-⁩]/g
/** Eine Zeile: Steuerzeichen raus, Leerraum zusammenfassen. */
export const oneLine = (s: string) => s.replace(/[\r\n\t]+/g, ' ').replace(CONTROL, '').replace(/\s+/g, ' ').trim()

function clip(s: string, max: number): string {
  const t = oneLine(s)
  if (t.length <= max) return t
  const cut = t.slice(0, max - 1)
  const space = cut.lastIndexOf(' ')
  return `${(space > max * 0.6 ? cut.slice(0, space) : cut).replace(/[\s,.;:–-]+$/, '')}…`
}

const RELEASE = /^\d+\.\d+(?:\.\d+)?$/
function compareVersions(a: string, b: string): number {
  const x = a.split('.').map(Number)
  const y = b.split('.').map(Number)
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (y[i] ?? 0) - (x[i] ?? 0)
    if (d !== 0) return d
  }
  return 0
}
/** Nur Release-Versionen, neueste zuerst, höchstens 8. */
export function releaseVersions(list: unknown[]): string[] {
  const set = new Set(list.filter((v): v is string => typeof v === 'string' && RELEASE.test(v.trim())).map((v) => v.trim()))
  return [...set].sort(compareVersions).slice(0, MAX_VERSIONS)
}
function modLoaders(list: unknown[]): string[] {
  const set = new Set(list.filter((v): v is string => typeof v === 'string').map((v) => v.trim().toLowerCase()))
  return MOD_LOADERS.filter((l) => set.has(l))
}

const str = (max: number) => z.string().max(max)
const mrProject = z.object({
  id: z.string().regex(/^[A-Za-z0-9]{8}$/),
  slug: str(100),
  title: str(300),
  description: str(4000).nullish(),
  project_type: str(40),
  downloads: z.number().nonnegative().catch(0),
  loaders: z.array(z.unknown()).catch([]),
  game_versions: z.array(z.unknown()).catch([]),
  icon_url: str(1000).nullish(),
  organization: str(40).nullish(),
  gallery: z.array(z.object({ url: str(1000), featured: z.boolean().catch(false), ordering: z.number().catch(0) }).loose()).catch([]),
}).loose()
const mrMembers = z.array(z.object({
  role: str(100).nullish(),
  is_owner: z.boolean().nullish(),
  ordering: z.number().nullish(),
  user: z.object({ username: str(100) }).loose(),
}).loose())

async function modrinthMeta(ctx: AppContext, ref: string): Promise<PackMeta> {
  const raw = await getJson(ctx, 'modrinth', `${MODRINTH_API}/project/${encodeURIComponent(ref)}`)
  if (raw === null) throw projectNotFound()
  const p = mrProject.safeParse(raw)
  if (!p.success) throw upstream('Modrinth')
  const d = p.data
  if (d.project_type !== 'modpack') throw notAModpack()
  let author: string | null = null
  try {
    const members = mrMembers.safeParse(await getJson(ctx, 'modrinth', `${MODRINTH_API}/project/${d.id}/members`))
    if (members.success && members.data.length) {
      const sorted = [...members.data].sort((a, b) => (a.ordering ?? 0) - (b.ordering ?? 0))
      const owner = sorted.find((m) => m.is_owner === true) ?? sorted.find((m) => m.role?.toLowerCase() === 'owner') ?? sorted[0]!
      author = oneLine(owner.user.username).slice(0, 100) || null
    }
    if (!author && d.organization) {
      const org = z.object({ name: str(200) }).loose().safeParse(await getJson(ctx, 'modrinth', `${MODRINTH_API_V3}/organization/${encodeURIComponent(d.organization)}`))
      if (org.success) author = oneLine(org.data.name).slice(0, 100) || null
    }
  } catch {
    // Autor ist nur Beiwerk.
  }
  const gallery = [...d.gallery].sort((a, b) => a.ordering - b.ordering)
  const banner = gallery.find((g) => g.featured) ?? gallery[0]
  return {
    source: 'modrinth',
    projectId: d.id,
    slug: d.slug,
    title: clip(d.title, 120) || d.slug,
    summary: clip(d.description ?? '', MAX_SUMMARY),
    author,
    downloads: Math.round(d.downloads),
    loaders: modLoaders(d.loaders),
    gameVersions: releaseVersions(d.game_versions),
    iconSrc: d.icon_url ?? null,
    bannerSrc: banner?.url ?? null,
  }
}

const cfMod = z.object({
  id: z.number().int().positive(),
  gameId: z.number().int(),
  classId: z.number().int().nullish(),
  name: str(300),
  slug: str(200),
  summary: str(4000).nullish(),
  downloadCount: z.number().nonnegative().catch(0),
  authors: z.array(z.object({ name: str(200) }).loose()).catch([]),
  logo: z.object({ url: str(1000).nullish(), thumbnailUrl: str(1000).nullish() }).loose().nullish().catch(null),
  screenshots: z.array(z.object({ url: str(1000) }).loose()).catch([]),
  latestFilesIndexes: z.array(z.object({ gameVersion: z.unknown(), modLoader: z.unknown() }).loose()).catch([]),
  latestFiles: z.array(z.object({ gameVersions: z.array(z.unknown()).catch([]) }).loose()).catch([]),
}).loose()
type CfMod = z.output<typeof cfMod>

async function curseforgeMeta(ctx: AppContext, ref: { slug: string } | { id: number }): Promise<PackMeta> {
  const key = cfKey(ctx)
  const h = { 'x-api-key': key }
  let mod: CfMod | null = null
  if ('id' in ref) {
    const raw = await getJson(ctx, 'curseforge', `${CURSEFORGE_API}/mods/${ref.id}`, h)
    if (raw === null) throw projectNotFound()
    const r = cfMod.safeParse((raw as { data?: unknown }).data)
    if (!r.success) throw upstream('CurseForge')
    mod = r.data
  } else {
    const search = async (classId: number | null) => {
      const q = `gameId=${CF_GAME}${classId ? `&classId=${classId}` : ''}&slug=${encodeURIComponent(ref.slug)}`
      const raw = await getJson(ctx, 'curseforge', `${CURSEFORGE_API}/mods/search?${q}`, h)
      const list = raw && typeof raw === 'object' && Array.isArray((raw as { data?: unknown }).data) ? (raw as { data: unknown[] }).data : []
      for (const m of list) {
        const r = cfMod.safeParse(m)
        if (r.success && r.data.slug.toLowerCase() === ref.slug) return r.data
      }
      return null
    }
    mod = await search(CF_MODPACK_CLASS)
    if (!mod) {
      // Gibt es den Slug in einer anderen Klasse (Mod, Ressourcenpaket …)? Dann ist es kein Modpack.
      if (await search(null)) throw notAModpack()
      throw projectNotFound()
    }
  }
  if (mod.gameId !== CF_GAME) throw projectNotFound()
  if (mod.classId !== CF_MODPACK_CLASS) throw notAModpack()
  const versions: unknown[] = [
    ...mod.latestFilesIndexes.map((i) => i.gameVersion),
    ...mod.latestFiles.flatMap((f) => f.gameVersions),
  ]
  const loaders: unknown[] = [
    ...mod.latestFilesIndexes.map((i) => (typeof i.modLoader === 'number' ? CF_LOADERS[i.modLoader] : undefined)),
    ...mod.latestFiles.flatMap((f) => f.gameVersions),
  ]
  return {
    source: 'curseforge',
    projectId: String(mod.id),
    slug: mod.slug.toLowerCase(),
    title: clip(mod.name, 120) || mod.slug,
    summary: clip(mod.summary ?? '', MAX_SUMMARY),
    author: mod.authors[0] ? oneLine(mod.authors[0].name).slice(0, 100) || null : null,
    downloads: Math.round(mod.downloadCount),
    loaders: modLoaders(loaders),
    gameVersions: releaseVersions(versions),
    iconSrc: mod.logo?.url ?? mod.logo?.thumbnailUrl ?? null,
    bannerSrc: mod.screenshots[0]?.url ?? null,
  }
}

/** Projekt bei der Plattform nachschlagen (Fehler wie §37.2). */
export function fetchMeta(ctx: AppContext, ref: FeaturedRef): Promise<PackMeta> {
  if (ref.source === 'modrinth') return modrinthMeta(ctx, ref.ref)
  return curseforgeMeta(ctx, 'id' in ref ? { id: ref.id } : { slug: ref.slug })
}

async function fetchImages(ctx: AppContext, meta: PackMeta): Promise<{ icon: FeaturedImage | null, banner: FeaturedImage | null }> {
  const [icon, banner] = await Promise.all([downloadImage(ctx, meta.source, meta.iconSrc), downloadImage(ctx, meta.source, meta.bannerSrc)])
  return { icon, banner }
}

function storeImage(ctx: AppContext, id: string, kind: 'icon' | 'banner', img: FeaturedImage | null): void {
  if (!img) {
    run(ctx.db, 'DELETE FROM featured_pack_images WHERE pack_id = ? AND kind = ?', id, kind)
    return
  }
  run(
    ctx.db,
    `INSERT INTO featured_pack_images (pack_id, kind, mime, data, sha256) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT (pack_id, kind) DO UPDATE SET mime = excluded.mime, data = excluded.data, sha256 = excluded.sha256`,
    id, kind, img.mime, img.data, img.sha256,
  )
}

// ---------------------------------------------------------------- Team

export const featuredCreateBody = z.object({ url: z.string().max(500) }).strict()

const textField = (max: number) => z
  .string()
  .max(max * 4)
  .transform(oneLine)
  .pipe(z.string().max(max))
  .transform((s) => s || null)
  .nullable()
  .optional()
const langPatch = z.object({ title: textField(MAX_TITLE), text: textField(MAX_TEXT) }).strict()

export const featuredPatchBody = z
  .object({
    texts: z.object({ en: langPatch.optional(), de: langPatch.optional() }).strict().optional(),
    badge: z.enum(FEATURED_BADGES).nullable().optional(),
    pinned: z.boolean().optional(),
    startsAt: z.iso.datetime({ offset: true }).nullable().optional(),
    endsAt: z.iso.datetime({ offset: true }).nullable().optional(),
  })
  .strict()
/** Schlichtes Interface statt zod-Typ – hält die Routen-Typen von Nitro flach. */
export interface FeaturedPatch {
  texts?: Partial<Record<FeaturedLang, { title?: string | null, text?: string | null }>>
  badge?: FeaturedBadge | null
  pinned?: boolean
  startsAt?: string | null
  endsAt?: string | null
}

export const featuredOrderBody = z.object({ ids: z.array(z.string().max(40)).max(MAX_FEATURED) }).strict()

/** Alle Packs (auch geplante/abgelaufene), gleiche Reihenfolge wie öffentlich. */
export function adminFeatured(ctx: AppContext, staff: Staff): AdminFeaturedPack[] {
  assertCan(staff, 'featured.manage')
  return all<FeaturedRow>(ctx.db, `${SELECT} ${ORDER}`).map((r) => adminFeaturedView(ctx, r))
}

function newId(): string {
  const alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789'
  const bytes = randomBytes(12)
  let s = 'fp_'
  for (const b of bytes) s += alphabet[b % 36]
  return s
}

function alreadyFeatured(): ApiError {
  return conflict('already_featured', 'This modpack is already featured')
}

/** Pack per Link hinzufügen (§37.2): Plattform-Daten + Bilder holen, ans Ende der Liste. */
export async function createFeatured(ctx: AppContext, staff: Staff, body: { url: string }): Promise<AdminFeaturedPack> {
  assertCan(staff, 'featured.manage')
  const ref = parseFeaturedLink(body.url)
  if (!ref) throw badRequest('invalid_link', 'Paste a Modrinth or CurseForge modpack link')
  if (ref.source === 'curseforge') cfKey(ctx)
  const count = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM featured_packs')!.n
  if (count >= MAX_FEATURED) throw conflict('featured_limit', `At most ${MAX_FEATURED} featured packs`)
  // Schon da? (Modrinth-ID bzw. CurseForge-Zahl direkt – ohne Anfrage bei der Plattform.)
  const known = (source: FeaturedSource, value: string) =>
    one(ctx.db, 'SELECT 1 FROM featured_packs WHERE source = ? AND (project_id = ? OR slug = ?)', source, value, value.toLowerCase())
  if (ref.source === 'modrinth' ? known('modrinth', ref.ref) : known('curseforge', 'id' in ref ? String(ref.id) : ref.slug)) throw alreadyFeatured()
  const meta = await fetchMeta(ctx, ref)
  if (one(ctx.db, 'SELECT 1 FROM featured_packs WHERE source = ? AND project_id = ?', meta.source, meta.projectId)) throw alreadyFeatured()
  const images = await fetchImages(ctx, meta)
  const id = newId()
  const t = ctx.now()
  try {
    tx(ctx.db, () => {
      const sort = (one<{ s: number | null }>(ctx.db, 'SELECT MAX(sort) AS s FROM featured_packs')?.s ?? -1) + 1
      run(
        ctx.db,
        `INSERT INTO featured_packs (id, source, project_id, slug, title, summary, author, downloads, loaders, game_versions,
           badge, texts, pinned, sort, starts_at, ends_at, rev, created_at, created_by, updated_at, refreshed_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, 0, ?, NULL, NULL, 1, ?, ?, ?, ?)`,
        id, meta.source, meta.projectId, meta.slug, meta.title, meta.summary, meta.author, meta.downloads,
        JSON.stringify(meta.loaders), JSON.stringify(meta.gameVersions), JSON.stringify(emptyTexts()), sort, t, staff.uuid, t, t,
      )
      storeImage(ctx, id, 'icon', images.icon)
      storeImage(ctx, id, 'banner', images.banner)
      audit(ctx, staff.uuid, 'featured.create', id, `${meta.source}:${meta.projectId} ${meta.slug}`)
    })
  } catch (err) {
    if (/UNIQUE/i.test((err as Error).message)) throw alreadyFeatured()
    throw err
  }
  return adminFeaturedView(ctx, rowOf(ctx, id))
}

function parseTime(v: string): number {
  const t = Date.parse(v)
  if (!Number.isFinite(t)) throw badRequest('invalid_window', 'Invalid date')
  return t
}

/** Texte, Abzeichen, Anheften, Zeitfenster ändern (§37.2). `null` leert. */
export function updateFeatured(ctx: AppContext, staff: Staff, id: string, body: FeaturedPatch): AdminFeaturedPack {
  assertCan(staff, 'featured.manage')
  const r = rowOf(ctx, id)
  const texts = parseFeaturedTexts(r.texts)
  for (const l of FEATURED_LANGS) {
    const p = body.texts?.[l]
    if (!p) continue
    // Die Route prüft schon per zod; hier noch einmal säubern (auch für Aufrufe ohne Route).
    if (p.title !== undefined) texts[l].title = p.title === null ? null : oneLine(p.title).slice(0, MAX_TITLE) || null
    if (p.text !== undefined) texts[l].text = p.text === null ? null : oneLine(p.text).slice(0, MAX_TEXT) || null
  }
  const startsAt = body.startsAt === undefined ? r.starts_at : body.startsAt === null ? null : parseTime(body.startsAt)
  const endsAt = body.endsAt === undefined ? r.ends_at : body.endsAt === null ? null : parseTime(body.endsAt)
  if (startsAt !== null && endsAt !== null && endsAt <= startsAt) throw badRequest('invalid_window', 'The end must be after the start')
  const limit = ctx.now() + MAX_WINDOW_MS
  if ((startsAt !== null && startsAt > limit) || (endsAt !== null && endsAt > limit)) throw badRequest('invalid_window', 'Too far in the future')
  const badge = body.badge === undefined ? r.badge : body.badge
  const pinned = body.pinned === undefined ? r.pinned : body.pinned ? 1 : 0
  const changed = Object.keys(body).filter((k) => (body as Record<string, unknown>)[k] !== undefined)
  tx(ctx.db, () => {
    run(
      ctx.db,
      'UPDATE featured_packs SET texts = ?, badge = ?, pinned = ?, starts_at = ?, ends_at = ?, updated_at = ? WHERE id = ?',
      JSON.stringify(texts), badge, pinned, startsAt, endsAt, ctx.now(), id,
    )
    audit(ctx, staff.uuid, 'featured.update', id, changed.join(',') || undefined)
  })
  return adminFeaturedView(ctx, rowOf(ctx, id))
}

export function deleteFeatured(ctx: AppContext, staff: Staff, id: string): void {
  assertCan(staff, 'featured.manage')
  const r = rowOf(ctx, id)
  tx(ctx.db, () => {
    run(ctx.db, 'DELETE FROM featured_packs WHERE id = ?', id)
    audit(ctx, staff.uuid, 'featured.delete', id, `${r.source}:${r.project_id} ${r.slug}`)
  })
}

/** Neue Reihenfolge: genau die aktuelle Menge, sonst `400 invalid_order`. */
export function orderFeatured(ctx: AppContext, staff: Staff, ids: string[]): AdminFeaturedPack[] {
  assertCan(staff, 'featured.manage')
  const current = all<{ id: string }>(ctx.db, 'SELECT id FROM featured_packs').map((x) => x.id)
  const set = new Set(ids)
  if (set.size !== ids.length || ids.length !== current.length || !current.every((id) => set.has(id))) {
    throw badRequest('invalid_order', 'The list must contain every featured pack exactly once')
  }
  tx(ctx.db, () => {
    ids.forEach((id, i) => run(ctx.db, 'UPDATE featured_packs SET sort = ? WHERE id = ?', i, id))
    audit(ctx, staff.uuid, 'featured.order', null, String(ids.length))
  })
  return adminFeatured(ctx, staff)
}

/** Plattform-Daten + Bilder neu holen, `rev` hochzählen (neue Bild-Adressen). */
async function refreshRow(ctx: AppContext, r: FeaturedRow): Promise<void> {
  const ref: FeaturedRef = r.source === 'modrinth' ? { source: 'modrinth', ref: r.project_id } : { source: 'curseforge', id: Number(r.project_id) }
  const meta = await fetchMeta(ctx, ref)
  const images = await fetchImages(ctx, meta)
  const t = ctx.now()
  tx(ctx.db, () => {
    // Gelöscht, während wir warteten? Dann nichts tun.
    if (!one(ctx.db, 'SELECT 1 FROM featured_packs WHERE id = ?', r.id)) return
    run(
      ctx.db,
      `UPDATE featured_packs SET slug = ?, title = ?, summary = ?, author = ?, downloads = ?, loaders = ?, game_versions = ?,
         rev = rev + 1, updated_at = ?, refreshed_at = ? WHERE id = ?`,
      meta.slug, meta.title, meta.summary, meta.author, meta.downloads, JSON.stringify(meta.loaders), JSON.stringify(meta.gameVersions),
      t, t, r.id,
    )
    // Ein Bild, das diesmal nicht ladbar war, bleibt erhalten (nur ein fehlendes Bild bei der Plattform löscht es).
    if (images.icon || !meta.iconSrc) storeImage(ctx, r.id, 'icon', images.icon)
    if (images.banner || !meta.bannerSrc) storeImage(ctx, r.id, 'banner', images.banner)
  })
}

export async function refreshFeatured(ctx: AppContext, staff: Staff, id: string): Promise<AdminFeaturedPack> {
  assertCan(staff, 'featured.manage')
  const r = rowOf(ctx, id)
  await refreshRow(ctx, r)
  audit(ctx, staff.uuid, 'featured.refresh', id)
  return adminFeaturedView(ctx, rowOf(ctx, id))
}

const refreshing = new Set<string>()
const failedAt = new Map<string, number>()

/**
 * Stilles Auffrischen bei öffentlichen Abrufen: höchstens ein aktives Pack auf einmal, dessen Daten älter als 24 h
 * sind. Blockiert nie, Fehler werden verschluckt (dann 1 h Pause für dieses Pack).
 */
export function refreshStaleInBackground(ctx: AppContext): void {
  if (refreshing.size > 0) return
  const t = ctx.now()
  const stale = all<FeaturedRow>(
    ctx.db,
    `${SELECT} WHERE p.refreshed_at < ? AND (p.starts_at IS NULL OR p.starts_at <= ?) AND (p.ends_at IS NULL OR p.ends_at > ?)
     ORDER BY p.refreshed_at ASC LIMIT 5`,
    t - STALE_MS, t, t,
  ).find((r) => (failedAt.get(r.id) ?? 0) + RETRY_MS <= t && !(r.source === 'curseforge' && !ctx.config.curseforgeApiKey))
  if (!stale) return
  refreshing.add(stale.id)
  void refreshRow(ctx, stale)
    .then(() => failedAt.delete(stale.id))
    .catch((err: unknown) => {
      failedAt.set(stale.id, ctx.now())
      console.warn(`[trs-api] featured pack ${stale.id} could not be refreshed: ${(err as Error).message}`)
    })
    .finally(() => refreshing.delete(stale.id))
}
