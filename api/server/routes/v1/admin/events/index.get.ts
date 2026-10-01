import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { requireStaff } from '../../../../lib/http'
import { adminEvents } from '../../../../lib/liveevents'

/** Team (§32, `events.manage`): alle Events mit globalem Schalter und Spielerliste. */
export default defineEventHandler((event) => {
  requireStaff(event, 'events.manage')
  return adminEvents(useCtx())
})
