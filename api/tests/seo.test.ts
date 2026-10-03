import { describe, expect, it } from 'vitest'
import {
  alternates,
  blogPostingLd,
  breadcrumbLd,
  buildPageHead,
  buildRobots,
  buildSitemap,
  clampText,
  COMPETITOR_PATTERN,
  faqPageLd,
  localizedUrl,
  MOVED_PAGES,
  organizationLd,
  SITE_PAGES,
  softwareLd,
  websiteLd,
  withLang,
  type JsonLdNode,
  type PageHead,
} from '../shared/seo'
import { messages, type Lang } from '../app/utils/messages'
import { LANDING_IDS, landingPath, landingTexts, landingWords, SHOT_SIZES } from '../shared/landing'

const SITE = 'https://trs-launcher.theredstonee.de'

// --- kleine schema.org-Prüfung: Pflichtfelder je Typ (nach den Vorgaben der Google-Rich-Results) ---

const ABSOLUTE = /^https:\/\/[^\s]+$/

function get(obj: unknown, path: string): unknown {
  return path.split('.').reduce<unknown>((o, k) => (o && typeof o === 'object' ? (o as Record<string, unknown>)[k] : undefined), obj)
}

const REQUIRED: Record<string, string[]> = {
  Organization: ['name', 'url', 'logo.url'],
  WebSite: ['name', 'url'],
  SoftwareApplication: ['name', 'applicationCategory', 'operatingSystem', 'offers.price', 'offers.priceCurrency', 'downloadUrl'],
  BlogPosting: ['headline', 'datePublished', 'image', 'author.name', 'publisher', 'mainEntityOfPage'],
  FAQPage: ['mainEntity'],
  BreadcrumbList: ['itemListElement'],
}

/** Fehlerliste eines Knotens (leer = gültig). */
function validateNode(node: JsonLdNode): string[] {
  const type = String(node['@type'])
  const errors: string[] = []
  const required = REQUIRED[type]
  if (!required) return [`unknown type ${type}`]
  for (const path of required) {
    const v = get(node, path)
    if (v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0)) errors.push(`${type}: ${path} missing`)
  }
  if (type === 'BlogPosting') {
    if (String(node.headline).length > 110) errors.push('BlogPosting: headline > 110')
    for (const img of node.image as string[]) if (!ABSOLUTE.test(img)) errors.push(`BlogPosting: image not absolute ${img}`)
    if (Number.isNaN(Date.parse(String(node.datePublished)))) errors.push('BlogPosting: datePublished invalid')
  }
  if (type === 'FAQPage') {
    for (const q of node.mainEntity as { '@type': string, name: string, acceptedAnswer: { '@type': string, text: string } }[]) {
      if (q['@type'] !== 'Question' || !q.name || q.acceptedAnswer?.['@type'] !== 'Answer' || !q.acceptedAnswer.text) errors.push(`FAQPage: bad question ${q.name}`)
    }
  }
  if (type === 'BreadcrumbList') {
    ;(node.itemListElement as { position: number, name: string, item: string }[]).forEach((e, i) => {
      if (e.position !== i + 1 || !e.name || !ABSOLUTE.test(e.item)) errors.push(`BreadcrumbList: bad item ${i}`)
    })
  }
  if (type === 'SoftwareApplication') {
    for (const s of (node.screenshot as string[] | undefined) ?? []) if (!ABSOLUTE.test(s)) errors.push(`SoftwareApplication: screenshot not absolute ${s}`)
    if (get(node, 'offers.price') !== '0') errors.push('SoftwareApplication: price must be 0')
  }
  return errors
}

/** JSON-LD aus dem Kopf holen, parsen und prüfen. */
function ldOf(head: PageHead): JsonLdNode[] {
  const script = head.script.find((s) => s.type === 'application/ld+json')
  if (!script) return []
  expect(script.innerHTML).not.toMatch(/<\/?script/i)
  const parsed = JSON.parse(script.innerHTML) as { '@context': string, '@graph': JsonLdNode[] }
  expect(parsed['@context']).toBe('https://schema.org')
  for (const node of parsed['@graph']) expect(validateNode(node)).toEqual([])
  return parsed['@graph']
}

