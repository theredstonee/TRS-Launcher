import { randomUUID } from 'node:crypto'
import { defineEventHandler, getCookie, getHeader, getRequestHost, sendRedirect, setResponseHeader, setResponseHeaders } from 'h3'
import { docsHome, isDocsRoot, legacyDocsTarget, pickDocsLang } from '../../shared/docs'
import { MOVED_PAGES } from '../../shared/seo'
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
    // Umgezogene Seiten: dauerhaft weiterleiten, Abfrage (z. B. `?lang=de`) bleibt erhalten.
    const moved = MOVED_PAGES[pathname.replace(/(?<=.)\/$/, '')]
    if (moved) return sendRedirect(event, `${moved}${event.path.slice(pathname.length)}`, 301)
    // Dokumentation: liegt auf eigener Domain (WIKI_URL, server/lib/wiki.ts). Alte Adressen /docs/<rest> → 301 auf
    // <wiki>/<rest> (Query bleibt). /docs selbst: Sprache hier wählen – das Cookie trs_lang gibt es nur auf dieser Domain.
    const docsTarget = legacyDocsTarget(event.node.req.originalUrl || event.node.req.url || event.path, ctx.config.wikiUrl)
    if (docsTarget) {
      limit(`ip:${clientIp(event)}`, RULES.globalIp)
      if (isDocsRoot(pathname)) {
        setResponseHeaders(event, { 'Vary': 'Accept-Language, Cookie', 'Cache-Control': 'private, no-store' })
        const lang = pickDocsLang(getHeader(event, 'accept-language'), getCookie(event, 'trs_lang'))
        return sendRedirect(event, `${ctx.config.wikiUrl}${docsHome(lang)}`, 302)
      }
      return sendRedirect(event, docsTarget, 301)
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

  // Angemeldete Aufrufe (Launcher/Client) bremst die IP-Grenze nicht – nur anonyme.
  if (getHeader(event, 'authorization') === undefined) limit(`ip:${clientIp(event)}`, RULES.globalIp)
})
