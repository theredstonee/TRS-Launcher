import { defineEventHandler } from 'h3'
import { deleteCosmeticAdmin } from '../../../../lib/admin'
import { useCtx } from '../../../../lib/context'
import { noContent, paramWith, requireStaff } from '../../../../lib/http'
import { cosmeticIdSchema } from '../../../../lib/schemas'

export default defineEventHandler((event) => {
  const actor = requireStaff(event, 'uploads.delete').uuid
  deleteCosmeticAdmin(useCtx(), actor, paramWith(event, 'id', cosmeticIdSchema))
  return noContent(event)
})
