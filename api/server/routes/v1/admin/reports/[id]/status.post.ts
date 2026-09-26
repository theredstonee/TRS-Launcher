import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { paramWith, readJson, requireAdmin } from '../../../../../lib/http'
import { adminSetStatus } from '../../../../../lib/moderation'
import { adminReportStatusBody, reportIdSchema } from '../../../../../lib/schemas'

export default defineEventHandler(async (event) => {
  const actor = requireAdmin(event)
  const id = paramWith(event, 'id', reportIdSchema)
  const body = await readJson(event, adminReportStatusBody)
  return { report: adminSetStatus(useCtx(), actor, id, body.status) }
})
