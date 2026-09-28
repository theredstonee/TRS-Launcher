import { defineEventHandler, getCookie, getHeader, setCookie, setResponseHeader } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { assertSameOrigin, clientIp, created, limit, readJson } from '../../../../lib/http'
import { LAUNCHER_LOGIN_COOKIE, LAUNCHER_LOGIN_COOKIE_PATH, LAUNCHER_LOGIN_TTL_MS, createLauncherLogin } from '../../../../lib/launcherlogin'
import { safeReturnTo } from '../../../../lib/microsoft'
import { RULES } from '../../../../lib/ratelimit'

const body = z.object({ return: z.string().max(200).optional() }).strict().optional()

/**
 * Website: „Mit TRS Launcher anmelden“ (§29.1). Legt eine Anfrage an, setzt das Cookie mit dem Browser-Wert und
 * liefert Link-Token, Bestätigungscode und Link `trs-launcher://web-login/<token>`. Nur von der eigenen Website.
 */
export default defineEventHandler(async (event) => {
  assertSameOrigin(event)
  limit(`llogin-create:${clientIp(event)}`, RULES.launcherLoginCreateIp)
  const input = await readJson(event, body)
  const login = createLauncherLogin(useCtx(), {
    returnTo: safeReturnTo(input?.return),
    userAgent: getHeader(event, 'user-agent'),
    previousBrowserSecret: getCookie(event, LAUNCHER_LOGIN_COOKIE),
  })
  setResponseHeader(event, 'Cache-Control', 'no-store')
  setCookie(event, LAUNCHER_LOGIN_COOKIE, login.browserSecret, {
    httpOnly: true,
    secure: true,
    sameSite: 'strict',
    path: LAUNCHER_LOGIN_COOKIE_PATH,
    maxAge: Math.floor(LAUNCHER_LOGIN_TTL_MS / 1000) + 60,
  })
  // `expiresIn` (Sekunden) = Restzeit unabhängig von der Uhr des Besuchers.
  return created(event, {
    token: login.token,
    code: login.code,
    link: login.link,
    expiresAt: login.expiresAt,
    expiresIn: Math.floor(LAUNCHER_LOGIN_TTL_MS / 1000),
    pollMs: 2000,
  })
})