const metaOf = (head: PageHead, key: string) => head.meta.filter((m) => m.name === key || m.property === key).map((m) => m.content)

describe('Adressen und Sprachen', () => {
  it('Englisch ohne Zusatz, sonst ?lang=', () => {
    expect(localizedUrl(SITE, '/', 'en')).toBe(`${SITE}/`)
    expect(localizedUrl(SITE, '/download', 'de')).toBe(`${SITE}/download?lang=de`)
    expect(localizedUrl(`${SITE}/`, 'faq', 'es')).toBe(`${SITE}/faq?lang=es`)
  })

  it('hreflang: jede Sprache plus x-default (= Englisch/ohne Zusatz)', () => {
    expect(alternates(SITE, '/cosmetics')).toEqual([
      { hreflang: 'en', href: `${SITE}/cosmetics` },
      { hreflang: 'de', href: `${SITE}/cosmetics?lang=de` },
      { hreflang: 'es', href: `${SITE}/cosmetics?lang=es` },
      { hreflang: 'x-default', href: `${SITE}/cosmetics` },
    ])
  })

  it('Links behalten eine per Adresse gewählte Sprache (auch mit Anker)', () => {
    expect(withLang('/download#linux', 'de')).toBe('/download?lang=de#linux')
    expect(withLang('/blog', 'en')).toBe('/blog')
    expect(withLang('/blog', null)).toBe('/blog')
    expect(withLang('/faq?x=1', 'es')).toBe('/faq?x=1&lang=es')
    expect(withLang('/faq?lang=de', 'es')).toBe('/faq?lang=de')
    expect(withLang('https://example.com/', 'de')).toBe('https://example.com/')
  })

  it('Beschreibungen werden am Wortende gekürzt', () => {
    expect(clampText('kurz')).toBe('kurz')
    const long = 'wort '.repeat(60)
    const cut = clampText(long, 50)
    expect(cut.length).toBeLessThanOrEqual(50)
    expect(cut.endsWith('…')).toBe(true)
    expect(cut).not.toMatch(/wo…$/)
  })
})

