import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { endSupport } from '../../../../lib/creators'
import { limit, noContent, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Unterstützung beenden (§35.2): Fan-Abzeichen weg, Fan-Umhänge bleiben. 204. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`creator-fan:${auth.uuid}`, RULES.creatorFanUser)
  endSupport(useCtx(), auth.uuid)
  return noContent(event)
})
