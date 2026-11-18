import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingAddons } from '../../../../lib/craftinghosting'
import { requireWebOrUser } from '../../../../lib/http'

/** Installierte Plugins bzw. Mods (§43.11). */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  return craftingAddons(useCtx(), auth.user)
})
