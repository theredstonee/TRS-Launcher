import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { craftingInfo } from '../../../lib/craftinghosting'
import { requireWebOrUser } from '../../../lib/http'

/** Tarif, Servertypen und Regeln von CraftingHosting (§43.1). */
export default defineEventHandler(async (event) => {
  requireWebOrUser(event, 'craftinghosting')
  return craftingInfo(useCtx())
})
