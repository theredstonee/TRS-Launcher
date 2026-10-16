import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { ensureEventBadge, halloweenView } from '../../../../lib/halloween'
import { requireWebOrUser } from '../../../../lib/http'

/**
 * `GET /v1/me/halloween` (§36): Kürbis-Suche, „Süßes oder Saures“ von heute, Abzeichen, Effekt-Freigaben.
 * Bearer (Launcher, TRS Client) oder Website-Sitzung. Vergibt wie `GET /v1/me` das Abzeichen „Halloween 2026“.
 */
export default defineEventHandler((event) => {
  const me = requireWebOrUser(event)
  const ctx = useCtx()
  ensureEventBadge(ctx, me.uuid)
  return halloweenView(ctx, me.uuid)
})
