import { createHmac, timingSafeEqual } from 'node:crypto'

/**
 * Relay-Token der TRS API: `trsr1.<payloadB64url>.<sigB64url>`,
 * sig = base64url(HMAC-SHA256(secret, "trsr1." + payloadB64url)).
 */
export interface RelayToken {
  v: 1
  /** Raum-ID der API. */
  r: string
  /** UUID des Inhabers. */
  u: string
  /** UUID des Hosts. */
  h: string
  role: 'host' | 'guest'
  /** Max. Spieler inkl. Host (2–10). */
  m: number
  iat: number
  exp: number
  n: string
}

export type TokenResult =
  | { ok: true, token: RelayToken }
  | { ok: false, code: 'bad_token' | 'expired' }

export const TOKEN_PREFIX = 'trsr1'
export const MAX_TOKEN_LENGTH = 512
/** Höchste Gültigkeit (exp − iat) in Sekunden. */
export const MAX_TOKEN_LIFETIME_SEC = 130

const KEYS = ['v', 'r', 'u', 'h', 'role', 'm', 'iat', 'exp', 'n'] as const
const ROOM_ID = /^h[0-9a-f]{20}$/
const UUID = /^[0-9a-f]{32}$/
const NONCE = /^[0-9a-f]{16}$/
const B64URL = /^[A-Za-z0-9_-]+$/

function sign(secret: string, payloadB64: string): Buffer {
  return createHmac('sha256', secret).update(`${TOKEN_PREFIX}.${payloadB64}`).digest()
}

/** Nur für Tests und Werkzeuge – im Betrieb signiert die API. */
export function signToken(payload: RelayToken, secret: string): string {
  const p = Buffer.from(JSON.stringify(payload)).toString('base64url')
  return `${TOKEN_PREFIX}.${p}.${sign(secret, p).toString('base64url')}`
}

const bad = { ok: false, code: 'bad_token' } as const

function validPayload(x: unknown): x is RelayToken {
  if (typeof x !== 'object' || x === null || Array.isArray(x)) return false
  const o = x as Record<string, unknown>
  const keys = Object.keys(o)
  if (keys.length !== KEYS.length || !KEYS.every((k) => Object.hasOwn(o, k))) return false
  const isInt = (v: unknown) => typeof v === 'number' && Number.isSafeInteger(v)
  return o.v === 1
    && typeof o.r === 'string' && ROOM_ID.test(o.r)
    && typeof o.u === 'string' && UUID.test(o.u)
    && typeof o.h === 'string' && UUID.test(o.h)
    && (o.role === 'host' || o.role === 'guest')
    && isInt(o.m) && (o.m as number) >= 2 && (o.m as number) <= 10
    && isInt(o.iat) && isInt(o.exp)
    && typeof o.n === 'string' && NONCE.test(o.n)
}

/**
 * Prüft Signatur (gegen jeden Schlüssel, zeitkonstant), Aufbau und Zeiten.
 * `nowSec` in Unix-Sekunden.
 */
export function verifyToken(raw: string, secrets: readonly string[], nowSec: number, skewSec = 5): TokenResult {
  if (raw.length > MAX_TOKEN_LENGTH) return bad
  const parts = raw.split('.')
  if (parts.length !== 3 || parts[0] !== TOKEN_PREFIX) return bad
  const [, p, s] = parts as [string, string, string]
  if (!B64URL.test(p) || s.length !== 43 || !B64URL.test(s)) return bad
  const sig = Buffer.from(s, 'base64url')
  // Nur kanonische Kodierung (sonst gäbe es mehrere gültige Schreibweisen).
  if (sig.length !== 32 || sig.toString('base64url') !== s) return bad
  let match = false
  for (const secret of secrets) {
    if (timingSafeEqual(sign(secret, p), sig)) match = true
  }
  if (!match) return bad
  let payload: unknown
  try {
    payload = JSON.parse(Buffer.from(p, 'base64url').toString('utf8'))
  } catch {
    return bad
  }
  if (!validPayload(payload)) return bad
  const t = payload
  if (t.role === 'host' ? t.u !== t.h : t.u === t.h) return bad
  if (t.exp <= t.iat || t.exp - t.iat > MAX_TOKEN_LIFETIME_SEC) return bad
  if (t.iat > nowSec + skewSec) return bad
  if (t.exp + skewSec <= nowSec) return { ok: false, code: 'expired' }
  return { ok: true, token: t }
}
