import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { created, limit, readJson, requireUser } from '../../../lib/http'
import { createReport } from '../../../lib/moderation'
import { RULES } from '../../../lib/ratelimit'
import { chatReportBody } from '../../../lib/schemas'

/** Nachricht, Bild, Spieler oder Gruppe melden (Beweis-Schnappschuss zur Meldezeit). */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const body = await readJson(event, chatReportBody)
  limit(`chatReport:${auth.uuid}`, RULES.chatReportUser)
  return created(event, { report: createReport(useCtx(), auth.uuid, body) })
})
