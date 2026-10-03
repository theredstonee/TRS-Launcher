import { defineEventHandler, setResponseHeader } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { isApiError, notFound, tooMany } from '../../../../lib/errors'
import { clientIp, limit, readJson } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'
import { confirmPairCode, type PeerView } from '../../../../lib/remote'
import { requireDevice } from '../../../../lib/remote-http'

const body = z.strictObject({ code: z.string().max(80) })

/**
 * Handy: Kopplungs-Code des PCs einlösen (§33.2) – eingetippt oder aus dem QR-Code. Beide Geräte müssen zum selben
 * TRS-Konto gehören. Falsche Codes zählen streng (je Konto und je IP), damit sich Codes nicht durchprobieren lassen.
 */
export default defineEventHandler(async (event) => {
  const { auth, device } = requireDevice(event, 'write', 'phone')
  limit(`remotePair:${auth.uuid}`, RULES.remotePairUser)
  const { code } = await readJson(event, body)
  const ctx = useCtx()
  setResponseHeader(event, 'Cache-Control', 'no-store')
  const ip = clientIp(event)
  const keys = [[`remotePairFail:${auth.uuid}`, RULES.remotePairFailUser], [`remotePairFail-ip:${ip}`, RULES.remotePairFailIp]] as const
  for (const [key, rule] of keys) {
    const r = ctx.limiter.check(key, rule)
    if (!r.ok) throw tooMany(r.retryAfter)
  }
  const fail = () => {
    for (const [key, rule] of keys) ctx.limiter.take(key, rule)
  }
  let desktop: PeerView | null
  try {
    desktop = confirmPairCode(ctx, device, code)
  } catch (e) {
    if (isApiError(e) && (e.code === 'remote_wrong_account' || e.code === 'invalid_code')) fail()
    throw e
  }
  if (!desktop) {
    fail()
    throw notFound('remote_code_expired', 'No open pairing code like this – check it or create a new one on the PC')
  }
  return { desktop }
})
