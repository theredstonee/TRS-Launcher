import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { mySanctions } from '../../../../lib/sanctions'

/** Eigene Strafen (aktiv + vergangen) – auch mit dem Einspruch-Token eines gesperrten Kontos (§22.8). */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'read', { appeal: true })
  limit(`my-sanctions:${auth.uuid}`, RULES.mySanctionsUser)
  return mySanctions(useCtx(), auth.uuid)
})
