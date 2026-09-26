import { defineEventHandler } from 'h3'
import { listAudit } from '../../../lib/admin'
import { useCtx } from '../../../lib/context'
import { queryWith, requireAdmin } from '../../../lib/http'
import { auditQuery } from '../../../lib/schemas'

/** Audit-Log (neueste zuerst), optional je Meldung (`ref`) oder Spieler (`target`). */
export default defineEventHandler((event) => {
  requireAdmin(event)
  const q = queryWith(event, auditQuery)
  return listAudit(useCtx(), q)
})
