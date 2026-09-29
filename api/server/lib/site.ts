import type { AppContext } from './context'
import { ACHIEVEMENTS } from './achievement-catalog'
import { v2Assets, v2Fields, type CosmeticV2Fields } from './cosmetics'
import { all } from './db'
import { changelogFor, parseChangelog, postContent, type ChangelogEntry, type PostShot, type UpdateBanner } from './changelog'
import { extractContributors, mergeContributors } from './contributors'

// Daten für die Website: neueste Launcher-Version (GitHub Releases), Blog (CHANGELOG.md aus dem
// Repo) und die öffentlichen TRS-Umhänge. Externe Quellen werden zwischengespeichert; schlägt ein
// Abruf fehl, bleibt der letzte gute Stand (die Seite funktioniert auch, wenn GitHub hakt).

const REPO = 'theredstonee/TRS-Launcher'
const RAW = `https://raw.githubusercontent.com/${REPO}/main`
const TTL_MS = 10 * 60 * 1000

interface Cached<T> {
  value: T
  at: number
}
const cache = new Map<string, Cached<unknown>>()
const inflight = new Map<string, Promise<unknown>>()

/** Zwischenspeicher mit Ablaufzeit; bei Fehlern den alten Wert behalten (sonst Fehler weitergeben). */
async function cached<T>(key: string, load: () => Promise<T>, now = Date.now()): Promise<T> {
  const hit = cache.get(key) as Cached<T> | undefined
  if (hit && now - hit.at < TTL_MS) return hit.value
  const running = inflight.get(key) as Promise<T> | undefined
  if (running) return running
  const p = load()
    .then((value) => {
      cache.set(key, { value, at: Date.now() })
      return value
    })
    .catch((err) => {
      if (hit) {
        console.warn(`[site] ${key}: ${(err as Error).message} – nutze alten Stand`)
        return hit.value
      }
      throw err
    })
    .finally(() => inflight.delete(key))
  inflight.set(key, p)
  return p
}

async function fetchText(url: string, accept: string): Promise<string> {
  const res = await fetch(url, {
    headers: { Accept: accept, 'User-Agent': 'trs-launcher-website' },
    signal: AbortSignal.timeout(10_000),
    redirect: 'follow',
  })
  if (!res.ok) throw new Error(`${url} → HTTP ${res.status}`)
  return res.text()
}

// --- Downloads -----------------------------------------------------------------------

export type Platform = 'windows' | 'appimage' | 'deb' | 'rpm'

export interface ReleaseAsset {
  platform: Platform
  name: string
  url: string
  size: number
}

export interface LatestRelease {
  version: string
  tag: string
  publishedAt: string | null
  pageUrl: string
  assets: ReleaseAsset[]
}

interface GhRelease {
  tag_name: string
  html_url: string
  published_at: string | null
  draft: boolean
  /** Release-Text (Markdown); darin ggf. der Danke-Abschnitt mit `<!-- contributors: … -->`. */
  body?: string | null
  assets: { name: string, browser_download_url: string, size: number }[]
}

/** Welche Datei ist welche Plattform? Signaturen (.sig) und latest.json zählen nicht. */
export function platformOf(name: string): Platform | null {
  const n = name.toLowerCase()
  if (n.endsWith('.sig') || n.endsWith('.json')) return null
  if (n.endsWith('_x64-setup.exe')) return 'windows'
  if (n.endsWith('.appimage')) return 'appimage'
  if (n.endsWith('.deb')) return 'deb'
  if (n.endsWith('.rpm')) return 'rpm'
  return null
}

/** Neueste echte Launcher-Version (Tags `v1.2.3`; die festen Releases `updater`/`client-mod` nicht). */
export function pickLatest(releases: GhRelease[]): LatestRelease | null {
  const versions = releases.filter((r) => !r.draft && /^v\d+\.\d+\.\d+/.test(r.tag_name))
  versions.sort((a, b) => (b.published_at ?? '').localeCompare(a.published_at ?? ''))
  const r = versions[0]
  if (!r) return null
  const assets = r.assets
    .map((a) => ({ platform: platformOf(a.name), name: a.name, url: a.browser_download_url, size: a.size }))
    .filter((a): a is ReleaseAsset => a.platform !== null && a.url.startsWith(`https://github.com/${REPO}/releases/download/`))
  return { version: r.tag_name.slice(1), tag: r.tag_name, publishedAt: r.published_at, pageUrl: r.html_url, assets }
}

