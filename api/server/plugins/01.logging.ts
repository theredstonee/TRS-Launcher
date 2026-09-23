import { getResponseStatus } from 'h3'
import { useCtx } from '../lib/context'

/**
 * Zugriffslog (nur mit LOG_REQUESTS=true): Methode, Pfad ohne Query, Status,
 * Dauer, Request-ID – bewusst ohne IP-Adressen und Tokens (Datensparsamkeit).
 */
export default defineNitroPlugin((nitroApp) => {
  nitroApp.hooks.hook('afterResponse', (event) => {
    let enabled: boolean
    try {
      enabled = useCtx().config.logRequests
    } catch {
      return
    }
    if (!enabled) return
    const started = event.context.startedAt as number | undefined
    const ms = started === undefined ? -1 : Math.round(performance.now() - started)
    const path = (event.path ?? '').split('?')[0]
    console.info(`${event.method} ${path} ${getResponseStatus(event)} ${ms}ms ${String(event.context.requestId ?? '-')}`)
  })
})
