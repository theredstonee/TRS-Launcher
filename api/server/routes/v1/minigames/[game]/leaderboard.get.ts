import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, paramWith, requireUser } from '../../../../lib/http'
import { gameSchema, leaderboard } from '../../../../lib/minigames'
import { RULES } from '../../../../lib/ratelimit'

/** Bestenliste (§42.3): Top 100, eigener Platz, Gesamtzahl. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'read')
  const game = paramWith(event, 'game', gameSchema)
  limit(`minigameBoard:${auth.uuid}`, RULES.minigameBoardUser)
  return leaderboard(useCtx(), auth.uuid, game)
})
