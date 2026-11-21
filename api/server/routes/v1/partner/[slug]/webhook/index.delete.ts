import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { noContent, paramWith, requireWebOrUser } from '../../../../../lib/http'
import { deleteWebhook } from '../../../../../lib/partner-webhooks'
import { PARTNER_SLUG, webhookActor } from '../../../../../lib/partners'

/** Webhook entfernen (offene Zustellungen → failed). 204. */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  const a = webhookActor(useCtx(), auth.uuid, slug, 'delete')
  deleteWebhook(useCtx(), a.partner.id)
  return noContent(event)
})
