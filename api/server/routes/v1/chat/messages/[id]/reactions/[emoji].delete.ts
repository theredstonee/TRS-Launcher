import { defineEventHandler } from 'h3'
import { REACTION_IDS, setReaction, type ReactionId } from '../../../../../../lib/chat'
import { useCtx } from '../../../../../../lib/context'
import { limit, paramWith, requireUser } from '../../../../../../lib/http'
import { RULES } from '../../../../../../lib/ratelimit'
import { messageIdSchema } from '../../../../../../lib/schemas'
import { z } from 'zod'

const emojiSchema = z.enum(REACTION_IDS as [ReactionId, ...ReactionId[]])

/** Reaktion entfernen (jede Reaktion höchstens einmal je Person). Liefert alle Reaktionen der Nachricht. */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  const id = paramWith(event, 'id', messageIdSchema)
  const emoji = paramWith(event, 'emoji', emojiSchema)
  limit(`chatReact:${auth.uuid}`, RULES.chatReactUser)
  return { reactions: setReaction(useCtx(), auth.uuid, id, emoji, false) }
})
