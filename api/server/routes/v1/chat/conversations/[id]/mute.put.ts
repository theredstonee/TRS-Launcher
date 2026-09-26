import { defineEventHandler } from 'h3'
import { MUTED_FOREVER, setConversationMute } from '../../../../../lib/chat'
import { useCtx } from '../../../../../lib/context'
import { badRequest } from '../../../../../lib/errors'
import { limit, paramWith, readJson, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { conversationIdSchema, muteConversationBody } from '../../../../../lib/schemas'

/** Unterhaltung stummschalten (Benachrichtigungen aus) – `{ muted, until? }`. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event)
  const id = paramWith(event, 'id', conversationIdSchema)
  const body = await readJson(event, muteConversationBody)
  limit(`chatState:${auth.uuid}`, RULES.chatStateUser)
  const ctx = useCtx()
  let until: number | null = null
  if (body.muted) {
    until = body.until ? Date.parse(body.until) : MUTED_FOREVER
    if (until <= ctx.now()) throw badRequest('invalid_until', 'until must be in the future')
  }
  return { conversation: setConversationMute(ctx, auth.uuid, id, until) }
})
