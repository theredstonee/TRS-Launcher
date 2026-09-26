import { defineEventHandler } from 'h3'
import { removeMember } from '../../../../../../lib/chat'
import { useCtx } from '../../../../../../lib/context'
import { limit, noContent, paramWith, requireUser } from '../../../../../../lib/http'
import { RULES } from '../../../../../../lib/ratelimit'
import { conversationIdSchema, uuidSchema } from '../../../../../../lib/schemas'

/** Mitglied entfernen (Besitzer) – mit der eigenen UUID = austreten. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  const id = paramWith(event, 'id', conversationIdSchema)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  limit(`chatGroup:${auth.uuid}`, RULES.chatGroupUser)
  removeMember(useCtx(), auth.uuid, id, uuid)
  return noContent(event)
})
