import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { limit, queryWith, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { wpshareWorldQuery } from '../../../lib/schemas'
import { friendsWaypoints } from '../../../lib/waypoint-share'

/** §41.4: Wegpunkte (+ frische Position) der Freunde, die mir freigeben – nur für die Welt der eigenen Sitzung. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'read')
  limit(`wpread:${auth.uuid}`, RULES.wpReadUser)
  const q = queryWith(event, wpshareWorldQuery)
  return friendsWaypoints(useCtx(), auth.uuid, q.world)
})
