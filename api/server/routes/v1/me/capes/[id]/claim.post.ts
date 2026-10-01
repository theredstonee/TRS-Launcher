import { defineEventHandler } from 'h3'
import { claimCape } from '../../../../../lib/capes'
import { useCtx } from '../../../../../lib/context'
import { paramWith, requireUser } from '../../../../../lib/http'
import { capeIdSchema } from '../../../../../lib/schemas'

/** Event-Umhang gratis abholen (§32). 200 `{ owned: true }`; Event nicht aktiv für dich → 403 `event_inactive`. Idempotent. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  return claimCape(useCtx(), auth.uuid, paramWith(event, 'id', capeIdSchema))
})
