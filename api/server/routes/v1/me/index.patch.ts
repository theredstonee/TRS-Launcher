import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { broadcastPresence } from '../../../lib/friends'
import { readJson, requireUser } from '../../../lib/http'
import { emitBadge, emitCape, emitCosmetics } from '../../../lib/playerevents'
import { settingsPatch } from '../../../lib/schemas'
import { meView, updateSettings } from '../../../lib/users'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const patch = await readJson(event, settingsPatch)
  const ctx = useCtx()
  const before = auth.user
  const user = updateSettings(ctx, auth.uuid, patch)
  // Sichtbarkeit geändert → Freunde bzw. Beobachter sofort informieren.
  if (before.presence_visibility !== user.presence_visibility || before.share_server !== user.share_server) {
    broadcastPresence(ctx, auth.uuid)
  }
  if (before.show_badge !== user.show_badge) emitBadge(ctx, auth.uuid)
  if (before.show_cape !== user.show_cape) emitCape(ctx, auth.uuid)
  if (before.show_cosmetics !== user.show_cosmetics) emitCosmetics(ctx, auth.uuid)
  return meView(ctx, user)
})
