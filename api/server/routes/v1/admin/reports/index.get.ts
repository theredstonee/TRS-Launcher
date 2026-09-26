import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { queryWith, requireStaff } from '../../../../lib/http'
import { adminListReports } from '../../../../lib/moderation'
import { adminReportListQuery } from '../../../../lib/schemas'

/** Meldungen filtern (Status, Art, Ziel, Grund, Bearbeiter, Dringlichkeit, Zeitraum), Cursor-Seiten, Zähler. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event)
  const q = queryWith(event, adminReportListQuery)
  return adminListReports(useCtx(), { ...q, assigned: q.assigned === 'me' ? staff.uuid : q.assigned })
})
