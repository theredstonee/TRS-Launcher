import { defineEventHandler, getQuery } from 'h3'
import { useCtx } from '../../../../lib/context'
import { actingAs } from '../../../../lib/creator-http'
import { SYMBOL_MAX_BYTES, uploadSymbol } from '../../../../lib/creators'
import { readPng, requireWebOrUser } from '../../../../lib/http'

/**
 * Neues Fan-Symbol (§35.3): Body = PNG-Streifen (Content-Type image/png, ≤ 256 KiB), `?frameMs=` (50–1000, Standard 150).
 * Wartet danach auf Prüfung und ersetzt ein noch wartendes. Höchstens 5 Uploads in 24 h je Creator. Helfer mit `uploads` per `?as=`.
 */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  const ctx = useCtx()
  const query = { ...getQuery<Record<string, unknown>>(event) }
  const as = actingAs(event)
  const body = await readPng(event, SYMBOL_MAX_BYTES)
  return uploadSymbol(ctx, auth.uuid, body, query, as)
})
