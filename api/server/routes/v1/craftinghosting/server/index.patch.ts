import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingUpdate, patchServerBody } from '../../../../lib/craftinghosting'
import { readJson, requireWebOrUser } from '../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const body = await readJson(event, patchServerBody)
  return { server: await craftingUpdate(useCtx(), auth.user, body) }
})
