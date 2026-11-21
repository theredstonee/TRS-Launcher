import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../../lib/context'
import { limit, paramWith, requireStaff } from '../../../../../../../lib/http'
import { adminRemoveItem, PARTNER_SLUG } from '../../../../../../../lib/partners'
import { RULES } from '../../../../../../../lib/ratelimit'

/** Zuordnung lösen (Spieler behalten das Teil). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'partners.manage')
  limit(`admin-partners:${staff.uuid}`, RULES.adminPartners)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  const kind = paramWith(event, 'kind', z.enum(['cape', 'cosmetic']))
  const id = paramWith(event, 'id', z.string().min(1).max(40))
  return adminRemoveItem(useCtx(), staff, slug, kind, id)
})
