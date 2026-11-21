import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { noContent, paramWith, requireWebOrUser } from '../../../../../../lib/http'
import { PARTNER_SLUG, withdrawRequest } from '../../../../../../lib/partners'

/** Offenen Antrag zurückziehen. 204. */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  withdrawRequest(useCtx(), auth.uuid, slug, paramWith(event, 'id', z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER)))
  return noContent(event)
})
