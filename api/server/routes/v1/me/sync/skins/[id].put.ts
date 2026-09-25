import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { syncSkinIdSchema, syncSkinPutBody } from '../../../../../lib/schemas'
import { SYNC_SKIN_BODY_LIMIT, decodeBase64Png, putSkin } from '../../../../../lib/sync'

/** Skin anlegen oder ersetzen: `{ name, variant, png: <base64> }` (64×64 oder 64×32, ≤ 128 KB). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'sync')
  const id = paramWith(event, 'id', syncSkinIdSchema)
  limit(`syncUpload:${auth.uuid}`, RULES.syncUploadUser)
  const body = await readJson(event, syncSkinPutBody, SYNC_SKIN_BODY_LIMIT)
  const skin = putSkin(useCtx(), auth.uuid, id, { name: body.name, variant: body.variant, png: decodeBase64Png(body.png) })
  return { skin }
})
