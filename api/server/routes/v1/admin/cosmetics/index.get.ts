import { defineEventHandler } from 'h3'
import { listCosmeticsForReview } from '../../../../lib/admin'
import { useCtx } from '../../../../lib/context'
import { queryWith, requireAdmin } from '../../../../lib/http'
import { adminCosmeticListQuery } from '../../../../lib/schemas'

/** Kosmetik-Uploads nach Status (`?status=pending|approved|rejected|reported`, Standard: pending). */
export default defineEventHandler((event) => {
  requireAdmin(event)
  const q = queryWith(event, adminCosmeticListQuery)
  return { cosmetics: listCosmeticsForReview(useCtx(), q.status) }
})
