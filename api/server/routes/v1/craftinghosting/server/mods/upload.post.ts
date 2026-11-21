import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { craftingModsUpload, modsUploadBody } from '../../../../../lib/craftinghosting'
import { limit, readJson, requireWebOrUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const body = await readJson(event, modsUploadBody)
  limit(`craftinghostingModsUpload:${auth.uuid}`, RULES.craftinghostingModsUpload)
  return craftingModsUpload(useCtx(), auth.user, body.replace)
})
