import { createReadStream, existsSync, readdirSync, statSync } from 'node:fs'
import { extname, join, relative, sep } from 'node:path'
import {
  defineEventHandler,
  getCookie,
  getHeader,
  getRequestHost,
  send,
  sendStream,
  setResponseHeader,
  setResponseHeaders,
  setResponseStatus,
  type EventHandler,
  type H3Event,
} from 'h3'
import { buildDocsCsp, docsHome, docsPath, groupDocsRoutes, LEGACY_DOCS_BASE, pickDocsLang, type DocsRoute } from '../../shared/docs'
import { isApiPath, SECURITY_HEADERS } from './headers'

// Doku-Domain (https://trs-launcher.wiki): dieselbe App wie die Website, unterschieden nur am Host-Header.
//
// Der Handler sitzt als ERSTE Schicht im h3-Stack (server/plugins/02.wiki.ts) – vor Nitros Route-Regeln, dem
// Static-Handler der Website und aller Middleware. So kann auf der Doku-Domain nie eine Datei oder Seite der Website
// ausgeliefert werden (beide Apps haben z. B. /icon.png und /shots/), und umgekehrt liegt die Doku nicht in den
// Public-Assets der Website. Für andere Hosts gibt er nichts zurück (h3 macht mit der nächsten Schicht weiter).
//
// Regeln auf der Doku-Domain (reine Funktion `routeWikiRequest`, getestet in tests/wiki.test.ts):
// - /v1/…                       → 404 JSON wie die API (die API gibt es nur auf der Website-Domain; keine
//                                 Weiterleitung, damit Clients nicht stillschweigend umgelenkt werden und keine
//                                 Zugangsdaten an eine andere Adresse gehen).
// - andere Methoden als GET/HEAD → 405.
// - /                            → 302 auf /<sprache> (Cookie trs_lang, sonst Accept-Language, sonst en).
// - /docs, /docs/…               → 301 auf denselben Pfad ohne /docs (alte Links mit falschem Host).
// - /robots.txt, /sitemap.xml    → eigene Fassung der Doku-Domain (aus routes.json des Docs-Builds).
// - vorhandene Datei             → ausliefern (…/index.html für Seiten), mit CSP aus den Skript-Hashes.
// - /en|de|es/… ohne Datei       → 404-Seite der Doku (404.html, Status 404).
// - sonstige Doku-Ordner (/_docs/, /raw/, …) ohne Datei → 404 Text.
// - alles andere                 → 301 auf dieselbe Adresse (Pfad + Query) der Website.

/** Eine Datei der Doku-Ausgabe. */
export interface WikiFile {
  file: string
  size: number
  etag: string
  type: string
}

const TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.md': 'text/markdown; charset=utf-8',
  '.xml': 'application/xml; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
  '.avif': 'image/avif',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
  '.wasm': 'application/wasm',
  '.webmanifest': 'application/manifest+json',
}

export function contentTypeOf(path: string): string {
  return TYPES[extname(path).toLowerCase()] ?? 'application/octet-stream'
}

/**
 * Alle Dateien der Doku-Ausgabe als Tabelle `/<pfad>` → Datei. Anfragen werden nur in dieser Tabelle nachgeschlagen –
 * kein Zusammensetzen von Dateipfaden aus der Adresse (kein `..`-Ausbruch möglich). Symlinks werden übersprungen.
 */
export function indexWikiDir(dir: string): Map<string, WikiFile> {
  const files = new Map<string, WikiFile>()
  if (!dir || !existsSync(dir)) return files
  const walk = (d: string) => {
    for (const ent of readdirSync(d, { withFileTypes: true })) {
      const p = join(d, ent.name)
      if (ent.isSymbolicLink()) continue
      if (ent.isDirectory()) walk(p)
      else if (ent.isFile()) {
        const st = statSync(p)
        const key = `/${relative(dir, p).split(sep).join('/')}`
        files.set(key, { file: p, size: st.size, etag: `W/"${st.size.toString(36)}-${Math.floor(st.mtimeMs).toString(36)}"`, type: contentTypeOf(p) })
      }
    }
  }
  walk(dir)
  return files
}

