import type { AppContext } from './context'
import { one, run, tx } from './db'
import { ApiError, forbidden, tooMany, unauthorized, upstreamFailed } from './errors'
import { SESSION_TOKEN, newServerId, newSessionToken, safeEqual, sha256Hex } from './ids'
import { RULES } from './ratelimit'
import { MojangUnavailable } from './mojang'
import { updatePresence } from './playerevents'
import { getUser, isAdmin, isBanned, upsertOnLogin, meView, type MeView, type UserRow } from './users'
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
  if (isBanned(ctx, profile.uuid)) throw forbidden('banned', 'This account is banned')

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

export interface AuthedUser {
  uuid: string
  admin: boolean
  user: UserRow
  tokenHash: string
}

/** Prüft einen Bearer-Token. Gesperrte Konten → 403 `banned`. */
export function authenticate(ctx: AppContext, authorization: string | undefined): AuthedUser {
  if (!authorization) throw unauthorized()
  const m = /^Bearer (\S+)$/.exec(authorization)
  if (!m || !SESSION_TOKEN.test(m[1]!)) throw unauthorized('Invalid token')
  const hash = sha256Hex(m[1]!)
  const t = ctx.now()
  const s = one<{ uuid: string, last_used_at: number }>(
    ctx.db,
    'SELECT uuid, last_used_at FROM sessions WHERE token_hash = ? AND expires_at > ?',
    hash, t,
  )
  if (!s) throw unauthorized('Invalid or expired token')
  if (isBanned(ctx, s.uuid)) throw forbidden('banned', 'This account is banned')
  const user = getUser(ctx, s.uuid)
  if (!user) throw unauthorized('Invalid or expired token')
  // Nur grob mitschreiben (spart Schreibzugriffe).
  if (t - s.last_used_at > 10 * 60 * 1000) run(ctx.db, 'UPDATE sessions SET last_used_at = ? WHERE token_hash = ?', t, hash)
  return { uuid: s.uuid, admin: isAdmin(ctx, s.uuid), user, tokenHash: hash }
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

/** Admin-Zugriff: Admin-Sitzung ODER `X-Admin-Key` (zeitkonstant verglichen). Liefert den Akteur fürs Log. */
export function authenticateAdmin(
  ctx: AppContext,
  headers: { authorization?: string, adminKey?: string },
): string {
  if (headers.adminKey !== undefined) {
    const key = ctx.config.adminApiKey
    if (key && safeEqual(headers.adminKey, key)) return 'api-key'
    throw unauthorized('Invalid admin key')
  }
  const auth = authenticate(ctx, headers.authorization)
  if (!auth.admin) throw forbidden('forbidden', 'Admin only')
  return auth.uuid
}

export function sweepExpired(ctx: AppContext): void {
  const t = ctx.now()
  run(ctx.db, 'DELETE FROM challenges WHERE expires_at <= ?', t)
  run(ctx.db, 'DELETE FROM sessions WHERE expires_at <= ?', t)
  sweepWebLogins(ctx)
  sweepSyncTombstones(ctx)
}
