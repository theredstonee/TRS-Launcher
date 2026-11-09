import { createError, defineEventHandler, getCookie, getHeader, sendRedirect, setCookie, setResponseHeaders, type H3Event } from 'h3'
import { useCtx } from '../../lib/context'
import { upsertOnLogin } from '../../lib/users'
import { WEB_SESSION_COOKIE, WEB_SESSION_TTL_MS, createWebSession } from '../../lib/weblogin'

// Nur für `nuxt dev` auf dem eigenen Rechner: meldet ohne Microsoft als erstes Konto aus ADMIN_UUIDS an
// (Inhaber-Rang = Super-Admin). Mehrfach gesperrt: im gebauten Server ist `import.meta.dev` false (der Code fällt
// beim Bauen weg), zusätzlich muss TRS_DEV_LOGIN=1 gesetzt sein, die Anfrage von 127.0.0.1/::1 kommen und der
// Host-Name lokal sein (gegen DNS-Rebinding). Sonst 404 wie jede unbekannte Seite.

const LOOPBACK = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1'])
const LOCAL_HOST = /^(127\.0\.0\.1|localhost|\[::1\])(:\d{1,5})?$/
const NAME = /^\w{3,16}$/

/** Absender der Anfrage. `nuxt dev` reicht Anfragen über einen eigenen Proxy weiter und setzt dabei X-Forwarded-For. */
function devPeer(event: H3Event): string {
  return event.node.req.socket.remoteAddress ?? getHeader(event, 'x-forwarded-for')?.split(',')[0]?.trim() ?? ''
}

export default defineEventHandler((event) => {
  if (
    !import.meta.dev
    || process.env.TRS_DEV_LOGIN !== '1'
    || !LOOPBACK.has(devPeer(event))
    || !LOCAL_HOST.test(getHeader(event, 'host') ?? '')
  ) {
    throw createError({ statusCode: 404 })
  }
  const ctx = useCtx()
  const uuid = [...ctx.config.adminUuids][0]
  if (!uuid) throw createError({ statusCode: 500, statusMessage: 'ADMIN_UUIDS is empty' })
  const raw = process.env.TRS_DEV_LOGIN_NAME ?? ''
  upsertOnLogin(ctx, uuid, NAME.test(raw) ? raw : 'Theredstonee')
  const session = createWebSession(ctx, uuid, getCookie(event, WEB_SESSION_COOKIE))
  setCookie(event, WEB_SESSION_COOKIE, session.token, {
    httpOnly: true,
    secure: true,
    sameSite: 'strict',
    path: '/',
    maxAge: Math.floor(WEB_SESSION_TTL_MS / 1000),
  })
  setResponseHeaders(event, { 'Cache-Control': 'no-store' })
  return sendRedirect(event, '/admin', 302)
})
