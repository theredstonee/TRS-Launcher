import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingProperties } from '../../../../lib/craftinghosting'
import { requireWebOrUser } from '../../../../lib/http'

/** Alle Schlüssel aus server.properties (§43.11). */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  return craftingProperties(useCtx(), auth.user)
})
