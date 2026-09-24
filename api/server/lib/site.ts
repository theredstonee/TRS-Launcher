import type { AppContext } from './context'
import { all } from './db'
import { changelogFor, parseChangelog, splitPost, type ChangelogEntry, type PostBlock } from './changelog'

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

export function latestRelease(): Promise<LatestRelease | null> {
  return cached('release', async () => {
    const text = await fetchText(`https://api.github.com/repos/${REPO}/releases?per_page=20`, 'application/vnd.github+json')
    return pickLatest(JSON.parse(text) as GhRelease[])
  })
}

// --- Blog ------------------------------------------------------------------------------

export interface BlogPostSummary {
  version: string
  date: string | null
  title: { en: string, de: string } | null
  /** Die fetten Schlagzeilen der Punkte (für Karten). */
  headlines: { en: string[], de: string[] }
}

export interface BlogPost extends BlogPostSummary {
  blocks: { en: PostBlock[], de: PostBlock[] }
}

/** Bilder aus public/news/ liegen im Repo – ausgeliefert werden sie von GitHub. */
function absoluteImages(blocks: PostBlock[]): PostBlock[] {
  return blocks.map((b) => (b.kind === 'image' ? { ...b, src: `${RAW}/public${b.src}` } : b))
}

function headlines(text: string): string[] {
  return [...text.matchAll(/^- \*\*(.+?)\*\*/gm)].map((m) => m[1]!.replace(/[.!:]$/, '')).slice(0, 6)
}

function summary(e: ChangelogEntry & { version: string }): BlogPostSummary {
  return { version: e.version, date: e.date, title: e.title, headlines: { en: headlines(e.en), de: headlines(e.de) } }
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
  return {
    ...summary(e as ChangelogEntry & { version: string }),
    blocks: { en: absoluteImages(splitPost(e.en)), de: absoluteImages(splitPost(e.de)) },
  }
}

// --- Umhänge ---------------------------------------------------------------------------

export interface PublicCape {
  id: string
  name: string
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
  return rows.map((c) => ({
    id: c.id,
    name: c.name,
    unlock: c.unlock,
    url: `/v1/capes/${c.id}.png?v=${c.sha256.slice(0, 12)}`,
    scale: Math.round(c.width / 64),
    frames: c.frames,
    frameTimeMs: c.frames > 1 ? c.frame_time_ms : null,
  }))
}
