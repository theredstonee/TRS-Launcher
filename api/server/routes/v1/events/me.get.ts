import { defineEventHandler, getHeader } from 'h3'
import { useCtx } from '../../../lib/context'
import { limit, queryWith, requireUser } from '../../../lib/http'
import { isOwnDevice } from '../../../lib/push'
import { RULES } from '../../../lib/ratelimit'
import { eventsMeQuery } from '../../../lib/schemas'
import { openUserStream } from '../../../lib/sse'

/**
 * Der Push-Kanal je Nutzer: ALLE Ereignisse (Chat, Freunde, Präsenz, Umhang-Angebote, Meldungen,
 * Moderation) mit ID. Fortsetzen per `Last-Event-ID`-Header (oder `?lastEventId=`), sonst `resync`.
 * `?pushDevice=<id>` = Stream der App auf diesem Push-Gerät (§33): solange offen, bekommt das Gerät keine Push-Nachrichten;
 * ohne = Desktop (Launcher/TRS Client).
 */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  limit(`eventsMe:${auth.uuid}`, RULES.eventsMeUser)
  const q = queryWith(event, eventsMeQuery)
  const lastEventId = getHeader(event, 'last-event-id')?.trim() || q.lastEventId
  const ctx = useCtx()
  const res = event.node.res
  const stream = openUserStream(ctx, auth.uuid, lastEventId, {
    write: (chunk) => {
      res.write(chunk)
    },
    buffered: () => res.writableLength,
    end: () => {
      if (!res.writableEnded) res.end()
    },
  }, () => {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-store, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    })
    res.flushHeaders()
  })
  // Fremde/unbekannte Geräte-ID zählt gar nicht (weder App noch Desktop).
  const device = q.pushDevice === undefined ? null : isOwnDevice(ctx, auth.uuid, q.pushDevice) ? q.pushDevice : undefined
  const release = device === undefined ? () => {} : ctx.push.activity.open(auth.uuid, device)
  const close = () => {
    release()
    stream.close()
  }
  event.node.req.on('close', close)
  res.on('close', close)
  res.on('error', close)
})
