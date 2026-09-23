import { defineEventHandler } from 'h3'
import { redeemCode } from '../../../lib/codes'
import { useCtx } from '../../../lib/context'
import { isApiError, tooMany } from '../../../lib/errors'
import { clientIp, limit, readJson, requireUser } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { redeemBody } from '../../../lib/schemas'

/**
 * Code einlösen. Brute-Force-Schutz: höchstens 5 Fehlversuche je Konto und 20
 * je IP in 15 Minuten; danach 429, auch für richtige Codes.
 */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const ctx = useCtx()
  const ip = clientIp(event)
  limit(`redeem:${auth.uuid}`, RULES.redeemUser)
  for (const [key, rule] of [[`redeem-fail:${auth.uuid}`, RULES.redeemFailUser], [`redeem-fail-ip:${ip}`, RULES.redeemFailIp]] as const) {
    const r = ctx.limiter.check(key, rule)
    if (!r.ok) throw tooMany(r.retryAfter)
  }
  const countFailure = () => {
    ctx.limiter.take(`redeem-fail:${auth.uuid}`, RULES.redeemFailUser)
    ctx.limiter.take(`redeem-fail-ip:${ip}`, RULES.redeemFailIp)
  }
  let code: string
  try {
    code = (await readJson(event, redeemBody)).code
  } catch (err) {
    countFailure()
    throw err
  }
  try {
    return redeemCode(ctx, auth.uuid, code)
  } catch (err) {
    if (isApiError(err) && err.status < 500) countFailure()
    throw err
  }
})
