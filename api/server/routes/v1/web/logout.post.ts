import { defineEventHandler, deleteCookie } from 'h3'
import { useCtx } from '../../../lib/context'
import { noContent, requireWeb } from '../../../lib/http'
import { WEB_SESSION_COOKIE, endWebSession } from '../../../lib/weblogin'

/** Website: abmelden (Cookie + CSRF-Token). Die Sitzung endet serverseitig. */
export default defineEventHandler((event) => {
  const session = requireWeb(event, 'write')
  endWebSession(useCtx(), session.tokenHash)
  deleteCookie(event, WEB_SESSION_COOKIE, { path: '/', secure: true, httpOnly: true, sameSite: 'strict' })
  return noContent(event)
})
