import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { limit, paramWith, requireStaff } from '../../../../../../lib/http'
import { adminDeleteFixedCode, PARTNER_SLUG } from '../../../../../../lib/partners'
import { RULES } from '../../../../../../lib/ratelimit'

/** Zusatz-Code ohne Einlösungen löschen → `{ partner }`. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'partners.manage')
  limit(`admin-partners:${staff.uuid}`, RULES.adminPartners)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  const code = paramWith(event, 'code', z.string().min(4).max(19))
  return adminDeleteFixedCode(useCtx(), staff, slug, code)
})
