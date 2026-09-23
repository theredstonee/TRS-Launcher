import type { H3Event } from 'h3'
import { redeemCode, type RedeemResult } from './codes'
import { useCtx } from './context'
import { isApiError, tooMany } from './errors'
import { clientIp, limit, readJson, requireUser } from './http'
import { RULES } from './ratelimit'
import { redeemBody } from './schemas'

/**
 * Code einlösen (Umhang oder Kosmetik/Emote). Brute-Force-Schutz: höchstens 5
 * Fehlversuche je Konto und 20 je IP in 15 Minuten; danach 429, auch für
 * richtige Codes. `POST /v1/redeem` und `POST /v1/capes/redeem` teilen sich die Limits.
 */
export async function handleRedeem(event: H3Event): Promise<RedeemResult> {
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
}
