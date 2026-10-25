import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { adminPetOverview } from '../../../../../lib/creator-pets'
import { limit, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Creator-Haustiere (§35.13): Zuordnungen, wählbare Haustiere (Platz `pet`) und Monatsgrenzen. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  return adminPetOverview(useCtx(), staff)
})
