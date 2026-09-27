import { defineEventHandler } from 'h3'
import { submissionBody, submitCircuit } from '../../../lib/circuits'
import { useCtx } from '../../../lib/context'
import { created, limit, readJson, requireWebOrUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'

/**
 * Schaltung einreichen (§25.5): Launcher/Client mit Bearer-Token oder Website-Sitzung. `circuit` = Schaltung im
 * Client-Format (von der Datei-Umwandlung `POST /v1/circuits/convert` oder aus dem Client). → 201 `{ id, status, submission }`.
 * Fehler: 403 `sanctioned` (Upload-Sperre), 429 `rate_limited` (Tagesgrenze), 400 `invalid_circuit`, 409 `circuit_duplicate`.
 */
export default defineEventHandler(async (event) => {
  const me = requireWebOrUser(event, 'write')
  limit(`circuitSubmit:${me.uuid}`, RULES.circuitSubmitUser)
  const body = await readJson(event, submissionBody, 300 * 1024)
  const submission = submitCircuit(useCtx(), me, body)
  // `id` + `status` oben (Vertrag mit dem TRS Client, docs/circuit-format.md), dazu die volle Sicht für die Website.
  return created(event, { id: submission.id, status: submission.status, submission })
})
