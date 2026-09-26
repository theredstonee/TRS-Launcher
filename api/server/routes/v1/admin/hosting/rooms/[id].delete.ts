import { defineEventHandler } from 'h3'
import { audit } from '../../../../../lib/audit'
import { useCtx } from '../../../../../lib/context'
import { adminCloseRoom } from '../../../../../lib/hosting'
import { noContent, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { closeRoomBody, roomIdSchema } from '../../../../../lib/schemas'

/** Welt schließen (Team). Für dauerhaftes Fernhalten: Strafe `hosting_ban`. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event)
  const id = paramWith(event, 'id', roomIdSchema)
  const body = await readJson(event, closeRoomBody)
  const ctx = useCtx()
  const { host } = adminCloseRoom(ctx, id)
  audit(ctx, staff.uuid, 'hosting.close', host, body?.reason ?? id)
  return noContent(event)
})
