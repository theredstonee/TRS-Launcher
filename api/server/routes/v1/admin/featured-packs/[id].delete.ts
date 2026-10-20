import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { deleteFeatured } from '../../../../lib/featured'
import { limit, noContent, paramWith, requireStaff } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Pack samt Bildern entfernen (§37.2). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'featured.manage')
  limit(`adminFeatured:${staff.uuid}`, RULES.adminFeatured)
  const id = paramWith(event, 'id', z.string().max(40))
  deleteFeatured(useCtx(), staff, id)
  return noContent(event)
})
