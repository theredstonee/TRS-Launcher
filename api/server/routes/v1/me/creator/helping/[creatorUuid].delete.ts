import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { leaveHelping } from '../../../../../lib/creators'
import { limit, noContent, paramWith, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { uuidSchema } from '../../../../../lib/schemas'

/** Als Helfer aufhören (§35.9). 204. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`creator-fan:${auth.uuid}`, RULES.creatorFanUser)
  leaveHelping(useCtx(), auth.uuid, paramWith(event, 'creatorUuid', uuidSchema))
  return noContent(event)
})
