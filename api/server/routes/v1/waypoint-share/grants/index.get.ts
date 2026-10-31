import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { listGrants } from '../../../../lib/waypoint-share'

/** §41.1: wem ich freigebe (`grants`) und wer mir freigibt (`grantedToMe`). */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'read')
  limit(`wpread:${auth.uuid}`, RULES.wpReadUser)
  return listGrants(useCtx(), auth.uuid)
})
