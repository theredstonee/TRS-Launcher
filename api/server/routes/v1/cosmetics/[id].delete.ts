import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { deleteOwnCosmetic } from '../../../lib/cosmetics'
import { noContent, paramWith, requireUser } from '../../../lib/http'
import { cosmeticIdSchema } from '../../../lib/schemas'

/** Eigenen Kosmetik-Upload löschen. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  const id = paramWith(event, 'id', cosmeticIdSchema)
  deleteOwnCosmetic(useCtx(), auth.uuid, id)
  return noContent(event)
})
