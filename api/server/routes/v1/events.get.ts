import { createEventStream, defineEventHandler, setResponseHeader } from 'h3'
import { useCtx } from '../../lib/context'
import { unavailable } from '../../lib/errors'
import { limit, requireUser } from '../../lib/http'
import { RULES } from '../../lib/ratelimit'

const KEEPALIVE_MS = 25_000
const MAX_LIFETIME_MS = 60 * 60_000

/**
 * Server-Sent Events für Freundes-/Präsenz-Änderungen. Optional – Polling von
 * `GET /v1/friends` bleibt die Grundlage. Streams enden spätestens nach 1 h
 * (Client verbindet neu) und sofort bei Abmelden-überall, Sperre oder Löschung.
 */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event)
  limit(`events:${auth.uuid}`, RULES.eventsUser)
  const ctx = useCtx()
  const stream = createEventStream(event)
  const sub = ctx.events.subscribe(
    auth.uuid,
    (e) => {
      void stream.push({ event: e.type, data: JSON.stringify(e) })
    },
    () => {
      void stream.close()
    },
  )
  if (!sub) throw unavailable('too_many_streams', 'Too many open event streams')

  const keepalive = setInterval(() => {
    void stream.push({ event: 'ping', data: '{}' })
  }, KEEPALIVE_MS)
  const lifetime = setTimeout(() => {
    void stream.close()
  }, MAX_LIFETIME_MS)
  stream.onClosed(() => {
    clearInterval(keepalive)
    clearTimeout(lifetime)
    sub.close()
  })
  setResponseHeader(event, 'Cache-Control', 'no-store, no-transform')
  void stream.push({ event: 'hello', data: JSON.stringify({ type: 'hello', keepaliveSec: KEEPALIVE_MS / 1000 }) })
  return stream.send()
})
