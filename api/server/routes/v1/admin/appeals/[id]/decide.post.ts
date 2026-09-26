import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { decideAppeal } from '../../../../../lib/sanctions'
import { appealDecisionBody, appealIdSchema } from '../../../../../lib/schemas'

/** Einspruch entscheiden: aufheben, verkürzen oder bestehen lassen – mit Antwort an den Spieler. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event)
  limit(`admin-sanction:${staff.uuid}`, RULES.adminSanction)
  const id = paramWith(event, 'id', appealIdSchema)
  const body = await readJson(event, appealDecisionBody)
  return { appeal: decideAppeal(useCtx(), staff, id, body) }
})
