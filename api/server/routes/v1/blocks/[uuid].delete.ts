import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { unblock } from '../../../lib/friends'
import { limit, noContent, paramWith, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { uuidSchema } from '../../../lib/schemas'

export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`friends:${auth.uuid}`, RULES.friendsWriteUser)
  unblock(useCtx(), auth.uuid, paramWith(event, 'uuid', uuidSchema))
  return noContent(event)
})
