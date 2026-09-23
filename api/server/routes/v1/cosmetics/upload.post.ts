import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { MAX_COSMETIC_UPLOAD_BYTES, uploadCosmetic } from '../../../lib/cosmetics'
import { created, limit, queryWith, readPng, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { cosmeticUploadQuery } from '../../../lib/schemas'

/**
 * Eigene Textur für eine Vorlage: Body = PNG, `?template=` Pflicht, `?name=`
 * optional, `?frameTimeMs=` Pflicht bei Animation (senkrechter Streifen). Status danach `pending`.
 */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const q = queryWith(event, cosmeticUploadQuery)
  limit(`cupload:${auth.uuid}`, RULES.cosmeticUploadUser)
  const body = await readPng(event, MAX_COSMETIC_UPLOAD_BYTES)
  return created(event, {
    cosmetic: uploadCosmetic(useCtx(), auth.uuid, body, { template: q.template, name: q.name, frameTimeMs: q.frameTimeMs }),
  })
})
