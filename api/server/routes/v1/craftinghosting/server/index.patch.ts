import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingUpdate, patchServerBody } from '../../../../lib/craftinghosting'
import { readJson, requireUser } from '../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'craftinghosting')
  const body = await readJson(event, patchServerBody)
  return { server: await craftingUpdate(useCtx(), auth.user, body) }
})
