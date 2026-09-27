import { defineEventHandler } from 'h3'
import { readCircuitUpload } from '../../../lib/circuit-http'
import { limit, requireWebOrUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'

/**
 * Datei → Schaltung (§25.6), nur zur Vorschau vor dem Einreichen – nichts wird gespeichert. Body = rohe Datei
 * (.litematic, .schem, .nbt oder JSON, ≤ 2 MB), `?name=` = Dateiname. Nur angemeldet (Rechenzeit).
 * → `{ format, circuit, warnings, size, blockCount }`.
 */
export default defineEventHandler(async (event) => {
  const me = requireWebOrUser(event, 'write')
  limit(`circuitConvert:${me.uuid}`, RULES.circuitConvertUser)
  return await readCircuitUpload(event)
})
