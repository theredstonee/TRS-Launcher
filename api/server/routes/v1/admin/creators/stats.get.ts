import { defineEventHandler, getQuery } from 'h3'
import { useCtx } from '../../../../lib/context'
import { adminCreatorStats } from '../../../../lib/creator-stats'
import { limit, requireStaff } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Creator-Statistik (§35.14): Rangliste aller Creator, `?q=&tier=&state=&sort=&order=&limit=` (strikt geprüft). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  return adminCreatorStats(useCtx(), { ...getQuery<Record<string, unknown>>(event) })
})
