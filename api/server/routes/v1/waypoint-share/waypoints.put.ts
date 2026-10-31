import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { limit, readJson, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { wpshareWaypointsBody } from '../../../lib/schemas'
import { WPSHARE_BODY_LIMIT, putWaypoints } from '../../../lib/waypoint-share'

/** §41.4: eigene geteilte Liste für eine Welt ersetzen (leer = löschen), `rev` für Optimistic Concurrency. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`wpwrite:${auth.uuid}`, RULES.wpWriteUser)
  const body = await readJson(event, wpshareWaypointsBody, WPSHARE_BODY_LIMIT)
  return putWaypoints(useCtx(), auth.uuid, body.world, body.rev, body.waypoints)
})
