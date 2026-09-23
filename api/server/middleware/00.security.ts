import { randomUUID } from 'node:crypto'
import { defineEventHandler, getHeader, setResponseHeader, setResponseHeaders } from 'h3'
import { useCtx, whenReady } from '../lib/context'
import { forbidden } from '../lib/errors'
import { CORS_ALLOWED_HEADERS, CORS_ALLOWED_METHODS, CORS_EXPOSED_HEADERS, SECURITY_HEADERS } from '../lib/headers'
import { clientIp, limit, noContent } from '../lib/http'
import { RULES } from '../lib/ratelimit'

/**
 * Läuft vor jeder Route: Sicherheits-Header, strenges CORS (nur Origins aus
 * CORS_ORIGINS, nie `*`), globales Rate-Limit je IP.
 */
export default defineEventHandler(async (event) => {
  await whenReady()
  const ctx = useCtx()
  const requestId = randomUUID()
  event.context.requestId = requestId
  event.context.startedAt = performance.now()
  setResponseHeaders(event, SECURITY_HEADERS)
  setResponseHeader(event, 'X-Request-Id', requestId)

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
