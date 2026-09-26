import { defineEventHandler } from 'h3'
import { unreadSummary } from '../../../lib/chat'
import { useCtx } from '../../../lib/context'
import { requireUser } from '../../../lib/http'

/** Ungelesen-Zähler (Rückfall fürs Polling, z. B. Leisten-Abzeichen). */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  return unreadSummary(useCtx(), auth.uuid)
})
