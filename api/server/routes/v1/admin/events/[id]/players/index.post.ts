import { defineEventHandler, setResponseStatus } from 'h3'
import { z } from 'zod'
import type { AppContext } from '../../../../../../lib/context'
import { useCtx } from '../../../../../../lib/context'
import { notFound, upstreamFailed } from '../../../../../../lib/errors'
import { limit, paramWith, readJson, requireStaff } from '../../../../../../lib/http'
import { normalizeUuid } from '../../../../../../lib/ids'
import { addEventPlayer, adminEvents, EVENT_ID } from '../../../../../../lib/liveevents'
import { MojangUnavailable } from '../../../../../../lib/mojang'
import { RULES } from '../../../../../../lib/ratelimit'
import { getUser, getUserByName } from '../../../../../../lib/users'

const Body = z.union([
  z.strictObject({ name: z.string().trim().regex(/^[A-Za-z0-9_]{1,16}$/) }),
  z.strictObject({ uuid: z.string().trim().min(32).max(36) }),
])

async function resolvePlayer(ctx: AppContext, body: z.output<typeof Body>): Promise<{ uuid: string, name: string | null }> {
  if ('uuid' in body) {
    const uuid = normalizeUuid(body.uuid)
    if (!uuid) throw notFound('player_not_found', 'Invalid player UUID')
    return { uuid, name: getUser(ctx, uuid)?.name ?? null }
  }
  const known = getUserByName(ctx, body.name)
  if (known) return { uuid: known.uuid, name: known.name }
  try {
    const profile = await ctx.mojang.profileByName(body.name)
    if (!profile) throw notFound('player_not_found', 'No Minecraft account with this name')
    return { uuid: profile.uuid, name: profile.name }
  } catch (err) {
    if (err instanceof MojangUnavailable) throw upstreamFailed()
    throw err
  }
}

/**
 * Spieler für ein Event freigeben (§32): `{ name }` (Minecraft-Name, über TRS-Konten bzw. Mojang aufgelöst) oder
 * `{ uuid }`. → 201 + die geänderte Zeile von `GET /v1/admin/events`.
 */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'events.manage')
  limit(`admin-events:${staff.uuid}`, RULES.adminSanction)
  const id = paramWith(event, 'id', z.string().regex(EVENT_ID))
  const body = await readJson(event, Body)
  const ctx = useCtx()
  const who = await resolvePlayer(ctx, body)
  addEventPlayer(ctx, staff.uuid, id, who.uuid, who.name)
  setResponseStatus(event, 201)
  return adminEvents(ctx).find((e) => e.id === id)
})
