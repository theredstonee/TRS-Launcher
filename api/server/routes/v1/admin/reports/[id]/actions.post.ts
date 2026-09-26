import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { paramWith, readJson, requireAdmin } from '../../../../../lib/http'
import { adminReportAction } from '../../../../../lib/moderation'
import { adminReportActionBody, reportIdSchema } from '../../../../../lib/schemas'

/** Aktion zu einer Meldung: Nachricht löschen, verwarnen, stummschalten, sperren, abweisen, erledigen. */
export default defineEventHandler(async (event) => {
  const actor = requireAdmin(event)
  const id = paramWith(event, 'id', reportIdSchema)
  const body = await readJson(event, adminReportActionBody)
  return { report: adminReportAction(useCtx(), actor, id, body) }
})
