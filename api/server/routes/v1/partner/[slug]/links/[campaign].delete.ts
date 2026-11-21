import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { noContent, paramWith, requireWebOrUser } from '../../../../../lib/http'
import { CAMPAIGN, PARTNER_SLUG, removeCampaign } from '../../../../../lib/partners'

/** Kampagne löschen (Zahlen bleiben in der Statistik). 204. */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  removeCampaign(useCtx(), auth.uuid, slug, paramWith(event, 'campaign', z.string().regex(CAMPAIGN)))
  return noContent(event)
})
