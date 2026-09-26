import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { created, limit, paramWith, readJson, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { createAppeal } from '../../../../../lib/sanctions'
import { appealBody, sanctionIdSchema } from '../../../../../lib/schemas'

/** Einspruch einlegen: genau einmal je Strafe, 20–1000 Zeichen. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write', { appeal: true })
  const id = paramWith(event, 'id', sanctionIdSchema)
  limit(`appeal:${auth.uuid}`, RULES.appealUser)
  const body = await readJson(event, appealBody)
  return created(event, { sanction: createAppeal(useCtx(), auth.uuid, id, body.text) })
})
