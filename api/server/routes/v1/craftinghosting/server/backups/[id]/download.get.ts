import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../../lib/context'
import { backupIdParam, craftingBackupAction } from '../../../../../../lib/craftinghosting'
import { limit, paramWith, requireWebOrUser } from '../../../../../../lib/http'
import { RULES } from '../../../../../../lib/ratelimit'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const id = paramWith(event, 'id', backupIdParam)
  limit(`craftinghostingFiles:${auth.uuid}`, RULES.craftinghostingFilesUser)
  return craftingBackupAction(useCtx(), auth.user, 'download', id)
})
