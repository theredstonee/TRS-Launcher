import { defineEventHandler, getQuery } from 'h3'
import { useCtx } from '../../../../lib/context'
import { SYMBOL_MAX_BYTES, uploadSymbol } from '../../../../lib/creators'
import { limit, readPng, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/**
 * Neues Fan-Symbol (§35.3): Body = PNG-Streifen (Content-Type image/png, ≤ 256 KiB), `?frameMs=` (50–1000, Standard 150).
 * Wartet danach auf Prüfung und ersetzt ein noch wartendes. Höchstens 5 Uploads in 24 h.
 */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const ctx = useCtx()
  const query = { ...getQuery<Record<string, unknown>>(event) }
  const body = await readPng(event, SYMBOL_MAX_BYTES)
  limit(`creator-symbol:${auth.uuid}`, RULES.creatorUploadUser)
  return uploadSymbol(ctx, auth.uuid, body, query)
})
