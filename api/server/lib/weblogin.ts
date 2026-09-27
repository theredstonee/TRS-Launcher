import { randomBytes } from 'node:crypto'
import type { AppContext } from './context'
import { one, run, tx } from './db'
import { forbidden, unauthorized } from './errors'
import { safeEqual, sha256Hex } from './ids'
import { getUser, isBanned } from './users'

// Website-Sitzungen (§23.1): nach der Anmeldung mit Microsoft bekommt der Browser ein httpOnly-Cookie
// (SameSite=Strict) mit einem zufälligen Token; gespeichert wird nur dessen SHA-256. Ändernde Anfragen
// schicken zusätzlich das CSRF-Token im Header `X-CSRF-Token` (Double-Submit gegen die Sitzung).
// Team-Rechte hängen NICHT an der Sitzung – jede Anfrage liest sie neu (Entzug wirkt sofort).

/** Gültigkeit einer Website-Sitzung. */
export const WEB_SESSION_TTL_MS = 8 * 60 * 60 * 1000
export const WEB_SESSION_COOKIE = 'trs_session'
/** Altes Cookie der Code-Anmeldung (wird beim Anmelden gelöscht). */
export const LEGACY_WEB_SESSION_COOKIE = 'trs_admin'
/** Höchstens so viele Website-Sitzungen je Konto (älteste fallen weg). */
export const MAX_WEB_SESSIONS = 5

const TOKEN = /^[A-Za-z0-9_-]{43}$/

export interface NewWebSession {
  token: string
  csrf: string
  expiresAt: string
}

/**
 * Neue Sitzung nach erfolgreicher Anmeldung. `previousToken` (Cookie vor der Anmeldung) wird verworfen –
 * Session-Rotation gegen Session-Fixation.
 */
export function createWebSession(ctx: AppContext, uuid: string, previousToken?: string): NewWebSession {
  const t = ctx.now()
  const token = randomBytes(32).toString('base64url')
  const csrf = randomBytes(24).toString('base64url')
  const expires = t + WEB_SESSION_TTL_MS
  tx(ctx.db, () => {
    if (previousToken && TOKEN.test(previousToken)) run(ctx.db, 'DELETE FROM web_sessions WHERE token_hash = ?', sha256Hex(previousToken))
    run(
      ctx.db,
      'INSERT INTO web_sessions (token_hash, uuid, csrf, created_at, expires_at) VALUES (?, ?, ?, ?, ?)',
      sha256Hex(token), uuid, csrf, t, expires,
    )
    run(
      ctx.db,
      `DELETE FROM web_sessions WHERE uuid = ? AND token_hash NOT IN (
         SELECT token_hash FROM web_sessions WHERE uuid = ? ORDER BY created_at DESC LIMIT ?)`,
      uuid, uuid, MAX_WEB_SESSIONS,
    )
  })
  return { token, csrf, expiresAt: new Date(expires).toISOString() }
}

export interface WebSession {
  uuid: string
  name: string
  csrf: string
  tokenHash: string
  expiresAt: string
}

/** Sitzung aus dem Cookie; ändernde Anfragen brauchen zusätzlich das passende CSRF-Token. */
export function webSession(ctx: AppContext, token: string | undefined, csrf: string | undefined, mutating: boolean): WebSession {
  if (!token || !TOKEN.test(token)) throw unauthorized()
  const hash = sha256Hex(token)
  const s = one<{ uuid: string, csrf: string, expires_at: number }>(
    ctx.db, 'SELECT uuid, csrf, expires_at FROM web_sessions WHERE token_hash = ? AND expires_at > ?', hash, ctx.now(),
  )
  if (!s) throw unauthorized('Session expired')
  if (isBanned(ctx, s.uuid)) {
    run(ctx.db, 'DELETE FROM web_sessions WHERE uuid = ?', s.uuid)
    throw forbidden('banned', 'This account is banned')
  }
  if (mutating && (!csrf || !safeEqual(csrf, s.csrf))) throw forbidden('csrf_failed', 'Missing or invalid CSRF token')
  return { uuid: s.uuid, name: getUser(ctx, s.uuid)?.name ?? '', csrf: s.csrf, tokenHash: hash, expiresAt: new Date(s.expires_at).toISOString() }
}

export function endWebSession(ctx: AppContext, tokenHash: string): void {
  run(ctx.db, 'DELETE FROM web_sessions WHERE token_hash = ?', tokenHash)
}

export function sweepWebLogins(ctx: AppContext): void {
  run(ctx.db, 'DELETE FROM web_sessions WHERE expires_at <= ?', ctx.now())
  ctx.oauth.sweep()
}
