import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { requireWeb } from '../../../lib/http'
import { activeEventsFor, lookEventsFor } from '../../../lib/liveevents'
import { myTeamView, teamOf } from '../../../lib/team'

/**
 * Website: aktuelle Sitzung (nach dem Neuladen) – Name, UUID, Skin (Kopf), CSRF-Token, Ablauf und Team-Rechte (§24.1).
 * `team` = `null` für normale Spieler. `events`/`eventLook` wie in `GET /v1/me` (§32.2, §32.6). 401 ohne Sitzung.
 */
export default defineEventHandler(async (event) => {
  const session = requireWeb(event)
  const ctx = useCtx()
  const staff = teamOf(ctx, session.uuid)
  let skin: string | null
  try {
    skin = (await ctx.skins.byUuid(session.uuid)).textureUrl
  } catch {
    skin = null
  }
  return {
    uuid: session.uuid,
    name: session.name,
    skin,
    csrf: session.csrf,
    expiresAt: session.expiresAt,
    team: staff ? myTeamView(ctx, staff) : null,
    events: activeEventsFor(ctx, session.uuid),
    eventLook: lookEventsFor(ctx, session.uuid),
  }
})
