import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { getQuery } from 'h3'
import { limit, requireStaff } from '../../../../../lib/http'
import { adminListRequests } from '../../../../../lib/partners'
import { RULES } from '../../../../../lib/ratelimit'

/** Anträge (`?status=open|accepted|rejected|withdrawn`, Standard open). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'partners.manage')
  limit(`admin-partners:${staff.uuid}`, RULES.adminPartners)
  return adminListRequests(useCtx(), staff, { ...getQuery<Record<string, unknown>>(event) })
})
