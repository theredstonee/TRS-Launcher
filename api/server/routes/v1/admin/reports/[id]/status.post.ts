import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { adminSetStatus, reportDetailFor } from '../../../../../lib/moderation'
import { can } from '../../../../../lib/team'
import { adminReportStatusBody, reportIdSchema } from '../../../../../lib/schemas'

export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'reports.handle')
  const actor = staff.uuid
  const id = paramWith(event, 'id', reportIdSchema)
  const body = await readJson(event, adminReportStatusBody)
  return { report: reportDetailFor(adminSetStatus(useCtx(), actor, id, body.status), can(staff, 'reports.content')) }
})
