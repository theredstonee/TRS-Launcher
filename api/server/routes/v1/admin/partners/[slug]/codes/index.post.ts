import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { created, limit, paramWith, readJson, requireStaff } from '../../../../../../lib/http'
import { adminAddFixedCode, PARTNER_SLUG } from '../../../../../../lib/partners'
import { RULES } from '../../../../../../lib/ratelimit'

/** Weiteren festen Code anlegen: `{ code, main? }` → 201 `{ partner }`. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'partners.manage')
  limit(`admin-partners:${staff.uuid}`, RULES.adminPartners)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return created(event, adminAddFixedCode(useCtx(), staff, slug, await readJson(event, z.unknown())))
})
