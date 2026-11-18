import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingServer } from '../../../../lib/craftinghosting'
import { requireUser } from '../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'craftinghosting')
  return { server: await craftingServer(useCtx(), auth.user) }
})
