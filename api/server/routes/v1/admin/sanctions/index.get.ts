import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { queryWith, requireStaff } from '../../../../lib/http'
import { listSanctions } from '../../../../lib/sanctions'
import { adminSanctionListQuery } from '../../../../lib/schemas'

/** Strafen filtern (Status, Art, Spieler, Bearbeiter, Zeitraum), Cursor-Seiten. */
export default defineEventHandler((event) => {
  requireStaff(event)
  return listSanctions(useCtx(), queryWith(event, adminSanctionListQuery))
})
