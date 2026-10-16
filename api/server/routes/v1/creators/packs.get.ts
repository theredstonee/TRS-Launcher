import { defineEventHandler, getQuery, setResponseHeaders } from 'h3'
import { useCtx } from '../../../lib/context'
import { listCreatorPacks } from '../../../lib/creators'
import { clientIp, limit, optionalUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'

/** Creator-Packs (§35.4): gelistete Packs aktiver Creator, meiste Installationen zuerst. Mit Konto ohne blockierte Creator. */
export default defineEventHandler((event) => {
  const user = optionalUser(event)
  if (user) limit(`packLookup:${user.uuid}`, RULES.packLookupUser)
  else limit(`packPublic:${clientIp(event)}`, RULES.packPublicIp)
  setResponseHeaders(event, { 'Cache-Control': 'private, no-store' })
  return listCreatorPacks(useCtx(), user?.uuid ?? null, { ...getQuery<Record<string, unknown>>(event) })
})
