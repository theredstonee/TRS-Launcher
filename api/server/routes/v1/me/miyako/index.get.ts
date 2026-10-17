import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { notFound } from '../../../../lib/errors'
import { limit, requireUser } from '../../../../lib/http'
import { MIYAKO_TIMER_MAX_MS, isMiyako, miyakoIntroDone } from '../../../../lib/miyako'
import { RULES } from '../../../../lib/ratelimit'

/**
 * `GET /v1/me/miyako` (§36, privat, nur MiyakoMC): `{ introDone, timerMaxMs }`. Jedes andere Konto bekommt 404,
 * damit nichts durchsickert.
 */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  if (!isMiyako(auth.uuid)) throw notFound()
  limit(`miyako:${auth.uuid}`, RULES.miyakoUser)
  const ctx = useCtx()
  return { introDone: miyakoIntroDone(ctx, auth.uuid), timerMaxMs: MIYAKO_TIMER_MAX_MS }
})
