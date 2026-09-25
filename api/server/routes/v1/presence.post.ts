import { defineEventHandler, getHeader } from 'h3'
import { authenticate } from '../../lib/auth'
import { useCtx } from '../../lib/context'
import { limit, readJson } from '../../lib/http'
import { reportPresence } from '../../lib/playerevents'
import { RULES } from '../../lib/ratelimit'
import { presenceBody } from '../../lib/schemas'

/**
 * Heartbeat (spätestens alle 60 s). Ohne Heartbeat gilt eine Meldung nach 3 min
 * als abgelaufen. Launcher und Mod melden getrennt (`via`, siehe `reportPresence`).
 */
export default defineEventHandler(async (event) => {
  const ctx = useCtx()
  const auth = authenticate(ctx, getHeader(event, 'authorization'))
  limit(`presence:${auth.uuid}`, RULES.presenceUser)
  const body = await readJson(event, presenceBody)
  reportPresence(ctx, auth.user, body)
  return { state: body.state, expiresInSec: body.state === 'offline' ? 0 : Math.round(ctx.config.limits.presenceTtlMs / 1000) }
})
