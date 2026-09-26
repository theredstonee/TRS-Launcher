// Suchmaschinen-Bausteine der Website, von Seiten (Kopf) und Server (Sitemap, robots.txt) gemeinsam genutzt.
// Alles hier ist reine Datenverarbeitung ohne Nuxt/Nitro – dadurch direkt mit vitest prüfbar.
//
// Sprachen: Englisch ist die Adresse ohne Zusatz (`/download`), Deutsch und Spanisch hängen `?lang=de|es`
// an. Die Seite rendert serverseitig genau die Sprache aus `?lang=` (ohne Cookie), deshalb kann jede
// Sprachfassung gecrawlt werden. `x-default` ist die Adresse ohne Zusatz (erkennt die Sprache selbst).

export type SeoLang = 'en' | 'de' | 'es'

export const SEO_LANGS: readonly SeoLang[] = ['en', 'de', 'es']
export const DEFAULT_LANG: SeoLang = 'en'
export const SITE_NAME = 'TRS Launcher'
export const DEFAULT_SITE_URL = 'https://trs-launcher.theredstonee.de'
export const REPO_URL = 'https://github.com/theredstonee/TRS-Launcher'
export const DISCORD_URL = 'https://dc.theredstonee.de'

/** Open-Graph-Gebietsschema je Sprache. */
export const OG_LOCALE: Record<SeoLang, string> = { en: 'en_US', de: 'de_DE', es: 'es_ES' }

/** Öffentliche, indexierbare Seiten (ohne Blog-Beiträge). Reihenfolge = Reihenfolge in der Sitemap. */
export const SITE_PAGES: readonly { path: string, priority: number }[] = [
  { path: '/', priority: 1 },
  { path: '/features', priority: 0.9 },
  { path: '/download', priority: 0.9 },
  { path: '/blog', priority: 0.7 },
  { path: '/capes', priority: 0.7 },
  { path: '/faq', priority: 0.6 },
  { path: '/privacy', priority: 0.3 },
]

export function isSeoLang(v: unknown): v is SeoLang {
  return v === 'en' || v === 'de' || v === 'es'
}

function trimBase(siteUrl: string): string {
  return siteUrl.replace(/\/+$/, '')
}

/** Absolute Adresse einer Seite in einer Sprache (Englisch ohne `?lang=`). */
export function localizedUrl(siteUrl: string, path: string, lang: SeoLang): string {
  const p = path.startsWith('/') ? path : `/${path}`
  const url = `${trimBase(siteUrl)}${p}`
  return lang === DEFAULT_LANG ? url : `${url}?lang=${lang}`
}

/** Relativer Link, der eine per Adresse gewählte Sprache mitnimmt (`/download#linux` → `/download?lang=de#linux`). */
export function withLang(to: string, lang: SeoLang | null): string {
  if (!lang || lang === DEFAULT_LANG || !to.startsWith('/')) return to
  const hashAt = to.indexOf('#')
  const base = hashAt === -1 ? to : to.slice(0, hashAt)
  const hash = hashAt === -1 ? '' : to.slice(hashAt)
  if (/[?&]lang=/.test(base)) return to
  return `${base}${base.includes('?') ? '&' : '?'}lang=${lang}${hash}`
}

export interface Alternate {
  hreflang: SeoLang | 'x-default'
  href: string
}

/** hreflang-Alternativen einer Seite: jede Sprache plus `x-default`. */
export function alternates(siteUrl: string, path: string, langs: readonly SeoLang[] = SEO_LANGS): Alternate[] {
  return [
    ...langs.map((lang) => ({ hreflang: lang, href: localizedUrl(siteUrl, path, lang) })),
    { hreflang: 'x-default' as const, href: localizedUrl(siteUrl, path, DEFAULT_LANG) },
  ]
}

/** Beschreibung auf höchstens `max` Zeichen kürzen (am Wortende, mit „…“). */
export function clampText(text: string, max = 160): string {
  const t = text.replace(/\s+/g, ' ').trim()
  if (t.length <= max) return t
  const cut = t.slice(0, max - 1)
  const space = cut.lastIndexOf(' ')
  return `${(space > max * 0.6 ? cut.slice(0, space) : cut).replace(/[\s,.;:–-]+$/, '')}…`
}

// --- Kopf einer Seite ----------------------------------------------------------------

export interface SeoImage {
  url: string
  alt?: string
  width?: number
  height?: number
}

