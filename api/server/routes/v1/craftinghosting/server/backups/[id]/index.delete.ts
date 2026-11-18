import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../../lib/context'
import { backupIdParam, craftingBackupAction } from '../../../../../../lib/craftinghosting'
import { noContent, paramWith, requireWebOrUser } from '../../../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const id = paramWith(event, 'id', backupIdParam)
  await craftingBackupAction(useCtx(), auth.user, 'delete', id)
  return noContent(event)
})
