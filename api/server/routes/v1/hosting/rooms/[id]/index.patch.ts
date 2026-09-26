import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { updateRoom } from '../../../../../lib/hosting'
import { limit, paramWith, readJson, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { roomIdSchema, updateRoomBody } from '../../../../../lib/schemas'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'hosting')
  const id = paramWith(event, 'id', roomIdSchema)
  const body = await readJson(event, updateRoomBody)
  limit(`hostingManage:${auth.uuid}`, RULES.hostingManageUser)
  return { room: updateRoom(useCtx(), auth.uuid, id, body) }
})
