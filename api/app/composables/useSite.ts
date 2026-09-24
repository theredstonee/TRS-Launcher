import type { PostBlock } from '~/utils/changelog'

// Daten der Website aus der eigenen API (/v1/site/…); serverseitig gerendert und im Browser weiterverwendet.

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

export interface BlogPostSummary {
  version: string
  date: string | null
  title: { en: string, de: string } | null
  headlines: { en: string[], de: string[] }
}

export interface BlogPost extends BlogPostSummary {
  blocks: { en: PostBlock[], de: PostBlock[] }
}

export const REPO_URL = 'https://github.com/theredstonee/TRS-Launcher'
export const RELEASES_URL = `${REPO_URL}/releases`
export const DISCORD_URL = 'https://dc.theredstonee.de'
export const WIKI_URL = `${REPO_URL}/wiki`
export const IMPRINT_URL = 'https://theredstonee.de/imprint/'

export function useRelease() {
  return useFetch<{ release: LatestRelease | null }>('/v1/site/releases', { key: 'release', default: () => ({ release: null }) })
}

export function useBlog() {
  return useFetch<{ posts: BlogPostSummary[] }>('/v1/site/blog', { key: 'blog', default: () => ({ posts: [] }) })
}

export function useCapes() {
  return useFetch<{ capes: SiteCape[] }>('/v1/site/capes', { key: 'capes', default: () => ({ capes: [] }) })
}

export function assetFor(release: LatestRelease | null | undefined, platform: Platform): ReleaseAsset | null {
  return release?.assets.find((a) => a.platform === platform) ?? null
}

/** Titel und Schlagzeilen eines Beitrags in der Seitensprache (Spanisch nutzt Englisch). */
export function postTitle(post: BlogPostSummary, lang: Lang, fallback: string): string {
  if (!post.title) return fallback
  return lang === 'de' ? post.title.de : post.title.en
}

export function postHeadlines(post: BlogPostSummary, lang: Lang): string[] {
  return lang === 'de' ? post.headlines.de : post.headlines.en
}

/** Betriebssystem des Besuchers (für den großen Download-Knopf). */
export function useVisitorOs() {
  const ua = import.meta.server ? (useRequestHeaders(['user-agent'])['user-agent'] ?? '') : navigator.userAgent
  const os = useState<'windows' | 'linux' | 'other'>('visitor-os', () => {
    if (/Windows/i.test(ua)) return 'windows'
    if (/Linux|X11/i.test(ua) && !/Android/i.test(ua)) return 'linux'
    return 'other'
  })
  return os
}
