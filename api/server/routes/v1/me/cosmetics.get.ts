import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { equippedView, ownedEmotes } from '../../../lib/cosmetics'
import { requireUser } from '../../../lib/http'

/** Eigene Ausrüstung je Platz + IDs der freigeschalteten Emotes. */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  const ctx = useCtx()
  return { equipped: equippedView(ctx, auth.uuid), emotes: ownedEmotes(ctx, auth.uuid) }
})