export interface PageSeo {
  siteUrl: string
  /** Pfad ohne Sprache, z. B. `/blog/0.6.4`. */
  path: string
  /** Sprache, in der die Seite gerendert wird. */
  lang: SeoLang
  /** Vollständiger Titel (ohne Vorlage). */
  title: string
  description: string
  image?: SeoImage | null
  type?: 'website' | 'article'
  /** Nur für Beiträge: Erscheinungsdatum (YYYY-MM-DD oder ISO). */
  publishedTime?: string | null
  /** Sprachen, in denen es die Seite gibt (Standard: alle). */
  langs?: readonly SeoLang[]
  /** JSON-LD-Knoten; werden als ein `@graph` ausgegeben. */
  jsonLd?: JsonLdNode[]
  noindex?: boolean
}

/** `key` bestimmt, welche Einträge sich gegenseitig ersetzen (ohne key: nach name/property/rel). */
export interface HeadLink {
  rel: string
  href: string
  hreflang?: string
  key?: string
}

export interface HeadMeta {
  name?: string
  property?: string
  content: string
  key?: string
}

export interface HeadScript {
  type: string
  innerHTML: string
  key: string
}

export interface PageHead {
  title: string
  link: HeadLink[]
  meta: HeadMeta[]
  script: HeadScript[]
}

export const DEFAULT_OG_IMAGE: SeoImage = { url: '/og.png', width: 1260, height: 660, alt: 'TRS Launcher' }

