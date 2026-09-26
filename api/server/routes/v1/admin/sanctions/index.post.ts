import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { created, limit, readJson, requireStaff } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { adminSanctionView, createSanction, minutesOf } from '../../../../lib/sanctions'
import { adminSanctionBody } from '../../../../lib/schemas'

/** Strafe verhängen (Rechte je Rolle, §22.2). */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event)
  limit(`admin-sanction:${staff.uuid}`, RULES.adminSanction)
  const body = await readJson(event, adminSanctionBody)
  const ctx = useCtx()
  const s = createSanction(ctx, staff, {
    uuid: body.uuid,
    kind: body.kind,
    minutes: minutesOf(body.duration, body.minutes),
    reasonCode: body.reasonCode,
    reason: body.reason ?? null,
    note: body.note ?? null,
    reportId: body.reportId ?? null,
  })
  return created(event, { sanction: adminSanctionView(ctx, s) })
})
