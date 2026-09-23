import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { acceptRequest } from '../../../../../lib/friends'
import { limit, paramWith, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { uuidSchema } from '../../../../../lib/schemas'

export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`friends:${auth.uuid}`, RULES.friendsWriteUser)
  const from = paramWith(event, 'uuid', uuidSchema)
  return { friend: acceptRequest(useCtx(), auth.user, from) }
})
