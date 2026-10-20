import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { refreshFeatured } from '../../../../../lib/featured'
import { limit, paramWith, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Plattform-Daten und Bilder neu holen (§37.2); `rev` steigt, die Bild-Adressen ändern sich. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'featured.manage')
  limit(`adminFeatured:${staff.uuid}`, RULES.adminFeatured)
  const id = paramWith(event, 'id', z.string().max(40))
  return { pack: await refreshFeatured(useCtx(), staff, id) }
})
