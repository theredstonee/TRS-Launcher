import { defineEventHandler, getCookie } from 'h3'
import { useCtx } from '../../../lib/context'
import { WEB_SESSION_COOKIE, webSession } from '../../../lib/weblogin'

/** Website: aktuelle Admin-Sitzung (nach dem Neuladen) – Name + CSRF-Token. 401 ohne Sitzung. */
export default defineEventHandler((event) => {
  const session = webSession(useCtx(), getCookie(event, WEB_SESSION_COOKIE), undefined, false)
  return { name: session.name, uuid: session.uuid, csrf: session.csrf }
})
