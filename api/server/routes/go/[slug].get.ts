import { defineEventHandler, getHeader, getQuery, getRouterParam, sendRedirect, setResponseHeaders } from 'h3'
import { useCtx, whenReady } from '../../lib/context'
import { clientIp, limit } from '../../lib/http'
import { countClick } from '../../lib/partners'
import { RULES } from '../../lib/ratelimit'

/**
 * Werbe-Link eines Partners (§44.3): `/go/<slug>?c=<kampagne>` zählt einen Klick (Summe je Tag; doppelte Klicks derselben
 * IP+UA in 10 min einmal, nur im RAM) und leitet auf die Download-Seite mit Hinweis weiter. Unbekannt → Download-Seite.
 */
export default defineEventHandler(async (event) => {
  await whenReady()
  const ip = clientIp(event)
  limit(`partner-count:${ip}`, RULES.partnerCountIp)
  const slug = String(getRouterParam(event, 'slug', { decode: true }) ?? '').toLowerCase().slice(0, 40)
  const to = countClick(useCtx(), slug, getQuery(event).c, { ip, ua: (getHeader(event, 'user-agent') ?? '').slice(0, 300) })
  setResponseHeaders(event, { 'Cache-Control': 'no-store', 'X-Robots-Tag': 'noindex', 'Referrer-Policy': 'no-referrer' })
  return sendRedirect(event, to, 302)
})
