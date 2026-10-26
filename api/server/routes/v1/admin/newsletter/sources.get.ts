import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit } from '../../../../lib/http'
import { creatorSources, itemSources } from '../../../../lib/mail/campaigns'
import { requireNewsletter } from '../../../../lib/mail/http'
import { actorId } from '../../../../lib/mail/keys'
import { autoLook } from '../../../../lib/mail/theme'
import { RULES } from '../../../../lib/ratelimit'
import { blogPosts } from '../../../../lib/site'

/**
 * Auswahl-Listen für den Editor (§40.4): Versionen aus dem Changelog, Umhänge/Kosmetik für die Karte, aktive Creator
 * für den Spotlight und der Look, den „automatisch“ gerade ergibt.
 */
export default defineEventHandler(async (event) => {
  const a = requireNewsletter(event, 'newsletter.manage')
  limit(`adminNl:${actorId(a)}`, RULES.adminNewsletter)
  const ctx = useCtx()
  const posts = await blogPosts().catch(() => [])
  return {
    versions: posts.slice(0, 30).map((p) => ({ version: p.version, date: p.date, title: p.title })),
    items: itemSources(ctx),
    creators: creatorSources(ctx),
    autoLook: autoLook(ctx),
    mailAvailable: ctx.mail.available,
  }
})
