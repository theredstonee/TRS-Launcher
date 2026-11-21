import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, requireStaff } from '../../../../../lib/http'
import { adminGetPartner, PARTNER_SLUG } from '../../../../../lib/partners'
import { RULES } from '../../../../../lib/ratelimit'

/** Ein Partner (Team-Sicht). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'partners.manage')
  limit(`admin-partners:${staff.uuid}`, RULES.adminPartners)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return adminGetPartner(useCtx(), staff, slug)
})
