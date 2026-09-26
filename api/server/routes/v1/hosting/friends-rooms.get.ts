import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { friendsRooms } from '../../../lib/hosting'
import { requireUser } from '../../../lib/http'

/** Offene Welten von Freunden + Welten, in die ich eingeladen bin / angefragt habe / drin bin. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'hosting')
  return { rooms: friendsRooms(useCtx(), auth.uuid) }
})
