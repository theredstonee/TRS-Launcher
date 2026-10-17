import { defineEventHandler } from 'h3'
import { z } from 'zod'
import type { AppContext } from '../../../../lib/context'
import { useCtx } from '../../../../lib/context'
import { forbidden, notFound, upstreamFailed } from '../../../../lib/errors'
import { limit, readJson, requireStaff } from '../../../../lib/http'
import { normalizeUuid } from '../../../../lib/ids'
import { MojangUnavailable } from '../../../../lib/mojang'
import { RULES } from '../../../../lib/ratelimit'
import { pickScareKind, sendScare } from '../../../../lib/scare'
import { getUserByName } from '../../../../lib/users'

const Body = z.strictObject({ target: z.string().trim().min(1).max(36) })

/** Ziel auflösen: sieht es wie eine UUID aus → direkt; sonst Minecraft-Name über TRS-Konten bzw. Mojang. */
async function resolveTarget(ctx: AppContext, target: string): Promise<string> {
  const uuid = normalizeUuid(target)
  if (uuid) return uuid
  if (!/^[A-Za-z0-9_]{1,16}$/.test(target)) throw notFound('player_not_found', 'No such player')
  const known = getUserByName(ctx, target)
  if (known) return known.uuid
  try {
    const profile = await ctx.mojang.profileByName(target)
    if (!profile) throw notFound('player_not_found', 'No Minecraft account with this name')
    return profile.uuid
  } catch (err) {
    if (err instanceof MojangUnavailable) throw upstreamFailed()
    throw err
  }
}

/**
 * `POST /v1/admin/halloween/scare` `{ target: "<uuid-or-name>" }` (§36, Part C): NUR der Owner (`403 not_owner`).
 * Schickt dem Ziel einen Schreck auf `GET /v1/events/me` (`{ type: "jumpscare", kind }`). `404 player_offline`,
 * wenn das Ziel keinen offenen Stream hat. Opt-out/Sicherheit prüft am Ende der Client (der Mod ist die letzte Instanz).
 */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event)
  if (!staff.owner) throw forbidden('not_owner', 'Only the owner can do this')
  limit(`halloween-scare:${staff.uuid}`, RULES.halloweenScareOwner)
  const { target } = await readJson(event, Body)
  const ctx = useCtx()
  const uuid = await resolveTarget(ctx, target)
  const kind = pickScareKind()
  if (!sendScare(ctx, uuid, kind)) throw notFound('player_offline', 'Player is not online')
  return { sent: true }
})
