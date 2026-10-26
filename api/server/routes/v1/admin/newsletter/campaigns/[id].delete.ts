import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, noContent, paramWith } from '../../../../../lib/http'
import { CAMPAIGN_ID, deleteCampaign } from '../../../../../lib/mail/campaigns'
import { requireNewsletter } from '../../../../../lib/mail/http'
import { actorId } from '../../../../../lib/mail/keys'
import { RULES } from '../../../../../lib/ratelimit'

/** Kampagne löschen (§40.4) – nicht, solange sie geplant ist oder gesendet wird. */
export default defineEventHandler((event) => {
  const a = requireNewsletter(event, 'newsletter.manage')
  limit(`adminNl:${actorId(a)}`, RULES.adminNewsletter)
  const id = paramWith(event, 'id', z.string().regex(CAMPAIGN_ID))
  deleteCampaign(useCtx(), a, id)
  return noContent(event)
})
