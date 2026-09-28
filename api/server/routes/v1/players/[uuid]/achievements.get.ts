import { defineEventHandler } from 'h3'
import { playerAchievements } from '../../../../lib/achievements'
import { useCtx } from '../../../../lib/context'
import { limit, paramWith, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { uuidSchema } from '../../../../lib/schemas'

/** Erfolge eines Spielers (§31.5): nur selbst oder angenommene Freunde, sonst 404 `player_not_found`. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'read')
  limit(`achView:${auth.uuid}`, RULES.achievementViewUser)
  const target = paramWith(event, 'uuid', uuidSchema)
  return playerAchievements(useCtx(), auth.uuid, target)
})
