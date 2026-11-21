import { defineEventHandler, setResponseHeader } from 'h3'
import { useCtx } from '../../../lib/context'
import { clientIp, limit } from '../../../lib/http'
import { listPartners } from '../../../lib/partners'
import { RULES } from '../../../lib/ratelimit'

/** Öffentliche Partner-Liste (§44.3): nur aktive Partner. */
export default defineEventHandler((event) => {
  limit(`partnersPublic:${clientIp(event)}`, RULES.partnersPublicIp)
  setResponseHeader(event, 'Cache-Control', 'public, max-age=60')
  return listPartners(useCtx())
})
