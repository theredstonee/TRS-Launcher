import { defineEventHandler } from 'h3'
import { achievementSettingsBody, setAchievementsVisible } from '../../../../lib/achievements'
import { useCtx } from '../../../../lib/context'
import { readJson, requireUser } from '../../../../lib/http'

/** Erfolge vor Freunden verbergen oder zeigen (§31.5): `{ visibleToFriends }` → `{ visibleToFriends }`. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const body = await readJson(event, achievementSettingsBody)
  return setAchievementsVisible(useCtx(), auth.uuid, body.visibleToFriends)
})
