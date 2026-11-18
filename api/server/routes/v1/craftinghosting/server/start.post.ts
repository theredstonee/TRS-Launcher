import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingStart, startServerBody } from '../../../../lib/craftinghosting'
import { readJson, requireUser } from '../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'craftinghosting')
  const body = await readJson(event, startServerBody)
  return { server: await craftingStart(useCtx(), auth.user, body.acceptEula) }
})
