import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingDelete } from '../../../../lib/craftinghosting'
import { limit, noContent, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  limit(`craftinghostingCreate:${auth.uuid}`, RULES.craftinghostingCreateUser)
  await craftingDelete(useCtx(), auth.user)
  return noContent(event)
})
