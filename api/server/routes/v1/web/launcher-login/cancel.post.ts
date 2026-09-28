import { defineEventHandler, deleteCookie, getCookie } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { assertSameOrigin, clientIp, limit, noContent, readJson } from '../../../../lib/http'
import { LAUNCHER_LOGIN_COOKIE, LAUNCHER_LOGIN_COOKIE_PATH, cancelLauncherLogin } from '../../../../lib/launcherlogin'
import { RULES } from '../../../../lib/ratelimit'

const body = z.object({ token: z.string().max(64) }).strict()

/** Website: Anmeldung per TRS Launcher abbrechen („Zurück“, §29.2). Immer 204. */
export default defineEventHandler(async (event) => {
  assertSameOrigin(event)
  limit(`llogin-poll:${clientIp(event)}`, RULES.launcherLoginPollIp)
  const { token } = await readJson(event, body)
  cancelLauncherLogin(useCtx(), token, getCookie(event, LAUNCHER_LOGIN_COOKIE))
  deleteCookie(event, LAUNCHER_LOGIN_COOKIE, { path: LAUNCHER_LOGIN_COOKIE_PATH, secure: true, httpOnly: true, sameSite: 'strict' })
  return noContent(event)
})
