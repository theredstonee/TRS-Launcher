import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { requireWeb } from '../../../lib/http'
import { myTeamView, teamOf } from '../../../lib/team'

/**
 * Website: aktuelle Sitzung (nach dem Neuladen) – Name, UUID, CSRF-Token, Ablauf und Team-Rechte (§23.1).
 * `team` = `null` für normale Spieler. 401 ohne Sitzung.
 */
export default defineEventHandler((event) => {
  const session = requireWeb(event)
  const ctx = useCtx()
  const staff = teamOf(ctx, session.uuid)
  return {
    uuid: session.uuid,
    name: session.name,
    csrf: session.csrf,
    expiresAt: session.expiresAt,
    team: staff ? myTeamView(ctx, staff) : null,
  }
})
