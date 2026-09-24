import { STATUS_CODES } from 'node:http'
import { send, setResponseHeaders, setResponseStatus } from 'h3'
import { isApiError } from './lib/errors'
import { SECURITY_HEADERS, isApiPath } from './lib/headers'

/**
 * Einheitliche Fehlerantwort `{ "error": { "code", "message", ...details } }`.
 * Unerwartete Fehler: generische Meldung an den Client, Details nur ins Log.
 */
export default defineNitroErrorHandler((error, event) => {
  if (event.node.res.headersSent) {
    event.node.res.end()
    return
  }
  // Seiten der Website: nicht hier behandeln – Nuxt zeigt dann seine Fehlerseite.
  if (!isApiPath(event.path)) return
  const cause = (error as { cause?: unknown }).cause
  const api = isApiError(error) ? error : isApiError(cause) ? cause : null
  let status: number
  let body: { error: Record<string, unknown> }
  const headers: Record<string, string> = {}
  if (api) {
    status = api.status
    body = { error: { code: api.code, message: api.message, ...api.details } }
    Object.assign(headers, api.headers)
    if (status >= 500) console.warn(`[trs-api] ${api.code} ${event.method} ${event.path?.split('?')[0]}`)
  } else if (error.statusCode === 404) {
    status = 404
    body = { error: { code: 'not_found', message: 'Not found' } }
  } else if (error.statusCode === 405) {
    status = 405
    body = { error: { code: 'method_not_allowed', message: 'Method not allowed' } }
  } else if (error.statusCode && error.statusCode >= 400 && error.statusCode < 500) {
    status = error.statusCode
    body = { error: { code: 'bad_request', message: 'Bad request' } }
  } else {
    status = 500
    body = { error: { code: 'internal_error', message: 'An internal error occurred' } }
    console.error(
      `[trs-api] unhandled error ${event.method} ${event.path?.split('?')[0]} (request ${String(event.context.requestId ?? '-')})`,
      cause ?? error,
    )
  }
  setResponseHeaders(event, {
    ...SECURITY_HEADERS,
    ...headers,
    'Content-Type': 'application/json; charset=utf-8',
  })
  // Standard-Statustext statt h3-Meldung (die z. B. den angefragten Pfad enthält).
  setResponseStatus(event, status, STATUS_CODES[status] ?? 'Error')
  return send(event, JSON.stringify(body))
})
