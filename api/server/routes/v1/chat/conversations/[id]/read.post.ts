import { defineEventHandler } from 'h3'
import { markRead } from '../../../../../lib/chat'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { conversationIdSchema, readBody } from '../../../../../lib/schemas'

/** Gelesen bis `seq` (Lesebestätigung für andere nur, wenn beide sie teilen). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event)
  const id = paramWith(event, 'id', conversationIdSchema)
  const body = await readJson(event, readBody)
  limit(`chatState:${auth.uuid}`, RULES.chatStateUser)
  return { conversation: markRead(useCtx(), auth.uuid, id, body.seq) }
})
