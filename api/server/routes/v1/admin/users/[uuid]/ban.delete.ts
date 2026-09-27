import { defineEventHandler } from 'h3'
import { unbanUser } from '../../../../../lib/admin'
import { useCtx } from '../../../../../lib/context'
import { noContent, paramWith, requireStaff } from '../../../../../lib/http'
import { uuidSchema } from '../../../../../lib/schemas'

export default defineEventHandler((event) => {
  const actor = requireStaff(event, 'sanctions.lift')
  unbanUser(useCtx(), actor, paramWith(event, 'uuid', uuidSchema))
  return noContent(event)
})
