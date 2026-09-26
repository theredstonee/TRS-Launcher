import { defineEventHandler } from 'h3'
import { offerCape } from '../../../lib/capeshares'
import { useCtx } from '../../../lib/context'
import { created, limit, readJson, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { capeOfferBody } from '../../../lib/schemas'

/** Eigenen freigegebenen (oder angenommenen geteilten) Umhang einem Freund anbieten. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`capeshare:${auth.uuid}`, RULES.capeShareUser)
  const body = await readJson(event, capeOfferBody)
  return created(event, { offer: offerCape(useCtx(), auth.user, body.capeId, body.friend) })
})
