import { defineEventHandler, setResponseHeader } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { notFound, tooMany } from '../../../lib/errors'
import { clientIp, limit, readJson, requireUser } from '../../../lib/http'
import { lookupByCode, lookupByToken } from '../../../lib/launcherlogin'
import { RULES } from '../../../lib/ratelimit'

const body = z.union([
  z.object({ token: z.string().max(64) }).strict(),
  z.object({ code: z.string().max(20) }).strict(),
])

/**
 * Launcher: Anmelde-Anfrage der Website nachschlagen (§29.3) – per Link-Token (`trs-launcher://web-login/<token>`) oder
 * per eingetipptem Code. Liefert, was der Bestätigungsdialog zeigt; bestätigt wird erst mit `approve`.
 * Falsche Codes zählen streng (je Konto und je IP), damit sich Codes nicht durchprobieren lassen.
 */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`llogin-lookup:${auth.uuid}`, RULES.launcherLoginLookupUser)
  const input = await readJson(event, body)
  const ctx = useCtx()
  setResponseHeader(event, 'Cache-Control', 'no-store')
  if ('token' in input) return { request: lookupByToken(ctx, input.token) }

  const ip = clientIp(event)
  for (const [key, rule] of [[`llogin-fail:${auth.uuid}`, RULES.launcherLoginCodeFailUser], [`llogin-fail-ip:${ip}`, RULES.launcherLoginCodeFailIp]] as const) {
    const r = ctx.limiter.check(key, rule)
    if (!r.ok) throw tooMany(r.retryAfter)
  }
  const found = lookupByCode(ctx, input.code)
  if (!found) {
    ctx.limiter.take(`llogin-fail:${auth.uuid}`, RULES.launcherLoginCodeFailUser)
    ctx.limiter.take(`llogin-fail-ip:${ip}`, RULES.launcherLoginCodeFailIp)
    throw notFound('login_request_expired', 'No open sign-in request has this code – check it or start again on the website')
  }
  return { request: found }
})
