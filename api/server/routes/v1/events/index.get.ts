import { createEventStream, defineEventHandler, getHeader, setResponseHeader } from 'h3'
import { useCtx } from '../../../lib/context'
import { all } from '../../../lib/db'
import { unavailable } from '../../../lib/errors'
import { limit, requireUser } from '../../../lib/http'
import { globalEvents } from '../../../lib/liveevents'
import { RULES } from '../../../lib/ratelimit'

const KEEPALIVE_MS = 25_000
const MAX_LIFETIME_MS = 60 * 60_000

/**
 * Zwei Dinge unter einer Adresse (nach `Accept` unterschieden):
 * - `Accept: text/event-stream` → der alte Stream (unten, braucht Anmeldung).
 * - sonst → öffentliche Event-Liste (§32): `{ events: [{ id, active }] }`, nur global, 60 s cachebar, ohne Anmeldung.
 *
 * Server-Sent Events für Freundes-/Präsenz-Änderungen. Optional – Polling von
 * `GET /v1/friends` bleibt die Grundlage. Streams enden spätestens nach 1 h
 * (Client verbindet neu) und sofort bei Abmelden-überall, Sperre oder Löschung.
 */
export default defineEventHandler(async (event) => {
  if (!(getHeader(event, 'accept') ?? '').includes('text/event-stream')) {
    setResponseHeader(event, 'Cache-Control', 'public, max-age=60')
    setResponseHeader(event, 'Vary', 'Accept')
    const ctx = useCtx()
    const active = new Set(globalEvents(ctx))
    const ids = all<{ id: string }>(ctx.db, 'SELECT id FROM events ORDER BY id').map((r) => r.id)
    return { events: ids.map((id) => ({ id, active: active.has(id) })) }
  }
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
