import { defineEventHandler } from 'h3'
import { reportCape } from '../../../../lib/capes'
import { useCtx } from '../../../../lib/context'
import { limit, noContent, paramWith, readJson, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { capeIdSchema, reportBody } from '../../../../lib/schemas'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const id = paramWith(event, 'id', capeIdSchema)
  limit(`report:${auth.uuid}`, RULES.reportUser)
  const body = await readJson(event, reportBody)
  reportCape(useCtx(), auth.uuid, id, body.reason, body.note)
  return noContent(event)
})
