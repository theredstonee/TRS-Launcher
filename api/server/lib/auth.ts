import type { AppContext } from './context'
import { one, run, tx } from './db'
import { ApiError, forbidden, tooMany, unauthorized, upstreamFailed } from './errors'
import type { Staff } from './sanctions'
import { staffRole, type StaffRole } from './users'
import { SESSION_TOKEN, newServerId, newSessionToken, safeEqual, sha256Hex } from './ids'
import { RULES } from './ratelimit'
import { MojangUnavailable } from './mojang'
import { updatePresence } from './playerevents'
import { getUser, isAdmin, upsertOnLogin, meView, type MeView, type UserRow } from './users'
import { activeSanction, sanctionError } from './sanctions'
import { sweepSyncTombstones } from './sync'
import { sweepWebLogins } from './weblogin'

export interface Challenge {
  serverId: string
  expiresAt: string
}

/** Schritt 1: einmalige serverId (60 s gültig). */
export function createChallenge(ctx: AppContext): Challenge {
  const t = ctx.now()
  const serverId = newServerId()
  const expires = t + ctx.config.limits.challengeTtlMs
  run(ctx.db, 'INSERT INTO challenges (server_id, expires_at) VALUES (?, ?)', serverId, expires)
  return { serverId, expiresAt: new Date(expires).toISOString() }
}

export interface LoginResult {
  token: string
  expiresAt: string
  user: MeView
}

/**
 * Schritt 3: Der Client ist per Mojang `join` mit der serverId beigetreten –
 * wir fragen `hasJoined` und stellen bei Erfolg einen Bearer-Token aus.
 * Die Challenge wird in jedem Fall verbraucht (kein Wiederholen).
 */
export async function verifyLogin(ctx: AppContext, username: string, serverId: string): Promise<LoginResult> {
  const t = ctx.now()
  const consumed = run(ctx.db, 'DELETE FROM challenges WHERE server_id = ? AND expires_at > ?', serverId, t)
  if (consumed === 0) throw new ApiError(401, 'invalid_challenge', 'Challenge is unknown, expired or already used')

  let profile
  try {
    profile = await ctx.mojang.hasJoined(username, serverId)
  } catch (err) {
    if (err instanceof MojangUnavailable) {
      console.warn('[auth] Mojang hasJoined failed:', err.message)
      throw upstreamFailed()
    }
    throw err
  }
  if (!profile || profile.name.toLowerCase() !== username.toLowerCase()) {
    throw new ApiError(401, 'not_joined', 'Mojang did not confirm the session join')
  }

  const perUuid = ctx.limiter.take(`login:${profile.uuid}`, RULES.loginUuid)
  if (!perUuid.ok) throw tooMany(perUuid.retryAfter)
  const ban = activeSanction(ctx, profile.uuid, 'account_ban')
  if (ban) throw bannedLogin(ctx, profile.uuid, profile.name, ban)

  const token = newSessionToken()
  const expires = ctx.now() + ctx.config.limits.sessionTtlMs
  const user = tx(ctx.db, () => {
    const u = upsertOnLogin(ctx, profile.uuid, profile.name)
    run(
      ctx.db,
      'INSERT INTO sessions (token_hash, uuid, created_at, expires_at, last_used_at) VALUES (?, ?, ?, ?, ?)',
      sha256Hex(token), u.uuid, ctx.now(), expires, ctx.now(),
    )
    // Höchstens N Sitzungen je Konto – die ältesten fallen weg.
    run(
      ctx.db,
      `DELETE FROM sessions WHERE uuid = ? AND token_hash NOT IN (
         SELECT token_hash FROM sessions WHERE uuid = ? ORDER BY created_at DESC LIMIT ?)`,
      u.uuid, u.uuid, ctx.config.limits.maxSessionsPerUser,
    )
    return u
  })
  return { token, expiresAt: new Date(expires).toISOString(), user: meView(ctx, user) }
}

/** Gültigkeit eines Einspruch-Tokens für gesperrte Konten (§22.8). */
export const APPEAL_TOKEN_TTL_MS = 60 * 60 * 1000

/**
 * Anmeldung eines gesperrten Kontos: statt einer Sitzung ein kurzlebiger Token, der NUR
 * `GET /v1/me/sanctions` und `POST /v1/me/sanctions/{id}/appeal` darf. Antwort bleibt `403 banned`
 * (wie bisher), mit Angaben zur Strafe und `appealToken`.
 */
