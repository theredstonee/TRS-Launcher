import { defineEventHandler } from 'h3'
import { approveCosmetic } from '../../../../../lib/admin'
import { useCtx } from '../../../../../lib/context'
import { paramWith, requireAdmin } from '../../../../../lib/http'
import { cosmeticIdSchema } from '../../../../../lib/schemas'

export default defineEventHandler((event) => {
  const actor = requireAdmin(event)
  return { cosmetic: approveCosmetic(useCtx(), actor, paramWith(event, 'id', cosmeticIdSchema)) }
})
