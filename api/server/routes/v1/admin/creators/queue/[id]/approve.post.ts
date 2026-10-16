import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { decideQueueItem, QUEUE_ID } from '../../../../../../lib/creators'
import { limit, noContent, paramWith, requireStaff } from '../../../../../../lib/http'
import { RULES } from '../../../../../../lib/ratelimit'

/** Prüf-Eintrag annehmen (§35.6): ist sofort bei den Fans. 204. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'creators.review')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  const id = paramWith(event, 'id', z.string().regex(QUEUE_ID))
  decideQueueItem(useCtx(), staff, id, true)
  return noContent(event)
})
