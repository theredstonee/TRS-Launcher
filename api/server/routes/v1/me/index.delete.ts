import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { broadcastPresence } from '../../../lib/friends'
import { limit, noContent, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { deleteUser } from '../../../lib/users'

/** DSGVO Art. 17 – löscht alle Daten des Kontos sofort. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`delete:${auth.uuid}`, RULES.deleteUser)
  const ctx = useCtx()
  // Freunde sehen den Nutzer ab sofort offline (vor dem Löschen, solange die Freundschaften noch existieren).
  if (ctx.presence.delete(auth.uuid)) broadcastPresence(ctx, auth.uuid)
  deleteUser(ctx, auth.uuid)
  return noContent(event)
})
