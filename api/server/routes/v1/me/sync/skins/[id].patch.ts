import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { paramWith, readJson, requireUser } from '../../../../../lib/http'
import { syncSkinIdSchema, syncSkinPatchBody } from '../../../../../lib/schemas'
import { patchSkin } from '../../../../../lib/sync'

/** Umbenennen bzw. Variante ändern, ohne das Bild neu zu senden. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'sync')
  const id = paramWith(event, 'id', syncSkinIdSchema)
  const body = await readJson(event, syncSkinPatchBody)
  return { skin: patchSkin(useCtx(), auth.uuid, id, body) }
})
