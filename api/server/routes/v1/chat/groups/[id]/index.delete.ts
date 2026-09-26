import { defineEventHandler } from 'h3'
import { deleteGroup } from '../../../../../lib/chat'
import { useCtx } from '../../../../../lib/context'
import { limit, noContent, paramWith, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { conversationIdSchema } from '../../../../../lib/schemas'

/** Gruppe für alle löschen (nur Besitzer) – samt Nachrichten und Bildern. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  const id = paramWith(event, 'id', conversationIdSchema)
  limit(`chatGroup:${auth.uuid}`, RULES.chatGroupUser)
  deleteGroup(useCtx(), auth.uuid, id)
  return noContent(event)
})
