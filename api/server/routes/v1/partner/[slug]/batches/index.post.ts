import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { created, paramWith, readJson, requireWebOrUser } from '../../../../../lib/http'
import { createBatch, PARTNER_SLUG } from '../../../../../lib/partners'

/** Einmal-Codes erzeugen: `{ label, count ≤ 500 }` → 201 `{ batch, codes }` (Codes nur hier und im CSV). Recht `codes`. */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return created(event, createBatch(useCtx(), auth.uuid, slug, await readJson(event, z.unknown())))
})
