import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { limit, queryWith, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { wpshareOptionalWorldQuery } from '../../../lib/schemas'
import { ownWaypoints, ownWorlds } from '../../../lib/waypoint-share'

/** §41.4: eigene geteilte Liste einer Welt (`?world=`) bzw. ohne `world` alle eigenen Welten in Kurzform. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'read')
  limit(`wpread:${auth.uuid}`, RULES.wpReadUser)
  const q = queryWith(event, wpshareOptionalWorldQuery)
  const ctx = useCtx()
  return q.world ? ownWaypoints(ctx, auth.uuid, q.world) : ownWorlds(ctx, auth.uuid)
})
