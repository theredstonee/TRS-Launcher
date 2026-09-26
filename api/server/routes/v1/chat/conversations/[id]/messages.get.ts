import { defineEventHandler } from 'h3'
import { listMessages } from '../../../../../lib/chat'
import { useCtx } from '../../../../../lib/context'
import { paramWith, queryWith, requireUser } from '../../../../../lib/http'
import { conversationIdSchema, listMessagesQuery } from '../../../../../lib/schemas'

/** Nachrichten seitenweise (`before`/`after` = seq), immer aufsteigend sortiert. */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  const id = paramWith(event, 'id', conversationIdSchema)
  const q = queryWith(event, listMessagesQuery)
  return listMessages(useCtx(), auth.uuid, id, q)
})
