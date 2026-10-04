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
  /** Text ohne Bildzeilen und ohne Danke-Abschnitt – die Bilder stehen in `gallery`, die Namen in `contributors`. */
  markdown: { en: string, de: string }
  /** GitHub-Logins der Mitwirkenden des Releases (ohne „@“); ältere API-Stände liefern das Feld nicht. */
  contributors?: string[]
}

/** GitHub-Login (wie server/lib/contributors.ts) – nur solche Namen werden verlinkt. */
export const GITHUB_LOGIN = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$/

/** Mitwirkende eines Beitrags als Links auf ihr GitHub-Profil; ungültige Einträge fallen weg. */
export function postContributors(post: BlogPost): { login: string, url: string }[] {
  const list = Array.isArray(post.contributors) ? post.contributors : []
  return list
    .filter((login): login is string => typeof login === 'string' && GITHUB_LOGIN.test(login))
    .map((login) => ({ login, url: `https://github.com/${login}` }))
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

export interface MobileFile {
  url: string
  name: string
  sha256: string
  size: number
}

/** Neueste Handy-Apps aus dem signierten Kanal `mobile` (vom Server geprüft, siehe server/lib/mobile.ts). */
export interface MobileLatest {
  version: string
  publishedAt: string | null
  android: MobileFile | null
  ios: (MobileFile & { altstore: string }) | null
}

/** Fester Kanal: Release-Seite und AltStore-/SideStore-Quelle (die Adressen ändern sich nie). */
export const MOBILE_RELEASE_PAGE = `${REPO_URL}/releases/tag/mobile`
export const ALTSTORE_SOURCE = `${REPO_URL}/releases/download/mobile/altstore.json`
/** SHA-256 des Zertifikats, mit dem die APK signiert ist (zum Nachprüfen, z. B. mit apksigner). */
export const APK_CERT_SHA256 = '8a:aa:a3:d9:8b:06:a7:24:d1:c7:ee:d0:d0:de:f4:3a:27:75:de:f5:58:a4:51:2e:07:e7:81:61:63:1f:91:aa'

export function useMobileRelease() {
  return useApiFetch<{ mobile: MobileLatest | null }>('/v1/site/mobile-latest', { key: 'mobile-release', default: () => ({ mobile: null }) })
}

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

/** Kopf-Kosmetik im Format v2: `hats` und `companions` (Begleiter), ohne versteckte Teile. */
export function useHats() {
  return useApiFetch<{ hats: SiteHat[], companions: SiteHat[] }>('/v1/site/cosmetics', { key: 'hats', default: () => ({ hats: [], companions: [] }) })
}

export interface PublicEvent {
  id: string
  active: boolean
}

/** Öffentlicher Event-Stand (`GET /v1/events`, nur global). Dieselbe Adresse mit `text/event-stream` ist der alte Stream – useFetch fragt JSON. */
export function usePublicEvents() {
  return useApiFetch<{ events: PublicEvent[] }>('/v1/events', {
    key: 'public-events',
    default: () => ({ events: [] }),
    headers: { accept: 'application/json' },
  })
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
  const os = useState<'windows' | 'linux' | 'android' | 'ios' | 'other'>('visitor-os', () => {
    if (/Android/i.test(ua)) return 'android'
    if (/iPhone|iPad|iPod/i.test(ua)) return 'ios'
    if (/Windows/i.test(ua)) return 'windows'
    if (/Linux|X11/i.test(ua) && !/Android/i.test(ua)) return 'linux'
    return 'other'
  })
  return os
}
