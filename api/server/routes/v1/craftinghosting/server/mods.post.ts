import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingInstallMods, modsBody } from '../../../../lib/craftinghosting'
import { limit, readJson, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const body = await readJson(event, modsBody)
  limit(`craftinghostingFiles:${auth.uuid}`, RULES.craftinghostingFilesUser)
  return craftingInstallMods(useCtx(), auth.user, body.projects)
})
