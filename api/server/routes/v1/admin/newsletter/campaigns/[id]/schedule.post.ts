import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { limit, paramWith, readJson } from '../../../../../../lib/http'
import { CAMPAIGN_ID, scheduleBody, scheduleCampaign } from '../../../../../../lib/mail/campaigns'
import { requireNewsletter } from '../../../../../../lib/mail/http'
import { actorId } from '../../../../../../lib/mail/keys'
import { RULES } from '../../../../../../lib/ratelimit'

/** Versand planen (§40.4, Recht `newsletter.send`): `{ at, langs? }`. */
export default defineEventHandler(async (event) => {
  const a = requireNewsletter(event, 'newsletter.send')
  limit(`adminNl:${actorId(a)}`, RULES.adminNewsletter)
  const id = paramWith(event, 'id', z.string().regex(CAMPAIGN_ID))
  const body = await readJson(event, scheduleBody)
  return { campaign: scheduleCampaign(useCtx(), a, id, body) }
})
