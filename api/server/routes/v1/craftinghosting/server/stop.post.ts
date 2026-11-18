import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingStop, stopServerBody } from '../../../../lib/craftinghosting'
import { readJson, requireUser } from '../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'craftinghosting')
  const body = await readJson(event, stopServerBody)
  return { server: await craftingStop(useCtx(), auth.user, body.whenEmpty) }
})
