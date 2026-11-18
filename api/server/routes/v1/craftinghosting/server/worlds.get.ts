import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingWorlds } from '../../../../lib/craftinghosting'
import { requireWebOrUser } from '../../../../lib/http'

/** Welten auf dem Server, aktive Welt (§43.12). */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  return craftingWorlds(useCtx(), auth.user)
})
