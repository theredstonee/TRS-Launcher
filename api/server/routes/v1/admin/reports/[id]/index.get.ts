import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { paramWith, requireStaff } from '../../../../../lib/http'
import { adminReportDetail, reportDetailFor } from '../../../../../lib/moderation'
import { can } from '../../../../../lib/team'
import { reportIdSchema } from '../../../../../lib/schemas'

/** Meldung mit Kontext (Nachrichten davor/danach), Notizen, Audit, Moderationsstand des Ziels. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'reports.view')
  const id = paramWith(event, 'id', reportIdSchema)
  return { report: reportDetailFor(adminReportDetail(useCtx(), id), can(staff, 'reports.content')) }
})
