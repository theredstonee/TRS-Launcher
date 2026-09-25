import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { noContent, paramWith, requireUser } from '../../../../../lib/http'
import { syncSkinIdSchema } from '../../../../../lib/schemas'
import { deleteSkin } from '../../../../../lib/sync'

/** Skin löschen; legt immer einen Grabstein an (auch für unbekannte IDs), damit andere Geräte nachziehen. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'sync')
  const id = paramWith(event, 'id', syncSkinIdSchema)
  deleteSkin(useCtx(), auth.uuid, id)
  return noContent(event)
})
