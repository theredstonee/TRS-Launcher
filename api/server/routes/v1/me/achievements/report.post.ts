import { defineEventHandler } from 'h3'
import { achievementReportBody, reportAchievement } from '../../../../lib/achievements'
import { useCtx } from '../../../../lib/context'
import { limit, readJson, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Launcher-Meldung für Erfolge (§31.4): `{ kind, hour?, count? }` → `{ newlyUnlocked }`. Nie mit Belohnungen verknüpft. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'read')
  limit(`achReport:${auth.uuid}`, RULES.achievementReportUser)
  const body = await readJson(event, achievementReportBody)
  return reportAchievement(useCtx(), auth.uuid, body)
})
