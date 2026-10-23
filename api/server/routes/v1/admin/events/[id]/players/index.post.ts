import { defineEventHandler, setResponseStatus } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { EventPlayerBody, resolveEventPlayer } from '../../../../../../lib/event-http'
import { limit, paramWith, readJson, requireStaff } from '../../../../../../lib/http'
import { addEventPlayer, adminEvents, EVENT_ID } from '../../../../../../lib/liveevents'
import { RULES } from '../../../../../../lib/ratelimit'

/**
 * Spieler für ein Event freigeben (§32): `{ name }` (Minecraft-Name, über TRS-Konten bzw. Mojang aufgelöst) oder
 * `{ uuid }`. → 201 + die geänderte Zeile von `GET /v1/admin/events`.
 */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'events.manage')
  limit(`admin-events:${staff.uuid}`, RULES.adminSanction)
  const id = paramWith(event, 'id', z.string().regex(EVENT_ID))
  const body = await readJson(event, EventPlayerBody)
  const ctx = useCtx()
  const who = await resolveEventPlayer(ctx, body)
  addEventPlayer(ctx, staff.uuid, id, who.uuid, who.name)
  setResponseStatus(event, 201)
  return adminEvents(ctx).find((e) => e.id === id)
})
