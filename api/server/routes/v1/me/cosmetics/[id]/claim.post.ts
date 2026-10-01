import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { claimCosmetic } from '../../../../../lib/cosmetics'
import { v2IdParam } from '../../../../../lib/cosmetics-v2'
import { requireUser } from '../../../../../lib/http'

/** Event-Teil gratis abholen (§32). 200 `{ owned: true }`; Event nicht aktiv für dich → 403 `event_inactive`. Idempotent. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  return claimCosmetic(useCtx(), auth.uuid, v2IdParam(event))
})
