import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../../lib/context'
import { paramWith, requireWebOrUser } from '../../../../../../../lib/http'
import { retryDelivery } from '../../../../../../../lib/partner-webhooks'
import { PARTNER_SLUG, webhookActor } from '../../../../../../../lib/partners'

/** Fehlgeschlagene Zustellung erneut senden (ein Versuch sofort) → `{ delivery }`. */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  const id = paramWith(event, 'id', z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER))
  const a = webhookActor(useCtx(), auth.uuid, slug, 'retry')
  return { delivery: await retryDelivery(useCtx(), a.partner.id, id) }
})
