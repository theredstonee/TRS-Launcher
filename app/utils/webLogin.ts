import { z } from 'zod'

// Anmeldung auf der Website per TRS Launcher (API §29): Schemas für die gesäuberten Antworten des Kerns und reine
// Helfer (Code-Eingabe, Link). Bestätigt wird nur im WebLoginDialog nach einem Klick – nie automatisch.

/** Zeichen der Bestätigungscodes (ohne 0/O, 1/I/L, U – wie Server und Kern). */
export const WEB_LOGIN_CODE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTVWXYZ'

export const webLoginRequestSchema = z.object({
  id: z.string().regex(/^[A-Za-z0-9_-]{22}$/),
  code: z.string().regex(/^[2-9A-HJKMNP-TV-Z]{3}-[2-9A-HJKMNP-TV-Z]{3}$/),
  site: z.string().min(1).max(100).regex(/^[a-z0-9.:-]+$/),
  browser: z.string().max(40).nullable(),
  createdAt: z.string().max(40).nullable(),
  expiresAt: z.string().max(40).nullable(),
})

export const webLoginAccountSchema = z.object({
  id: z.string().regex(/^[0-9a-f]{32}$/),
  name: z.string().max(32),
  skinUrl: z.string().max(300).nullable(),
  active: z.boolean(),
})

export type WebLoginRequest = z.infer<typeof webLoginRequestSchema>
export type WebLoginAccount = z.infer<typeof webLoginAccountSchema>

/** Eingabe → `XXX-XXX` (Groß/klein egal, mit oder ohne Strich/Leerzeichen), sonst `null`. */
export function normalizeWebLoginCode(input: string): string | null {
  if (input.length > 20) return null
  const c = input.trim().toUpperCase().replace(/[\s-]/g, '')
  if (c.length !== 6 || [...c].some((ch) => !WEB_LOGIN_CODE_ALPHABET.includes(ch))) return null
  return `${c.slice(0, 3)}-${c.slice(3)}`
}

/** Link-Token (43 Zeichen base64url) – nur solche kommen vom Kern. */
export function isWebLoginToken(token: unknown): token is string {
  return typeof token === 'string' && /^[A-Za-z0-9_-]{43}$/.test(token)
}

/** Restzeit in Sekunden bis `expiresAt` (0, wenn vorbei oder unbekannt). */
export function secondsLeft(expiresAt: string | null, now = Date.now()): number {
  const t = expiresAt ? Date.parse(expiresAt) : Number.NaN
  return Number.isFinite(t) ? Math.max(0, Math.ceil((t - now) / 1000)) : 0
}
