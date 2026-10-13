import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { request, createServer, type IncomingHttpHeaders, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { createApp, defineEventHandler, toNodeListener } from 'h3'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { createWikiHandler, indexWikiDir, resolveWikiFile, routeWikiRequest, wikiTopLevel, type WikiFile } from '../server/lib/wiki'

const SITE = 'https://trs-launcher.theredstonee.de'
const WIKI = 'https://trs-launcher.wiki'

// Kleine Doku-Ausgabe wie aus `npm run generate` (docs-site): Seiten als <pfad>/index.html, Skripte unter /_docs/.
const FILES: Record<string, string> = {
  '404.html': '<!doctype html><title>404</title>',
  'en/index.html': '<!doctype html><title>EN</title>',
  'de/index.html': '<!doctype html><title>DE</title>',
  'de/getting-started/index.html': '<!doctype html><title>Erste Schritte</title>',
  'de/getting-started/_payload.json': '{}',
  '_docs/entry.abc123.js': 'console.log(1)',
  '_docs/entry.abc123.css': 'body{}',
  'icon.png': 'PNG',
  'llms.txt': '# TRS',
  'raw/en/help.md': '# Help',
  '__nuxt_content/docs_en/sql_dump.txt': 'x',
}

let dir: string
let files: Map<string, WikiFile>

beforeAll(() => {
  dir = mkdtempSync(join(tmpdir(), 'trs-wiki-'))
  for (const [rel, body] of Object.entries(FILES)) {
    const p = join(dir, rel)
    mkdirSync(dirname(p), { recursive: true })
    writeFileSync(p, body)
  }
  files = indexWikiDir(dir)
})

afterAll(() => rmSync(dir, { recursive: true, force: true }))

const route = (pathname: string, method = 'GET', rawUrl = pathname) =>
  routeWikiRequest({ method, pathname, rawUrl, files, topLevel: wikiTopLevel(files), siteUrl: SITE })

describe('Doku-Domain: Datei-Tabelle', () => {
  it('Seiten über <pfad>/index.html, Dateien genau, kein Ausbruch aus dem Ordner', () => {
    expect(resolveWikiFile(files, '/de')?.file).toBe(join(dir, 'de', 'index.html'))
    expect(resolveWikiFile(files, '/de/')?.file).toBe(join(dir, 'de', 'index.html'))
    expect(resolveWikiFile(files, '/de/getting-started')?.type).toBe('text/html; charset=utf-8')
    expect(resolveWikiFile(files, '/_docs/entry.abc123.js')?.type).toBe('text/javascript; charset=utf-8')
    expect(resolveWikiFile(files, '/_docs/entry.abc123.css')?.type).toBe('text/css; charset=utf-8')
    expect(resolveWikiFile(files, '/icon.png')?.type).toBe('image/png')
    expect(resolveWikiFile(files, '/de/../../etc/passwd')).toBeNull()
    expect(resolveWikiFile(files, '/de/getting-started/../../404.html')).toBeNull()
    expect(resolveWikiFile(files, 'de')).toBeNull()
    expect(resolveWikiFile(files, '/de\0')).toBeNull()
  })
})

describe('Doku-Domain: Regeln', () => {
  it('/ → Sprachwahl, vorhandene Seiten und Dateien → ausliefern', () => {
    expect(route('/').kind).toBe('choose-language')
    expect(route('/de/').kind).toBe('file')
    expect(route('/de/getting-started').kind).toBe('file')
    expect(route('/_docs/entry.abc123.js').kind).toBe('file')
    expect(route('/llms.txt').kind).toBe('file')
    expect(route('/robots.txt').kind).toBe('robots')
    expect(route('/sitemap.xml').kind).toBe('sitemap')
  })

  it('fehlende Doku-Seiten → 404-Seite der Doku, fehlende Doku-Dateien → 404', () => {
    expect(route('/de/gibt-es-nicht').kind).toBe('page-not-found')
    expect(route('/es').kind).toBe('page-not-found')
    expect(route('/_docs/alt.js').kind).toBe('asset-not-found')
    expect(route('/raw/en/fehlt.md').kind).toBe('asset-not-found')
  })

  it('alles andere → 301 auf dieselbe Adresse der Website (mit Query)', () => {
    expect(route('/features')).toEqual({ kind: 'to-website', location: `${SITE}/features` })
    expect(route('/blog/0.21.0', 'GET', '/blog/0.21.0?lang=de')).toEqual({ kind: 'to-website', location: `${SITE}/blog/0.21.0?lang=de` })
    // Dateien der Website gibt es auf der Doku-Domain nicht – auch nicht /_nuxt.
    expect(route('/_nuxt/app.js').kind).toBe('to-website')
    expect(route('/favicon.ico').kind).toBe('to-website')
    expect(route('/english').kind).toBe('to-website')
    // `//host` bleibt ein Pfad der Website (Ziel beginnt immer mit der Website-Adresse).
    expect(route('//evil.example/x').kind === 'to-website' && route('//evil.example/x')).toEqual({ kind: 'to-website', location: `${SITE}//evil.example/x` })
  })

  it('API nicht auf der Doku-Domain: 404 JSON statt Weiterleitung, auch für POST', () => {
    expect(route('/v1/me').kind).toBe('api-not-here')
    expect(route('/v1/capes', 'POST').kind).toBe('api-not-here')
    expect(route('/v1').kind).toBe('api-not-here')
  })

  it('nur GET/HEAD', () => {
    expect(route('/de/', 'HEAD').kind).toBe('file')
    expect(route('/de/', 'POST').kind).toBe('method-not-allowed')
    expect(route('/features', 'DELETE').kind).toBe('method-not-allowed')
  })

  it('/docs/… auf der Doku-Domain → derselbe Pfad ohne /docs', () => {
    expect(route('/docs/de/getting-started', 'GET', '/docs/de/getting-started?a=1')).toEqual({ kind: 'legacy-docs', location: '/de/getting-started?a=1' })
    expect(route('/docs')).toEqual({ kind: 'legacy-docs', location: '/' })
    expect(route('/docs//evil.example')).toEqual({ kind: 'legacy-docs', location: '/evil.example' })
  })
})

// Echter Server: Wiki-Handler als erste Schicht, danach eine „Website“, die jede Anfrage beantwortet.
describe('Doku-Domain: Host-Handler', () => {
  let server: Server
  let port: number
  let limited = false

  beforeAll(async () => {
    const app = createApp()
    app.use(createWikiHandler(() => ({
      hosts: new Set(['trs-launcher.wiki']),
      wikiUrl: WIKI,
      siteUrl: SITE,
      files: indexWikiDir(dir),
      scriptHashes: ["'sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='"],
      routes: [{ path: '/de', lang: 'de', key: '/' }, { path: '/en', lang: 'en', key: '/' }],
      limit: () => (limited ? 7 : null),
    })))
    app.use(defineEventHandler(() => 'website'))
    server = createServer(toNodeListener(app))
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', () => r()))
    port = (server.address() as AddressInfo).port
  })

  afterAll(() => new Promise<void>((r) => server.close(() => r())))

  function get(path: string, headers: Record<string, string>, method = 'GET'): Promise<{ status: number, headers: IncomingHttpHeaders, body: string }> {
    return new Promise((resolve, reject) => {
      const req = request({ host: '127.0.0.1', port, path, method, headers }, (res) => {
        const chunks: Buffer[] = []
        res.on('data', (c: Buffer) => chunks.push(c))
        res.on('end', () => resolve({ status: res.statusCode ?? 0, headers: res.headers, body: Buffer.concat(chunks).toString('utf8') }))
      })
      req.on('error', reject)
      req.end()
    })
  }
  const wiki = { Host: 'trs-launcher.wiki' }

  it('/ → 302 auf die Sprache aus Accept-Language bzw. Cookie', async () => {
    const r = await get('/', { ...wiki, 'Accept-Language': 'de-DE,de;q=0.9,en;q=0.5' })
    expect(r.status).toBe(302)
    expect(r.headers.location).toBe('/de')
    expect(r.headers['cache-control']).toBe('private, no-store')
    expect((await get('/', { ...wiki, 'Accept-Language': 'de', Cookie: 'trs_lang=es' })).headers.location).toBe('/es')
    expect((await get('/', { ...wiki })).headers.location).toBe('/en')
  })

  it('Seite: 200 HTML mit Doku-CSP (Hashes, kein unsafe-inline für Skripte), HSTS & Co.', async () => {
    const r = await get('/de/', wiki)
    expect(r.status).toBe(200)
    expect(r.body).toContain('<title>DE</title>')
    expect(r.headers['content-type']).toBe('text/html; charset=utf-8')
    const csp = String(r.headers['content-security-policy'])
    expect(csp).toContain("script-src 'self' 'wasm-unsafe-eval' 'sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='")
    expect(csp).toContain("frame-ancestors 'none'")
    expect(csp).not.toMatch(/script-src[^;]*'unsafe-inline'/)
    expect(r.headers['strict-transport-security']).toBe('max-age=63072000; includeSubDomains; preload')
    expect(r.headers['x-content-type-options']).toBe('nosniff')
    expect(r.headers['x-frame-options']).toBe('DENY')
    expect(r.headers['referrer-policy']).toBe('strict-origin-when-cross-origin')
    expect(r.headers['cache-control']).toContain('max-age=300')
  })

  it('gebaute Dateien: richtiger Typ, lange Cache-Zeit, ETag → 304', async () => {
    const r = await get('/_docs/entry.abc123.js', wiki)
    expect(r.status).toBe(200)
    expect(r.headers['content-type']).toBe('text/javascript; charset=utf-8')
    expect(r.headers['cache-control']).toBe('public, max-age=31536000, immutable')
    expect(r.body).toBe('console.log(1)')
    const again = await get('/_docs/entry.abc123.js', { ...wiki, 'If-None-Match': String(r.headers.etag) })
    expect(again.status).toBe(304)
    expect(again.body).toBe('')
    const head = await get('/icon.png', wiki, 'HEAD')
    expect(head.status).toBe(200)
    expect(head.headers['content-length']).toBe('3')
  })

  it('Hilfsdateien noindex; llms.txt als Text', async () => {
    expect((await get('/raw/en/help.md', wiki)).headers['x-robots-tag']).toBe('noindex')
    const l = await get('/llms.txt', wiki)
    expect(l.headers['content-type']).toBe('text/plain; charset=utf-8')
    expect(l.body).toBe('# TRS')
  })

  it('fehlende Doku-Seite → 404 mit der 404-Seite der Doku', async () => {
    const r = await get('/de/gibt-es-nicht', wiki)
    expect(r.status).toBe(404)
    expect(r.body).toContain('<title>404</title>')
    expect(r.headers['x-robots-tag']).toBe('noindex')
  })

  it('andere Pfade → 301 auf die Website, Query bleibt', async () => {
    const r = await get('/features?lang=de', wiki)
    expect(r.status).toBe(301)
    expect(r.headers.location).toBe(`${SITE}/features?lang=de`)
    expect(r.headers['strict-transport-security']).toBeTruthy()
    expect((await get('/_nuxt/app.js', wiki)).headers.location).toBe(`${SITE}/_nuxt/app.js`)
  })

  it('/v1 auf der Doku-Domain → 404 JSON wie die API', async () => {
    const r = await get('/v1/me', wiki, 'POST')
    expect(r.status).toBe(404)
    expect(r.headers['content-type']).toBe('application/json; charset=utf-8')
    expect(JSON.parse(r.body)).toMatchObject({ error: { code: 'not_found' } })
  })

  it('robots.txt und sitemap.xml der Doku-Domain', async () => {
    expect((await get('/robots.txt', wiki)).body).toContain(`Sitemap: ${WIKI}/sitemap.xml`)
    const s = await get('/sitemap.xml', wiki)
    expect(s.headers['content-type']).toBe('application/xml; charset=utf-8')
    expect(s.body).toContain(`<loc>${WIKI}/de</loc>`)
  })

  it('Host mit Port und Großbuchstaben zählt als Doku-Domain', async () => {
    expect((await get('/de/', { Host: 'TRS-Launcher.wiki:443' })).status).toBe(200)
  })

  it('andere Hosts gehen unverändert zur Website – X-Forwarded-Host allein schaltet nicht um', async () => {
    const r = await get('/de/', { Host: 'trs-launcher.theredstonee.de' })
    expect(r.status).toBe(200)
    expect(r.body).toBe('website')
    const forged = await get('/icon.png', { 'Host': 'trs-launcher.theredstonee.de', 'X-Forwarded-Host': 'trs-launcher.wiki' })
    expect(forged.body).toBe('website')
  })

  it('Rate-Limit: 429 für Weiterleitungen, Dateien bleiben frei', async () => {
    limited = true
    try {
      const r = await get('/features', wiki)
      expect(r.status).toBe(429)
      expect(r.headers['retry-after']).toBe('7')
      expect((await get('/_docs/entry.abc123.js', wiki)).status).toBe(200)
    } finally {
      limited = false
    }
  })
})
