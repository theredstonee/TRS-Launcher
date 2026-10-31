import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { limit, readJson, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { wpshareSessionBody } from '../../../lib/schemas'
import { WPSHARE_SESSION_TTL_MS, setSession } from '../../../lib/waypoint-share'

/** §41.2: Sitzung in einer Welt (Betreten, Welt-/Dimensionswechsel, alle 60 s). Nur im RAM, läuft nach 3 min ab. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`wpsession:${auth.uuid}`, RULES.wpSessionUser)
  const body = await readJson(event, wpshareSessionBody)
  const peers = setSession(useCtx(), auth.uuid, body.world, body.dimension)
  return { peers, expiresInSec: Math.round(WPSHARE_SESSION_TTL_MS / 1000) }
})
