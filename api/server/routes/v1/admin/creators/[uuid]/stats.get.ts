import { defineEventHandler, getQuery } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { adminCreatorStatsDetail } from '../../../../../lib/creator-stats'
import { limit, paramWith, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { uuidSchema } from '../../../../../lib/schemas'

/** Creator-Statistik eines Creators mit Tagesverlauf (§35.14), `?days=7…365` (Standard 90). 404 `creator_not_found`. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  return adminCreatorStatsDetail(useCtx(), paramWith(event, 'uuid', uuidSchema), { ...getQuery<Record<string, unknown>>(event) })
})
