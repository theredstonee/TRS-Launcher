import { describe, expect, it } from 'vitest'
import { messages } from '../app/utils/messages'
import { buildDocsCsp, docsHome, docsPath, groupDocsRoutes, isDocsPath, isDocsRoot, isDocsRoute, pickDocsLang, type DocsRoute } from '../shared/docs'
import { buildRobots, buildSitemap, COMPETITOR_PATTERN, docsSitemapEntries } from '../shared/seo'

const SITE = 'https://trs-launcher.theredstonee.de'

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
  it('Startseite und Seiten je Sprache', () => {
    expect(docsHome('en')).toBe('/docs/en')
    expect(docsPath('/', 'de')).toBe('/docs/de')
    expect(docsPath('/launcher/instances', 'es')).toBe('/docs/es/launcher/instances')
  })

  it('erkennt /docs, /docs/ und Unterpfade – aber nicht /docsx', () => {
    expect(isDocsRoot('/docs')).toBe(true)
    expect(isDocsRoot('/docs/')).toBe(true)
    expect(isDocsRoot('/docs/en')).toBe(false)
    expect(isDocsPath('/docs/en/help')).toBe(true)
    expect(isDocsPath('/docsx')).toBe(false)
    expect(isDocsPath('/download')).toBe(false)
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

  it('Sitemap-Einträge: je Sprache eine URL, hreflang nur zu vorhandenen Sprachen, x-default Englisch', () => {
    const entries = docsSitemapEntries(`${SITE}/`, ROUTES)
    expect(entries).toHaveLength(6)
    const inst = entries.find((e) => e.includes(`<loc>${SITE}/docs/de/launcher/instances</loc>`))!
    expect(inst).toContain(`hreflang="en" href="${SITE}/docs/en/launcher/instances"`)
    expect(inst).toContain(`hreflang="de" href="${SITE}/docs/de/launcher/instances"`)
    expect(inst).not.toContain('hreflang="es"')
    expect(inst).toContain(`hreflang="x-default" href="${SITE}/docs/en/launcher/instances"`)
    expect(inst).toContain('<lastmod>2026-09-28T12:00:00Z</lastmod>')
    const onlyDe = entries.find((e) => e.includes('/help/crash'))!
    expect(onlyDe).toContain(`hreflang="x-default" href="${SITE}/docs/de/help/crash"`)
    const esHome = entries.find((e) => e.includes(`<loc>${SITE}/docs/es</loc>`))!
    expect(esHome).not.toContain('<lastmod>')
    expect(esHome).toContain('<priority>0.8</priority>')
  })

  it('die Website-Sitemap enthält die Doku-Seiten', () => {
    const xml = buildSitemap(SITE, [], '2026-09-20T08:00:00.000Z', [], [], [], ROUTES)
    for (const r of ROUTES) expect(xml).toContain(`<loc>${SITE}/docs${r.path}</loc>`)
    expect(xml).toContain(`<loc>${SITE}/</loc>`)
    // ohne Doku-Build: keine Doku-Einträge
    expect(buildSitemap(SITE, [], null)).not.toContain('/docs/')
  })

  it('robots.txt: Doku crawlbar, Such-Datenbank nicht', () => {
    const lines = buildRobots(SITE).split('\n')
    expect(lines).toContain('Disallow: /docs/__nuxt_content/')
    expect(lines).toContain('Allow: /docs/llms.txt')
    expect(lines.some((l) => l === 'Disallow: /docs/' || l === 'Disallow: /docs')).toBe(false)
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
