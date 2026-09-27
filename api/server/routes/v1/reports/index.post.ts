import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { created, limit, readJson, requireWebOrUser } from '../../../lib/http'
import { createReport } from '../../../lib/moderation'
import { RULES } from '../../../lib/ratelimit'
import { chatReportBody } from '../../../lib/schemas'

/**
 * Nachricht, Bild, Spieler, Gruppe, geteilten Screenshot oder Schaltung melden (Beweis-Schnappschuss zur Meldezeit).
 * Launcher/Client mit Bearer-Token, die Website mit ihrer Sitzung (Cookie + CSRF) – z. B. „Schaltung melden“.
 */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  const body = await readJson(event, chatReportBody)
  limit(`chatReport:${auth.uuid}`, RULES.chatReportUser)
  return created(event, { report: createReport(useCtx(), auth.uuid, body) })
})
