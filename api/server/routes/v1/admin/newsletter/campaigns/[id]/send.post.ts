import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { limit, paramWith, readJson } from '../../../../../../lib/http'
import { CAMPAIGN_ID, sendBody, sendCampaign } from '../../../../../../lib/mail/campaigns'
import { audienceCount } from '../../../../../../lib/mail/subscribers'
import { requireNewsletter } from '../../../../../../lib/mail/http'
import { actorId } from '../../../../../../lib/mail/keys'
import { RULES } from '../../../../../../lib/ratelimit'

/**
 * Jetzt an alle bestätigten Empfänger senden (§40.4, Recht `newsletter.send`): `{ langs?, expectedCount? }`.
 * `expectedCount` = Zahl aus dem Bestätigungsdialog; weicht sie ab → 409 `audience_changed` mit `count`.
 */
export default defineEventHandler(async (event) => {
  const a = requireNewsletter(event, 'newsletter.send')
  limit(`adminNl:${actorId(a)}`, RULES.adminNewsletter)
  const id = paramWith(event, 'id', z.string().regex(CAMPAIGN_ID))
  const body = await readJson(event, sendBody)
  const ctx = useCtx()
  return sendCampaign(ctx, a, id, body, (langs) => audienceCount(ctx, langs))
})
