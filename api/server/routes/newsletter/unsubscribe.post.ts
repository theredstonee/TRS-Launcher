import { defineEventHandler, getQuery, setResponseHeaders, setResponseStatus } from 'h3'
import { useCtx } from '../../lib/context'
import { isApiError } from '../../lib/errors'
import { clientIp, limit } from '../../lib/http'
import { unsubscribeByToken } from '../../lib/mail/subscribers'
import { RULES } from '../../lib/ratelimit'

/**
 * RFC 8058 One-Click: Mail-Programme schicken `POST <List-Unsubscribe-Adresse>` mit `List-Unsubscribe=One-Click` –
 * ohne Cookies, ohne CSRF-Token. Der Token in der Adresse ist die Berechtigung. GET derselben Adresse ist die Seite
 * mit Knopf (app/pages/newsletter/unsubscribe.vue).
 */
export default defineEventHandler((event) => {
  limit(`nlToken:${clientIp(event)}`, RULES.newsletterTokenIp)
  setResponseHeaders(event, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store', 'X-Robots-Tag': 'noindex' })
  const token = getQuery(event).token
  try {
    unsubscribeByToken(useCtx(), typeof token === 'string' ? token : '')
    return 'Unsubscribed.\n'
  } catch (err) {
    if (isApiError(err)) {
      setResponseStatus(event, err.status)
      return 'This unsubscribe link is not valid.\n'
    }
    throw err
  }
})