/** Datei zu einem (dekodierten) Pfad: genau so, sonst `<pfad>/index.html` (Seiten), sonst `<pfad>.html`. */
export function resolveWikiFile(files: ReadonlyMap<string, WikiFile>, pathname: string): WikiFile | null {
  if (!pathname.startsWith('/') || pathname.includes('\0')) return null
  if (!pathname.endsWith('/')) {
    const exact = files.get(pathname)
    if (exact) return exact
  }
  const bare = pathname.replace(/\/+$/, '')
  return files.get(`${bare}/index.html`) ?? (bare ? files.get(`${bare}.html`) : undefined) ?? null
}

/** Erste Pfad-Segmente der Doku-Ausgabe (Ordner und Dateien an der Wurzel, z. B. `_docs`, `raw`, `icon.png`). */
export function wikiTopLevel(files: ReadonlyMap<string, WikiFile>): Set<string> {
  const top = new Set<string>()
  for (const key of files.keys()) top.add(key.split('/')[1]!)
  return top
}

export type WikiAction =
  | { kind: 'api-not-here' }
  | { kind: 'method-not-allowed' }
  | { kind: 'choose-language' }
  | { kind: 'legacy-docs', location: string }
  | { kind: 'robots' }
  | { kind: 'sitemap' }
  | { kind: 'file', file: WikiFile }
  | { kind: 'page-not-found' }
  | { kind: 'asset-not-found' }
  | { kind: 'to-website', location: string }

const DOC_LANG_PATH = /^\/(?:en|de|es)(?:\/|$)/

/**
 * Was passiert mit einer Anfrage auf der Doku-Domain? `pathname` dekodiert (für die Datei-Tabelle), `rawUrl` = Pfad +
 * Query wie vom Client geschickt (für Weiterleitungen, bleibt kodiert).
 */
export function routeWikiRequest(input: {
  method: string
  pathname: string
  rawUrl: string
  files: ReadonlyMap<string, WikiFile>
  topLevel: ReadonlySet<string>
  siteUrl: string
}): WikiAction {
  const { method, pathname, rawUrl, files, topLevel, siteUrl } = input
  if (isApiPath(pathname)) return { kind: 'api-not-here' }
  if (method !== 'GET' && method !== 'HEAD') return { kind: 'method-not-allowed' }
  if (pathname === '/' || pathname === '') return { kind: 'choose-language' }
  if (pathname === LEGACY_DOCS_BASE || pathname.startsWith(`${LEGACY_DOCS_BASE}/`)) {
    const q = rawUrl.indexOf('?')
    const rawPath = q < 0 ? rawUrl : rawUrl.slice(0, q)
    const rest = rawPath.slice(LEGACY_DOCS_BASE.length) || '/'
    // `//…` am Anfang wäre für den Browser ein anderer Host – auf einen Schrägstrich kürzen.
    return { kind: 'legacy-docs', location: `${rest.replace(/^\/+/, '/')}${q < 0 ? '' : rawUrl.slice(q)}` }
  }
  if (pathname === '/robots.txt') return { kind: 'robots' }
  if (pathname === '/sitemap.xml') return { kind: 'sitemap' }
  const file = resolveWikiFile(files, pathname)
  if (file) return { kind: 'file', file }
  if (DOC_LANG_PATH.test(pathname)) return { kind: 'page-not-found' }
  if (topLevel.has(pathname.split('/')[1]!)) return { kind: 'asset-not-found' }
  return { kind: 'to-website', location: `${siteUrl.replace(/\/+$/, '')}${rawUrl.startsWith('/') ? rawUrl : `/${rawUrl}`}` }
}

/** Sitemap der Doku-Domain: je Sprachfassung ein Eintrag, hreflang nur zu vorhandenen Sprachen, `x-default` = Englisch. */
export function docsSitemapEntries(wikiUrl: string, routes: readonly DocsRoute[]): string[] {
  const base = wikiUrl.replace(/\/+$/, '')
  const out: string[] = []
  for (const [key, list] of groupDocsRoutes(routes)) {
    const def = list.find((r) => r.lang === 'en') ?? list[0]!
    const alt = [
      ...list.map((r) => `<xhtml:link rel="alternate" hreflang="${r.lang}" href="${escapeXml(`${base}${docsPath(key, r.lang)}`)}"/>`),
      `<xhtml:link rel="alternate" hreflang="x-default" href="${escapeXml(`${base}${docsPath(key, def.lang)}`)}"/>`,
    ].join('')
    const priority = key === '/' ? 0.8 : 0.6
    for (const r of list) {
      const lastmod = r.lastmod && !Number.isNaN(Date.parse(r.lastmod)) ? `<lastmod>${escapeXml(r.lastmod)}</lastmod>` : ''
      out.push(`<url><loc>${escapeXml(`${base}${docsPath(key, r.lang)}`)}</loc>${lastmod}<priority>${priority.toFixed(1)}</priority>${alt}</url>`)
    }
  }
  return out
}

