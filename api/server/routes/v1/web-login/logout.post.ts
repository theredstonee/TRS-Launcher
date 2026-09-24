import { defineEventHandler, deleteCookie, getCookie, getHeader } from 'h3'
import { useCtx } from '../../../lib/context'
import { noContent } from '../../../lib/http'
import { WEB_SESSION_COOKIE, endWebSession, webSession } from '../../../lib/weblogin'

/** Website: abmelden (Cookie + CSRF-Token). */
export default defineEventHandler((event) => {
  const ctx = useCtx()
  const session = webSession(ctx, getCookie(event, WEB_SESSION_COOKIE), getHeader(event, 'x-csrf-token'), true)
  endWebSession(ctx, session.tokenHash)
  deleteCookie(event, WEB_SESSION_COOKIE, { path: '/', secure: true, httpOnly: true, sameSite: 'strict' })
  return noContent(event)
})
