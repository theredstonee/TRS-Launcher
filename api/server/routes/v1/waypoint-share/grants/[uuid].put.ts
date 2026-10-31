import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, noContent, paramWith, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { uuidSchema } from '../../../../lib/schemas'
import { grant } from '../../../../lib/waypoint-share'

/** §41.1: einem Freund freigeben (er sieht dann meine Wegpunkte + Position in derselben Welt). Idempotent. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`wpwrite:${auth.uuid}`, RULES.wpWriteUser)
  grant(useCtx(), auth.uuid, paramWith(event, 'uuid', uuidSchema))
  return noContent(event)
})
