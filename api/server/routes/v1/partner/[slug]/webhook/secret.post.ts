import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { paramWith, requireWebOrUser } from '../../../../../lib/http'
import { rotateWebhookSecret } from '../../../../../lib/partner-webhooks'
import { PARTNER_SLUG, webhookActor } from '../../../../../lib/partners'

/** Neues Webhook-Geheimnis → `{ webhook, secret }` (nur in dieser Antwort). */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  const a = webhookActor(useCtx(), auth.uuid, slug, 'secret')
  return rotateWebhookSecret(useCtx(), a.partner.id)
})
