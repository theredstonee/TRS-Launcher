import { defineEventHandler, getQuery } from 'h3'
import { useCtx } from '../../../../lib/context'
import { adminListCreators } from '../../../../lib/creators'
import { limit, requireStaff } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Creator-Liste (§35.6), `?q=` (Name, UUID, Code) und `?state=`. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, ['creators.manage', 'creators.review'])
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  return adminListCreators(useCtx(), { ...getQuery<Record<string, unknown>>(event) })
})
