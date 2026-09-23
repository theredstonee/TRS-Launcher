import { createEventStream, defineEventHandler, setResponseHeader } from 'h3'
import { useCtx } from '../../../lib/context'
import { unavailable } from '../../../lib/errors'
import { limit, queryWith, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { playerStreamQuery } from '../../../lib/schemas'

const KEEPALIVE_MS = 25_000
const MAX_LIFETIME_MS = 60 * 60_000

/**
 * Spieler-Ereignisse für den In-Game-Mod: `?uuids=a,b,…` (≤ 200). Geliefert
 * werden nur Ereignisse dieser Spieler (Emote, Skin, Umhang, Kosmetik). Zum
 * Ändern der Menge neu verbinden und den alten Stream schließen.
 */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event)
  const { uuids } = queryWith(event, playerStreamQuery)
  limit(`pevents:${auth.uuid}`, RULES.playerEventsUser)
  const ctx = useCtx()
  const stream = createEventStream(event)
  const sub = ctx.watch.subscribe(
    auth.uuid,
    uuids.slice(0, ctx.config.limits.maxWatchedPerStream),
    (e) => {
      void stream.push({ event: e.type, data: JSON.stringify(e) })
    },
    () => {
      void stream.close()
    },
  )
  if (!sub) throw unavailable('too_many_streams', 'Too many open player event streams')

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
  void stream.push({
    event: 'hello',
    data: JSON.stringify({ type: 'hello', keepaliveSec: KEEPALIVE_MS / 1000, watching: sub.watching }),
  })
  return stream.send()
})
