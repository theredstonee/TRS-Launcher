import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { limit, paramWith, queryWith } from '../../../../../../lib/http'
import { CAMPAIGN_ID, getCampaign } from '../../../../../../lib/mail/campaigns'
import { audienceCount } from '../../../../../../lib/mail/subscribers'
import { requireNewsletter } from '../../../../../../lib/mail/http'
import { actorId } from '../../../../../../lib/mail/keys'
import { RULES } from '../../../../../../lib/ratelimit'

const Query = z.strictObject({ langs: z.string().regex(/^(de|en|es)(,(de|en|es)){0,2}$/).optional() })

/** Empfänger-Zahl für den Bestätigungsdialog (§40.4): `?langs=de,en` → `{ count }`. */
export default defineEventHandler((event) => {
  const a = requireNewsletter(event, 'newsletter.send')
  limit(`adminNl:${actorId(a)}`, RULES.adminNewsletter)
  const id = paramWith(event, 'id', z.string().regex(CAMPAIGN_ID))
  const q = queryWith(event, Query)
  const ctx = useCtx()
  getCampaign(ctx, id)
  const langs = q.langs ? (q.langs.split(',') as ('de' | 'en' | 'es')[]) : null
  return { count: audienceCount(ctx, langs) }
})
