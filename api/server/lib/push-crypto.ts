import { createCipheriv, createECDH, createPrivateKey, hkdfSync, randomBytes, sign, type KeyObject } from 'node:crypto'

/**
 * Web Push ohne Fremdbibliothek (node:crypto): Verschlüsselung nach RFC 8291 (`aes128gcm`, RFC 8188) und
 * VAPID-Anmeldung nach RFC 8292 (ES256-JWT). Geprüft mit dem Testvektor aus RFC 8291 Anhang A.
 */

const B64U = /^[A-Za-z0-9_-]*={0,2}$/

/** base64url (mit oder ohne `=`) → Bytes; `null` bei ungültigen Zeichen. */
export function b64uDecode(s: string): Buffer | null {
  if (!B64U.test(s)) return null
  return Buffer.from(s.replace(/=+$/, ''), 'base64url')
}

export const b64u = (b: Buffer | string): string => Buffer.from(b).toString('base64url')

const probe = createECDH('prime256v1')
probe.generateKeys()

/** Unkomprimierter P-256-Punkt (65 Byte, 0x04 …), der wirklich auf der Kurve liegt (RFC 8291 §7). */
export function isP256Point(buf: Buffer): boolean {
  if (buf.length !== 65 || buf[0] !== 0x04) return false
  try {
    probe.computeSecret(buf)
    return true
  } catch {
    return false
  }
}

export interface EncryptOptions {
  /** Nur für Tests (Testvektor): fester Salt (16 Byte). */
  salt?: Buffer
  /** Nur für Tests (Testvektor): fester Server-Schlüssel (32 Byte). Sonst je Nachricht neu. */
  asPrivateKey?: Buffer
  /** Auffüllen auf ein Vielfaches davon (verschleiert die Länge); 0 = nicht auffüllen. */
  padBlock?: number
}

/** Größte Nachricht, die Push-Dienste sicher annehmen (4096 Byte inkl. Kopf und Tag). */
export const MAX_PUSH_BODY = 4096
const RECORD_SIZE = 4096
const HEADER = 16 + 4 + 1 + 65
const TAG = 16

/**
 * Verschlüsselt `plaintext` für einen Empfänger (`uaPublic` = p256dh, `authSecret` = auth) als EINEN
 * `aes128gcm`-Datensatz. Ergebnis: Kopf (Salt, Datensatzgröße, Server-Schlüssel) + Chiffre + Tag.
 */
export function encryptWebPush(plaintext: Buffer, uaPublic: Buffer, authSecret: Buffer, opts: EncryptOptions = {}): Buffer {
  const ecdh = createECDH('prime256v1')
  if (opts.asPrivateKey) ecdh.setPrivateKey(opts.asPrivateKey)
  else ecdh.generateKeys()
  const asPublic = ecdh.getPublicKey()
  const ecdhSecret = ecdh.computeSecret(uaPublic)
  // RFC 8291 §3.4: IKM = HKDF(auth_secret, ecdh_secret, "WebPush: info" || 0x00 || ua_public || as_public, 32)
  const keyInfo = Buffer.concat([Buffer.from('WebPush: info\0', 'latin1'), uaPublic, asPublic])
  const ikm = Buffer.from(hkdfSync('sha256', ecdhSecret, authSecret, keyInfo, 32))
  const salt = opts.salt ?? randomBytes(16)
  const cek = Buffer.from(hkdfSync('sha256', ikm, salt, Buffer.from('Content-Encoding: aes128gcm\0', 'latin1'), 16))
  const nonce = Buffer.from(hkdfSync('sha256', ikm, salt, Buffer.from('Content-Encoding: nonce\0', 'latin1'), 12))
  const max = MAX_PUSH_BODY - HEADER - TAG
  const block = opts.padBlock ?? 64
  let size = plaintext.length + 1
  if (block > 0) size = Math.min(max, Math.ceil(size / block) * block)
  if (plaintext.length + 1 > max) throw new Error('push payload too large')
  // Letzter (einziger) Datensatz: Daten || 0x02 || Nullen.
  const record = Buffer.alloc(size)
  plaintext.copy(record, 0)
  record[plaintext.length] = 0x02
  const cipher = createCipheriv('aes-128-gcm', cek, nonce)
  const body = Buffer.concat([cipher.update(record), cipher.final(), cipher.getAuthTag()])
  const header = Buffer.alloc(21)
  salt.copy(header, 0)
  header.writeUInt32BE(RECORD_SIZE, 16)
  header[20] = asPublic.length
  return Buffer.concat([header, asPublic, body])
}

