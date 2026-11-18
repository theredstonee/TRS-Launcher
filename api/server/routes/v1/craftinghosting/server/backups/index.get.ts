import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { craftingBackups } from '../../../../../lib/craftinghosting'
import { requireWebOrUser } from '../../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  return craftingBackups(useCtx(), auth.user)
})
