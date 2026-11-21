import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { adminGrantQuota, PARTNER_SLUG } from '../../../../../lib/partners'
import { RULES } from '../../../../../lib/ratelimit'

/** Kontingent-Paket freigeben: `{ amount: 500|1000|5000 }`; schließt einen offenen Nachschub-Antrag. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'partners.manage')
  limit(`admin-partners:${staff.uuid}`, RULES.adminPartners)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return adminGrantQuota(useCtx(), staff, slug, await readJson(event, z.unknown()))
})
