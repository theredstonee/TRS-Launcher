import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { creatorPreviews } from '../../../lib/creators'
import { requireUser } from '../../../lib/http'

/** Vorschau-Builds von Launcher und TRS Client (§35.3, Partner+; Starter → 403 `creator_tier`). */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  return creatorPreviews(useCtx(), auth.uuid)
})
