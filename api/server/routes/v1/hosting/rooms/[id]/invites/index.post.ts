import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../../lib/context'
import { invite } from '../../../../../../lib/hosting'
import { created, limit, paramWith, readJson, requireUser } from '../../../../../../lib/http'
import { RULES } from '../../../../../../lib/ratelimit'
import { hostingInviteBody, roomIdSchema } from '../../../../../../lib/schemas'

/** Freund einladen (optional mit Weltkarte in der DM). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'hosting')
  const id = paramWith(event, 'id', roomIdSchema)
  const body = await readJson(event, hostingInviteBody)
  limit(`hostingManage:${auth.uuid}`, RULES.hostingManageUser)
  return created(event, invite(useCtx(), auth.user, id, body.uuid, { chat: body.chat }))
})
