import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../../lib/http'
import { adminAddItem, PARTNER_SLUG } from '../../../../../../lib/partners'
import { RULES } from '../../../../../../lib/ratelimit'

/** Katalog-Teil zuordnen: `{ kind: "cape"|"cosmetic", id }`. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'partners.manage')
  limit(`admin-partners:${staff.uuid}`, RULES.adminPartners)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return adminAddItem(useCtx(), staff, slug, await readJson(event, z.unknown()))
})
