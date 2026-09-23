import { defineEventHandler, getHeader } from 'h3'
import { authenticate } from '../../../lib/auth'
import { useCtx } from '../../../lib/context'
import { limit, noContent, readJson } from '../../../lib/http'
import { emitSkin } from '../../../lib/playerevents'
import { RULES } from '../../../lib/ratelimit'
import { skinChangedBody } from '../../../lib/schemas'

/** Der Client hat seinen Mojang-Skin geändert → Beobachter laden ihn sofort neu (`skin`-Ereignis). */
export default defineEventHandler(async (event) => {
  const ctx = useCtx()
  const auth = authenticate(ctx, getHeader(event, 'authorization'))
  limit(`skin:${auth.uuid}`, RULES.skinChangedUser)
  await readJson(event, skinChangedBody)
  ctx.skins.invalidate(auth.uuid)
  emitSkin(ctx, auth.uuid)
  return noContent(event)
})
