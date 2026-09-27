import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { exportQuery, sendCircuitFile } from '../../../../lib/circuit-http'
import { circuitJson, publishedCircuit } from '../../../../lib/circuits'
import { useCtx } from '../../../../lib/context'
import { notFound } from '../../../../lib/errors'
import { clientIp, limit, paramWith, queryWith } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Öffentlich (§25.2): veröffentlichte Schaltung herunterladen – `?format=nbt` (Strukturblock, Standard) oder `json`. */
export default defineEventHandler((event) => {
  limit(`circuitPublic:${clientIp(event)}`, RULES.circuitPublicIp)
  const id = paramWith(event, 'id', z.string().regex(/^[a-z0-9_]{1,48}$/))
  const q = queryWith(event, exportQuery)
  const row = publishedCircuit(useCtx(), id)
  if (!row) throw notFound('circuit_not_found', 'Circuit not found')
  return sendCircuitFile(event, circuitJson(row), q.format, 'public, max-age=300')
})
