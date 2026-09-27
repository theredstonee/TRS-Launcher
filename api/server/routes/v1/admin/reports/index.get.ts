import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { queryWith, requireStaff } from '../../../../lib/http'
import { adminListReports, redactSummary } from '../../../../lib/moderation'
import { can } from '../../../../lib/team'
import { adminReportListQuery } from '../../../../lib/schemas'

/** Meldungen filtern (Status, Art, Ziel, Grund, Bearbeiter, Dringlichkeit, Zeitraum), Cursor-Seiten, Zähler. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'reports.view')
  const q = queryWith(event, adminReportListQuery)
  const list = adminListReports(useCtx(), { ...q, assigned: q.assigned === 'me' ? staff.uuid : q.assigned })
  return can(staff, 'reports.content') ? list : { ...list, reports: list.reports.map(redactSummary) }
})
