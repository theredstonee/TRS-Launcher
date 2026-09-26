import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { adminSanctionView, liftSanction } from '../../../../../lib/sanctions'
import { liftBody, sanctionIdSchema } from '../../../../../lib/schemas'

/** Aktive Strafe aufheben (mit Begründung). */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event)
  limit(`admin-sanction:${staff.uuid}`, RULES.adminSanction)
  const id = paramWith(event, 'id', sanctionIdSchema)
  const body = await readJson(event, liftBody)
  const ctx = useCtx()
  return { sanction: adminSanctionView(ctx, liftSanction(ctx, staff, id, body.reason)) }
})
