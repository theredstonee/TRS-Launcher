import { defineEventHandler } from 'h3'
import { logout } from '../../../lib/auth'
import { useCtx } from '../../../lib/context'
import { noContent, readJson, requireUser } from '../../../lib/http'
import { logoutBody } from '../../../lib/schemas'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const body = await readJson(event, logoutBody)
  logout(useCtx(), auth, body?.all === true)
  return noContent(event)
})
