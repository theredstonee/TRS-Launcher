import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { created, limit, paramWith } from '../../../../../../lib/http'
import { CAMPAIGN_ID, duplicateCampaign } from '../../../../../../lib/mail/campaigns'
import { requireNewsletter } from '../../../../../../lib/mail/http'
import { actorId } from '../../../../../../lib/mail/keys'
import { RULES } from '../../../../../../lib/ratelimit'

/** Kampagne als neuen Entwurf kopieren (§40.4) – ohne Codes. */
export default defineEventHandler((event) => {
  const a = requireNewsletter(event, 'newsletter.manage')
  limit(`adminNl:${actorId(a)}`, RULES.adminNewsletter)
  const id = paramWith(event, 'id', z.string().regex(CAMPAIGN_ID))
  return created(event, { campaign: duplicateCampaign(useCtx(), a, id) })
})
