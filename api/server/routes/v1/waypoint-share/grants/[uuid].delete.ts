import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, noContent, paramWith, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { uuidSchema } from '../../../../lib/schemas'
import { revoke } from '../../../../lib/waypoint-share'

/** §41.1: Freigabe entziehen (der Freund bekommt `wpshare_revoked`). Idempotent. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`wpwrite:${auth.uuid}`, RULES.wpWriteUser)
  revoke(useCtx(), auth.uuid, paramWith(event, 'uuid', uuidSchema))
  return noContent(event)
})
