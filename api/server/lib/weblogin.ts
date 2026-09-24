import { randomBytes } from 'node:crypto'
import type { AuthedUser } from './auth'
import type { AppContext } from './context'
import { one, run, tx } from './db'
import { ApiError, forbidden, unauthorized } from './errors'
import { safeEqual, sha256Hex } from './ids'
import { getUser, isAdmin, isBanned } from './users'

// Admin-Login der Website ohne Passwort: Die Website fordert einen kurzen Code an und zeigt
// ihn an; der Admin bestätigt ihn im TRS Launcher (der mit seinem Minecraft-Konto bei der API
// angemeldet ist). Danach holt die Website mit ihrem geheimen Abfrage-Schlüssel eine Sitzung ab
// – als httpOnly-Cookie, dazu ein CSRF-Token für ändernde Anfragen.

/** Gültigkeit eines Codes. */
export const WEB_LOGIN_TTL_MS = 5 * 60 * 1000
/** Gültigkeit einer Website-Sitzung. */
export const WEB_SESSION_TTL_MS = 8 * 60 * 60 * 1000
export const WEB_SESSION_COOKIE = 'trs_admin'

/** Crockford-Base32 ohne I, L, O, U – gut abzutippen. */
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'

/** Code wie „7K3P-QX9M“ (8 Zeichen, 40 Bit – kurzlebig, nur mit Bestätigung im Launcher nutzbar). */
export function newWebLoginCode(): string {
  const bytes = randomBytes(8)
  let out = ''
  for (let i = 0; i < 8; i++) out += ALPHABET[bytes[i]! & 31]
  return `${out.slice(0, 4)}-${out.slice(4)}`
}

/** Eingabe normalisieren: Groß, ohne Trenner/Leerzeichen, O→0, I/L→1. `null`, wenn es kein Code sein kann. */
export function normalizeWebLoginCode(input: string): string | null {
  const s = input.toUpperCase().replace(/[\s-]/g, '').replace(/O/g, '0').replace(/[IL]/g, '1')
  if (!/^[0-9A-HJKMNP-TV-Z]{8}$/.test(s)) return null
  return `${s.slice(0, 4)}-${s.slice(4)}`
}

export interface WebLoginStart {
  code: string
  /** Geheimer Schlüssel nur für die Website, mit dem sie den Status abfragt. */
  pollSecret: string
  expiresAt: string
}

export function startWebLogin(ctx: AppContext): WebLoginStart {
  const t = ctx.now()
  const code = newWebLoginCode()
  const pollSecret = randomBytes(32).toString('base64url')
  run(
    ctx.db,
    'INSERT INTO web_logins (code_hash, poll_hash, created_at, expires_at, approved_uuid) VALUES (?, ?, ?, ?, NULL)',
    sha256Hex(code), sha256Hex(pollSecret), t, t + WEB_LOGIN_TTL_MS,
  )
  return { code, pollSecret, expiresAt: new Date(t + WEB_LOGIN_TTL_MS).toISOString() }
}

/** Im Launcher bestätigt: nur Admins, nur gültige und noch offene Codes. */
export function approveWebLogin(ctx: AppContext, auth: AuthedUser, rawCode: string): void {
  if (!auth.admin) throw forbidden('not_admin', 'Only admins can sign in to the website')
  const code = normalizeWebLoginCode(rawCode)
  if (!code) throw new ApiError(400, 'invalid_code', 'This is not a valid code')
  const row = one<{ expires_at: number, approved_uuid: string | null }>(
    ctx.db, 'SELECT expires_at, approved_uuid FROM web_logins WHERE code_hash = ?', sha256Hex(code),
  )
  if (!row || row.approved_uuid) throw new ApiError(404, 'invalid_code', 'Unknown or already used code')
  if (row.expires_at <= ctx.now()) throw new ApiError(410, 'expired', 'The code has expired')
  run(ctx.db, 'UPDATE web_logins SET approved_uuid = ? WHERE code_hash = ? AND approved_uuid IS NULL', auth.uuid, sha256Hex(code))
}

export type WebLoginPoll =
  | { status: 'pending' | 'expired' }
  | { status: 'approved', token: string, csrf: string, expiresAt: string, name: string }

/** Abfrage durch die Website. Bei Bestätigung: Code verbrauchen, Sitzung anlegen (einmalig). */
export function pollWebLogin(ctx: AppContext, pollSecret: string): WebLoginPoll {
  if (!/^[A-Za-z0-9_-]{43}$/.test(pollSecret)) throw new ApiError(400, 'invalid_request', 'Invalid poll secret')
  const t = ctx.now()
  return tx(ctx.db, () => {
    const row = one<{ code_hash: string, expires_at: number, approved_uuid: string | null }>(
      ctx.db, 'SELECT code_hash, expires_at, approved_uuid FROM web_logins WHERE poll_hash = ?', sha256Hex(pollSecret),
    )
    if (!row || row.expires_at <= t) return { status: 'expired' }
    if (!row.approved_uuid) return { status: 'pending' }
    run(ctx.db, 'DELETE FROM web_logins WHERE code_hash = ?', row.code_hash)
    const uuid = row.approved_uuid
    // Nochmals prüfen: Admin geblieben, nicht gesperrt?
    if (!isAdmin(ctx, uuid) || isBanned(ctx, uuid)) return { status: 'expired' }
    const token = randomBytes(32).toString('base64url')
    const csrf = randomBytes(24).toString('base64url')
    const expires = t + WEB_SESSION_TTL_MS
    run(
      ctx.db,
      'INSERT INTO web_sessions (token_hash, uuid, csrf, created_at, expires_at) VALUES (?, ?, ?, ?, ?)',
      sha256Hex(token), uuid, csrf, t, expires,
    )
    return { status: 'approved', token, csrf, expiresAt: new Date(expires).toISOString(), name: getUser(ctx, uuid)?.name ?? '' }
  })
}

export interface WebSession {
  uuid: string
  name: string
  csrf: string
  tokenHash: string
}

/** Sitzung aus dem Cookie; ändernde Anfragen brauchen zusätzlich das passende CSRF-Token. */
export function webSession(ctx: AppContext, token: string | undefined, csrf: string | undefined, mutating: boolean): WebSession {
  if (!token || !/^[A-Za-z0-9_-]{43}$/.test(token)) throw unauthorized()
  const hash = sha256Hex(token)
  const s = one<{ uuid: string, csrf: string }>(
    ctx.db, 'SELECT uuid, csrf FROM web_sessions WHERE token_hash = ? AND expires_at > ?', hash, ctx.now(),
  )
  if (!s) throw unauthorized('Session expired')
  if (!isAdmin(ctx, s.uuid) || isBanned(ctx, s.uuid)) throw forbidden('forbidden', 'Admin only')
  if (mutating && (!csrf || !safeEqual(csrf, s.csrf))) throw forbidden('csrf_failed', 'Missing or invalid CSRF token')
  return { uuid: s.uuid, name: getUser(ctx, s.uuid)?.name ?? '', csrf: s.csrf, tokenHash: hash }
}

export function endWebSession(ctx: AppContext, tokenHash: string): void {
  run(ctx.db, 'DELETE FROM web_sessions WHERE token_hash = ?', tokenHash)
}

export function sweepWebLogins(ctx: AppContext): void {
  const t = ctx.now()
  run(ctx.db, 'DELETE FROM web_logins WHERE expires_at <= ?', t)
  run(ctx.db, 'DELETE FROM web_sessions WHERE expires_at <= ?', t)
}
