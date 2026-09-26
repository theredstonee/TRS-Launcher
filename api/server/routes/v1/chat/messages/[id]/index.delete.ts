import { defineEventHandler } from 'h3'
import { deleteMessage } from '../../../../../lib/chat'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { messageIdSchema } from '../../../../../lib/schemas'

/** Für alle löschen: eigene Nachricht, in Gruppen als Besitzer auch fremde. Liefert den Grabstein. */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  const id = paramWith(event, 'id', messageIdSchema)
  limit(`chatSend:${auth.uuid}`, RULES.chatSendUser)
  return { message: deleteMessage(useCtx(), auth.uuid, id) }
})
