import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { paramWith, requireStaff } from '../../../../../lib/http'
import { adminReportDetail } from '../../../../../lib/moderation'
import { reportIdSchema } from '../../../../../lib/schemas'

/** Meldung mit Kontext (Nachrichten davor/danach), Notizen, Audit, Moderationsstand des Ziels. */
export default defineEventHandler((event) => {
  requireStaff(event)
  const id = paramWith(event, 'id', reportIdSchema)
  return { report: adminReportDetail(useCtx(), id) }
})
