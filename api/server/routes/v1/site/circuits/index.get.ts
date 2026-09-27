import { defineEventHandler, setResponseHeader } from 'h3'
import { siteCircuits } from '../../../../lib/circuits'
import { useCtx } from '../../../../lib/context'
import { clientIp, limit } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Website (§25.3): alle veröffentlichten Schaltungen mit Maßen, Materialliste und Versionen (für Liste + Vorschau). */
export default defineEventHandler((event) => {
  limit(`circuitPublic:${clientIp(event)}`, RULES.circuitPublicIp)
  setResponseHeader(event, 'Cache-Control', 'public, max-age=60')
  return { circuits: siteCircuits(useCtx()) }
})
