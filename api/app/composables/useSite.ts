import type { PostShot } from '~/utils/changelog'

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
  banner: { accent: string, motif: string | null } | null
  /** Screenshots der Neuerungen je Sprache (absolute Adressen, Bildunterschrift schon übersetzt oder leer). */
  gallery: { en: PostShot[], de: PostShot[] }
}

export interface BlogPost extends BlogPostSummary {
  /** Text ohne Bildzeilen – die Bilder stehen in `gallery`. */
  markdown: { en: string, de: string }
}

export const REPO_URL = 'https://github.com/theredstonee/TRS-Launcher'
export const RELEASES_URL = `${REPO_URL}/releases`
export const DISCORD_URL = 'https://dc.theredstonee.de'
/**
 * Dokumentation (docs-site, statisch unter /docs mit eigener App) – Startseite in der Sprache der Website. Außerhalb
 * dieser Nuxt-App: als normalen Link öffnen (`<a href>` bzw. NuxtLink mit `external`), nicht über den Router.
 */
export function docsUrl(lang: Lang): string {
  return `/docs/${lang}`
}
export const IMPRINT_URL = 'https://theredstonee.de/imprint/'

export function useRelease() {
  return useApiFetch<{ release: LatestRelease | null }>('/v1/site/releases', { key: 'release', default: () => ({ release: null }) })
}

/** Update-Beiträge (`posts`, aus CHANGELOG.md) und News-Beiträge des Teams (`news`, §30). */
export function useBlog() {
  return useApiFetch<{ posts: BlogPostSummary[], news?: NewsSummary[] }>('/v1/site/blog', { key: 'blog', default: () => ({ posts: [], news: [] }) })
}

export function useCapes() {
  return useApiFetch<{ capes: SiteCape[] }>('/v1/site/capes', { key: 'capes', default: () => ({ capes: [] }) })
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

/** Screenshots eines Beitrags in der Seitensprache (Spanisch nutzt Englisch). */
export function postGallery(post: BlogPostSummary, lang: Lang): PostShot[] {
  return (lang === 'de' ? post.gallery?.de : post.gallery?.en) ?? []
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
