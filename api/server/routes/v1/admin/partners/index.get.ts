import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, requireStaff } from '../../../../lib/http'
import { adminListPartners } from '../../../../lib/partners'
import { RULES } from '../../../../lib/ratelimit'

/** Alle Partner (§44.7). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'partners.manage')
  limit(`admin-partners:${staff.uuid}`, RULES.adminPartners)
  return adminListPartners(useCtx(), staff)
})
