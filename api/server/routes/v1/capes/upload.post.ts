import { defineEventHandler } from 'h3'
import { uploadCape } from '../../../lib/capes'
import { useCtx } from '../../../lib/context'
import { created, limit, queryWith, readPng, requireUser } from '../../../lib/http'
import { MAX_UPLOAD_BYTES } from '../../../lib/png'
import { RULES } from '../../../lib/ratelimit'
import { uploadQuery } from '../../../lib/schemas'

/** Eigener Umhang: Body = PNG (Content-Type image/png), Name optional per `?name=`. Status danach `pending`. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const query = queryWith(event, uploadQuery)
  limit(`upload:${auth.uuid}`, RULES.uploadUser)
  const body = await readPng(event, MAX_UPLOAD_BYTES)
  return created(event, { cape: uploadCape(useCtx(), auth.uuid, body, query.name) })
})
