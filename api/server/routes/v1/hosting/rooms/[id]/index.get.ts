import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { roomFor } from '../../../../../lib/hosting'
import { paramWith, requireUser } from '../../../../../lib/http'
import { roomIdSchema } from '../../../../../lib/schemas'

/** Host: volle Sicht; Freunde/Eingeladene: RoomView; sonst 404. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'hosting')
  const id = paramWith(event, 'id', roomIdSchema)
  return { room: roomFor(useCtx(), auth.uuid, id) }
})
