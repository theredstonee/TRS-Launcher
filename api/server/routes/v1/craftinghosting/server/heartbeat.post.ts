import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingHeartbeat } from '../../../../lib/craftinghosting'
import { requireUser } from '../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'craftinghosting')
  return { server: await craftingHeartbeat(useCtx(), auth.user) }
})
