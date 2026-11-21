import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { adminUpdatePartner, PARTNER_SLUG } from '../../../../../lib/partners'
import { RULES } from '../../../../../lib/ratelimit'

/** Partner ändern: name, category, state (active|paused), code, prefix, owner, wikiUrl, text, links. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'partners.manage')
  limit(`admin-partners:${staff.uuid}`, RULES.adminPartners)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return adminUpdatePartner(useCtx(), staff, slug, await readJson(event, z.unknown()))
})
