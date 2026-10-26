import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { limit, paramWith, readJson } from '../../../../../../lib/http'
import { CAMPAIGN_ID, previewCampaign, savedPreviewBody } from '../../../../../../lib/mail/campaigns'
import { requireNewsletter } from '../../../../../../lib/mail/http'
import { actorId } from '../../../../../../lib/mail/keys'
import { RULES } from '../../../../../../lib/ratelimit'

/** Gespeicherte Kampagne als Mail rendern (§40.4): `{ lang?, look? }` → `{ subject, preheader, html, text, look }`. */
export default defineEventHandler(async (event) => {
  const a = requireNewsletter(event, 'newsletter.manage')
  limit(`nlPreview:${actorId(a)}`, RULES.newsletterPreview)
  const id = paramWith(event, 'id', z.string().regex(CAMPAIGN_ID))
  const body = await readJson(event, savedPreviewBody)
  return previewCampaign(useCtx(), id, body)
})
