import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../../lib/context'
import { paramWith, requireStaff } from '../../../../../../lib/http'
import { adminUserModeration } from '../../../../../../lib/moderation'
import { uuidSchema } from '../../../../../../lib/schemas'

export default defineEventHandler((event) => {
  requireStaff(event)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  return { moderation: adminUserModeration(useCtx(), uuid) }
})
