import { defineEventHandler, getQuery } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { adminListPetRequests } from '../../../../../lib/creator-pets'
import { limit, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Haustier-Wünsche der Creator (§35.13), offene und in Arbeit zuerst; `?status=`. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  return adminListPetRequests(useCtx(), staff, { ...getQuery<Record<string, unknown>>(event) })
})
