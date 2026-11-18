import { createHash, createHmac, timingSafeEqual } from 'node:crypto'
import { getHeader, type H3Event } from 'h3'
import { useCtx } from './context'
import { forbidden, unavailable } from './errors'
import { readLimited } from './http'

const SKEW_S = 60

/**
 * Anfragen von TRS Wake (Relay): HMAC-SHA256 mit RELAY_SECRET über Zeit, Methode, Pfad samt Query und Body-Hash
 * (relay/WAKE.md). Gibt den rohen Body zurück.
 */
export async function requireRelay(event: H3Event): Promise<Buffer> {
  const secrets = useCtx().config.hosting?.relaySecrets
  if (!secrets?.length) throw unavailable('hosting_unavailable', 'Relay not configured')
  const time = getHeader(event, 'x-relay-time') ?? ''
  const sig = getHeader(event, 'x-relay-sig') ?? ''
  const body = await readLimited(event, 4096)
  const t = Number(time)
  if (!/^\d{1,12}$/.test(time) || Math.abs(Date.now() / 1000 - t) > SKEW_S || !/^[0-9a-f]{64}$/.test(sig)) throw forbidden('relay_auth')
  const msg = `${time}\n${event.method}\n${event.path}\n${createHash('sha256').update(body).digest('hex')}`
  const got = Buffer.from(sig, 'hex')
  for (const secret of secrets) {
    const want = createHmac('sha256', secret).update(msg).digest()
    if (want.length === got.length && timingSafeEqual(want, got)) return body
  }
  throw forbidden('relay_auth')
}
