import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { rejectSubmission, rejectSubmissionBody } from '../../../../../lib/circuits'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Team (§25.5): Einreichung ablehnen – der Grund ist für den Ersteller sichtbar. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'circuits.manage')
  limit(`adminCircuit:${staff.uuid}`, RULES.adminCircuit)
  const id = paramWith(event, 'id', z.string().regex(/^cs[0-9a-f]{16}$/))
  const body = await readJson(event, rejectSubmissionBody)
  return { submission: rejectSubmission(useCtx(), staff, id, body.reason) }
})
