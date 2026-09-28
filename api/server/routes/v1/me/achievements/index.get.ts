import { defineEventHandler } from 'h3'
import { myAchievements } from '../../../../lib/achievements'
import { useCtx } from '../../../../lib/context'
import { requireUser } from '../../../../lib/http'

/** Eigene Erfolge (§31.3): Katalog aus eigener Sicht, Freischaltungen, Fortschritt, Punkte. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'read')
  return myAchievements(useCtx(), auth.uuid)
})
