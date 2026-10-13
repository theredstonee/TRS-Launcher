import { describe, expect, it } from 'vitest'
import { messages } from '../app/utils/messages'
import {
  buildDocsCsp,
  docsHome,
  docsPageUrl,
  docsPath,
  groupDocsRoutes,
  isDocsPath,
  isDocsRoot,
  isDocsRoute,
  legacyDocsTarget,
  pickDocsLang,
  WIKI_URL,
  type DocsRoute,
} from '../shared/docs'
import { buildRobots, buildSitemap, COMPETITOR_PATTERN } from '../shared/seo'
import { buildWikiRobots, buildWikiSitemap, docsSitemapEntries } from '../server/lib/wiki'

const SITE = 'https://trs-launcher.theredstonee.de'
const WIKI = 'https://trs-launcher.wiki'

const ROUTES: DocsRoute[] = [
  { path: '/en', lang: 'en', key: '/', lastmod: '2026-09-29T10:00:00+02:00' },
  { path: '/de', lang: 'de', key: '/', lastmod: '2026-09-29T10:00:00+02:00' },
  { path: '/es', lang: 'es', key: '/', lastmod: null },
  { path: '/en/launcher/instances', lang: 'en', key: '/launcher/instances', lastmod: '2026-09-28T12:00:00Z' },
  { path: '/de/launcher/instances', lang: 'de', key: '/launcher/instances', lastmod: '2026-09-28T12:00:00Z' },
  // nur Deutsch vorhanden
  { path: '/de/help/crash', lang: 'de', key: '/help/crash', lastmod: '2026-09-27T12:00:00Z' },
]

describe('Doku: Pfade und Sprache', () => {
  it('Startseite und Seiten je Sprache – an der Wurzel der Doku-Domain', () => {
    expect(WIKI_URL).toBe(WIKI)
    expect(docsHome('en')).toBe('/en')
    expect(docsPath('/', 'de')).toBe('/de')
    expect(docsPath('/launcher/instances', 'es')).toBe('/es/launcher/instances')
    expect(docsPageUrl('de')).toBe(`${WIKI}/de`)
    expect(docsPageUrl('en', '/getting-started/android')).toBe(`${WIKI}/en/getting-started/android`)
  })

  it('erkennt alte Adressen /docs, /docs/ und Unterpfade – aber nicht /docsx', () => {
    expect(isDocsRoot('/docs')).toBe(true)
    expect(isDocsRoot('/docs/')).toBe(true)
    expect(isDocsRoot('/docs/en')).toBe(false)
    expect(isDocsPath('/docs/en/help')).toBe(true)
    expect(isDocsPath('/docsx')).toBe(false)
    expect(isDocsPath('/download')).toBe(false)
  })

  it('alte Adressen /docs/<rest> → Doku-Domain, Query bleibt', () => {
    expect(legacyDocsTarget('/docs/de/getting-started')).toBe(`${WIKI}/de/getting-started`)
    expect(legacyDocsTarget('/docs/en/help?x=1&lang=de')).toBe(`${WIKI}/en/help?x=1&lang=de`)
    expect(legacyDocsTarget('/docs/llms.txt')).toBe(`${WIKI}/llms.txt`)
    expect(legacyDocsTarget('/docs')).toBe(`${WIKI}/`)
    expect(legacyDocsTarget('/docs/?a=b')).toBe(`${WIKI}/?a=b`)
    expect(legacyDocsTarget('/docs/en', 'https://wiki.example/')).toBe('https://wiki.example/en')
    expect(legacyDocsTarget('/docsx')).toBeNull()
    expect(legacyDocsTarget('/download?docs=1')).toBeNull()
    expect(legacyDocsTarget('/download?x=/docs/en')).toBeNull()
  })

  it('Sprache: Cookie der Website, dann Accept-Language nach Gewicht, sonst Englisch', () => {
    expect(pickDocsLang(undefined)).toBe('en')
    expect(pickDocsLang('')).toBe('en')
    expect(pickDocsLang('de-DE,de;q=0.9,en;q=0.8')).toBe('de')
    expect(pickDocsLang('fr-FR,fr;q=0.9,es;q=0.5,en;q=0.4')).toBe('es')
    expect(pickDocsLang('en;q=0.2, de;q=0.8')).toBe('de')
    expect(pickDocsLang('fr, it')).toBe('en')
    expect(pickDocsLang('de;q=0, es')).toBe('es')
    expect(pickDocsLang('en-US', 'de')).toBe('de')
    expect(pickDocsLang('es', 'xx')).toBe('es')
  })
})

