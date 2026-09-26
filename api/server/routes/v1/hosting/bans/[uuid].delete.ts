import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { requireHosting, unbanForHost } from '../../../../lib/hosting'
import { limit, noContent, paramWith, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { uuidSchema } from '../../../../lib/schemas'

export default defineEventHandler((event) => {
  const auth = requireUser(event, 'hosting')
  const uuid = paramWith(event, 'uuid', uuidSchema)
  limit(`hostingManage:${auth.uuid}`, RULES.hostingManageUser)
  const ctx = useCtx()
  requireHosting(ctx)
  unbanForHost(ctx, auth.uuid, uuid)
  return noContent(event)
})
