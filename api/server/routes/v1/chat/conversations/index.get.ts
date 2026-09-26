import { defineEventHandler } from 'h3'
import { listConversations } from '../../../../lib/chat'
import { useCtx } from '../../../../lib/context'
import { queryWith, requireUser } from '../../../../lib/http'
import { listConversationsQuery } from '../../../../lib/schemas'

/** Eigene Unterhaltungen (DMs + Gruppen), neueste Aktivität zuerst, Cursor-Seiten. */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  const q = queryWith(event, listConversationsQuery)
  return listConversations(useCtx(), auth.uuid, q)
})
