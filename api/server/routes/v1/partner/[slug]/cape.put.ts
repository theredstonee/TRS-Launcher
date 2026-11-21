import { defineEventHandler, getQuery } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { created, paramWith, readPng, requireWebOrUser } from '../../../../lib/http'
import { CAPE_MAX_BYTES, PARTNER_SLUG, uploadPartnerCape } from '../../../../lib/partners'

/** Partner-Umhang hochladen (`?name=&frames=&frameTimeMs=`, HD/animiert) → 201 `{ request }` (Antrag `cape`). Recht `cosmetics`. */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return created(event, uploadPartnerCape(useCtx(), auth.uuid, slug, await readPng(event, CAPE_MAX_BYTES), { ...getQuery<Record<string, unknown>>(event) }))
})