describe('Kopf einer Seite', () => {
  const base = { siteUrl: SITE, title: 'Download TRS Launcher', description: 'Download it.' }

  it('kanonische Adresse = gerenderte Sprache, hreflang für alle Sprachen', () => {
    const de = buildPageHead({ ...base, path: '/download', lang: 'de' })
    expect(de.link.filter((l) => l.rel === 'canonical')).toEqual([{ rel: 'canonical', href: `${SITE}/download?lang=de` }])
    const alt = de.link.filter((l) => l.rel === 'alternate').map((l) => [l.hreflang, l.href])
    expect(alt).toEqual([
      ['en', `${SITE}/download`],
      ['de', `${SITE}/download?lang=de`],
      ['es', `${SITE}/download?lang=es`],
      ['x-default', `${SITE}/download`],
    ])
    const en = buildPageHead({ ...base, path: '/download', lang: 'en' })
    expect(en.link[0]).toEqual({ rel: 'canonical', href: `${SITE}/download` })
  })

  it('Open Graph und Twitter: Titel, Beschreibung, absolute Bild-URL, Sprache + Alternativen', () => {
    const h = buildPageHead({ ...base, path: '/download', lang: 'es' })
    expect(h.title).toBe('Download TRS Launcher')
    expect(metaOf(h, 'description')).toEqual(['Download it.'])
    expect(metaOf(h, 'og:title')).toEqual(['Download TRS Launcher'])
    expect(metaOf(h, 'og:description')).toEqual(['Download it.'])
    expect(metaOf(h, 'og:url')).toEqual([`${SITE}/download?lang=es`])
    expect(metaOf(h, 'og:image')).toEqual([`${SITE}/og.png`])
    expect(metaOf(h, 'og:image:width')).toEqual(['1260'])
    expect(metaOf(h, 'og:locale')).toEqual(['es_ES'])
    expect(metaOf(h, 'og:locale:alternate')).toEqual(['en_US', 'de_DE'])
    // mehrere og:locale:alternate brauchen eigene Schlüssel, sonst ersetzt einer den anderen
    expect(new Set(h.meta.filter((m) => m.property === 'og:locale:alternate').map((m) => m.key)).size).toBe(2)
    expect(metaOf(h, 'twitter:card')).toEqual(['summary_large_image'])
    expect(metaOf(h, 'twitter:image')).toEqual([`${SITE}/og.png`])
    expect(metaOf(h, 'robots')[0]).toMatch(/^index, follow/)
    expect(h.script).toEqual([])
  })

  it('Beitrag: article-Typ, Datum, eigenes Bild', () => {
    const h = buildPageHead({
      ...base,
      path: '/blog/0.6.4',
      lang: 'de',
      type: 'article',
      publishedTime: '2026-09-26',
      image: { url: 'https://raw.githubusercontent.com/x/y/main/public/news/0.6.4/tabs.png', alt: 'Tabs' },
    })
    expect(metaOf(h, 'og:type')).toEqual(['article'])
    expect(metaOf(h, 'article:published_time')).toEqual(['2026-09-26T12:00:00Z'])
    expect(metaOf(h, 'og:image')).toEqual(['https://raw.githubusercontent.com/x/y/main/public/news/0.6.4/tabs.png'])
    expect(metaOf(h, 'og:image:alt')).toEqual(['Tabs'])
    expect(metaOf(h, 'og:image:width')).toEqual([])
  })

  it('noindex', () => {
    const h = buildPageHead({ ...base, path: '/x', lang: 'en', noindex: true })
    expect(metaOf(h, 'robots')).toEqual(['noindex, nofollow'])
  })
})

