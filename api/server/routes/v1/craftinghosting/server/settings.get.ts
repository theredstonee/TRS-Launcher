import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingSettings } from '../../../../lib/craftinghosting'
import { requireWebOrUser } from '../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  return { settings: await craftingSettings(useCtx(), auth.user) }
})
