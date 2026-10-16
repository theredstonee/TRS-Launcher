import { defineEventHandler, getQuery } from 'h3'
import { useCtx } from '../../../../lib/context'
import { uploadFanCape } from '../../../../lib/creators'
import { limit, readPng, requireUser } from '../../../../lib/http'
import { MAX_UPLOAD_BYTES } from '../../../../lib/png'
import { RULES } from '../../../../lib/ratelimit'

/**
 * Fan-Umhang hochladen (§35.3): wie `POST /v1/capes/upload` (`?name=`, `?frames=`, `?frameTimeMs=`). Partner: bis
 * 512×256, ein Frame; Top: HD + Animation. Starter → 403 `creator_tier`. Wartet auf Prüfung, ersetzt einen wartenden.
 */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const ctx = useCtx()
  const query = { ...getQuery<Record<string, unknown>>(event) }
  const body = await readPng(event, MAX_UPLOAD_BYTES)
  limit(`creator-cape:${auth.uuid}`, RULES.creatorUploadUser)
  return uploadFanCape(ctx, auth.uuid, body, query)
})
