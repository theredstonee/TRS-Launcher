import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { broadcastPresence } from '../../../lib/friends'
import { readJson, requireUser } from '../../../lib/http'
import { settingsPatch } from '../../../lib/schemas'
import { meView, updateSettings } from '../../../lib/users'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const patch = await readJson(event, settingsPatch)
  const ctx = useCtx()
  const before = auth.user
  const user = updateSettings(ctx, auth.uuid, patch)
  // Sichtbarkeit geändert → Freunde sofort informieren.
  if (before.presence_visibility !== user.presence_visibility || before.share_server !== user.share_server) {
    broadcastPresence(ctx, auth.uuid)
  }
  return meView(ctx, user)
})
