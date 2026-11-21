import { createHash } from 'node:crypto'
import { defineEventHandler, getHeader, getQuery, setResponseHeaders, setResponseStatus } from 'h3'
import { useCtx } from '../../../../lib/context'
import { clientIp, limit } from '../../../../lib/http'
import { listPartnerServers } from '../../../../lib/partner-servers'
import { RULES } from '../../../../lib/ratelimit'

/**
 * Partner-Server für die Mehrspieler-Liste des TRS Client (§44.3): `?mc=<Version>`, höchstens 3, Reihenfolge je UTC-Tag.
 * ETag/304, 5 min cachebar.
 */
export default defineEventHandler((event) => {
  limit(`partnersPublic:${clientIp(event)}`, RULES.partnersPublicIp)
  const body = listPartnerServers(useCtx(), getQuery(event).mc)
  const etag = `"${createHash('sha256').update(JSON.stringify(body)).digest('hex').slice(0, 32)}"`
  setResponseHeaders(event, { 'Cache-Control': 'public, max-age=300', ETag: etag })
  if (getHeader(event, 'if-none-match') === etag) {
    setResponseStatus(event, 304)
    return ''
  }
  return body
})
