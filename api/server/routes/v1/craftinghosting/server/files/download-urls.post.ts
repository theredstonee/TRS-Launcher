import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { craftingDownloadUrls, downloadUrlsBody } from '../../../../../lib/craftinghosting'
import { limit, readJson, requireWebOrUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const body = await readJson(event, downloadUrlsBody)
  limit(`craftinghostingDownload:${auth.uuid}`, RULES.craftinghostingDownloadUser)
  return craftingDownloadUrls(useCtx(), auth.user, body.paths)
})
