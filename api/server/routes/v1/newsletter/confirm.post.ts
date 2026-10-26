import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { clientIp, limit, readJson } from '../../../lib/http'
import { confirmToken } from '../../../lib/mail/subscribers'
import { RULES } from '../../../lib/ratelimit'

const Body = z.strictObject({ token: z.string().max(100) })

/** Bestätigungs-Link einlösen (Seite /newsletter/confirm): Newsletter (`c…`) oder Team-Adresse (`t…`). → `{ kind, email }` (maskiert). */
export default defineEventHandler(async (event) => {
  limit(`nlToken:${clientIp(event)}`, RULES.newsletterTokenIp)
  const { token } = await readJson(event, Body)
  return confirmToken(useCtx(), token)
})
