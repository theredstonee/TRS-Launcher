import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, readJson, requireStaff } from '../../../../lib/http'
import { adminBulkReports } from '../../../../lib/moderation'
import { RULES } from '../../../../lib/ratelimit'
import { bulkReportsBody } from '../../../../lib/schemas'

/** Sammelaktion: bis zu 50 Meldungen abweisen oder erledigen (eine Transaktion). */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event)
  limit(`admin-bulk:${staff.uuid}`, RULES.adminBulk)
  const body = await readJson(event, bulkReportsBody)
  return adminBulkReports(useCtx(), staff, body.ids, body.action === 'dismiss' ? 'dismissed' : 'actioned')
})
