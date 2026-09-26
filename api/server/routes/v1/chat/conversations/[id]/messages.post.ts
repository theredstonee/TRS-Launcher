import { defineEventHandler } from 'h3'
import { sendMessage } from '../../../../../lib/chat'
import { useCtx } from '../../../../../lib/context'
import { created, limit, paramWith, readJson, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { conversationIdSchema, sendMessageBody } from '../../../../../lib/schemas'

/** Nachricht senden: 201 neu, 200 wenn die nonce schon benutzt wurde (gleiche Nachricht). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event)
  const id = paramWith(event, 'id', conversationIdSchema)
  const body = await readJson(event, sendMessageBody)
  limit(`chatSend:${auth.uuid}`, RULES.chatSendUser)
  limit(`chatBurst:${auth.uuid}`, RULES.chatBurstUser)
  const r = sendMessage(useCtx(), auth.uuid, id, body)
  return r.created ? created(event, { message: r.message }) : { message: r.message }
})
