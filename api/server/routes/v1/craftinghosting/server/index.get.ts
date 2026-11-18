import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingServer } from '../../../../lib/craftinghosting'
import { requireWebOrUser } from '../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  return { server: await craftingServer(useCtx(), auth.user) }
})
