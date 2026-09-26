import { defineEventHandler } from 'h3'
import { setTyping } from '../../../../../lib/chat'
import { useCtx } from '../../../../../lib/context'
import { limit, noContent, paramWith, readJson, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { conversationIdSchema, typingBody } from '../../../../../lib/schemas'

/** „Tippt gerade“ – höchstens alle 3 s `true`, beim Leeren `false`. Immer 204. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event)
  const id = paramWith(event, 'id', conversationIdSchema)
  const body = await readJson(event, typingBody)
  limit(`chatTyping:${auth.uuid}`, RULES.chatTypingUser)
  setTyping(useCtx(), auth.uuid, id, body.typing)
  return noContent(event)
})
