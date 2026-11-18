import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { adminCraftingServers } from '../../../../../lib/craftinghosting'
import { requireStaff } from '../../../../../lib/http'

/** CraftingHosting-Server aller Konten (§43.8). */
export default defineEventHandler(async (event) => {
  requireStaff(event, 'worlds.view')
  return adminCraftingServers(useCtx())
})
