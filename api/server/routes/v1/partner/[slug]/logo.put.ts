import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { created, paramWith, readPng, requireWebOrUser } from '../../../../lib/http'
import { LOGO_MAX_BYTES, PARTNER_SLUG, uploadLogo } from '../../../../lib/partners'

/** Logo hochladen (PNG, quadratisch, wird 128×128) → 201 `{ request }` (Antrag `logo`). Recht `profile`, 5 Uploads/24 h je Partner. */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return created(event, uploadLogo(useCtx(), auth.uuid, slug, await readPng(event, LOGO_MAX_BYTES)))
})