describe('Doku: CSP', () => {
  const hashes = ["'sha256-4xkckzyHcKgxy/kqGPB95CrN/aW6NaX2SVeq7s2F+d0='", "'sha256-PPJCYXQKIGoelFB21dN/ymb3jGX752rs/n9uv7t1/Mw='"]
  const csp = buildDocsCsp([...hashes, "'unsafe-inline'", 'https://evil.example'])
  const directives = Object.fromEntries(csp.split('; ').map((d) => [d.split(' ')[0], d.split(' ').slice(1)]))

  it('Skripte nur aus der eigenen Domain plus Hashes – kein unsafe-inline/unsafe-eval, keine fremden Quellen', () => {
    expect(directives['script-src']).toEqual(["'self'", "'wasm-unsafe-eval'", ...hashes])
    expect(csp).not.toContain("'unsafe-eval'")
    expect(directives['script-src']).not.toContain("'unsafe-inline'")
    expect(csp).not.toContain('evil.example')
  })

  it('gleiche Grenzen wie die Website (Frames, Objekte, Basis, Bilder)', () => {
    expect(directives['frame-ancestors']).toEqual(["'none'"])
    expect(directives['object-src']).toEqual(["'none'"])
    expect(directives['base-uri']).toEqual(["'none'"])
    expect(directives['default-src']).toEqual(["'self'"])
    expect(directives['img-src']).toEqual(["'self'", 'data:', 'blob:', 'https://raw.githubusercontent.com', 'https://textures.minecraft.net'])
    expect(directives['font-src']).toEqual(["'self'"])
    expect(directives['connect-src']).toEqual(["'self'"])
  })

  it('ohne Docs-Build: CSP ohne Hashes bleibt gültig', () => {
    expect(buildDocsCsp([])).toContain("script-src 'self' 'wasm-unsafe-eval';")
  })
})

describe('Doku: Routenliste und Sitemap', () => {
  it('prüft Einträge aus routes.json', () => {
    for (const r of ROUTES) expect(isDocsRoute(r), r.path).toBe(true)
    expect(isDocsRoute({ path: '/fr/x', lang: 'fr', key: '/x' })).toBe(false)
    expect(isDocsRoute({ path: '/en/../admin', lang: 'en', key: '/x' })).toBe(false)
    expect(isDocsRoute({ path: '/en/x"><script>', lang: 'en', key: '/x' })).toBe(false)
    expect(isDocsRoute(null)).toBe(false)
  })

  it('gruppiert Sprachfassungen je Seite', () => {
    const groups = groupDocsRoutes(ROUTES)
    expect([...groups.keys()]).toEqual(['/', '/launcher/instances', '/help/crash'])
    expect(groups.get('/')!.map((r) => r.lang)).toEqual(['en', 'de', 'es'])
  })

  it('Sitemap-Einträge: je Sprache eine URL auf der Doku-Domain, hreflang nur zu vorhandenen Sprachen, x-default Englisch', () => {
    const entries = docsSitemapEntries(`${WIKI}/`, ROUTES)
    expect(entries).toHaveLength(6)
    const inst = entries.find((e) => e.includes(`<loc>${WIKI}/de/launcher/instances</loc>`))!
    expect(inst).toContain(`hreflang="en" href="${WIKI}/en/launcher/instances"`)
    expect(inst).toContain(`hreflang="de" href="${WIKI}/de/launcher/instances"`)
    expect(inst).not.toContain('hreflang="es"')
    expect(inst).toContain(`hreflang="x-default" href="${WIKI}/en/launcher/instances"`)
    expect(inst).toContain('<lastmod>2026-09-28T12:00:00Z</lastmod>')
    const onlyDe = entries.find((e) => e.includes('/help/crash'))!
    expect(onlyDe).toContain(`hreflang="x-default" href="${WIKI}/de/help/crash"`)
    const esHome = entries.find((e) => e.includes(`<loc>${WIKI}/es</loc>`))!
    expect(esHome).not.toContain('<lastmod>')
    expect(esHome).toContain('<priority>0.8</priority>')
  })

  it('eigene Sitemap der Doku-Domain; die Website-Sitemap führt keine Doku-Seiten mehr', () => {
    const xml = buildWikiSitemap(WIKI, ROUTES)
    for (const r of ROUTES) expect(xml).toContain(`<loc>${WIKI}${r.path}</loc>`)
    expect(xml).not.toContain('theredstonee.de')
    expect(xml).not.toContain('/docs')
    const site = buildSitemap(SITE, [], '2026-09-20T08:00:00.000Z')
    expect(site).toContain(`<loc>${SITE}/</loc>`)
    expect(site).not.toContain('/docs')
    expect(site).not.toContain('trs-launcher.wiki')
  })

  it('robots.txt: Doku-Domain crawlbar (Such-Datenbank nicht), Website ohne /docs-Regeln', () => {
    const wiki = buildWikiRobots(`${WIKI}/`).split('\n')
    expect(wiki).toContain('Disallow: /__nuxt_content/')
    expect(wiki).toContain('Disallow: /_i18n/')
    expect(wiki).toContain('Allow: /llms.txt')
    expect(wiki).toContain(`Sitemap: ${WIKI}/sitemap.xml`)
    expect(wiki.some((l) => l === 'Disallow: /' || l.startsWith('Disallow: /_docs'))).toBe(false)
    expect(buildRobots(SITE)).not.toContain('/docs')
  })
})

describe('Doku: Links auf der Website', () => {
  it('Menü-/Fußzeilen-Text in allen Sprachen, kein Wiki mehr', () => {
    expect(messages.en.nav.docs).toBe('Docs')
    expect(messages.de.nav.docs).toBe('Doku')
    expect(messages.es.nav.docs).toBe('Documentación')
    for (const lang of ['en', 'de', 'es'] as const) {
      expect(messages[lang].footer.docs).toBeTruthy()
      expect(messages[lang].faq.docs).toBeTruthy()
      expect(JSON.stringify(messages[lang].footer)).not.toMatch(/wiki/i)
      expect(messages[lang].faq.lead).not.toMatch(/wiki/i)
      expect(messages[lang].faq.lead).not.toMatch(COMPETITOR_PATTERN)
    }
  })
})
