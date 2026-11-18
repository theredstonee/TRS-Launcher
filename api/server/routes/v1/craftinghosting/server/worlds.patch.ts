import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingActivateWorld, worldActivateBody } from '../../../../lib/craftinghosting'
import { limit, readJson, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const body = await readJson(event, worldActivateBody)
  limit(`craftinghostingEdit:${auth.uuid}`, RULES.craftinghostingEditUser)
  return craftingActivateWorld(useCtx(), auth.user, body.active)
})
