import { defineEventHandler } from 'h3'
import { listAudit } from '../../../lib/admin'
import { useCtx } from '../../../lib/context'
import { queryWith, requireStaff } from '../../../lib/http'
import { auditQuery } from '../../../lib/schemas'

/** Audit-Log (neueste zuerst), filterbar nach Bezug, Ziel, Akteur, Aktion (Präfix) und Zeitraum. */
export default defineEventHandler((event) => {
  requireStaff(event)
  const q = queryWith(event, auditQuery)
  return listAudit(useCtx(), q)
})
