import { createHash, createHmac, randomBytes, timingSafeEqual } from 'node:crypto'

const UUID_PLAIN = /^[0-9a-f]{32}$/
const UUID_DASHED = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

/** Minecraft-UUID (mit oder ohne Bindestriche) → 32 Hex-Zeichen klein; sonst `null`. */
export function normalizeUuid(input: string): string | null {
  const s = input.trim().toLowerCase()
  if (UUID_PLAIN.test(s)) return s
  if (UUID_DASHED.test(s)) return s.replaceAll('-', '')
  return null
}

export function dashUuid(uuid: string): string {
  return `${uuid.slice(0, 8)}-${uuid.slice(8, 12)}-${uuid.slice(12, 16)}-${uuid.slice(16, 20)}-${uuid.slice(20)}`
}

export function sha256Hex(data: string | Buffer): string {
  return createHash('sha256').update(data).digest('hex')
}

export function hmacHex(key: string, data: string): string {
  return createHmac('sha256', key).update(data).digest('hex')
}

/** Zeitkonstanter Vergleich beliebig langer Strings (vergleicht SHA-256-Hashes gleicher Länge). */
export function safeEqual(a: string, b: string): boolean {
  const ha = createHash('sha256').update(a).digest()
  const hb = createHash('sha256').update(b).digest()
  return timingSafeEqual(ha, hb) && a.length === b.length
}

/** Opaker Bearer-Token: `trs_` + 32 Zufallsbytes (base64url, 43 Zeichen). */
export function newSessionToken(): string {
  return `trs_${randomBytes(32).toString('base64url')}`
}

export const SESSION_TOKEN = /^trs_[A-Za-z0-9_-]{43}$/

/** Challenge für den Mojang-Join: 20 Zufallsbytes als 40 Hex-Zeichen (sieht aus wie ein SHA-1-Server-Hash). */
export function newServerId(): string {
  return randomBytes(20).toString('hex')
}

export const SERVER_ID = /^[0-9a-f]{40}$/

/** Crockford-Base32 ohne I, L, O, U. */
const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'

/** Einlösecode: 20 Zeichen Crockford-Base32 (100 Bit), angezeigt als XXXXX-XXXXX-XXXXX-XXXXX. */
export function newRedeemCode(): string {
  const bytes = randomBytes(20)
  let out = ''
  for (let i = 0; i < 20; i++) out += CROCKFORD[bytes[i]! & 31]
  return `${out.slice(0, 5)}-${out.slice(5, 10)}-${out.slice(10, 15)}-${out.slice(15)}`
}

/** Normalisiert eine Eingabe: Groß, ohne Trenner, O→0, I/L→1. `null`, wenn sie kein Code sein kann. */
export function normalizeRedeemCode(input: string): string | null {
  const s = input
    .toUpperCase()
    .replace(/[\s-]/g, '')
    .replace(/O/g, '0')
    .replace(/[IL]/g, '1')
  return /^[0-9A-HJKMNP-TV-Z]{20}$/.test(s) ? s : null
}

/** ID für hochgeladene Umhänge: `u` + 20 Hex-Zeichen. */
export function newUploadCapeId(): string {
  return `u${randomBytes(10).toString('hex')}`
}

export const CAPE_ID = /^[a-z0-9][a-z0-9_-]{0,39}$/
