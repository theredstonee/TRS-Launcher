import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { ensureEventBadge } from '../../../lib/halloween'
import { requireUser } from '../../../lib/http'
import { meView } from '../../../lib/users'

export default defineEventHandler((event) => {
  const auth = requireUser(event)
  const ctx = useCtx()
  // Halloween 2026 (§36): Abzeichen bei jeder Anmeldung im Event-Zeitraum. Darf /v1/me nie stören.
  try {
    ensureEventBadge(ctx, auth.uuid)
  } catch (err) {
    console.error('[trs-api] halloween badge grant failed', (err as Error).message)
  }
  return meView(ctx, auth.user)
})
