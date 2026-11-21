import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, noContent, requireWebOrUser } from '../../../../lib/http'
import { endPartnerSupport } from '../../../../lib/partners'
import { RULES } from '../../../../lib/ratelimit'

/** Partner-Unterstützung beenden (§44.2); Kosmetik bleibt. 204. */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`partner-write:${auth.uuid}`, RULES.partnerWriteUser)
  endPartnerSupport(useCtx(), auth.uuid)
  return noContent(event)
})
