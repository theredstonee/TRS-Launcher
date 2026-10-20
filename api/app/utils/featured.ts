// Empfohlene Modpacks (API.md §37): Typen und Anzeige-Regeln für Startseite und Team-Bereich.

export type FeaturedSource = 'modrinth' | 'curseforge'
export type FeaturedBadge = 'recommended' | 'new' | 'event'

export interface FeaturedText {
  title: string | null
  text: string | null
}

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
  texts: { en: FeaturedText, de: FeaturedText }
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

export const FEATURED_BADGES: FeaturedBadge[] = ['recommended', 'new', 'event']

/** Anzeige-Sprache der eigenen Texte: Deutsch nur für `de`, sonst Englisch. */
const textLang = (lang: string): 'en' | 'de' => (lang === 'de' ? 'de' : 'en')

/** Titel: eigener Titel (Sprache → Englisch), sonst der Titel der Plattform. */
export function featuredTitle(p: FeaturedPack, lang: string): string {
  return p.texts[textLang(lang)].title || p.texts.en.title || p.title
}

/** Text: eigener Text (Sprache → Englisch), sonst der Kurztext der Plattform. */
export function featuredText(p: FeaturedPack, lang: string): string {
  return p.texts[textLang(lang)].text || p.texts.en.text || p.summary
}

/** „Im TRS Launcher öffnen“ (der Launcher registriert das Protokoll). */
export function featuredDeepLink(p: Pick<FeaturedPack, 'source' | 'projectId'>): string {
  return `trs-launcher://modpack/${p.source}/${encodeURIComponent(p.projectId)}`
}

export const SOURCE_NAMES: Record<FeaturedSource, string> = { modrinth: 'Modrinth', curseforge: 'CurseForge' }

/**
 * Bild-Adresse für die Website: nur Pfad + Abfrage der API-Adresse (gleiche Herkunft, CSP `img-src 'self'`), damit
 * lokale Builds und andere Hosts nicht die Live-Domain laden. Fremde Pfade → `null`.
 */
export function sameOriginImage(url: string | null): string | null {
  if (!url) return null
  try {
    const u = new URL(url, 'https://x.invalid')
    return u.pathname.startsWith('/v1/site/featured-packs/') ? `${u.pathname}${u.search}` : null
  } catch {
    return null
  }
}

/** Neueste Minecraft-Version (die API liefert neueste zuerst). */
export const newestVersion = (p: FeaturedPack): string | null => p.gameVersions[0] ?? null

export const LOADER_NAMES: Record<string, string> = { fabric: 'Fabric', forge: 'Forge', neoforge: 'NeoForge', quilt: 'Quilt' }
