import { defineEventHandler, getQuery } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { adminListRequests } from '../../../../../lib/creators'
import { limit, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Anfragen der Creator (§35.6), offene zuerst; `?status=`. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  return adminListRequests(useCtx(), { ...getQuery<Record<string, unknown>>(event) })
})