describe('Strukturierte Daten (JSON-LD)', () => {
  it('Startseite: Organization, WebSite, SoftwareApplication sind gültig', () => {
    const h = buildPageHead({
      siteUrl: SITE,
      path: '/',
      lang: 'en',
      title: 't',
      description: 'd',
      jsonLd: [
        organizationLd(SITE),
        websiteLd(SITE, 'd'),
        softwareLd({
          siteUrl: SITE,
          lang: 'en',
          description: 'd',
          version: '0.6.4',
          releasedAt: '2026-09-26T10:00:00Z',
          screenshots: ['/shots/library.png', 'https://raw.githubusercontent.com/a.png'],
          featureList: ['A', 'B'],
          keywords: ['Minecraft launcher', 'Minecraft client'],
        }),
      ],
    })
    const graph = ldOf(h)
    expect(graph.map((n) => n['@type'])).toEqual(['Organization', 'WebSite', 'SoftwareApplication'])
    const app = graph[2]!
    expect(app).toMatchObject({
      name: 'TRS Launcher',
      applicationCategory: 'GameApplication',
      operatingSystem: 'Windows 10, Windows 11, Linux',
      softwareVersion: '0.6.4',
      downloadUrl: `${SITE}/download`,
      offers: { '@type': 'Offer', price: '0' },
      screenshot: [`${SITE}/shots/library.png`, 'https://raw.githubusercontent.com/a.png'],
      featureList: ['A', 'B'],
      keywords: 'Minecraft launcher, Minecraft client',
    })
    expect(graph[0]).toMatchObject({ logo: { url: `${SITE}/icon.png` } })
  })

  it('SoftwareApplication ohne Version/Screenshots bleibt gültig (GitHub nicht erreichbar)', () => {
    const node = softwareLd({ siteUrl: SITE, lang: 'de', description: 'd' })
    expect(validateNode(node)).toEqual([])
    expect(node).not.toHaveProperty('softwareVersion')
    expect(node).not.toHaveProperty('screenshot')
  })

  it('BlogPosting mit Datum, Bildern und Ersatzbild', () => {
    const post = blogPostingLd({
      siteUrl: SITE,
      lang: 'de',
      version: '0.6.4',
      headline: 'Das Werkstatt-Update',
      description: 'Neu',
      datePublished: '2026-09-26',
      images: ['https://raw.githubusercontent.com/a.png'],
    })
    expect(validateNode(post)).toEqual([])
    expect(post).toMatchObject({ url: `${SITE}/blog/0.6.4?lang=de`, datePublished: '2026-09-26T12:00:00Z', inLanguage: 'de' })
    const noImg = blogPostingLd({ siteUrl: SITE, lang: 'en', version: '0.1.0', headline: 'x'.repeat(200), description: 'd', datePublished: '2026-01-01' })
    expect(validateNode(noImg)).toEqual([])
    expect(noImg.image).toEqual([`${SITE}/og.png`])
  })

  it('FAQPage aus den sichtbaren Fragen jeder Sprache', () => {
    for (const lang of ['en', 'de', 'es'] as Lang[]) {
      const node = faqPageLd(SITE, lang, messages[lang].faq.items)
      expect(validateNode(node)).toEqual([])
      expect((node.mainEntity as unknown[]).length).toBe(messages[lang].faq.items.length)
    }
  })

  it('BreadcrumbList mit Positionen und absoluten Adressen in der Seitensprache', () => {
    const node = breadcrumbLd(SITE, 'es', [
      { name: 'Inicio', path: '/' },
      { name: 'Blog', path: '/blog' },
    ])
    expect(validateNode(node)).toEqual([])
    expect(node.itemListElement).toEqual([
      { '@type': 'ListItem', position: 1, name: 'Inicio', item: `${SITE}/?lang=es` },
      { '@type': 'ListItem', position: 2, name: 'Blog', item: `${SITE}/blog?lang=es` },
    ])
  })

  it('„<“ im Text kann den Skript-Block nicht beenden', () => {
    const h = buildPageHead({ siteUrl: SITE, path: '/faq', lang: 'en', title: 't', description: 'd', jsonLd: [faqPageLd(SITE, 'en', [{ q: '</script><script>x', a: 'a' }])] })
    const graph = ldOf(h)
    expect((graph[0]!.mainEntity as { name: string }[])[0]!.name).toBe('</script><script>x')
  })
})