/** Relative Bildadressen (`/og.png`) werden absolut – Crawler von Netzwerken brauchen volle URLs. */
export function absoluteUrl(siteUrl: string, url: string): string {
  if (/^https?:\/\//i.test(url)) return url
  return `${trimBase(siteUrl)}${url.startsWith('/') ? url : `/${url}`}`
}

/** Alle Kopf-Einträge einer Seite: kanonische Adresse, hreflang, Open Graph, Twitter, JSON-LD. */
export function buildPageHead(p: PageSeo): PageHead {
  const langs = p.langs ?? SEO_LANGS
  const lang = langs.includes(p.lang) ? p.lang : DEFAULT_LANG
  const canonical = localizedUrl(p.siteUrl, p.path, lang)
  const description = clampText(p.description)
  const image = p.image ?? DEFAULT_OG_IMAGE
  const imageUrl = absoluteUrl(p.siteUrl, image.url)

  const link: HeadLink[] = [
    { rel: 'canonical', href: canonical },
    ...alternates(p.siteUrl, p.path, langs).map((a) => ({ rel: 'alternate', hreflang: a.hreflang, href: a.href, key: `alt-${a.hreflang}` })),
  ]

  const meta: HeadMeta[] = [
    { name: 'description', content: description },
    { name: 'robots', content: p.noindex ? 'noindex, nofollow' : 'index, follow, max-image-preview:large' },
    { property: 'og:site_name', content: SITE_NAME },
    { property: 'og:type', content: p.type ?? 'website' },
    { property: 'og:title', content: p.title },
    { property: 'og:description', content: description },
    { property: 'og:url', content: canonical },
    { property: 'og:image', content: imageUrl },
    ...(image.width ? [{ property: 'og:image:width', content: String(image.width) }] : []),
    ...(image.height ? [{ property: 'og:image:height', content: String(image.height) }] : []),
    ...(image.alt ? [{ property: 'og:image:alt', content: image.alt }] : []),
    { property: 'og:locale', content: OG_LOCALE[lang] },
    ...langs.filter((l) => l !== lang).map((l) => ({ property: 'og:locale:alternate', content: OG_LOCALE[l], key: `og:locale:alternate:${l}` })),
    { name: 'twitter:card', content: 'summary_large_image' },
    { name: 'twitter:title', content: p.title },
    { name: 'twitter:description', content: description },
    { name: 'twitter:image', content: imageUrl },
    ...(image.alt ? [{ name: 'twitter:image:alt', content: image.alt }] : []),
  ]
  if (p.type === 'article' && p.publishedTime) {
    meta.push({ property: 'article:published_time', content: isoDate(p.publishedTime) })
  }

  const script: HeadScript[] = p.jsonLd?.length ? [{ type: 'application/ld+json', innerHTML: serializeJsonLd(p.jsonLd), key: 'ld-json' }] : []
  return { title: p.title, link, meta, script }
}

/** `2026-09-26` → `2026-09-26T12:00:00Z` (Mittag UTC, damit das Datum in jeder Zeitzone stimmt). */
export function isoDate(d: string): string {
  return /^\d{4}-\d{2}-\d{2}$/.test(d) ? `${d}T12:00:00Z` : d
}

// --- Strukturierte Daten (schema.org, JSON-LD) ----------------------------------------

export type JsonLdNode = { '@type': string | string[], [key: string]: unknown }

/** Mehrere Knoten als ein `@graph`; `<` wird maskiert, damit kein `</script>` im Text den Block beendet. */
export function serializeJsonLd(nodes: JsonLdNode[]): string {
  return JSON.stringify({ '@context': 'https://schema.org', '@graph': nodes }).replace(/</g, '\\u003c')
}

export function organizationId(siteUrl: string): string {
  return `${trimBase(siteUrl)}/#organization`
}

export function websiteId(siteUrl: string): string {
  return `${trimBase(siteUrl)}/#website`
}

export function softwareId(siteUrl: string): string {
  return `${trimBase(siteUrl)}/#software`
}

export function organizationLd(siteUrl: string): JsonLdNode {
  return {
    '@type': 'Organization',
    '@id': organizationId(siteUrl),
    name: SITE_NAME,
    url: `${trimBase(siteUrl)}/`,
    logo: { '@type': 'ImageObject', url: absoluteUrl(siteUrl, '/icon.png'), width: 512, height: 512 },
    sameAs: [REPO_URL, DISCORD_URL],
  }
}

export function websiteLd(siteUrl: string, description: string): JsonLdNode {
  return {
    '@type': 'WebSite',
    '@id': websiteId(siteUrl),
    name: SITE_NAME,
    alternateName: 'TRS',
    url: `${trimBase(siteUrl)}/`,
    description,
    inLanguage: [...SEO_LANGS],
    publisher: { '@id': organizationId(siteUrl) },
  }
}

export interface SoftwareInput {
  siteUrl: string
  lang: SeoLang
  description: string
  version?: string | null
  releasedAt?: string | null
  screenshots?: string[]
  featureList?: string[]
  keywords?: string[]
}

export function softwareLd(s: SoftwareInput): JsonLdNode {
  const shots = (s.screenshots ?? []).map((u) => absoluteUrl(s.siteUrl, u))
  return {
    '@type': 'SoftwareApplication',
    '@id': softwareId(s.siteUrl),
    name: SITE_NAME,
    url: `${trimBase(s.siteUrl)}/`,
    description: s.description,
    applicationCategory: 'GameApplication',
    applicationSubCategory: 'Minecraft launcher',
    operatingSystem: 'Windows 10, Windows 11, Linux',
    downloadUrl: localizedUrl(s.siteUrl, '/download', DEFAULT_LANG),
    installUrl: localizedUrl(s.siteUrl, '/download', DEFAULT_LANG),
    ...(s.version ? { softwareVersion: s.version } : {}),
    ...(s.releasedAt ? { dateModified: isoDate(s.releasedAt) } : {}),
    offers: { '@type': 'Offer', price: '0', priceCurrency: 'EUR', availability: 'https://schema.org/InStock' },
    isAccessibleForFree: true,
    license: 'https://www.gnu.org/licenses/gpl-3.0.html',
    image: absoluteUrl(s.siteUrl, DEFAULT_OG_IMAGE.url),
    ...(shots.length ? { screenshot: shots } : {}),
    ...(s.featureList?.length ? { featureList: s.featureList } : {}),
    ...(s.keywords?.length ? { keywords: s.keywords.join(', ') } : {}),
    inLanguage: s.lang,
    author: { '@id': organizationId(s.siteUrl) },
    publisher: { '@id': organizationId(s.siteUrl) },
  }
}

export interface BlogPostingInput {
  siteUrl: string
  lang: SeoLang
  version: string
  headline: string
  description: string
  datePublished?: string | null
  images?: string[]
}

export function blogPostingLd(b: BlogPostingInput): JsonLdNode {
  const url = localizedUrl(b.siteUrl, `/blog/${b.version}`, b.lang)
  const images = (b.images?.length ? b.images : [DEFAULT_OG_IMAGE.url]).map((u) => absoluteUrl(b.siteUrl, u))
  return {
    '@type': 'BlogPosting',
    '@id': `${url}#post`,
    headline: clampText(b.headline, 110),
    description: b.description,
    url,
    mainEntityOfPage: url,
    image: images,
    ...(b.datePublished ? { datePublished: isoDate(b.datePublished), dateModified: isoDate(b.datePublished) } : {}),
    inLanguage: b.lang,
    author: { '@type': 'Organization', '@id': organizationId(b.siteUrl), name: SITE_NAME, url: `${trimBase(b.siteUrl)}/` },
    publisher: { '@id': organizationId(b.siteUrl) },
    about: { '@id': softwareId(b.siteUrl) },
  }
}

export function faqPageLd(siteUrl: string, lang: SeoLang, items: { q: string, a: string }[]): JsonLdNode {
  return {
    '@type': 'FAQPage',
    '@id': `${localizedUrl(siteUrl, '/faq', lang)}#faq`,
    inLanguage: lang,
    mainEntity: items.map((i) => ({ '@type': 'Question', name: i.q, acceptedAnswer: { '@type': 'Answer', text: i.a } })),
  }
}

/** Brotkrumen: erster Eintrag ist immer die Startseite. */
export function breadcrumbLd(siteUrl: string, lang: SeoLang, trail: { name: string, path: string }[]): JsonLdNode {
  return {
    '@type': 'BreadcrumbList',
    itemListElement: trail.map((t, i) => ({ '@type': 'ListItem', position: i + 1, name: t.name, item: localizedUrl(siteUrl, t.path, lang) })),
  }
}

// --- Sitemap und robots.txt ------------------------------------------------------------

export interface SitemapPost {
  version: string
  date: string | null
}

function escapeXml(s: string): string {
  return s.replace(/[<>&'"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', "'": '&apos;', '"': '&quot;' })[c]!)
}

