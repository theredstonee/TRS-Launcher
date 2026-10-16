import type { H3Event } from 'h3'
import { touchAchievements } from './achievements'
import { redeemCode, type RedeemResult } from './codes'
import { redeemCreatorCode, type RedeemCreatorResult } from './creators'
import { useCtx } from './context'
import { isApiError, tooMany } from './errors'
import { clientIp, limit, readJson, requireUser } from './http'
import { RULES } from './ratelimit'
import { redeemBody } from './schemas'
import { z } from 'zod'

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
    const result = redeemCode(ctx, auth.uuid, code)
    // Erfolge (§31): Code eingelöst, ggf. die Quietscheente gefunden.
    touchAchievements(ctx, auth.uuid, ['codes_redeemed', 'duck_flag'])
    return result
  } catch (err) {
    if (isApiError(err) && err.status < 500) countFailure()
    throw err
  }
}

/**
 * Creator-Code einlösen (`POST /v1/me/creator/redeem`, §35.2) – gleiche Sperre wie oben (dieselben Töpfe): nach 5
 * Fehlversuchen je Konto bzw. 20 je IP in 15 Minuten 429. Als Fehlversuch zählen unbekannte Codes (404) und kaputte
 * Eingaben (400), nicht 409/410 (blockiert, abgelaufen).
 */
export async function handleCreatorRedeem(event: H3Event): Promise<RedeemCreatorResult> {
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
  try {
    const body = await readJson(event, z.unknown())
    return redeemCreatorCode(ctx, auth.uuid, body)
  } catch (err) {
    if (isApiError(err) && (err.status === 400 || err.status === 404)) countFailure()
    throw err
  }
}
