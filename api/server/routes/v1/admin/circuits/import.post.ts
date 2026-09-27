import { defineEventHandler } from 'h3'
import { readCircuitUpload } from '../../../../lib/circuit-http'
import { limit, requireStaff } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/**
 * Team (§25.6): Datei (.litematic, .schem, .nbt, JSON; ≤ 2 MB, rohe Bytes, `?name=` = Dateiname) → Schaltung zum
 * Weiterbearbeiten im Editor. Speichert nichts.
 */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'circuits.manage')
  limit(`adminCircuit:${staff.uuid}`, RULES.adminCircuit)
  return await readCircuitUpload(event)
})
