import { defineEventHandler } from 'h3'
import { adminCircuitBody, createCircuit } from '../../../../lib/circuits'
import { useCtx } from '../../../../lib/context'
import { created, limit, readJson, requireStaff } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Team (§25.4): Schaltung anlegen (Standard: Entwurf). 409 `circuit_exists`, 400 `invalid_circuit`. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'circuits.manage')
  limit(`adminCircuit:${staff.uuid}`, RULES.adminCircuit)
  const body = await readJson(event, adminCircuitBody, 300 * 1024)
  return created(event, { circuit: createCircuit(useCtx(), staff, body) })
})
