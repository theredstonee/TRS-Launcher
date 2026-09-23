import { defineEventHandler } from 'h3'
import { unbanUser } from '../../../../../lib/admin'
import { useCtx } from '../../../../../lib/context'
import { noContent, paramWith, requireAdmin } from '../../../../../lib/http'
import { uuidSchema } from '../../../../../lib/schemas'

export default defineEventHandler((event) => {
  const actor = requireAdmin(event)
  unbanUser(useCtx(), actor, paramWith(event, 'uuid', uuidSchema))
  return noContent(event)
})
