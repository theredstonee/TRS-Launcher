import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { featuredOrderBody, orderFeatured } from '../../../../lib/featured'
import { limit, readJson, requireStaff } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Neue Reihenfolge (§37.2): `ids` = genau alle Packs, sonst `400 invalid_order`. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'featured.manage')
  limit(`adminFeatured:${staff.uuid}`, RULES.adminFeatured)
  const body = await readJson(event, featuredOrderBody)
  return { packs: orderFeatured(useCtx(), staff, body.ids) }
})
