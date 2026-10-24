import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { actingAs } from '../../../lib/creator-http'
import { creatorPreviews } from '../../../lib/creators'
import { requireWebOrUser } from '../../../lib/http'

/** Vorschau-Builds von Launcher und TRS Client (§35.3, Partner+ oder Team-Recht `previews.access`; Starter → 403 `creator_tier`). */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event)
  return creatorPreviews(useCtx(), auth.uuid, actingAs(event))
})
