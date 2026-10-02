import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { created, limit, readJson, requireUser } from '../../../../lib/http'
import { createPackUpload, packUploadBody } from '../../../../lib/packupload'
import { RULES } from '../../../../lib/ratelimit'

/** Stück-Upload beginnen (§27.7): `{ size, sha256, name? }` → 201 `{ uploadId, chunkSize, expiresAt }`. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`packUpload:${auth.uuid}`, RULES.packUploadUser)
  const body = await readJson(event, packUploadBody)
  return created(event, createPackUpload(useCtx(), auth.uuid, body))
})
