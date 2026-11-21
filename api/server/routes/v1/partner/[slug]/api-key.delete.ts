import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { noContent, paramWith, requireWebOrUser } from '../../../../lib/http'
import { PARTNER_SLUG, revokeApiKey } from '../../../../lib/partners'

/** API-Schlüssel widerrufen. 204. */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  revokeApiKey(useCtx(), auth.uuid, slug)
  return noContent(event)
})
