import { defineEventHandler } from 'h3'
import { createGroup } from '../../../../lib/chat'
import { useCtx } from '../../../../lib/context'
import { created, limit, readJson, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { createGroupBody } from '../../../../lib/schemas'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const body = await readJson(event, createGroupBody)
  limit(`chatGroup:${auth.uuid}`, RULES.chatGroupUser)
  return created(event, { conversation: createGroup(useCtx(), auth.uuid, body.name, body.members) })
})
