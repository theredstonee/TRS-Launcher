import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingDeleteWorld, worldNameQuery } from '../../../../lib/craftinghosting'
import { limit, queryWith, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const q = queryWith(event, worldNameQuery)
  limit(`craftinghostingEdit:${auth.uuid}`, RULES.craftinghostingEditUser)
  return craftingDeleteWorld(useCtx(), auth.user, q.name)
})
