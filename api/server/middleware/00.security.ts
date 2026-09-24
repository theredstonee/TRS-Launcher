import { randomUUID } from 'node:crypto'
import { defineEventHandler, getHeader, getRequestHost, sendRedirect, setResponseHeader, setResponseHeaders } from 'h3'
import { useCtx, whenReady } from '../lib/context'
import { forbidden } from '../lib/errors'
import { CORS_ALLOWED_HEADERS, CORS_ALLOWED_METHODS, CORS_EXPOSED_HEADERS, SECURITY_HEADERS, isApiPath } from '../lib/headers'
import { clientIp, limit, noContent } from '../lib/http'
import { RULES } from '../lib/ratelimit'

/** Gebaute Dateien der Website (Skripte, Stile, Schriften) – nicht aufs IP-Limit anrechnen. */
const STATIC = /^\/(?:_nuxt|_fonts|news|img)\/|^\/(?:icon\.png|favicon\.ico)$/

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
    if (!STATIC.test(event.path)) limit(`ip:${clientIp(event)}`, RULES.globalIp)
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
