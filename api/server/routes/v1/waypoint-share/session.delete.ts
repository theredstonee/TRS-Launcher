import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { limit, noContent, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { endSession } from '../../../lib/waypoint-share'

/** §41.2: Welt verlassen. Idempotent. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`wpsession:${auth.uuid}`, RULES.wpSessionUser)
  endSession(useCtx(), auth.uuid)
  return noContent(event)
})
