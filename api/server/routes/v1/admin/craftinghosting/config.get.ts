import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { adminCraftingConfig } from '../../../../lib/craftinghosting'
import { requireStaff } from '../../../../lib/http'

/** Ankündigung, Wartung, Funktions-Schalter des Hostings (§43.14). */
export default defineEventHandler((event) => {
  requireStaff(event, 'worlds.view')
  return adminCraftingConfig(useCtx())
})
