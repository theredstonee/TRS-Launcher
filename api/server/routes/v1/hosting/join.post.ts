import { defineEventHandler, setResponseStatus } from 'h3'
import { useCtx } from '../../../lib/context'
import { tooMany } from '../../../lib/errors'
import { join, UnknownCode } from '../../../lib/hosting'
import { clientIp, limit, readJson, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { joinBody } from '../../../lib/schemas'

/**
 * Beitreten per `roomId` oder `code`: 200 `accepted` (+ Relay/STUN) oder 202 `requested`.
 * Unbekannte Codes zählen als Fehlversuch (je Konto und IP); danach 429, auch für richtige Codes.
 */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'hosting')
  const body = await readJson(event, joinBody)
  limit(`hostingJoin:${auth.uuid}`, RULES.hostingJoinUser)
  const ctx = useCtx()
  const ip = clientIp(event)
  const fails = [
    [`hostingCodeFail:${auth.uuid}`, RULES.hostingCodeFailUser],
    [`hostingCodeFailIp:${ip}`, RULES.hostingCodeFailIp],
  ] as const
  if ('code' in body) {
    for (const [key, rule] of fails) {
      const r = ctx.limiter.check(key, rule)
      if (!r.ok) throw tooMany(r.retryAfter)
    }
  }
  try {
    const result = join(ctx, auth.user, body)
    setResponseStatus(event, result.status === 'accepted' ? 200 : 202)
    return result
  } catch (err) {
    if (err instanceof UnknownCode) for (const [key, rule] of fails) ctx.limiter.take(key, rule)
    throw err
  }
})
