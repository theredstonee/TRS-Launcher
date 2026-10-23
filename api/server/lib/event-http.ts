import { z } from 'zod'
import type { AppContext } from './context'
import { notFound, upstreamFailed } from './errors'
import { normalizeUuid } from './ids'
import { MojangUnavailable } from './mojang'
import { getUser, getUserByName } from './users'

/** Body der Team-Routen, die einen Spieler an ein Event hängen (§32.3 Freigabe, §32.6 Look-Sperre). */
export const EventPlayerBody = z.union([
  z.strictObject({ name: z.string().trim().regex(/^[A-Za-z0-9_]{1,16}$/) }),
  z.strictObject({ uuid: z.string().trim().min(32).max(36) }),
])

/** `{ name }` über TRS-Konten, sonst Mojang auflösen; `{ uuid }` direkt (Name aus dem TRS-Konto, falls bekannt). */
export async function resolveEventPlayer(ctx: AppContext, body: z.output<typeof EventPlayerBody>): Promise<{ uuid: string, name: string | null }> {
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