describe('Sitemap', () => {
  const posts = [
    { version: '0.6.4', date: '2026-09-26' },
    { version: '0.6.3', date: '2026-09-25' },
    { version: '0.1.0', date: null },
  ]
  const xml = buildSitemap(SITE, posts, '2026-09-20T08:00:00.000Z')
  const urls = [...xml.matchAll(/<url>(.*?)<\/url>/g)].map((m) => m[1]!)
  const entry = (loc: string) => urls.find((u) => u.includes(`<loc>${loc}</loc>`))

  it('jede Seite und jeder Beitrag in allen drei Sprachen', () => {
    expect(xml).toContain('xmlns:xhtml="http://www.w3.org/1999/xhtml"')
    expect(urls).toHaveLength((SITE_PAGES.length + posts.length) * 3)
    expect(entry(`${SITE}/features?lang=de`)).toBeDefined()
    expect(entry(`${SITE}/blog/0.6.4?lang=es`)).toBeDefined()
    expect(xml).not.toContain('/admin')
    expect(xml).not.toContain('/v1/')
  })

  it('jeder Eintrag hat alle hreflang-Alternativen inkl. x-default', () => {
    for (const u of urls) {
      const links = [...u.matchAll(/<xhtml:link rel="alternate" hreflang="([\w-]+)" href="([^"]+)"\/>/g)].map((m) => m[1])
      expect(links).toEqual(['en', 'de', 'es', 'x-default'])
    }
    expect(entry(`${SITE}/download?lang=de`)).toContain(`hreflang="en" href="${SITE}/download"`)
  })

  it('lastmod: Seiten = Build, Blog = neuester Stand, Beiträge = Erscheinungsdatum', () => {
    expect(entry(`${SITE}/faq`)).toContain('<lastmod>2026-09-20T08:00:00.000Z</lastmod>')
    expect(entry(`${SITE}/blog`)).toContain('<lastmod>2026-09-26</lastmod>')
    expect(entry(`${SITE}/blog/0.6.3?lang=de`)).toContain('<lastmod>2026-09-25</lastmod>')
    expect(entry(`${SITE}/blog/0.1.0`)).not.toContain('<lastmod>')
    const later = buildSitemap(SITE, posts, '2026-09-30T00:00:00.000Z')
    expect(later).toMatch(new RegExp(`<loc>${SITE}/blog</loc><lastmod>2026-09-30T00:00:00.000Z</lastmod>`))
  })

  it('Sonderzeichen werden maskiert, ohne Build-Zeit kein lastmod für Seiten', () => {
    const x = buildSitemap(SITE, [], null)
    expect(x).toContain(`<loc>${SITE}/download?lang=de</loc>`)
    expect(x).not.toContain('&lang')
    expect(x.match(/<lastmod>/g)).toBeNull()
    const amp = buildSitemap('https://a.example/?x=1&y=2', [], null)
    expect(amp).toContain('&amp;y=2')
  })
})

describe('robots.txt', () => {
  const robots = buildRobots(`${SITE}/`)
  it('sperrt API und Admin, erlaubt Website-Daten und Umhang-Bilder, nennt die Sitemap', () => {
    const lines = robots.split('\n')
    expect(lines[0]).toBe('User-agent: *')
    expect(lines).toContain('Disallow: /v1/')
    expect(lines).toContain('Disallow: /admin')
    expect(lines).toContain('Allow: /v1/site/')
    expect(lines).toContain('Allow: /v1/capes/*.png')
    expect(lines).toContain('Allow: /v1/cosmetics/*.png')
    expect(lines).toContain('Allow: /v1/cosmetics/*/model.json')
    expect(lines).toContain('Allow: /v1/cosmetics/*/template.json')
    expect(lines).toContain(`Sitemap: ${SITE}/sitemap.xml`)
  })
})

describe('Texte für Suchmaschinen', () => {
  const langs: Lang[] = ['en', 'de', 'es']
  const pages = ['home', 'features', 'download', 'blog', 'cosmetics', 'faq', 'privacy'] as const

  it('jede Seite hat in jeder Sprache einen eigenen Titel (≤ 65) und eine Beschreibung (≤ 160)', () => {
    for (const lang of langs) {
      const seo = messages[lang].seo
      const titles = pages.map((p) => seo[p].title)
      expect(new Set(titles).size).toBe(pages.length)
      for (const p of pages) {
        expect(seo[p].title.length, `${lang} ${p} title`).toBeLessThanOrEqual(65)
        expect(seo[p].description.length, `${lang} ${p} description`).toBeGreaterThan(50)
        expect(seo[p].description.length, `${lang} ${p} description`).toBeLessThanOrEqual(160)
      }
    }
  })

  it('Funktionsseite und FAQ sind in allen Sprachen gleich aufgebaut', () => {
    const ids = messages.en.features.sections.map((s) => s.id)
    for (const lang of langs) {
      expect(messages[lang].features.sections.map((s) => s.id)).toEqual(ids)
      expect(messages[lang].faq.items).toHaveLength(messages.en.faq.items.length)
      for (const s of messages[lang].features.sections) if (s.img) expect(s.alt.length, `${lang} ${s.id} alt`).toBeGreaterThan(10)
    }
    expect(messages.es.faq.items[0]!.q).not.toBe(messages.en.faq.items[0]!.q)
  })

  it('keine anderen Launcher oder Clients mit Namen in den neuen Texten', () => {
    const text = JSON.stringify(
      langs.map((l) => [messages[l].seo, messages[l].features, messages[l].faq, messages[l].home.about, messages[l].footer, landingTexts[l]]),
    )
    expect(text).not.toMatch(COMPETITOR_PATTERN)
  })

  it('Startseite: Marke steht in der h1 und die Namensvarianten im Text', () => {
    for (const lang of langs) {
      const home = messages[lang].home
      expect(home.kicker.startsWith('TRS Launcher')).toBe(true)
      expect(home.about.text).toContain('Redstone Launcher')
      expect(home.about.text).toContain('TheRedstonee')
      expect(messages[lang].footer.tagline).toContain('Redstone Launcher')
      expect(messages[lang].seo.home.title.startsWith('TRS Launcher')).toBe(true)
    }
  })
})

