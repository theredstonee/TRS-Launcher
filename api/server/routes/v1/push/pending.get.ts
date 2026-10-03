import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { limit, queryWith, requireUser } from '../../../lib/http'
import { pendingFor } from '../../../lib/push'
import { RULES } from '../../../lib/ratelimit'
import { pushPendingQuery } from '../../../lib/schemas'

/** Abruf für „poll“-Geräte (iOS ohne APNs, §33.4). */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  limit(`pushPending:${auth.uuid}`, RULES.pushPendingUser)
  const q = queryWith(event, pushPendingQuery)
  return pendingFor(useCtx(), auth.uuid, q.device, q.since, q.limit)
})
