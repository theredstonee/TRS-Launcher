import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { adminRooms } from '../../../../../lib/hosting'
import { requireStaff } from '../../../../../lib/http'

/** Offene Welten (Host, Spieler, Mitglieder) – ohne Inhalte. */
export default defineEventHandler((event) => {
  requireStaff(event)
  return { rooms: adminRooms(useCtx(), { limit: 200 }) }
})
