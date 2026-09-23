import { defineEventHandler } from 'h3'
import { deleteCosmeticAdmin } from '../../../../lib/admin'
import { useCtx } from '../../../../lib/context'
import { noContent, paramWith, requireAdmin } from '../../../../lib/http'
import { cosmeticIdSchema } from '../../../../lib/schemas'

export default defineEventHandler((event) => {
  const actor = requireAdmin(event)
  deleteCosmeticAdmin(useCtx(), actor, paramWith(event, 'id', cosmeticIdSchema))
  return noContent(event)
})
