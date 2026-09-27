import { defineEventHandler, setResponseHeaders } from 'h3'
import { notModified } from '../../../../lib/circuit-http'
import { circuitIndex } from '../../../../lib/circuits'
import { useCtx } from '../../../../lib/context'
import { clientIp, limit } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/**
 * Öffentlich (§25.1): Index der veröffentlichten Schaltungen mit rev je Eintrag. Starker ETag = `"<version>"`;
 * der Client fragt bei jedem Start mit If-None-Match und bekommt meist nur 304.
 */
export default defineEventHandler((event) => {
  limit(`circuitPublic:${clientIp(event)}`, RULES.circuitPublicIp)
  const { index, etag } = circuitIndex(useCtx())
  setResponseHeaders(event, {
    ETag: etag,
    'Cache-Control': 'public, max-age=60, stale-while-revalidate=600',
  })
  if (notModified(event, etag)) return ''
  return index
})
