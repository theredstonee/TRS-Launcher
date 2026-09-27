import { defineEventHandler, setResponseHeaders } from 'h3'
import { z } from 'zod'
import { notModified } from '../../../../lib/circuit-http'
import { circuitJson, publishedCircuit } from '../../../../lib/circuits'
import { useCtx } from '../../../../lib/context'
import { ApiError, notFound } from '../../../../lib/errors'
import { clientIp, limit, paramWith, queryWith } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

const query = z.object({ rev: z.string().max(12).regex(/^[1-9][0-9]{0,9}$/).optional() }).loose()

/**
 * Öffentlich (§25.2): eine veröffentlichte Schaltung im Client-Format.
 * - `?rev=<aktuell>` → 200, unveränderlich (ein Jahr cachebar).
 * - `?rev=<andere>` → 404 `rev_mismatch` mit `details.rev` (aktuelle rev), nie gecacht – Index neu laden.
 *   Keine Weiterleitung: eine unveränderliche Adresse darf nie einen anderen Inhalt liefern.
 * - ohne `rev` → 200, kurz cachebar (60 s).
 */
export default defineEventHandler((event) => {
  limit(`circuitPublic:${clientIp(event)}`, RULES.circuitPublicIp)
  const id = paramWith(event, 'id', z.string().regex(/^[a-z0-9_]{1,48}$/))
  const q = queryWith(event, query)
  const row = publishedCircuit(useCtx(), id)
  if (!row) throw notFound('circuit_not_found', 'Circuit not found')
  if (q.rev !== undefined && Number(q.rev) !== row.rev) {
    throw new ApiError(404, 'rev_mismatch', 'This revision is not available – reload the index', { rev: row.rev }, { 'Cache-Control': 'no-store' })
  }
  const etag = `"c-${row.id}-${row.rev}"`
  setResponseHeaders(event, {
    ETag: etag,
    'Cache-Control': q.rev !== undefined ? 'public, max-age=31536000, immutable' : 'public, max-age=60',
  })
  if (notModified(event, etag)) return ''
  return circuitJson(row)
})
