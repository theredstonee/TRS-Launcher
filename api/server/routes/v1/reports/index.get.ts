import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { requireUser } from '../../../lib/http'
import { listMyReports } from '../../../lib/moderation'

/** Eigene Meldungen mit Status (ohne Details der Entscheidung). */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  return { reports: listMyReports(useCtx(), auth.uuid) }
})
