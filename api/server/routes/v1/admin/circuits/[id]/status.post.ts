import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { circuitStatusBody, setCircuitStatus } from '../../../../../lib/circuits'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Team (§25.4): veröffentlichen, verstecken oder zurück zum Entwurf (rev + 1). */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'circuits.manage')
  limit(`adminCircuit:${staff.uuid}`, RULES.adminCircuit)
  const id = paramWith(event, 'id', z.string().regex(/^[a-z0-9_]{1,48}$/))
  const body = await readJson(event, circuitStatusBody)
  return { circuit: setCircuitStatus(useCtx(), staff, id, body.status, body.baseRev) }
})
