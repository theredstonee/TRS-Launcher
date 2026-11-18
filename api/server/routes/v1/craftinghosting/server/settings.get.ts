import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingSettings } from '../../../../lib/craftinghosting'
import { requireUser } from '../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'craftinghosting')
  return { settings: await craftingSettings(useCtx(), auth.user) }
})
