import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { commandBody, craftingCommand } from '../../../../lib/craftinghosting'
import { limit, noContent, readJson, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'craftinghosting')
  const body = await readJson(event, commandBody)
  limit(`craftinghostingCommand:${auth.uuid}`, RULES.craftinghostingCommandUser)
  await craftingCommand(useCtx(), auth.user, body.command)
  return noContent(event)
})
