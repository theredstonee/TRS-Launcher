import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingStats } from '../../../../lib/craftinghosting'
import { requireWebOrUser } from '../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  return craftingStats(useCtx(), auth.user)
})
