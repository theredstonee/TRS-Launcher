import { defineEventHandler, getHeader } from 'h3'
import { useCtx } from '../../../lib/context'
import { limit, queryWith, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { eventsMeQuery } from '../../../lib/schemas'
import { openUserStream } from '../../../lib/sse'

/**
 * Der Push-Kanal je Nutzer: ALLE Ereignisse (Chat, Freunde, Präsenz, Umhang-Angebote, Meldungen,
 * Moderation) mit ID. Fortsetzen per `Last-Event-ID`-Header (oder `?lastEventId=`), sonst `resync`.
 */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  limit(`eventsMe:${auth.uuid}`, RULES.eventsMeUser)
  const q = queryWith(event, eventsMeQuery)
  const lastEventId = getHeader(event, 'last-event-id')?.trim() || q.lastEventId
  const res = event.node.res
  const stream = openUserStream(useCtx(), auth.uuid, lastEventId, {
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
  event.node.req.on('close', stream.close)
  res.on('close', stream.close)
  res.on('error', stream.close)
})
