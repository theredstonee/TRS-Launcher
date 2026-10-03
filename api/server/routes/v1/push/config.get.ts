import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { requireUser } from '../../../lib/http'
import { DEFAULT_CATEGORIES, PUSH_CATEGORIES } from '../../../lib/push'

/** Was die App zum Anmelden braucht (§33.1): VAPID-Schlüssel für den UnifiedPush-Verteiler, Kategorien, Grenzen. */
export default defineEventHandler((event) => {
  requireUser(event)
  const ctx = useCtx()
  return {
    unifiedPush: ctx.push.enabled,
    vapidPublicKey: ctx.config.vapid?.publicKey ?? null,
    categories: PUSH_CATEGORIES,
    defaults: DEFAULT_CATEGORIES,
    maxDevices: ctx.config.limits.maxPushDevices,
  }
})
