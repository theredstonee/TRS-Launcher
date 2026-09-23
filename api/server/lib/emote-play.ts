import type { AppContext } from './context'
import { canUseCosmetic, getCosmetic } from './cosmetics'
import { EMOTE_BY_ID } from './emotes'
import { forbidden, notFound, tooMany } from './errors'
import { emitEmote } from './playerevents'
import { RULES } from './ratelimit'

export interface EmotePlayed {
  emote: string
  durationMs: number
  at: string
}

/**
 * Prüft Existenz + Freischaltung, wendet das Limit an (1 je 2 s; nur gültige
 * Versuche zählen) und verteilt das Ereignis an die Beobachter des Spielers.
 */
export function playEmote(ctx: AppContext, uuid: string, emoteId: string): EmotePlayed {
  const def = EMOTE_BY_ID.get(emoteId)
  const row = def ? getCosmetic(ctx, def.id) : undefined
  if (!def || !row || row.slot !== 'emote' || row.retired) throw notFound('emote_not_found', 'Unknown emote')
  if (!canUseCosmetic(ctx, uuid, row)) throw forbidden('emote_locked', 'You have not unlocked this emote')
  const r = ctx.limiter.take(`emote:${uuid}`, RULES.emoteUser)
  if (!r.ok) throw tooMany(r.retryAfter)
  const at = ctx.now()
  emitEmote(ctx, uuid, def, at)
  return { emote: def.id, durationMs: def.durationMs, at: new Date(at).toISOString() }
}
