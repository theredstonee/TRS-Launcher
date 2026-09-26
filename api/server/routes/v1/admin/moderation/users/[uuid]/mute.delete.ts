import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../../lib/context'
import { notFound } from '../../../../../../lib/errors'
import { paramWith, requireStaff } from '../../../../../../lib/http'
import { adminUserModeration, unmuteUser } from '../../../../../../lib/moderation'
import { uuidSchema } from '../../../../../../lib/schemas'

export default defineEventHandler((event) => {
  const actor = requireStaff(event)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  const ctx = useCtx()
  if (unmuteUser(ctx, actor, uuid) === 0) throw notFound('not_muted', 'This player is not muted')
  return { moderation: adminUserModeration(ctx, uuid) }
})
