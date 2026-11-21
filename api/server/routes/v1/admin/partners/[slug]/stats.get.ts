import { defineEventHandler, getQuery } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, requireStaff } from '../../../../../lib/http'
import { adminPartnerStatsDetail } from '../../../../../lib/partner-stats'
import { PARTNER_SLUG } from '../../../../../lib/partners'
import { RULES } from '../../../../../lib/ratelimit'

/** Statistik eines Partners mit Tagesverlauf (§44.7), `?days=7…365` (Standard 30). 404 `partner_not_found`. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'partners.manage')
  limit(`admin-partners:${staff.uuid}`, RULES.adminPartners)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return adminPartnerStatsDetail(useCtx(), staff, slug, { ...getQuery<Record<string, unknown>>(event) })
})
