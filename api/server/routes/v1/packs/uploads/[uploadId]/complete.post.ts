import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, requireUser } from '../../../../../lib/http'
import { completePackUpload } from '../../../../../lib/packupload'
import { RULES } from '../../../../../lib/ratelimit'

const id = z.string().regex(/^[A-Za-z0-9_-]{22}$/)

/** Stücke zusammensetzen und prüfen (§27.7) → `{ uploadToken }` für `POST /v1/packs` oder `PUT /v1/packs/{id}/file`. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`packUpload:${auth.uuid}`, RULES.packUploadUser)
  const uploadId = paramWith(event, 'uploadId', id)
  return completePackUpload(useCtx(), auth.uuid, uploadId)
})
