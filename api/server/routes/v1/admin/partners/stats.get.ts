import { defineEventHandler, getQuery } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, requireStaff } from '../../../../lib/http'
import { adminPartnerStats } from '../../../../lib/partner-stats'
import { RULES } from '../../../../lib/ratelimit'

/** Rangliste aller Partner (§44.7): `?q=&state=&category=&sort=&order=`. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'partners.manage')
  limit(`admin-partners:${staff.uuid}`, RULES.adminPartners)
  return adminPartnerStats(useCtx(), staff, { ...getQuery<Record<string, unknown>>(event) })
})
