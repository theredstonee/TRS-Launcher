import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { craftingDeleteFiles, fileDeleteBody } from '../../../../../lib/craftinghosting'
import { limit, noContent, readJson, requireWebOrUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const body = await readJson(event, fileDeleteBody)
  limit(`craftinghostingEdit:${auth.uuid}`, RULES.craftinghostingEditUser)
  await craftingDeleteFiles(useCtx(), auth.user, body.paths)
  return noContent(event)
})
