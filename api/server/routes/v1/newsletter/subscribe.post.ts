import { defineEventHandler, setResponseStatus } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { badRequest } from '../../../lib/errors'
import { clientIp, limit, readJson } from '../../../lib/http'
import { hmacHex } from '../../../lib/ids'
import { requireMail } from '../../../lib/mail/outbox'
import { normalizeEmail, subscribe } from '../../../lib/mail/subscribers'
import { RULES } from '../../../lib/ratelimit'

const Body = z.strictObject({
  email: z.string().max(320),
  lang: z.enum(['de', 'en', 'es']),
  /** Honigtopf: Menschen lassen das Feld leer. */
  website: z.string().max(200).optional(),
})

/**
 * Newsletter abonnieren (§40.2): Double-Opt-in. Antwortet immer gleich (`202 {ok:true}`) – egal ob die Adresse neu,
 * schon bestätigt oder gerade gedrosselt ist. Nur ungültige Adressen (400) und fehlender SMTP (503) fallen auf.
 */
export default defineEventHandler(async (event) => {
  const ctx = useCtx()
  limit(`nlSub:${clientIp(event)}`, RULES.newsletterSubscribeIp)
  const body = await readJson(event, Body)
  requireMail(ctx)
  const email = normalizeEmail(body.email)
  if (!email) throw badRequest('invalid_email', 'This e-mail address is not valid')
  setResponseStatus(event, 202)
  if (body.website) return { ok: true }
  // Je Adresse gedrosselt (Schlüssel ohne Klartext-Adresse) – still, damit niemand Adressen abklopfen kann.
  const key = `nlAddr:${hmacHex(ctx.config.secretKey, `addr:${email}`).slice(0, 32)}`
  if (!ctx.limiter.take(key, RULES.newsletterSubscribeAddr).ok) return { ok: true }
  subscribe(ctx, { email, lang: body.lang, source: 'website' })
  return { ok: true }
})
