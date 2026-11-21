import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { requireWebOrUser } from '../../../../lib/http'
import { myPartnerState } from '../../../../lib/partners'

/** Eigene Partner-Unterstützung, Partner-Kosmetik, Dashboard-Zugänge und Einladungen (§44.2). */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event)
  return myPartnerState(useCtx(), auth.uuid)
})
