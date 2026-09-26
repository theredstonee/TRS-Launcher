import type { AppContext } from './context'
import { unavailable } from './errors'
import type { ApiEvent, ReplayResult } from './events'

/**
 * Der gemeinsame Push-Kanal je Nutzer (`GET /v1/events/me`): alle Ereignisse mit ID,
 * Wiederaufnahme per `Last-Event-ID` (begrenzt, sonst `resync`), Herzschlag, Lebensdauer,
 * Gegendruck (langsame Clients werden getrennt und setzen per Last-Event-ID fort).
 */

export const HEARTBEAT_MS = 20_000
export const MAX_LIFETIME_MS = 60 * 60_000
/** Mehr ungesendete Bytes als das → Stream schließen (Client verbindet neu und holt nach). */
export const MAX_BUFFERED_BYTES = 512 * 1024

export interface SseSink {
  /** Schreibt einen fertigen SSE-Block. */
  write: (chunk: string) => void
  /** Noch nicht an den Client übertragene Bytes. */
  buffered: () => number
  end: () => void
}

export function frame(event: string, data: unknown, id?: string | null): string {
  return `${id ? `id: ${id}\n` : ''}event: ${event}\ndata: ${JSON.stringify(data)}\n\n`
}

export interface UserStream {
  close: () => void
  replay: ReplayResult | null
}

/**
 * Öffnet den Stream: erst Nachholen berechnen, dann abonnieren (beides im selben Tick – es
 * kann nichts dazwischenkommen), dann `hello`, nachgeholte Ereignisse bzw. `resync`.
 * Wirft `503 too_many_streams`, bevor etwas geschrieben wurde.
 */
export function openUserStream(ctx: AppContext, uuid: string, lastEventId: string | undefined, sink: SseSink, begin: () => void): UserStream {
  const replay = lastEventId ? ctx.events.replay(uuid, lastEventId) : null
  let closed = false
  const timers: { heartbeat?: ReturnType<typeof setInterval>, lifetime?: ReturnType<typeof setTimeout> } = {}
  const close = () => {
    if (closed) return
    closed = true
    clearInterval(timers.heartbeat)
    clearTimeout(timers.lifetime)
    sub?.close()
    try {
      sink.end()
    } catch {
      // schon zu
    }
  }
  const send = (chunk: string) => {
    if (closed) return
    if (sink.buffered() > MAX_BUFFERED_BYTES) return close()
    sink.write(chunk)
  }
  const sub = ctx.events.subscribe(
    uuid,
    (e: ApiEvent, id: string | null) => send(frame(e.type, e, id)),
    close,
    'me',
  )
  if (!sub) throw unavailable('too_many_streams', 'Too many open event streams')
  begin()
  // Frischer Start/Resync: `hello` trägt die aktuelle ID (so klappt die Wiederaufnahme auch ohne
  // Ereignisse). Beim Fortsetzen ohne ID, damit die Last-Event-ID des Clients die des zuletzt
  // nachgeholten Ereignisses bleibt.
  send(frame('hello', {
    type: 'hello',
    keepaliveSec: HEARTBEAT_MS / 1000,
    resumed: replay?.ok === true,
    replayWindowSec: Math.round(ctx.config.limits.replayWindowMs / 1000),
  }, replay?.ok ? null : ctx.events.currentId()))
  if (replay) {
    if (replay.ok) for (const r of replay.events) send(frame(r.e.type, r.e, r.id))
    else send(frame('resync', { type: 'resync', reason: replay.reason }))
  }
  timers.heartbeat = setInterval(() => send(frame('ping', {})), HEARTBEAT_MS)
  timers.heartbeat.unref?.()
  timers.lifetime = setTimeout(close, MAX_LIFETIME_MS)
  timers.lifetime.unref?.()
  return { close, replay }
}
