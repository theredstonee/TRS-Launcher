import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../../../lib/context'
import { answerRequest } from '../../../../../../../lib/hosting'
import { limit, paramWith, requireUser } from '../../../../../../../lib/http'
import { RULES } from '../../../../../../../lib/ratelimit'
import { roomIdSchema, uuidSchema } from '../../../../../../../lib/schemas'

export default defineEventHandler((event) => {
  const auth = requireUser(event, 'hosting')
  const id = paramWith(event, 'id', roomIdSchema)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  limit(`hostingManage:${auth.uuid}`, RULES.hostingManageUser)
  return { member: answerRequest(useCtx(), auth.uuid, id, uuid, true) }
})
