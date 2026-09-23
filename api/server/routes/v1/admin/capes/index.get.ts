import { defineEventHandler } from 'h3'
import { listCapesForReview } from '../../../../lib/admin'
import { useCtx } from '../../../../lib/context'
import { queryWith, requireAdmin } from '../../../../lib/http'
import { adminCapeListQuery } from '../../../../lib/schemas'

/** Hochgeladene Umhänge nach Status (`?status=pending|approved|rejected|reported`, Standard: pending). */
export default defineEventHandler((event) => {
  requireAdmin(event)
  const q = queryWith(event, adminCapeListQuery)
  return { capes: listCapesForReview(useCtx(), q.status) }
})
