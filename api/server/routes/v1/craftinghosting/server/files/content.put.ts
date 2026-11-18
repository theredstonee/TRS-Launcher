import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { craftingWriteFile, fileQuery, fileWriteBody } from '../../../../../lib/craftinghosting'
import { limit, queryWith, readJson, requireWebOrUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const q = queryWith(event, fileQuery)
  const body = await readJson(event, fileWriteBody, 1_100_000)
  limit(`craftinghostingEdit:${auth.uuid}`, RULES.craftinghostingEditUser)
  return craftingWriteFile(useCtx(), auth.user, q.path, body.content)
})
