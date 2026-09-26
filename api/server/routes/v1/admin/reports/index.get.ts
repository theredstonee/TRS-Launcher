import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { queryWith, requireAdmin } from '../../../../lib/http'
import { adminListReports } from '../../../../lib/moderation'
import { adminReportListQuery } from '../../../../lib/schemas'

/** Meldungen filtern (Status, Art, Ziel), Cursor-Seiten, dazu Zähler je Status. */
export default defineEventHandler((event) => {
  requireAdmin(event)
  const q = queryWith(event, adminReportListQuery)
  return adminListReports(useCtx(), q)
})
