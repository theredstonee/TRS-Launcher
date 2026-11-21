import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { paramWith, requireWebOrUser } from '../../../../../../lib/http'
import { blockBatch, PARTNER_SLUG } from '../../../../../../lib/partners'

/** Offene Codes eines Pakets sperren → `{ batch, blocked }`. Recht `codes`. */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  const id = paramWith(event, 'id', z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER))
  return blockBatch(useCtx(), auth.uuid, slug, id)
})
