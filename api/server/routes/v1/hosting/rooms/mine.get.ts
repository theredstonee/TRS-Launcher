import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { myRooms } from '../../../../lib/hosting'
import { requireUser } from '../../../../lib/http'

/** Eigene offene Welt(en) mit Code und Mitgliedern (höchstens eine). */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'hosting')
  return { rooms: myRooms(useCtx(), auth.uuid) }
})
