import { defineEventHandler, setResponseHeader } from 'h3'
import { z } from 'zod'
import { publishedCircuit, siteCircuitView } from '../../../../lib/circuits'
import { useCtx } from '../../../../lib/context'
import { notFound } from '../../../../lib/errors'
import { clientIp, limit, paramWith } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Website (§25.3): eine veröffentlichte Schaltung mit Maßen, Materialliste und Versionen. */
export default defineEventHandler((event) => {
  limit(`circuitPublic:${clientIp(event)}`, RULES.circuitPublicIp)
  const id = paramWith(event, 'id', z.string().regex(/^[a-z0-9_]{1,48}$/))
  const row = publishedCircuit(useCtx(), id)
  if (!row) throw notFound('circuit_not_found', 'Circuit not found')
  setResponseHeader(event, 'Cache-Control', 'public, max-age=60')
  return siteCircuitView(row)
})
