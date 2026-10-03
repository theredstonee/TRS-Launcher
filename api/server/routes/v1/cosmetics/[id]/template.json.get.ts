import { defineEventHandler, getRouterParam } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { readCosmeticTemplate } from '../../../../lib/cosmetics'
import { notFound } from '../../../../lib/errors'
import { sendCached } from '../../../../lib/http'
import { COSMETIC_ID } from '../../../../lib/ids'

/**
 * `GET /v1/cosmetics/{id}/template.json` – v1-Vorlage des Teils (§11.1), öffentlich.
 * ETag = sha256 des JSON; mit passendem `?v=` ein Jahr immutable. Unbekannt oder Format v2 → 404.
 */
export default defineEventHandler((event) => {
  const parsed = z.string().regex(COSMETIC_ID).safeParse(getRouterParam(event, 'id') ?? '')
  if (!parsed.success) throw notFound('cosmetic_not_found', 'Cosmetic not found')
  return sendCached(event, `${parsed.data}-template.json`, 'application/json; charset=utf-8', readCosmeticTemplate(useCtx(), parsed.data))
})
