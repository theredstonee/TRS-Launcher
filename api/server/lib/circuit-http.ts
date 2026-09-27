import { getHeader, getQuery, setResponseHeaders, setResponseStatus, type H3Event } from 'h3'
import { z } from 'zod'
import type { CircuitData } from '../../shared/circuits'
import { exportStructureNbt, importCircuitFile, MAX_IMPORT_BYTES, type ImportResult } from './circuit-files'
import type { CircuitJson } from './circuits'
import { unsupportedMedia } from './errors'
import { readLimited } from './http'

/** Hilfen der Schaltungs-Routen (§25): Datei-Upload lesen, Export ausliefern, unveränderliche Antworten. */

const UPLOAD_TYPES = new Set(['application/octet-stream', 'application/json', 'application/gzip', 'application/x-gzip', 'application/x-nbt', ''])

export const fileQuery = z.object({ name: z.string().max(120).optional() }).loose()

/** Rohe Datei (≤ 2 MB) aus dem Body → umgewandelte Schaltung. Dateiname optional per `?name=`. */
export async function readCircuitUpload(event: H3Event): Promise<ImportResult> {
  const type = (getHeader(event, 'content-type') ?? '').split(';')[0]!.trim().toLowerCase()
  if (!UPLOAD_TYPES.has(type)) throw unsupportedMedia('Content-Type must be application/octet-stream (or application/json)')
  const body = await readLimited(event, MAX_IMPORT_BYTES)
  const q = fileQuery.safeParse({ ...getQuery(event) })
  return importCircuitFile(body, { filename: q.success ? q.data.name : undefined })
}

export const exportQuery = z.strictObject({ format: z.enum(['json', 'nbt']).default('nbt') })

/** Datei zum Herunterladen (JSON im Client-Format bzw. Strukturdatei). */
export function sendCircuitFile(event: H3Event, circuit: CircuitData | CircuitJson, format: 'json' | 'nbt', cache: string): Buffer | string {
  const name = circuit.id.replace(/[^a-z0-9_]/g, '') || 'circuit'
  if (format === 'json') {
    setResponseHeaders(event, {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Disposition': `attachment; filename="${name}.json"`,
      'Cache-Control': cache,
    })
    return `${JSON.stringify(circuit, null, 1)}\n`
  }
  setResponseHeaders(event, {
    'Content-Type': 'application/octet-stream',
    'Content-Disposition': `attachment; filename="${name}.nbt"`,
    'Cache-Control': cache,
  })
  return exportStructureNbt(circuit)
}

/** Starker ETag + 304 bei If-None-Match (auch Listen `"a", "b"` und `W/`-Varianten). */
export function notModified(event: H3Event, etag: string): boolean {
  const inm = getHeader(event, 'if-none-match')
  if (!inm) return false
  const tags = inm.split(',').map((t) => t.trim().replace(/^W\//, ''))
  if (!tags.includes(etag) && !tags.includes('*')) return false
  setResponseStatus(event, 304)
  return true
}
