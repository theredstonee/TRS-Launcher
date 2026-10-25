import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { adminRemovePet } from '../../../../../lib/creator-pets'
import { limit, noContent, paramWith, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { uuidSchema } from '../../../../../lib/schemas'

/** Haustier-Zuordnung lösen (§35.13): Besitz-Zuteilung weg, seine Haustier-Codes gelten nicht mehr. → 204 */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  adminRemovePet(useCtx(), staff, paramWith(event, 'uuid', uuidSchema))
  return noContent(event)
})