export function buildWikiSitemap(wikiUrl: string, routes: readonly DocsRoute[]): string {
  return `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n${docsSitemapEntries(wikiUrl, routes).join('\n')}\n</urlset>\n`
}

/** robots.txt der Doku-Domain: Seiten crawlbar, Such-Datenbank und Sprachdateien nicht. */
export function buildWikiRobots(wikiUrl: string): string {
  return [
    'User-agent: *',
    'Allow: /llms.txt',
    'Allow: /llms-full.txt',
    'Disallow: /__nuxt_content/',
    'Disallow: /_i18n/',
    '',
    `Sitemap: ${wikiUrl.replace(/\/+$/, '')}/sitemap.xml`,
    '',
  ].join('\n')
}

/** Header jeder Antwort der Doku-Domain (auch Weiterleitungen und Fehler). */
export function wikiSecurityHeaders(csp: string): Record<string, string> {
  return {
    'Strict-Transport-Security': 'max-age=63072000; includeSubDomains; preload',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'strict-origin-when-cross-origin',
    'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=()',
    'Cross-Origin-Opener-Policy': 'same-origin',
    'Cross-Origin-Resource-Policy': 'same-origin',
    'Content-Security-Policy': csp,
  }
}

const NOINDEX_DIRS = /^\/(?:raw|__nuxt_content|_i18n)\//

/** Cache-Regeln je Datei: gebaute Skripte/Stile (Hash im Namen) ein Jahr, sonst kurz und neu prüfen (ETag). */
export function wikiCacheHeaders(pathname: string, file: WikiFile): Record<string, string> {
  if (pathname.startsWith('/_docs/')) return { 'Cache-Control': 'public, max-age=31536000, immutable' }
  if (NOINDEX_DIRS.test(pathname)) return { 'Cache-Control': 'public, max-age=300', 'X-Robots-Tag': 'noindex' }
  if (pathname === '/llms.txt' || pathname === '/llms-full.txt') return { 'Cache-Control': 'public, max-age=3600' }
  if (file.type.startsWith('text/html')) return { 'Cache-Control': 'public, max-age=300, stale-while-revalidate=3600' }
  return { 'Cache-Control': 'public, max-age=3600, stale-while-revalidate=86400' }
}

export interface WikiSite {
  /** Hosts der Doku (klein, ohne Port). */
  hosts: ReadonlySet<string>
  /** Kanonische Adresse der Doku (`https://trs-launcher.wiki`). */
  wikiUrl: string
  /** Website (Ziel aller anderen Pfade). */
  siteUrl: string
  files: ReadonlyMap<string, WikiFile>
  scriptHashes: readonly string[]
  routes: readonly DocsRoute[]
  /** Rate-Limit je Anfrage (nicht für vorhandene Dateien): `null` = ok, sonst Sekunden bis zum nächsten Versuch. */
  limit?: (event: H3Event) => number | null
}

/** Host der Anfrage – nur der Host-Header (X-Forwarded-Host kann jeder Client setzen: Cache-Vergiftung). */
export function requestHostname(event: H3Event): string {
  return getRequestHost(event, { xForwardedHost: false }).toLowerCase().replace(/:\d+$/, '').replace(/\.$/, '')
}

/**
 * h3-Handler der Doku-Domain. `load` liefert die Einstellungen (einmal, beim ersten Aufruf); scheitert das, laufen
 * alle Anfragen normal weiter (Website), die Doku-Domain bekommt dann 503.
 */