export interface VapidKeys {
  /** Öffentlicher Schlüssel als base64url (65 Byte unkomprimiert) – geht so an die App/den Verteiler. */
  publicKey: string
  privateKey: KeyObject
  /** `mailto:…` oder `https://…` (Kontakt für den Push-Dienst). */
  subject: string
}

/** Schlüsselpaar aus `.env` lesen und prüfen (privater passt zum öffentlichen). `null` = ungültig. */
export function parseVapidKeys(publicB64u: string, privateB64u: string): { publicKey: string, privateKey: KeyObject } | null {
  const pub = b64uDecode(publicB64u.trim())
  const priv = b64uDecode(privateB64u.trim())
  if (!pub || !priv || priv.length !== 32 || !isP256Point(pub)) return null
  try {
    const ecdh = createECDH('prime256v1')
    ecdh.setPrivateKey(priv)
    if (!ecdh.getPublicKey().equals(pub)) return null
    const privateKey = createPrivateKey({
      key: { kty: 'EC', crv: 'P-256', d: b64u(priv), x: b64u(pub.subarray(1, 33)), y: b64u(pub.subarray(33)) },
      format: 'jwk',
    })
    return { publicKey: b64u(pub), privateKey }
  } catch {
    return null
  }
}

/** Neues VAPID-Schlüsselpaar (base64url), z. B. für `scripts/vapid-keys.mjs`. */
export function generateVapidKeys(): { publicKey: string, privateKey: string } {
  const ecdh = createECDH('prime256v1')
  ecdh.generateKeys()
  const priv = ecdh.getPrivateKey()
  return { publicKey: b64u(ecdh.getPublicKey()), privateKey: b64u(Buffer.concat([Buffer.alloc(32 - priv.length), priv])) }
}

/** JWT gilt 12 h (RFC 8292: höchstens 24 h); nach 11 h wird ein neues signiert. */
const JWT_TTL_S = 12 * 3600
const JWT_REFRESH_S = 11 * 3600

/** Signiert die `Authorization: vapid t=…, k=…`-Kopfzeile je Push-Dienst (Origin des Endpunkts), mit Cache. */
export class VapidSigner {
  private cache = new Map<string, { header: string, until: number }>()
  constructor(
    private readonly keys: VapidKeys,
    private readonly now: () => number = Date.now,
  ) {}

  header(endpoint: string): string {
    const aud = new URL(endpoint).origin
    const t = Math.floor(this.now() / 1000)
    const hit = this.cache.get(aud)
    if (hit && hit.until > t) return hit.header
    const head = b64u(JSON.stringify({ typ: 'JWT', alg: 'ES256' }))
    const claims = b64u(JSON.stringify({ aud, exp: t + JWT_TTL_S, sub: this.keys.subject }))
    const input = `${head}.${claims}`
    const sig = sign('sha256', Buffer.from(input), { key: this.keys.privateKey, dsaEncoding: 'ieee-p1363' })
    const header = `vapid t=${input}.${b64u(sig)}, k=${this.keys.publicKey}`
    if (this.cache.size > 1000) this.cache.clear()
    this.cache.set(aud, { header, until: t + JWT_REFRESH_S })
    return header
  }
}
