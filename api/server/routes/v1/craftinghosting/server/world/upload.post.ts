import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { craftingWorldUpload } from '../../../../../lib/craftinghosting'
import { limit, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'craftinghosting')
  limit(`craftinghostingFiles:${auth.uuid}`, RULES.craftinghostingFilesUser)
  return craftingWorldUpload(useCtx(), auth.user)
})
