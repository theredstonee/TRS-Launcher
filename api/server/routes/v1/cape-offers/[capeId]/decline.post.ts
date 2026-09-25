import { defineEventHandler } from 'h3'
import { declineOffer } from '../../../../lib/capeshares'
import { useCtx } from '../../../../lib/context'
import { limit, noContent, paramWith, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { capeIdSchema } from '../../../../lib/schemas'

/** Angebot ablehnen (der Anbieter wird nicht benachrichtigt). */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`capeshare:${auth.uuid}`, RULES.capeShareUser)
  declineOffer(useCtx(), auth.uuid, paramWith(event, 'capeId', capeIdSchema))
  return noContent(event)
})
