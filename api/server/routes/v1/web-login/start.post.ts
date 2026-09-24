import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { clientIp, limit } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { startWebLogin } from '../../../lib/weblogin'

/** Website: neuen Anmeldecode anfordern (5 min gültig). Antwort: `{ code, pollSecret, expiresAt }`. */
export default defineEventHandler((event) => {
  limit(`weblogin-start:${clientIp(event)}`, RULES.webLoginStartIp)
  return startWebLogin(useCtx())
})
