import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingSetProperties, propertiesPatchBody } from '../../../../lib/craftinghosting'
import { limit, readJson, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const body = await readJson(event, propertiesPatchBody)
  limit(`craftinghostingEdit:${auth.uuid}`, RULES.craftinghostingEditUser)
  return craftingSetProperties(useCtx(), auth.user, body.properties)
})
