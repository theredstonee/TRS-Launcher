import { defineEventHandler } from 'h3'
import { deleteOwnUpload } from '../../../lib/capes'
import { useCtx } from '../../../lib/context'
import { noContent, paramWith, requireUser } from '../../../lib/http'
import { capeIdSchema } from '../../../lib/schemas'

/** Eigenen hochgeladenen Umhang löschen. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  const id = paramWith(event, 'id', capeIdSchema)
  deleteOwnUpload(useCtx(), auth.uuid, id)
  return noContent(event)
})
