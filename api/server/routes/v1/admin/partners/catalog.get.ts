import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, requireStaff } from '../../../../lib/http'
import { adminCatalog } from '../../../../lib/partners'
import { RULES } from '../../../../lib/ratelimit'

/** Zuordenbare Katalog-Teile (Umhänge, Kosmetik). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'partners.manage')
  limit(`admin-partners:${staff.uuid}`, RULES.adminPartners)
  return adminCatalog(useCtx(), staff)
})
