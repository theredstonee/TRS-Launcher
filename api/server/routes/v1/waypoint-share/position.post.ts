import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { limit, noContent, readJson, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { wpsharePositionBody } from '../../../lib/schemas'
import { postPosition } from '../../../lib/waypoint-share'

/**
 * §41.3: eigene Position an Freunde mit Freigabe in derselben Welt (flüchtig). Immer 204 – auch ohne Sitzung oder
 * Empfänger. Die Position wird weder gespeichert noch geloggt.
 */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`wppos:${auth.uuid}`, RULES.wpPositionUser)
  const body = await readJson(event, wpsharePositionBody)
  postPosition(useCtx(), auth.uuid, body)
  return noContent(event)
})
