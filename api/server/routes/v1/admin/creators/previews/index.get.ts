import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { adminListPreviews } from '../../../../../lib/creators'
import { limit, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Vorschau-Builds verwalten (§35.6): Liste. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  return adminListPreviews(useCtx(), staff)
})