/** Die letzten GitHub-Releases (eine Anfrage für Downloads und Mitwirkende, zwischengespeichert). */
function ghReleases(): Promise<GhRelease[]> {
  return cached('releases', async () => {
    const text = await fetchText(`https://api.github.com/repos/${REPO}/releases?per_page=30`, 'application/vnd.github+json')
    const list: unknown = JSON.parse(text)
    if (!Array.isArray(list)) throw new Error('GitHub releases: keine Liste')
    return list as GhRelease[]
  })
}

export async function latestRelease(): Promise<LatestRelease | null> {
  return pickLatest(await ghReleases())
}

/**
 * Mitwirkende je Version aus den Release-Texten (Tags `v1.2.3`, keine Entwürfe): Kommentar `<!-- contributors: … -->`,
 * sonst `@name` im Abschnitt „Thanks to / Danke an“. Versionen ohne Mitwirkende fehlen in der Tabelle.
 */
export function contributorsByVersion(releases: GhRelease[]): Map<string, string[]> {
  const out = new Map<string, string[]>()
  for (const r of releases) {
    if (r.draft || typeof r.tag_name !== 'string' || !/^v\d+\.\d+\.\d+/.test(r.tag_name)) continue
    if (typeof r.body !== 'string' || !r.body) continue
    const { contributors } = extractContributors(r.body)
    if (contributors.length) out.set(r.tag_name.slice(1), contributors)
  }
  return out
}

/** Mitwirkende einer Version laut GitHub-Release; ist GitHub nicht erreichbar, eine leere Liste (der Beitrag bleibt). */
async function releaseContributors(version: string): Promise<string[]> {
  try {
    return contributorsByVersion(await ghReleases()).get(version) ?? []
  } catch {
    return []
  }
}

// --- Blog ------------------------------------------------------------------------------

export interface BlogPostSummary {
  version: string
  date: string | null
  title: { en: string, de: string } | null
  /** Die fetten Schlagzeilen der Punkte (für Karten). */
  headlines: { en: string[], de: string[] }
  /** Akzentfarbe + Motiv (absolute Bildadresse) des Update-Banners. */
  banner: UpdateBanner | null
  /**
   * Screenshots der Neuerungen je Sprache (Kommentar „shots:“ im Changelog, dazu ältere Bildzeilen im Text),
   * absolute Bildadressen, höchstens SHOTS_MAX.
   */
  gallery: { en: PostShot[], de: PostShot[] }
}

export interface BlogPost extends BlogPostSummary {
  /** Text des Beitrags ohne Bildzeilen (die stehen in `gallery`) und ohne Danke-Abschnitt (der steht in `contributors`). */
  markdown: { en: string, de: string }
  /** GitHub-Logins der Mitwirkenden dieses Releases (ohne „@“, geprüft), sonst leer. */
  contributors: string[]
}

/** Bilder aus public/news/ liegen im Repo – ausgeliefert werden sie von GitHub. */
function absoluteShots(shots: PostShot[]): PostShot[] {
  return shots.map((s) => ({ ...s, src: `${RAW}/public${s.src}` }))
}

function headlines(text: string): string[] {
  return [...text.matchAll(/^- \*\*(.+?)\*\*/gm)].map((m) => m[1]!.replace(/[.!:]$/, '')).slice(0, 6)
}

function summary(e: ChangelogEntry & { version: string }): BlogPostSummary {
  const banner = e.banner ? { accent: e.banner.accent, motif: e.banner.motif ? `${RAW}/public${e.banner.motif}` : null } : null
  const gallery = { en: absoluteShots(postContent(e, 'en').shots), de: absoluteShots(postContent(e, 'de').shots) }
  return { version: e.version, date: e.date, title: e.title, headlines: { en: headlines(e.en), de: headlines(e.de) }, banner, gallery }
}

function changelog(): Promise<ChangelogEntry[]> {
  return cached('changelog', async () => parseChangelog(await fetchText(`${RAW}/CHANGELOG.md`, 'text/plain')))
}

/** Alle veröffentlichten Versionen, neueste zuerst. */
export async function blogPosts(): Promise<BlogPostSummary[]> {
  return (await changelog()).filter((e): e is ChangelogEntry & { version: string } => e.version !== null).map(summary)
}

