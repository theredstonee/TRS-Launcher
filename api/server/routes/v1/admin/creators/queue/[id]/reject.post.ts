import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { decideQueueItem, QUEUE_ID } from '../../../../../../lib/creators'
import { limit, noContent, paramWith, readJson, requireStaff } from '../../../../../../lib/http'
import { RULES } from '../../../../../../lib/ratelimit'

/** Prüf-Eintrag ablehnen (§35.6): `{ reason }` ist Pflicht und geht an den Creator. 204. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'creators.review')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  const id = paramWith(event, 'id', z.string().regex(QUEUE_ID))
  decideQueueItem(useCtx(), staff, id, false, await readJson(event, z.unknown()))
  return noContent(event)
})
