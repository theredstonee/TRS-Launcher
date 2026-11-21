import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../../lib/http'
import { adminUpdateFixedCode, PARTNER_SLUG } from '../../../../../../lib/partners'
import { RULES } from '../../../../../../lib/ratelimit'

/** Festen Code (de)aktivieren oder zum Haupt-Code machen: `{ active?, main?: true }`. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'partners.manage')
  limit(`admin-partners:${staff.uuid}`, RULES.adminPartners)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  const code = paramWith(event, 'code', z.string().min(4).max(19))
  return adminUpdateFixedCode(useCtx(), staff, slug, code, await readJson(event, z.unknown()))
})
