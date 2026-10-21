import { defineEventHandler, getQuery } from 'h3'
import { useCtx } from '../../../../lib/context'
import { actingAs } from '../../../../lib/creator-http'
import { uploadFanCape } from '../../../../lib/creators'
import { readPng, requireWebOrUser } from '../../../../lib/http'
import { MAX_UPLOAD_BYTES } from '../../../../lib/png'

/**
 * Fan-Umhang hochladen (§35.3): wie `POST /v1/capes/upload` (`?name=`, `?frames=`, `?frameTimeMs=`). Partner: bis
 * 512×256, ein Frame; Top: HD + Animation. Starter → 403 `creator_tier`. Wartet auf Prüfung, ersetzt einen wartenden.
 */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  const ctx = useCtx()
  const query = { ...getQuery<Record<string, unknown>>(event) }
  const as = actingAs(event)
  const body = await readPng(event, MAX_UPLOAD_BYTES)
  return uploadFanCape(ctx, auth.uuid, body, query, as)
})
