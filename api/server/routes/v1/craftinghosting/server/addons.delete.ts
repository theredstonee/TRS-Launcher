import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { addonQuery, craftingRemoveAddon } from '../../../../lib/craftinghosting'
import { limit, noContent, queryWith, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const q = queryWith(event, addonQuery)
  limit(`craftinghostingEdit:${auth.uuid}`, RULES.craftinghostingEditUser)
  await craftingRemoveAddon(useCtx(), auth.user, q.file)
  return noContent(event)
})