function sitemapEntries(siteUrl: string, path: string, lastmod: string | null, priority: number): string[] {
  const alt = alternates(siteUrl, path)
    .map((a) => `<xhtml:link rel="alternate" hreflang="${a.hreflang}" href="${escapeXml(a.href)}"/>`)
    .join('')
  return SEO_LANGS.map(
    (lang) =>
      `<url><loc>${escapeXml(localizedUrl(siteUrl, path, lang))}</loc>${lastmod ? `<lastmod>${lastmod}</lastmod>` : ''}<priority>${priority.toFixed(1)}</priority>${alt}</url>`,
  )
}

/** Neuestes Datum (ISO oder YYYY-MM-DD), `null` wenn keins gültig ist. */
function latest(dates: (string | null | undefined)[]): string | null {
  let best: string | null = null
  let bestMs = -Infinity
  for (const d of dates) {
    if (!d) continue
    const ms = Date.parse(isoDate(d))
    if (Number.isFinite(ms) && ms > bestMs) {
      best = d
      bestMs = ms
    }
  }
  return best
}

/**
 * Sitemap mit allen Seiten und Beiträgen, jede Sprachfassung als eigener Eintrag mit allen
 * hreflang-Alternativen. lastmod: Seiten = Zeitpunkt des Builds, Blog-Übersicht = neuester von Build
 * und letztem Beitrag, Beiträge = Erscheinungsdatum.
 */
export function buildSitemap(siteUrl: string, posts: SitemapPost[], buildTime: string | null): string {
  const newestPost = latest(posts.map((p) => p.date))
  const urls = [
    ...SITE_PAGES.flatMap((pg) => sitemapEntries(siteUrl, pg.path, pg.path === '/blog' ? latest([buildTime, newestPost]) : buildTime, pg.priority)),
    ...posts.flatMap((p) => sitemapEntries(siteUrl, `/blog/${p.version}`, p.date, 0.5)),
  ]
  return `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n${urls.join('\n')}\n</urlset>\n`
}

/**
 * robots.txt: alles außer API und Admin darf gecrawlt werden. Ausnahmen in /v1: die öffentlichen
 * Website-Daten (Google rendert Seiten mit denselben Abrufen) und die Umhang-Bilder (Bildersuche).
 */
export function buildRobots(siteUrl: string): string {
  return [
    'User-agent: *',
    'Allow: /v1/site/',
    'Allow: /v1/capes/*.png',
    'Disallow: /v1/',
    'Disallow: /admin',
    '',
    `Sitemap: ${trimBase(siteUrl)}/sitemap.xml`,
    '',
  ].join('\n')
}
