import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { setRoomContent } from '../../../../../lib/hosting'
import { limit, noContent, paramWith, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { roomIdSchema } from '../../../../../lib/schemas'

/** Host: nichts mehr teilen (Mods und Resource Pack). */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'hosting')
  const id = paramWith(event, 'id', roomIdSchema)
  limit(`hostingManage:${auth.uuid}`, RULES.hostingManageUser)
  setRoomContent(useCtx(), auth.uuid, id, null)
  return noContent(event)
})
