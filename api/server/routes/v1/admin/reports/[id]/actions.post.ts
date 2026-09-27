import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { adminReportAction, reportDetailFor } from '../../../../../lib/moderation'
import { can } from '../../../../../lib/team'
import { adminReportActionBody, reportIdSchema } from '../../../../../lib/schemas'

/** Aktion zu einer Meldung: Nachricht löschen, verwarnen, stummschalten, sperren, Strafe (§22), abweisen, erledigen. Rechte je Rolle. */
export default defineEventHandler(async (event) => {
  const actor = requireStaff(event, 'reports.handle')
  const id = paramWith(event, 'id', reportIdSchema)
  const body = await readJson(event, adminReportActionBody)
  return { report: reportDetailFor(adminReportAction(useCtx(), actor, id, body), can(actor, 'reports.content')) }
})
