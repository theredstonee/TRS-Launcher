import { defineEventHandler } from 'h3'
import { addMembers } from '../../../../../lib/chat'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { addMembersBody, conversationIdSchema } from '../../../../../lib/schemas'

/** Freunde hinzufügen (nur Besitzer). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const id = paramWith(event, 'id', conversationIdSchema)
  const body = await readJson(event, addMembersBody)
  limit(`chatGroup:${auth.uuid}`, RULES.chatGroupUser)
  return { conversation: addMembers(useCtx(), auth.uuid, id, body.members) }
})
