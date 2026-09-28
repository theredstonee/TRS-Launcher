import { defineEventHandler } from 'h3'
import { countAchievement } from '../../../../lib/achievements'
import { useCtx } from '../../../../lib/context'
import { createRoom } from '../../../../lib/hosting'
import { created, limit, readJson, requireUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { createRoomBody } from '../../../../lib/schemas'

/** Welt öffnen: Raum + Join-Code + Relay-Token (Host) + STUN. Ein bestehender Raum des Hosts schließt dabei. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'hosting')
  const body = await readJson(event, createRoomBody)
  limit(`hostingCreate:${auth.uuid}`, RULES.hostingCreateUser)
  const ctx = useCtx()
  const room = createRoom(ctx, auth.user, body)
  countAchievement(ctx, auth.uuid, 'worlds_hosted')
  return created(event, room)
})