describe('Themen-Seiten (shared/landing.ts)', () => {
  const langs: Lang[] = ['en', 'de', 'es']
  const pages = ['home', 'features', 'download', 'blog', 'cosmetics', 'faq', 'privacy'] as const

  it('stehen in der Sitemap', () => {
    for (const id of LANDING_IDS) expect(SITE_PAGES.map((p) => p.path)).toContain(landingPath(id))
  })

  it('umgezogene Seiten: /capes → /cosmetics (nur das Ziel in der Sitemap)', () => {
    expect(MOVED_PAGES['/capes']).toBe('/cosmetics')
    const paths = SITE_PAGES.map((p) => p.path)
    for (const [from, to] of Object.entries(MOVED_PAGES)) {
      expect(paths).not.toContain(from)
      expect(paths).toContain(to)
    }
  })

  it('Titel ≤ 65, Beschreibung 51–160, alles eindeutig (auch gegenüber den übrigen Seiten)', () => {
    for (const lang of langs) {
      const titles = [...pages.map((p) => messages[lang].seo[p].title), ...LANDING_IDS.map((id) => landingTexts[lang].pages[id].seo.title)]
      const descriptions = [...pages.map((p) => messages[lang].seo[p].description), ...LANDING_IDS.map((id) => landingTexts[lang].pages[id].seo.description)]
      expect(new Set(titles).size, `${lang} titles`).toBe(titles.length)
      expect(new Set(descriptions).size, `${lang} descriptions`).toBe(descriptions.length)
      for (const id of LANDING_IDS) {
        const { title, description } = landingTexts[lang].pages[id].seo
        expect(title.length, `${lang} ${id} title`).toBeLessThanOrEqual(65)
        expect(description.length, `${lang} ${id} description`).toBeGreaterThan(50)
        expect(description.length, `${lang} ${id} description`).toBeLessThanOrEqual(160)
      }
    }
  })

  it('das Hauptwort steht in Titel oder Beschreibung', () => {
    const keyword: Record<string, RegExp> = {
      'minecraft-launcher': /Minecraft/i,
      'redstone-launcher': /Redstone/i,
      modpacks: /Modpack/i,
      'fps-boost-pvp-client': /FPS.*PvP|PvP.*FPS/i,
    }
    for (const lang of langs) {
      for (const id of LANDING_IDS) {
        const { title, description } = landingTexts[lang].pages[id].seo
        expect(`${title} ${description}`, `${lang} ${id}`).toMatch(keyword[id]!)
      }
    }
  })

  it('600–1000 Wörter auf Englisch, Übersetzungen im selben Rahmen', () => {
    for (const id of LANDING_IDS) {
      const en = landingWords(landingTexts.en.pages[id])
      expect(en, `en ${id}`).toBeGreaterThanOrEqual(600)
      expect(en, `en ${id}`).toBeLessThanOrEqual(1000)
      for (const lang of ['de', 'es'] as const) {
        const n = landingWords(landingTexts[lang].pages[id])
        expect(n, `${lang} ${id}`).toBeGreaterThanOrEqual(520)
        expect(n, `${lang} ${id}`).toBeLessThanOrEqual(1150)
      }
    }
  })

  it('gleich aufgebaut in allen Sprachen, 4–6 FAQ, Bilder mit Maßen und Alt-Text', () => {
    for (const id of LANDING_IDS) {
      const en = landingTexts.en.pages[id]
      expect(en.faq.length).toBeGreaterThanOrEqual(4)
      expect(en.faq.length).toBeLessThanOrEqual(6)
      expect(en.sections.filter((s) => s.shot).length, id).toBeGreaterThanOrEqual(3)
      for (const lang of langs) {
        const p = landingTexts[lang].pages[id]
        expect(p.sections.map((s) => s.id), `${lang} ${id}`).toEqual(en.sections.map((s) => s.id))
        expect(p.sections.map((s) => s.shot?.file ?? null)).toEqual(en.sections.map((s) => s.shot?.file ?? null))
        expect(p.sections.map((s) => s.link?.to ?? null)).toEqual(en.sections.map((s) => s.link?.to ?? null))
        expect(p.faq).toHaveLength(en.faq.length)
        for (const s of p.sections) {
          if (!s.shot) continue
          expect(SHOT_SIZES[s.shot.file], s.shot.file).toBeDefined()
          expect(s.shot.alt.length, `${lang} ${id} ${s.id} alt`).toBeGreaterThan(20)
          expect(s.shot.caption.length).toBeGreaterThan(5)
        }
        const ld = faqPageLd(SITE, lang, p.faq, landingPath(id))
        expect(validateNode(ld)).toEqual([])
        expect(ld['@id']).toBe(`${localizedUrl(SITE, landingPath(id), lang)}#faq`)
      }
    }
    expect(landingTexts.es.pages.modpacks.title).not.toBe(landingTexts.en.pages.modpacks.title)
  })

  it('SoftwareApplication, WebSite und Organization tragen die Namensvarianten', () => {
    const app = softwareLd({ siteUrl: SITE, lang: 'en', description: 'd' })
    expect(app.alternateName).toEqual(expect.arrayContaining(['TRS', 'Redstone Launcher', 'The Redstone Launcher', 'TheRedstonee Launcher']))
    expect(app).toMatchObject({ creator: { '@type': 'Person', name: 'TheRedstonee' } })
    expect(websiteLd(SITE, 'd').alternateName).toEqual(expect.arrayContaining(['TRS Client', 'Redstone Launcher']))
    expect(organizationLd(SITE).alternateName).toContain('TRS')
  })
})

