import { defineEventHandler } from 'h3'
import { acceptOffer } from '../../../../lib/capeshares'
import { useCtx } from '../../../../lib/context'
import { limit, paramWith, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { capeIdSchema } from '../../../../lib/schemas'

/** Angebot annehmen → der Umhang ist in der eigenen Sammlung (tragbar). */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`capeshare:${auth.uuid}`, RULES.capeShareUser)
  const capeId = paramWith(event, 'capeId', capeIdSchema)
  return { cape: acceptOffer(useCtx(), auth.user, capeId) }
})
