import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../../lib/context'
import { paramWith, readJson, requireAdmin } from '../../../../../../lib/http'
import { adminUserModeration, warnUser } from '../../../../../../lib/moderation'
import { adminWarnBody, uuidSchema } from '../../../../../../lib/schemas'

export default defineEventHandler(async (event) => {
  const actor = requireAdmin(event)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  const body = await readJson(event, adminWarnBody)
  const ctx = useCtx()
  warnUser(ctx, actor, uuid, body.reason)
  return { moderation: adminUserModeration(ctx, uuid) }
})
