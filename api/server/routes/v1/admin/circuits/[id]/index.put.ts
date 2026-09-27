import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { adminCircuitBody, updateCircuit } from '../../../../../lib/circuits'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Team (§25.4): Schaltung speichern (rev + 1). `baseRev` gegen gleichzeitiges Bearbeiten → 409 `stale`. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'circuits.manage')
  limit(`adminCircuit:${staff.uuid}`, RULES.adminCircuit)
  const id = paramWith(event, 'id', z.string().regex(/^[a-z0-9_]{1,48}$/))
  const body = await readJson(event, adminCircuitBody, 300 * 1024)
  return { circuit: updateCircuit(useCtx(), staff, id, body) }
})
