import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { paramWith, requireWebOrUser } from '../../../../../lib/http'
import { sendTestWebhook } from '../../../../../lib/partner-webhooks'
import { PARTNER_SLUG, webhookActor } from '../../../../../lib/partners'

/** `test.ping` sofort senden → `{ delivery }`. */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  const a = webhookActor(useCtx(), auth.uuid, slug, 'test')
  return { delivery: await sendTestWebhook(useCtx(), a.partner.id) }
})
