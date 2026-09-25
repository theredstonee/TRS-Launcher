import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { limit, noContent, requireUser } from '../../../lib/http'
import { updatePresence } from '../../../lib/playerevents'
import { RULES } from '../../../lib/ratelimit'
import { deleteUser } from '../../../lib/users'

/** DSGVO Art. 17 – löscht alle Daten des Kontos sofort. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`delete:${auth.uuid}`, RULES.deleteUser)
  const ctx = useCtx()
  // Freunde sehen den Nutzer ab sofort offline, Beobachter kein Abzeichen mehr
  // (vor dem Löschen, solange Konto und Freundschaften noch existieren).
  updatePresence(ctx, auth.uuid, () => ctx.presence.delete(auth.uuid))
  deleteUser(ctx, auth.uuid)
  return noContent(event)
})
