import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { created, paramWith, requireWebOrUser } from '../../../../lib/http'
import { PARTNER_SLUG, rotateApiKey } from '../../../../lib/partners'

/** API-Schlüssel erzeugen/ersetzen → 201 `{ key, hint, createdAt }` (Schlüssel nur in dieser Antwort). Recht `api`. */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return created(event, rotateApiKey(useCtx(), auth.uuid, slug))
})
