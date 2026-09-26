import { defineEventHandler } from 'h3'
import { access, conversationView } from '../../../../../lib/chat'
import { useCtx } from '../../../../../lib/context'
import { paramWith, requireUser } from '../../../../../lib/http'
import { conversationIdSchema } from '../../../../../lib/schemas'

export default defineEventHandler((event) => {
  const auth = requireUser(event)
  const id = paramWith(event, 'id', conversationIdSchema)
  const ctx = useCtx()
  const { conv, member } = access(ctx, auth.uuid, id)
  return { conversation: conversationView(ctx, conv, auth.uuid, member) }
})
