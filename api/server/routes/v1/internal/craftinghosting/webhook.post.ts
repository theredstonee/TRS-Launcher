import { defineEventHandler, getHeader, setResponseStatus } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingWebhook, verifyWebhook, webhookSecret } from '../../../../lib/craftinghosting'
import { forbidden, unavailable } from '../../../../lib/errors'
import { readLimited } from '../../../../lib/http'

/** Webhook von CraftingHosting (§43.15): Signatur prüfen, schnell 202 antworten. */
export default defineEventHandler(async (event) => {
  const ctx = useCtx()
  const secret = webhookSecret(ctx)
  if (!secret) throw unavailable('webhook_unavailable', 'No webhook configured')
  const raw = await readLimited(event, 64 * 1024)
  if (!verifyWebhook(secret, raw, getHeader(event, 'x-chf-timestamp') ?? '', getHeader(event, 'x-chf-signature') ?? '', Date.now())) {
    throw forbidden('webhook_signature', 'Invalid signature')
  }
  try {
    craftingWebhook(ctx, raw)
  } catch { /* kaputter Körper: trotzdem quittieren, sonst wiederholt der Partner ihn tagelang */ }
  setResponseStatus(event, 202)
  return { ok: true }
})
