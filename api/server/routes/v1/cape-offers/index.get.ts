import { defineEventHandler } from 'h3'
import { listOffers } from '../../../lib/capeshares'
import { useCtx } from '../../../lib/context'
import { requireUser } from '../../../lib/http'

/** Offene Umhang-Angebote: an mich (`incoming`) und von mir (`outgoing`). */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  return listOffers(useCtx(), auth.uuid)
})