export function createWikiHandler(load: () => Promise<WikiSite> | WikiSite): EventHandler {
  let site: Promise<WikiSite> | null = null
  let cache: { site: WikiSite, topLevel: Set<string>, csp: string, headers: Record<string, string> } | null = null
  return defineEventHandler(async (event) => {
    site ??= Promise.resolve().then(load)
    let s: WikiSite
    try {
      s = await site
    } catch (err) {
      console.error('[trs-wiki] Doku-Domain nicht verfügbar', err)
      site = null
      return
    }
    if (!s.hosts.has(requestHostname(event))) return
    if (cache?.site !== s) {
      const csp = buildDocsCsp(s.scriptHashes)
      cache = { site: s, topLevel: wikiTopLevel(s.files), csp, headers: wikiSecurityHeaders(csp) }
    }
    const { topLevel, headers } = cache
    setResponseHeaders(event, headers)

    const rawUrl = event.node.req.originalUrl || event.node.req.url || '/'
    const rawPath = rawUrl.split('?')[0]!
    let pathname: string
    try {
      pathname = decodeURIComponent(rawPath)
    } catch {
      return plain(event, 400, 'Bad request')
    }

    const action = routeWikiRequest({ method: event.method, pathname, rawUrl, files: s.files, topLevel, siteUrl: s.siteUrl })

    // Dateien sind billig (wie der Static-Handler der Website: kein Limit); Weiterleitungen, 404 und Erzeugtes zählen.
    if (action.kind !== 'file') {
      const retry = s.limit?.(event) ?? null
      if (retry !== null) {
        setResponseHeaders(event, { 'Retry-After': String(retry) })
        if (action.kind === 'api-not-here') return json(event, 429, { error: { code: 'rate_limited', message: 'Too many requests', retryAfter: retry } })
        return plain(event, 429, 'Too many requests')
      }
    }

    switch (action.kind) {
      case 'api-not-here':
        setResponseHeaders(event, SECURITY_HEADERS)
        return json(event, 404, { error: { code: 'not_found', message: `Not found – the API is served at ${s.siteUrl}/v1` } })
      case 'method-not-allowed':
        setResponseHeader(event, 'Allow', 'GET, HEAD')
        return plain(event, 405, 'Method not allowed')
      case 'choose-language': {
        const lang = pickDocsLang(getHeader(event, 'accept-language'), getCookie(event, 'trs_lang'))
        setResponseHeaders(event, { 'Vary': 'Accept-Language, Cookie', 'Cache-Control': 'private, no-store' })
        return redirect(event, docsHome(lang), 302)
      }
      case 'legacy-docs':
        return redirect(event, action.location, 301)
      case 'to-website':
        return redirect(event, action.location, 301)
      case 'robots':
        setResponseHeaders(event, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'public, max-age=86400' })
        return send(event, event.method === 'HEAD' ? '' : buildWikiRobots(s.wikiUrl))
      case 'sitemap':
        setResponseHeaders(event, { 'Content-Type': 'application/xml; charset=utf-8', 'Cache-Control': 'public, max-age=3600' })
        return send(event, event.method === 'HEAD' ? '' : buildWikiSitemap(s.wikiUrl, s.routes))
      case 'file':
        return serveFile(event, pathname, action.file, 200)
      case 'page-not-found': {
        const page = s.files.get('/404.html')
        if (page) {
          setResponseHeader(event, 'X-Robots-Tag', 'noindex')
          return serveFile(event, '/404.html', page, 404, 'no-store')
        }
        return plain(event, 404, 'Not found')
      }
      case 'asset-not-found':
        return plain(event, 404, 'Not found')
    }
  })
}

function serveFile(event: H3Event, pathname: string, file: WikiFile, status: number, cacheControl?: string) {
  setResponseHeaders(event, { ...wikiCacheHeaders(pathname, file), 'Content-Type': file.type, 'ETag': file.etag })
  if (cacheControl) setResponseHeader(event, 'Cache-Control', cacheControl)
  if (status === 200 && getHeader(event, 'if-none-match') === file.etag) {
    setResponseStatus(event, 304)
    return send(event, '')
  }
  setResponseStatus(event, status)
  setResponseHeaders(event, { 'Content-Length': String(file.size) })
  if (event.method === 'HEAD') return send(event, '')
  return sendStream(event, createReadStream(file.file))
}

function plain(event: H3Event, status: number, text: string) {
  setResponseHeaders(event, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' })
  setResponseStatus(event, status)
  return send(event, event.method === 'HEAD' ? '' : text)
}

function json(event: H3Event, status: number, body: unknown) {
  setResponseHeaders(event, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
  setResponseStatus(event, status)
  return send(event, JSON.stringify(body))
}

function redirect(event: H3Event, location: string, status: 301 | 302) {
  setResponseHeaders(event, { Location: location, 'Content-Type': 'text/plain; charset=utf-8' })
  if (status === 301) setResponseHeader(event, 'Cache-Control', 'public, max-age=3600')
  setResponseStatus(event, status)
  return send(event, '')
}

function escapeXml(s: string): string {
  return s.replace(/[<>&'"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', "'": '&apos;', '"': '&quot;' })[c]!)
}
