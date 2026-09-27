import { defineEventHandler, getQuery, sendRedirect, setCookie, setResponseHeader } from 'h3'
import { useCtx } from '../../../lib/context'
import { clientIp } from '../../../lib/http'
import { MS_STATE_COOKIE, MS_STATE_TTL_MS, authorizeUrl, safeReturnTo } from '../../../lib/microsoft'
import { RULES } from '../../../lib/ratelimit'

/**
 * Website: „Mit Microsoft anmelden“ (§24.1). Legt state + PKCE an (nur im Server-Speicher), setzt das kurzlebige
 * Cookie `trs_oauth` und leitet zu Microsoft weiter. `?return=/pfad` = Rücksprung nach der Anmeldung (nur eigene Pfade).
 */
export default defineEventHandler((event) => {
  const ctx = useCtx()
  setResponseHeader(event, 'Cache-Control', 'no-store')
  const cfg = ctx.config.microsoft
  if (!cfg) return sendRedirect(event, '/login?error=ms_disabled', 302)
  if (!ctx.limiter.take(`ms-login:${clientIp(event)}`, RULES.msLoginIp).ok) return sendRedirect(event, '/login?error=rate_limited', 302)
  const { handle, state, challenge } = ctx.oauth.create(safeReturnTo(getQuery(event).return))
  setCookie(event, MS_STATE_COOKIE, handle, {
    httpOnly: true,
    secure: true,
    // Lax: Microsoft leitet per Top-Level-GET zurück – sonst käme das Cookie nicht mit.
    sameSite: 'lax',
    path: '/auth/microsoft',
    maxAge: Math.floor(MS_STATE_TTL_MS / 1000),
  })
  return sendRedirect(event, authorizeUrl(cfg, state, challenge), 302)
})
