import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { craftingCreateFolder, folderBody } from '../../../../../lib/craftinghosting'
import { limit, noContent, readJson, requireWebOrUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const body = await readJson(event, folderBody)
  limit(`craftinghostingEdit:${auth.uuid}`, RULES.craftinghostingEditUser)
  await craftingCreateFolder(useCtx(), auth.user, body.path)
  return noContent(event)
})
