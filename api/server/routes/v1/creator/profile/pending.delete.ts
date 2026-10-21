import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { actingAs } from '../../../../lib/creator-http'
import { withdrawProfile } from '../../../../lib/creators'
import { limit, noContent, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Wartende Profil-Fassung zurückziehen (§35.11, nur der Creator). 204. */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  withdrawProfile(useCtx(), auth.uuid, actingAs(event))
  return noContent(event)
})
