import { defineEventHandler } from 'h3'
import { deleteCapeAdmin } from '../../../../lib/admin'
import { useCtx } from '../../../../lib/context'
import { noContent, paramWith, requireAdmin } from '../../../../lib/http'
import { capeIdSchema } from '../../../../lib/schemas'

export default defineEventHandler((event) => {
  const actor = requireAdmin(event)
  deleteCapeAdmin(useCtx(), actor, paramWith(event, 'id', capeIdSchema))
  return noContent(event)
})