export async function blogPost(version: string): Promise<BlogPost | null> {
  const e = changelogFor(await changelog(), version)
  if (!e || !e.version) return null
  // Steht ein Danke-Abschnitt auch im Changelog, wird er aus dem Text genommen und zu den Mitwirkenden gezählt.
  const en = extractContributors(postContent(e, 'en').markdown)
  const de = extractContributors(postContent(e, 'de').markdown)
  const fromRelease = await releaseContributors(e.version)
  return {
    ...summary(e as ChangelogEntry & { version: string }),
    markdown: { en: en.markdown, de: de.markdown },
    contributors: mergeContributors(fromRelease, [...en.contributors, ...de.contributors]),
  }
}

// --- Umhänge ---------------------------------------------------------------------------

export interface PublicCape {
  id: string
  name: string
  /** Titel des Erfolgs, der diesen Umhang als Belohnung vergibt (§31), sonst `null`. */
  achievement: { en: string, de: string, es: string } | null
  unlock: 'free' | 'code' | 'admin'
  /** Relativ zur Website – gleiche App, gleiche Herkunft. */
  url: string
  scale: number
  frames: number
  frameTimeMs: number | null
}

/** Mitgelieferte, freigegebene TRS-Umhänge (keine hochgeladenen Umhänge von Spielern). */
export function publicCapes(ctx: AppContext): PublicCape[] {
  const rows = all<{ id: string, name: string, unlock: 'free' | 'code' | 'admin', sha256: string, width: number, frames: number, frame_time_ms: number | null }>(
    ctx.db,
    `SELECT id, name, unlock, sha256, width, frames, frame_time_ms FROM capes
     WHERE kind = 'builtin' AND status = 'approved' AND retired = 0 ORDER BY sort, id`,
  )
  // Umhänge, die ein Erfolg als Belohnung vergibt (§31): Titel des Erfolgs statt „Nur Team“.
  const byReward = new Map(ACHIEVEMENTS.filter((a) => a.reward?.kind === 'cape').map((a) => [a.reward!.id, a.title]))
  return rows.map((c) => ({
    id: c.id,
    name: c.name,
    unlock: c.unlock,
    achievement: byReward.get(c.id) ?? null,
    url: `/v1/capes/${c.id}.png?v=${c.sha256.slice(0, 12)}`,
    scale: Math.round(c.width / 64),
    frames: c.frames,
    frameTimeMs: c.frames > 1 ? c.frame_time_ms : null,
  }))
}

// --- Kopf-Kosmetik (Format v2) ------------------------------------------------------------

export interface PublicHat extends CosmeticV2Fields {
  id: string
  name: string
  /** Titel des Erfolgs, der dieses Teil als Belohnung vergibt (§31), sonst `null`. */
  achievement: { en: string, de: string, es: string } | null
  unlock: 'free' | 'code' | 'admin'
  /** Grundtextur – relativ zur Website (gleiche Herkunft, CSP `img-src 'self'`). */
  texture: string
  /** Hat das Modell Knochen-Animationen (Treiber idle)? */
  animated: boolean
}

/**
 * Mitgelieferte Kopf-Kosmetik im Format v2 für die Website (Karten + 3D-Vorschau). Versteckte Teile (per Code,
 * z. B. die Ente) erscheinen nie, ausgemusterte auch nicht. URLs relativ (gleiche App).
 */
export function publicHats(ctx: AppContext): PublicHat[] {
  const rows = all<{ id: string, name: string, unlock: 'free' | 'code' | 'admin', format: number }>(
    ctx.db,
    `SELECT id, name, unlock, format FROM cosmetics
     WHERE kind = 'builtin' AND slot = 'hat' AND format = 2 AND status = 'approved' AND retired = 0 AND hidden = 0
     ORDER BY sort, id`,
  )
  const byReward = new Map(ACHIEVEMENTS.filter((a) => a.reward?.kind === 'cosmetic').map((a) => [a.reward!.id, a.title]))
  const out: PublicHat[] = []
  for (const r of rows) {
    const a = v2Assets(ctx, r)
    if (!a) continue
    const f = v2Fields(a, '')
    out.push({
      id: r.id,
      name: r.name,
      unlock: r.unlock,
      achievement: byReward.get(r.id) ?? null,
      texture: `/v1/cosmetics/${r.id}.png?v=${f.hash}`,
      animated: (a.model.animations ?? []).some((x) => (x.driver ?? 'idle') === 'idle'),
      ...f,
    })
  }
  return out
}
