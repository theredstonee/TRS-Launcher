import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { paramWith, readJson, requireWebOrUser } from '../../../../../lib/http'
import { saveWebhook, webhookBody } from '../../../../../lib/partner-webhooks'
import { PARTNER_SLUG, webhookActor } from '../../../../../lib/partners'

/** Webhook speichern: `{ url, events }` → `{ webhook, secret? }` (Geheimnis nur beim ersten Speichern). Recht `webhook`. */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  const input = await readJson(event, webhookBody)
  const a = webhookActor(useCtx(), auth.uuid, slug, 'save')
  return saveWebhook(useCtx(), a.partner.id, input)
})
