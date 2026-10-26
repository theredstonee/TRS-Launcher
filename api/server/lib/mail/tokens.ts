import { randomBytes } from 'node:crypto'
import type { AppContext } from '../context'
import { hmacHex, safeEqual, sha256Hex } from '../ids'

/**
 * Tokens der Mails:
 * - Bestätigung (Double-Opt-in, Team-Adresse): Zufall, in der DB nur SHA-256, 48 h gültig.
 * - Abmelden: `<id>.<HMAC(SECRET_KEY, id:nonce)>` – nicht gespeichert, gilt bis zur nächsten Anmeldung (neuer Nonce).
 */

export const CONFIRM_TTL_MS = 48 * 60 * 60 * 1000
/** `c…` = Newsletter, `t…` = Team-Adresse. */
export const CONFIRM_TOKEN = /^[ct][A-Za-z0-9_-]{43}$/
export const UNSUB_TOKEN = /^(\d{1,12})\.([0-9a-f]{40})$/

export function newConfirmToken(prefix: 'c' | 't' = 'c'): { token: string, hash: string } {
  const token = `${prefix}${randomBytes(32).toString('base64url')}`
  return { token, hash: sha256Hex(token) }
}

export const hashToken = (token: string) => sha256Hex(token)

export function newNonce(): string {
  return randomBytes(12).toString('base64url')
}

export function unsubscribeToken(ctx: AppContext, id: number, nonce: string): string {
  return `${id}.${hmacHex(ctx.config.secretKey, `unsub:${id}:${nonce}`).slice(0, 40)}`
}

/** Abmelde-Token prüfen → Abonnenten-ID oder `null`. `nonceOf` liest den aktuellen Nonce. */
export function verifyUnsubscribe(ctx: AppContext, token: string, nonceOf: (id: number) => string | null): number | null {
  const m = UNSUB_TOKEN.exec(token)
  if (!m) return null
  const id = Number(m[1])
  const nonce = nonceOf(id)
  if (nonce === null) return null
  return safeEqual(unsubscribeToken(ctx, id, nonce), token) ? id : null
}

export function unsubscribeUrl(ctx: AppContext, id: number, nonce: string): string {
  return `${ctx.config.siteUrl}/newsletter/unsubscribe?token=${unsubscribeToken(ctx, id, nonce)}`
}

/** `steve@example.com` → `s***e@example.com` (für Seiten nach dem Klick). */
export function maskEmail(email: string): string {
  const [local = '', domain = ''] = email.split('@')
  const head = local.length <= 2 ? local.slice(0, 1) : `${local[0]}${'*'.repeat(Math.min(5, local.length - 2))}${local[local.length - 1]}`
  return `${head}@${domain}`
}
