import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../lib/http'
import { adminEvents, EVENT_ID, setEventEnabled } from '../../../../lib/liveevents'
import { RULES } from '../../../../lib/ratelimit'

const Body = z.strictObject({ enabled: z.boolean() })

/** Event global ein-/ausschalten (§32). → die geänderte Zeile von `GET /v1/admin/events`. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'events.manage')
  limit(`admin-events:${staff.uuid}`, RULES.adminSanction)
  const id = paramWith(event, 'id', z.string().regex(EVENT_ID))
  const body = await readJson(event, Body)
  const ctx = useCtx()
  setEventEnabled(ctx, staff.uuid, id, body.enabled)
  return adminEvents(ctx).find((e) => e.id === id)
})
