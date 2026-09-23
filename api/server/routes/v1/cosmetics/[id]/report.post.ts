import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { reportCosmetic } from '../../../../lib/cosmetics'
import { limit, noContent, paramWith, readJson, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { cosmeticIdSchema, reportBody } from '../../../../lib/schemas'

/** Freigegebenen fremden Upload melden (gleiches Limit wie Umhang-Meldungen). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const id = paramWith(event, 'id', cosmeticIdSchema)
  limit(`report:${auth.uuid}`, RULES.reportUser)
  const body = await readJson(event, reportBody)
  reportCosmetic(useCtx(), auth.uuid, id, body.reason, body.note)
  return noContent(event)
})
