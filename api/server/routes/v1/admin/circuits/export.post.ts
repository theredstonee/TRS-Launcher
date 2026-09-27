import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { exportQuery, sendCircuitFile } from '../../../../lib/circuit-http'
import { validateCircuit } from '../../../../lib/circuits'
import { limit, queryWith, readJson, requireStaff } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Team (§25.6): ungespeicherten Stand aus dem Editor exportieren (`?format=nbt|json`, Body `{ circuit }`). */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'circuits.manage')
  limit(`adminCircuit:${staff.uuid}`, RULES.adminCircuit)
  const q = queryWith(event, exportQuery)
  const body = await readJson(event, z.strictObject({ circuit: z.unknown() }), 300 * 1024)
  const { circuit } = validateCircuit(body.circuit)
  return sendCircuitFile(event, circuit, q.format, 'private, no-store')
})
