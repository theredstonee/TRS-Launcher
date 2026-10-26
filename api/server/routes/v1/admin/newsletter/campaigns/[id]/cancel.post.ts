import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { limit, paramWith } from '../../../../../../lib/http'
import { CAMPAIGN_ID, cancelCampaign } from '../../../../../../lib/mail/campaigns'
import { requireNewsletter } from '../../../../../../lib/mail/http'
import { actorId } from '../../../../../../lib/mail/keys'
import { RULES } from '../../../../../../lib/ratelimit'

/** Geplanten oder laufenden Versand abbrechen (§40.4, Recht `newsletter.send`); schon versandte Mails bleiben. */
export default defineEventHandler((event) => {
  const a = requireNewsletter(event, 'newsletter.send')
  limit(`adminNl:${actorId(a)}`, RULES.adminNewsletter)
  const id = paramWith(event, 'id', z.string().regex(CAMPAIGN_ID))
  return { campaign: cancelCampaign(useCtx(), a, id) }
})
