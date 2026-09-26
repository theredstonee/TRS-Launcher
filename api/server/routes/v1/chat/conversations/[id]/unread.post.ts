import { defineEventHandler } from 'h3'
import { markUnread } from '../../../../../lib/chat'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { conversationIdSchema, unreadBody } from '../../../../../lib/schemas'

/** Als ungelesen markieren (optional ab Nachricht `seq`). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event)
  const id = paramWith(event, 'id', conversationIdSchema)
  const body = await readJson(event, unreadBody)
  limit(`chatState:${auth.uuid}`, RULES.chatStateUser)
  return { conversation: markUnread(useCtx(), auth.uuid, id, body?.seq) }
})
