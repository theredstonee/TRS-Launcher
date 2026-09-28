import { defineEventHandler, deleteCookie, getCookie, setCookie, setResponseHeader } from 'h3'
import { z } from 'zod'
import { audit } from '../../../../lib/audit'
import { useCtx } from '../../../../lib/context'
import { assertSameOrigin, clientIp, limit, readJson } from '../../../../lib/http'
import { LAUNCHER_LOGIN_COOKIE, LAUNCHER_LOGIN_COOKIE_PATH, pollLauncherLogin } from '../../../../lib/launcherlogin'
import { RULES } from '../../../../lib/ratelimit'
import { isTeamMember } from '../../../../lib/team'
import { getUser } from '../../../../lib/users'
import { LEGACY_WEB_SESSION_COOKIE, WEB_SESSION_COOKIE, WEB_SESSION_TTL_MS, createWebSession } from '../../../../lib/weblogin'

const body = z.object({ token: z.string().max(64) }).strict()

/**
 * Website: Stand der Anmeldung per TRS Launcher (§29.2), alle ~2 s. Nach der Freigabe genau einmal `approved` –
 * dabei entsteht die normale Website-Sitzung (neues Token, altes Sitzungs-Cookie verworfen = Rotation) wie nach der
 * Anmeldung mit Microsoft. Nur der Browser mit dem passenden Cookie kann die Anfrage einlösen.
 */
export default defineEventHandler(async (event) => {
  assertSameOrigin(event)
  limit(`llogin-poll:${clientIp(event)}`, RULES.launcherLoginPollIp)
  const { token } = await readJson(event, body)
  const ctx = useCtx()
  setResponseHeader(event, 'Cache-Control', 'no-store')
  const result = pollLauncherLogin(ctx, token, getCookie(event, LAUNCHER_LOGIN_COOKIE))
  if (result.status === 'pending') return result
  deleteCookie(event, LAUNCHER_LOGIN_COOKIE, { path: LAUNCHER_LOGIN_COOKIE_PATH, secure: true, httpOnly: true, sameSite: 'strict' })
  if (result.status !== 'approved') return result

  // Konto muss bekannt sein (der Launcher hat sich mit ihm angemeldet); sonst wie abgelaufen.
  if (!getUser(ctx, result.uuid)) return { status: 'expired' }
  const session = createWebSession(ctx, result.uuid, getCookie(event, WEB_SESSION_COOKIE))
  if (isTeamMember(ctx, result.uuid)) audit(ctx, result.uuid, 'web.login', result.uuid, 'launcher')
  setCookie(event, WEB_SESSION_COOKIE, session.token, {
    httpOnly: true,
    secure: true,
    sameSite: 'strict',
    path: '/',
    maxAge: Math.floor(WEB_SESSION_TTL_MS / 1000),
  })
  deleteCookie(event, LEGACY_WEB_SESSION_COOKIE, { path: '/', secure: true, httpOnly: true, sameSite: 'strict' })
  return { status: 'approved', returnTo: result.returnTo }
})
