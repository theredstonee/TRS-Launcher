import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { actingAs } from '../../../lib/creator-http'
import { creatorDashboard } from '../../../lib/creators'
import { requireWebOrUser } from '../../../lib/http'

/** Creator-Dashboard (§35.3): nur aktive Creator (sonst 403 `creator_inactive`) oder ihre Helfer mit `?as=` (§35.9).
 * Statistik nur als Zahlen. */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event)
  return creatorDashboard(useCtx(), auth.uuid, actingAs(event))
})
