import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { craftingWorldImport, importBody } from '../../../../../lib/craftinghosting'
import { limit, readJson, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'craftinghosting')
  await readJson(event, importBody)
  limit(`craftinghostingFiles:${auth.uuid}`, RULES.craftinghostingFilesUser)
  return { server: await craftingWorldImport(useCtx(), auth.user) }
})
