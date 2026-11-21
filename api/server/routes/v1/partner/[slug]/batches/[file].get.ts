import { defineEventHandler, setResponseHeaders } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { paramWith, requireWebOrUser } from '../../../../../lib/http'
import { batchCsv, PARTNER_SLUG } from '../../../../../lib/partners'

/** `GET /v1/partner/{slug}/batches/{id}.csv`: Codes eines Pakets (Code, ref, Status, Zeitpunkt), Recht `codes`. */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  const file = paramWith(event, 'file', z.string().regex(/^\d{1,15}\.csv$/))
  const r = batchCsv(useCtx(), auth.uuid, slug, Number(file.slice(0, -4)))
  setResponseHeaders(event, {
    'Content-Type': 'text/csv; charset=utf-8',
    'Content-Disposition': `attachment; filename="${r.filename}"`,
    'Cache-Control': 'private, no-store',
  })
  return r.csv
})
