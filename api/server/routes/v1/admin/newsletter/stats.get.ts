import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit } from '../../../../lib/http'
import { requireNewsletter } from '../../../../lib/mail/http'
import { actorId } from '../../../../lib/mail/keys'
import { subscriberStats } from '../../../../lib/mail/subscribers'
import { RULES } from '../../../../lib/ratelimit'

/** Abonnenten-Zahlen (§40.4): je Status, Sprache, Quelle – keine Adressen, kein Export. */
export default defineEventHandler((event) => {
  const a = requireNewsletter(event, 'newsletter.manage')
  limit(`adminNl:${actorId(a)}`, RULES.adminNewsletter)
  const ctx = useCtx()
  return { stats: subscriberStats(ctx), mailAvailable: ctx.mail.available }
})
