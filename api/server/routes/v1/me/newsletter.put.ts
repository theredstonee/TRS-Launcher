import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { limit, readJson, requireUser } from '../../../lib/http'
import { setMyNewsletter } from '../../../lib/mail/subscribers'
import { RULES } from '../../../lib/ratelimit'

const Body = z.strictObject({ email: z.string().max(320), lang: z.enum(['de', 'en', 'es']) })

/**
 * Launcher: Newsletter abonnieren bzw. Adresse/Sprache ändern (§40.3). Neue Adresse → Bestätigungs-Mail (Status
 * `pending`); dieselbe, schon bestätigte Adresse → nur die Sprache ändert sich. 503 `mail_unavailable` ohne SMTP.
 */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`nlMe:${auth.uuid}`, RULES.newsletterMeUser)
  const body = await readJson(event, Body)
  return { newsletter: setMyNewsletter(useCtx(), auth.uuid, body) }
})
