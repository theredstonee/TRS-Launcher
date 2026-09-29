import { randomUUID } from 'node:crypto'
import { defineEventHandler, getCookie, getHeader, getRequestHost, sendRedirect, setResponseHeader, setResponseHeaders } from 'h3'
import { docsHome, isDocsPath, isDocsRoot, pickDocsLang } from '../../shared/docs'
import { useCtx, whenReady } from '../lib/context'
import { forbidden } from '../lib/errors'
import { CORS_ALLOWED_HEADERS, CORS_ALLOWED_METHODS, CORS_EXPOSED_HEADERS, SECURITY_HEADERS, isApiPath } from '../lib/headers'
import { clientIp, limit, noContent } from '../lib/http'
import { RULES } from '../lib/ratelimit'

/** Gebaute Dateien der Website (Skripte, Stile, Schriften) – nicht aufs IP-Limit anrechnen. */
const STATIC = /^\/(?:_nuxt|_fonts|news|img|shots|flags)\/|^\/(?:icon\.png|og\.png|favicon\.ico)$/
/** Maschinenlesbare Dateien der Website – gleich in jeder Sprache. */
const FEEDS = /^\/(?:sitemap\.xml|robots\.txt|feed\.xml|llms\.txt|llms-full\.txt)$/

/**
 * Läuft vor jeder Route. API (/v1/…): Sicherheits-Header, strenges CORS (nur Origins aus
 * CORS_ORIGINS, nie `*`), globales Rate-Limit je IP. Seiten der Website bekommen ihre
 * Header (CSP mit Nonce) von nuxt-security; hier nur das Rate-Limit und – auf dem alten
 * API-Namen – die Weiterleitung zur Website.
 */
export default defineEventHandler(async (event) => {
  await whenReady()
  const ctx = useCtx()
  const requestId = randomUUID()
  event.context.requestId = requestId
  event.context.startedAt = performance.now()
  setResponseHeader(event, 'X-Request-Id', requestId)

  if (!isApiPath(event.path)) {
    // api.theredstonee.de ist nur noch die API – Seiten dort gehen zur Website.
    const host = getRequestHost(event, { xForwardedHost: false }).toLowerCase()
    if (ctx.config.apiOnlyHosts.has(host)) {
      return sendRedirect(event, `${ctx.config.siteUrl}${event.path}`, 301)
    }
    const pathname = event.path.split('?')[0]!
    // Dokumentation (/docs): vorhandene Dateien liefert der Static-Handler schon vor dieser Middleware aus (Header dafür:
    // Route-Regeln aus modules/docs.ts). Hier landen nur /docs selbst (Sprachwahl) und fehlende Dateien (→ 404-Seite).
    if (isDocsPath(pathname)) {
      limit(`ip:${clientIp(event)}`, RULES.globalIp)
      if (isDocsRoot(pathname)) {
        // /docs → Sprache wie auf der Website gewählt (Cookie) oder nach Accept-Language, sonst Englisch.
        setResponseHeaders(event, { 'Vary': 'Accept-Language, Cookie', 'Cache-Control': 'private, no-store' })
        return sendRedirect(event, docsHome(pickDocsLang(getHeader(event, 'accept-language'), getCookie(event, 'trs_lang'))), 302)
      }
      event.context.siteUrl = ctx.config.siteUrl
      return
    }
    if (!STATIC.test(event.path)) {
      limit(`ip:${clientIp(event)}`, RULES.globalIp)
      // Seiten: Adresse der Website für kanonische Links/hreflang (composables/useSeo.ts). Ohne `?lang=`
      // hängt die Sprache von Cookie und Accept-Language ab – das sagen wir Caches und Crawlern.
      event.context.siteUrl = ctx.config.siteUrl
      if (!FEEDS.test(event.path.split('?')[0]!)) setResponseHeader(event, 'Vary', 'Accept-Language, Cookie')
    }
    return
  }

  setResponseHeaders(event, SECURITY_HEADERS)
  const origin = getHeader(event, 'origin')
  const allowed = origin !== undefined && ctx.config.corsOrigins.has(origin)
  if (origin !== undefined) setResponseHeader(event, 'Vary', 'Origin')
  if (allowed) {
    setResponseHeaders(event, {
      'Access-Control-Allow-Origin': origin,
      'Access-Control-Expose-Headers': CORS_EXPOSED_HEADERS,
    })
  }
  if (event.method === 'OPTIONS') {
    if (!allowed) throw forbidden('cors_forbidden', 'Origin not allowed')
    setResponseHeaders(event, {
      'Access-Control-Allow-Methods': CORS_ALLOWED_METHODS,
      'Access-Control-Allow-Headers': CORS_ALLOWED_HEADERS,
      'Access-Control-Max-Age': '600',
    })
    return noContent(event)
  }

  limit(`ip:${clientIp(event)}`, RULES.globalIp)
})
