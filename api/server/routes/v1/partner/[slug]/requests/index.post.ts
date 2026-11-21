import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { created, paramWith, readJson, requireWebOrUser } from '../../../../../lib/http'
import { createRequest, PARTNER_SLUG } from '../../../../../lib/partners'

/** Antrag ans Team (§44.4): quota, code_change, profile, server, cosmetic_wish, promo → 201 `{ request }`. */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return created(event, createRequest(useCtx(), auth.uuid, slug, await readJson(event, z.unknown())))
})
