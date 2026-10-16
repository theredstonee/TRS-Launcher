import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { creatorDashboard } from '../../../lib/creators'
import { requireUser } from '../../../lib/http'

/** Creator-Dashboard (§35.3): nur aktive Creator (sonst 403 `creator_inactive`). Statistik nur als Zahlen. */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  return creatorDashboard(useCtx(), auth.uuid)
})
