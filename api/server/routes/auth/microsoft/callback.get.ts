import { defineEventHandler, deleteCookie, getCookie, getQuery, sendRedirect, setCookie, setResponseHeaders } from 'h3'
import { audit } from '../../../lib/audit'
import { useCtx } from '../../../lib/context'
import { clientIp } from '../../../lib/http'
import { MS_STATE_COOKIE, MsLoginError, exchangeCode, type MsLoginErrorCode } from '../../../lib/microsoft'
import { RULES } from '../../../lib/ratelimit'
import { activeSanction } from '../../../lib/sanctions'
import { isTeamMember } from '../../../lib/team'
import { upsertOnLogin } from '../../../lib/users'
import { LEGACY_WEB_SESSION_COOKIE, WEB_SESSION_COOKIE, WEB_SESSION_TTL_MS, createWebSession } from '../../../lib/weblogin'

const escapeHtml = (s: string) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`)

/**
 * Rücksprung von Microsoft (§24.1): state prüfen (Cookie + Parameter, einmalig), Code gegen Tokens tauschen, Kette bis
 * zum Minecraft-Profil, Sitzung anlegen (Rotation). Fehler → `/login?error=<code>`. Tokens werden nie gespeichert.
 * Weiter geht es per kleiner HTML-Seite (Meta-Refresh), damit das SameSite=Strict-Cookie beim nächsten Aufruf mitkommt.
 */
export default defineEventHandler(async (event) => {
  const ctx = useCtx()
  setResponseHeaders(event, { 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer' })
  const fail = (code: MsLoginErrorCode) => sendRedirect(event, `/login?error=${code}`, 302)
  const handle = getCookie(event, MS_STATE_COOKIE)
  deleteCookie(event, MS_STATE_COOKIE, { path: '/auth/microsoft', secure: true, httpOnly: true, sameSite: 'lax' })
  const cfg = ctx.config.microsoft
  if (!cfg) return fail('ms_disabled')
  if (!ctx.limiter.take(`ms-callback:${clientIp(event)}`, RULES.msCallbackIp).ok) return fail('rate_limited')

  const q = getQuery(event)
  const pending = ctx.oauth.take(handle, typeof q.state === 'string' ? q.state : undefined)
  if (!pending) return fail('ms_state')
  if (typeof q.error === 'string') return fail(q.error === 'access_denied' ? 'ms_cancelled' : 'ms_failed')
  const code = typeof q.code === 'string' && q.code.length <= 4096 ? q.code : null
  if (!code) return fail('ms_failed')

  let identity
  try {
    identity = await exchangeCode(cfg, code, pending.verifier, ctx.msFetch ?? fetch)
  } catch (err) {
    if (err instanceof MsLoginError) {
      if (err.code === 'ms_failed') console.warn(`[trs-api] Microsoft sign-in failed: ${err.message}`)
      return fail(err.code)
    }
    throw err
  }
  if (!ctx.limiter.take(`login:${identity.uuid}`, RULES.loginUuid).ok) return fail('rate_limited')
  if (activeSanction(ctx, identity.uuid, 'account_ban')) return fail('banned')

  upsertOnLogin(ctx, identity.uuid, identity.name)
  const session = createWebSession(ctx, identity.uuid, getCookie(event, WEB_SESSION_COOKIE))
  if (isTeamMember(ctx, identity.uuid)) audit(ctx, identity.uuid, 'web.login', identity.uuid)
  setCookie(event, WEB_SESSION_COOKIE, session.token, {
    httpOnly: true,
    secure: true,
    sameSite: 'strict',
    path: '/',
    maxAge: Math.floor(WEB_SESSION_TTL_MS / 1000),
  })
  deleteCookie(event, LEGACY_WEB_SESSION_COOKIE, { path: '/', secure: true, httpOnly: true, sameSite: 'strict' })
  const to = escapeHtml(pending.returnTo)
  setResponseHeaders(event, { 'Content-Type': 'text/html; charset=utf-8' })
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="robots" content="noindex"><meta http-equiv="refresh" content="0;url=${to}"><title>TRS</title></head><body><p><a href="${to}">Continue</a></p></body></html>`
})
