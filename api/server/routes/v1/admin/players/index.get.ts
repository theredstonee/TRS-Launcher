import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { listPlayers } from '../../../../lib/dossier'
import { queryWith, requireStaff } from '../../../../lib/http'
import { adminPlayersQuery } from '../../../../lib/schemas'

/** Spieler-Liste mit Filter (Name, Status) und Cursor. */
export default defineEventHandler((event) => {
  requireStaff(event)
  return listPlayers(useCtx(), queryWith(event, adminPlayersQuery))
})
