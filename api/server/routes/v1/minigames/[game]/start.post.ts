import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, paramWith, requireUser } from '../../../../lib/http'
import { gameSchema, startRun } from '../../../../lib/minigames'
import { RULES } from '../../../../lib/ratelimit'

/** Gewertete Partie starten (§42.1): `{ runId, seed }`. Der Startwert kommt vom Server. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  const game = paramWith(event, 'game', gameSchema)
  limit(`minigameStart:${auth.uuid}`, RULES.minigameStartUser)
  return startRun(useCtx(), auth.uuid, game)
})
