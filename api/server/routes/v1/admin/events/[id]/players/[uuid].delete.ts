import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { limit, paramWith, requireStaff } from '../../../../../../lib/http'
import { adminEvents, EVENT_ID, removeEventPlayer } from '../../../../../../lib/liveevents'
import { RULES } from '../../../../../../lib/ratelimit'
import { uuidSchema } from '../../../../../../lib/schemas'

/** Freigabe eines Spielers zurücknehmen (§32). Bereits abgeholte Teile bleiben. → die geänderte Zeile. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'events.manage')
  limit(`admin-events:${staff.uuid}`, RULES.adminSanction)
  const id = paramWith(event, 'id', z.string().regex(EVENT_ID))
  const uuid = paramWith(event, 'uuid', uuidSchema)
  const ctx = useCtx()
  removeEventPlayer(ctx, staff.uuid, id, uuid)
  return adminEvents(ctx).find((e) => e.id === id)
})
