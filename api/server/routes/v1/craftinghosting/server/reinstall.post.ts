import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingReinstall } from '../../../../lib/craftinghosting'
import { limit, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Server-Software neu einrichten (Welt und Dateien bleiben). */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  limit(`craftinghostingCreate:${auth.uuid}`, RULES.craftinghostingCreateUser)
  return { server: await craftingReinstall(useCtx(), auth.user) }
})
