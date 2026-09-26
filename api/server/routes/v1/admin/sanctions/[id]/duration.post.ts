import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { adminSanctionView, changeDuration } from '../../../../../lib/sanctions'
import { durationChangeBody, sanctionIdSchema } from '../../../../../lib/schemas'

/** Ende verkürzen oder verlängern (`endsAt: null` = dauerhaft, nur Admins). */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event)
  limit(`admin-sanction:${staff.uuid}`, RULES.adminSanction)
  const id = paramWith(event, 'id', sanctionIdSchema)
  const body = await readJson(event, durationChangeBody)
  const ctx = useCtx()
  return { sanction: adminSanctionView(ctx, changeDuration(ctx, staff, id, body.endsAt, body.reason)) }
})
