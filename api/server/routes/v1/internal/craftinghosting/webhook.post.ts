import { defineEventHandler, getHeader, setResponseStatus } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingWebhook, verifyWebhook, webhookSecrets } from '../../../../lib/craftinghosting'
import { forbidden, unavailable } from '../../../../lib/errors'
import { readLimited } from '../../../../lib/http'

/** Webhook von CraftingHosting (§43.15): Signatur prüfen, schnell 202 antworten. */
export default defineEventHandler(async (event) => {
  const ctx = useCtx()
  const secrets = webhookSecrets(ctx)
  if (!secrets.length) throw unavailable('webhook_unavailable', 'No webhook configured')
  const raw = await readLimited(event, 64 * 1024)
  const ts = getHeader(event, 'x-chf-timestamp') ?? ''
  const sig = getHeader(event, 'x-chf-signature') ?? ''
  if (!secrets.some((s) => verifyWebhook(s, raw, ts, sig, Date.now()))) {
    throw forbidden('webhook_signature', 'Invalid signature')
  }
  try {
    craftingWebhook(ctx, raw)
  } catch { /* kaputter Körper: trotzdem quittieren, sonst wiederholt der Partner ihn tagelang */ }
  setResponseStatus(event, 202)
  return { ok: true }
})
