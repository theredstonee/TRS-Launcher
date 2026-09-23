import { defineEventHandler, getHeader } from 'h3'
import { authenticate } from '../../lib/auth'
import { useCtx } from '../../lib/context'
import { broadcastPresence } from '../../lib/friends'
import { limit, readJson } from '../../lib/http'
import type { GameInfo } from '../../lib/presence'
import { RULES } from '../../lib/ratelimit'
import { presenceBody } from '../../lib/schemas'

/** Heartbeat (spätestens alle 60 s). Ohne Heartbeat gilt man nach 3 min als offline. */
export default defineEventHandler(async (event) => {
  const ctx = useCtx()
  const auth = authenticate(ctx, getHeader(event, 'authorization'))
  limit(`presence:${auth.uuid}`, RULES.presenceUser)
  const body = await readJson(event, presenceBody)
  let changed: boolean
  if (body.state === 'offline') {
    changed = ctx.presence.delete(auth.uuid)
  } else {
    let game: GameInfo | null = null
    if (body.game) {
      game = { version: body.game.version, loader: body.game.loader }
      // Server nur speichern, wenn der Nutzer das erlaubt und gerade spielt.
      if (body.game.server && auth.user.share_server === 1 && body.state === 'in-game') {
        game.server = body.game.server.toLowerCase()
      }
    }
    changed = ctx.presence.set(auth.uuid, body.state, game)
  }
  if (changed) broadcastPresence(ctx, auth.uuid)
  return { state: body.state, expiresInSec: body.state === 'offline' ? 0 : Math.round(ctx.config.limits.presenceTtlMs / 1000) }
})
