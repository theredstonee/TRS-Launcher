import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { deleteRoom } from '../../../../../lib/hosting'
import { noContent, paramWith, requireUser } from '../../../../../lib/http'
import { roomIdSchema } from '../../../../../lib/schemas'

/** Welt schließen: alle Beteiligten bekommen hosting_room_closed. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'hosting')
  const id = paramWith(event, 'id', roomIdSchema)
  deleteRoom(useCtx(), auth.uuid, id)
  return noContent(event)
})
