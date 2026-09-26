import { defineEventHandler, getCookie } from 'h3'
import { useCtx } from '../../../lib/context'
import { PERMISSIONS, limitsOf } from '../../../lib/staff'
import { WEB_SESSION_COOKIE, webSession } from '../../../lib/weblogin'

/**
 * Website: aktuelle Team-Sitzung (nach dem Neuladen) – Name, CSRF-Token, Rolle, Rechte und Grenzen (§22.1).
 * 401 ohne Sitzung.
 */
export default defineEventHandler((event) => {
  const session = webSession(useCtx(), getCookie(event, WEB_SESSION_COOKIE), undefined, false)
  return {
    name: session.name,
    uuid: session.uuid,
    csrf: session.csrf,
    role: session.role,
    permissions: PERMISSIONS[session.role],
    limits: limitsOf(session.role),
  }
})
