import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { exportQuery, sendCircuitFile } from '../../../../../lib/circuit-http'
import { circuitJson, getCircuit } from '../../../../../lib/circuits'
import { useCtx } from '../../../../../lib/context'
import { notFound } from '../../../../../lib/errors'
import { limit, paramWith, queryWith, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Team (§25.6): gespeicherte Schaltung (jeder Status) als `nbt` (Strukturblock) oder `json` herunterladen. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'circuits.manage')
  limit(`adminCircuit:${staff.uuid}`, RULES.adminCircuit)
  const id = paramWith(event, 'id', z.string().regex(/^[a-z0-9_]{1,48}$/))
  const q = queryWith(event, exportQuery)
  const row = getCircuit(useCtx(), id)
  if (!row) throw notFound('circuit_not_found', 'Circuit not found')
  return sendCircuitFile(event, circuitJson(row), q.format, 'private, no-store')
})
