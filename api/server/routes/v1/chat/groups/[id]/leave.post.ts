import { defineEventHandler } from 'h3'
import { leaveGroup } from '../../../../../lib/chat'
import { useCtx } from '../../../../../lib/context'
import { limit, noContent, paramWith, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { conversationIdSchema } from '../../../../../lib/schemas'

/** Austreten. Der Besitzer gibt die Gruppe dabei ans dienstälteste Mitglied weiter. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  const id = paramWith(event, 'id', conversationIdSchema)
  limit(`chatGroup:${auth.uuid}`, RULES.chatGroupUser)
  leaveGroup(useCtx(), auth.uuid, id)
  return noContent(event)
})
