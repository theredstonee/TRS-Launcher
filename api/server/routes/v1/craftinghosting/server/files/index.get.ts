import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { craftingFiles, filesQuery } from '../../../../../lib/craftinghosting'
import { queryWith, requireWebOrUser } from '../../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const q = queryWith(event, filesQuery)
  return craftingFiles(useCtx(), auth.user, q.dir)
})
