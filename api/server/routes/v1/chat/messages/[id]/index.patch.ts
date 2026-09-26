import { defineEventHandler } from 'h3'
import { editMessage } from '../../../../../lib/chat'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { editMessageBody, messageIdSchema } from '../../../../../lib/schemas'

/** Eigene Nachricht bearbeiten (`editedAt` wird gesetzt). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event)
  const id = paramWith(event, 'id', messageIdSchema)
  const body = await readJson(event, editMessageBody)
  limit(`chatSend:${auth.uuid}`, RULES.chatSendUser)
  return { message: editMessage(useCtx(), auth.uuid, id, body.text) }
})