describe('Team-Seite und Stellen (§24.3)', () => {
  it('Team-Texte: Titel ≤ 65, Beschreibung 51–160 in jeder Sprache', async () => {
    const { teamTexts } = await import('../app/utils/team-i18n')
    for (const lang of ['en', 'de', 'es'] as const) {
      const seo = teamTexts[lang].seo.team
      expect(seo.title.length, `${lang} team title`).toBeLessThanOrEqual(65)
      expect(seo.description.length, `${lang} team description`).toBeGreaterThan(50)
      expect(seo.description.length, `${lang} team description`).toBeLessThanOrEqual(160)
    }
  })

  it('offene Stellen stehen mit allen Sprachen in der Sitemap, persönliche Seiten sind gesperrt', () => {
    const xml = buildSitemap(SITE, [], '2026-09-20T08:00:00.000Z', [{ id: 'moderator', updatedAt: '2026-09-27T10:00:00.000Z' }])
    expect(xml).toContain(`<loc>${SITE}/team/moderator?lang=de</loc>`)
    expect(xml).toMatch(new RegExp(`<loc>${SITE}/team</loc><lastmod>2026-09-27T10:00:00.000Z</lastmod>`))
    const lines = buildRobots(SITE).split('\n')
    for (const p of ['/auth/', '/login', '/applications']) expect(lines).toContain(`Disallow: ${p}`)
  })
})