function bannedLogin(ctx: AppContext, uuid: string, name: string, ban: Parameters<typeof sanctionError>[1]): ApiError {
  const base = sanctionError(ctx, ban)
  const token = newSessionToken()
  const t = ctx.now()
  const expires = t + APPEAL_TOKEN_TTL_MS
  tx(ctx.db, () => {
    // Konto anlegen/aktualisieren, damit der Einspruch einem Namen zugeordnet werden kann.
    upsertOnLogin(ctx, uuid, name)
    run(
      ctx.db,
      "INSERT INTO sessions (token_hash, uuid, created_at, expires_at, last_used_at, scope) VALUES (?, ?, ?, ?, ?, 'appeal')",
      sha256Hex(token), uuid, t, expires, t,
    )
    run(ctx.db, "DELETE FROM sessions WHERE uuid = ? AND scope = 'appeal' AND token_hash NOT IN (SELECT token_hash FROM sessions WHERE uuid = ? AND scope = 'appeal' ORDER BY created_at DESC LIMIT 3)", uuid, uuid)
  })
  return new ApiError(403, 'banned', base.message, { ...base.details, appealToken: token, appealTokenExpiresAt: new Date(expires).toISOString() })
}

export interface AuthedUser {
  uuid: string
  admin: boolean
  user: UserRow
  tokenHash: string
  /** `appeal` = gesperrtes Konto, nur für die Einspruch-Routen. */
  scope: 'full' | 'appeal'
}

/**
 * Prüft einen Bearer-Token. Gesperrte Konten → 403 `banned` (mit Angaben zur Strafe). `opts.appeal` = Route für
 * gesperrte Konten erlaubt (eigene Strafen, Einspruch): dann gelten auch Einspruch-Tokens.
 */
export function authenticate(ctx: AppContext, authorization: string | undefined, opts: { appeal?: boolean } = {}): AuthedUser {
  if (!authorization) throw unauthorized()
  const m = /^Bearer (\S+)$/.exec(authorization)
  if (!m || !SESSION_TOKEN.test(m[1]!)) throw unauthorized('Invalid token')
  const hash = sha256Hex(m[1]!)
  const t = ctx.now()
  const s = one<{ uuid: string, last_used_at: number, scope: 'full' | 'appeal' }>(
    ctx.db,
    'SELECT uuid, last_used_at, scope FROM sessions WHERE token_hash = ? AND expires_at > ?',
    hash, t,
  )
  if (!s) throw unauthorized('Invalid or expired token')
  if (!opts.appeal) {
    const ban = activeSanction(ctx, s.uuid, 'account_ban')
    if (ban) throw sanctionError(ctx, ban)
    // Einspruch-Token nach Ablauf des Banns: bitte neu anmelden.
    if (s.scope === 'appeal') throw unauthorized('Sign in again')
  }
  const user = getUser(ctx, s.uuid)
  if (!user) throw unauthorized('Invalid or expired token')
  // Nur grob mitschreiben (spart Schreibzugriffe).
  if (t - s.last_used_at > 10 * 60 * 1000) run(ctx.db, 'UPDATE sessions SET last_used_at = ? WHERE token_hash = ?', t, hash)
  return { uuid: s.uuid, admin: isAdmin(ctx, s.uuid), user, tokenHash: hash, scope: s.scope }
}

export function logout(ctx: AppContext, auth: AuthedUser, everywhere: boolean): void {
  if (everywhere) {
    run(ctx.db, 'DELETE FROM sessions WHERE uuid = ?', auth.uuid)
    updatePresence(ctx, auth.uuid, () => ctx.presence.delete(auth.uuid))
    ctx.events.kick(auth.uuid)
    ctx.watch.kick(auth.uuid)
  } else {
    run(ctx.db, 'DELETE FROM sessions WHERE token_hash = ?', auth.tokenHash)
  }
}

/**
 * Team-Zugriff: Sitzung eines Admins/Moderators ODER `X-Admin-Key` (zeitkonstant verglichen, gilt als Admin).
 * `need` = nötige Rolle; zu wenig → 403 `forbidden`.
 */
export function authenticateStaff(
  ctx: AppContext,
  headers: { authorization?: string, adminKey?: string },
  need: StaffRole,
): Staff {
  if (headers.adminKey !== undefined) {
    const key = ctx.config.adminApiKey
    if (key && safeEqual(headers.adminKey, key)) return { uuid: 'api-key', role: 'admin' }
    throw unauthorized('Invalid admin key')
  }
  const auth = authenticate(ctx, headers.authorization)
  const role = staffRole(ctx, auth.uuid)
  if (!role || (need === 'admin' && role !== 'admin')) throw forbidden('forbidden', need === 'admin' ? 'Admin only' : 'Team only')
  return { uuid: auth.uuid, role }
}

/** Admin-Zugriff (alte Signatur): liefert den Akteur fürs Log. */
export function authenticateAdmin(
  ctx: AppContext,
  headers: { authorization?: string, adminKey?: string },
): string {
  return authenticateStaff(ctx, headers, 'admin').uuid
}

export function sweepExpired(ctx: AppContext): void {
  const t = ctx.now()
  run(ctx.db, 'DELETE FROM challenges WHERE expires_at <= ?', t)
  run(ctx.db, 'DELETE FROM sessions WHERE expires_at <= ?', t)
  sweepWebLogins(ctx)
  sweepSyncTombstones(ctx)
}
