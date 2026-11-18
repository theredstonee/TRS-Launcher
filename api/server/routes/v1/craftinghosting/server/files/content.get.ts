import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { craftingReadFile, fileQuery } from '../../../../../lib/craftinghosting'
import { queryWith, requireWebOrUser } from '../../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const q = queryWith(event, fileQuery)
  return craftingReadFile(useCtx(), auth.user, q.path)
})
