import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { clientIp, limit, readJson } from '../../../lib/http'
import { unsubscribeByToken } from '../../../lib/mail/subscribers'
import { RULES } from '../../../lib/ratelimit'

const Body = z.strictObject({ token: z.string().max(100) })

/** Abmelden per Token (Knopf auf /newsletter/unsubscribe). Schon abgemeldet → gleiche Antwort. → `{ status, email }` (maskiert). */
export default defineEventHandler(async (event) => {
  limit(`nlToken:${clientIp(event)}`, RULES.newsletterTokenIp)
  const { token } = await readJson(event, Body)
  return { status: 'unsubscribed', ...unsubscribeByToken(useCtx(), token) }
})
