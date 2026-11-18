import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingCreate, createServerBody } from '../../../../lib/craftinghosting'
import { created, limit, readJson, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const body = await readJson(event, createServerBody)
  limit(`craftinghostingCreate:${auth.uuid}`, RULES.craftinghostingCreateUser)
  return created(event, { server: await craftingCreate(useCtx(), auth.user, body) })
})
