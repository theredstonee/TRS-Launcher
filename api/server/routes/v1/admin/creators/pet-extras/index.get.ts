import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { limit, requireStaff } from '../../../../../lib/http'
import { adminPetExtrasOverview } from '../../../../../lib/pet-extras'
import { RULES } from '../../../../../lib/ratelimit'

/** Begleiter-Gefolge (§35.13): Creator mit Extras + wählbare Begleiter (Platz `pet`). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  return adminPetExtrasOverview(useCtx(), staff)
})
