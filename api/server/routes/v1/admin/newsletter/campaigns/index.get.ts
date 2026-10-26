import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { limit, queryWith } from '../../../../../lib/http'
import { listCampaigns, listQuery } from '../../../../../lib/mail/campaigns'
import { requireNewsletter } from '../../../../../lib/mail/http'
import { actorId } from '../../../../../lib/mail/keys'
import { RULES } from '../../../../../lib/ratelimit'

/** Kampagnen (§40.4), neueste zuerst; `?status=` filtert. */
export default defineEventHandler((event) => {
  const a = requireNewsletter(event, 'newsletter.manage')
  limit(`adminNl:${actorId(a)}`, RULES.adminNewsletter)
  const q = queryWith(event, listQuery)
  return { campaigns: listCampaigns(useCtx(), q.status) }
})
