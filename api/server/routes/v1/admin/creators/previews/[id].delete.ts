import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { adminDeletePreview } from '../../../../../lib/creators'
import { limit, noContent, paramWith, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Vorschau-Build entfernen (§35.6). 204. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  adminDeletePreview(useCtx(), staff, paramWith(event, 'id', z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER)))
  return noContent(event)
})
