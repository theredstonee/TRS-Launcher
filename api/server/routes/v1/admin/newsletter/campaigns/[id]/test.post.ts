import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { limit, paramWith, readJson } from '../../../../../../lib/http'
import { CAMPAIGN_ID, testBody, testCampaign } from '../../../../../../lib/mail/campaigns'
import { requireNewsletter } from '../../../../../../lib/mail/http'
import { actorId } from '../../../../../../lib/mail/keys'
import { RULES } from '../../../../../../lib/ratelimit'

/** Testmail an die eigene Adresse (§40.4): `{ lang?, look? }` → `{ to }` (maskiert). Betreff mit „[TEST]“. */
export default defineEventHandler(async (event) => {
  const a = requireNewsletter(event, 'newsletter.manage')
  limit(`nlTest:${actorId(a)}`, RULES.newsletterTest)
  const id = paramWith(event, 'id', z.string().regex(CAMPAIGN_ID))
  const body = await readJson(event, testBody)
  return testCampaign(useCtx(), a, id, body)
})
