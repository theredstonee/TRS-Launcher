import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { conflict, notFound, badRequest } from './errors'
import type { ApiEvent } from './events'
import type { Presence } from './presence'
import { getUser, getUserByName, isBanned, type UserRow } from './users'

export interface PresenceView {
  state: 'online' | 'in-game'
  game: { version: string, loader: string, server?: string } | null
  updatedAt: string
}

export interface FriendView {
  uuid: string
  name: string
  since: string
  presence: PresenceView | null
}

export interface RequestView {
  uuid: string
  name: string
  createdAt: string
}

export interface FriendsList {
  friends: FriendView[]
  requests: { incoming: RequestView[], outgoing: RequestView[] }
}

const pair = (x: string, y: string): [string, string] => (x < y ? [x, y] : [y, x])
const iso = (t: number) => new Date(t).toISOString()

export function areFriends(ctx: AppContext, x: string, y: string): boolean {
  const [a, b] = pair(x, y)
  return one(ctx.db, 'SELECT 1 AS x FROM friendships WHERE a = ? AND b = ?', a, b) !== undefined
}

export function hasBlocked(ctx: AppContext, blocker: string, blocked: string): boolean {
  return one(ctx.db, 'SELECT 1 AS x FROM blocks WHERE blocker = ? AND blocked = ?', blocker, blocked) !== undefined
}

/** Präsenz eines Nutzers, wie ein Freund sie sehen darf (Einstellungen des Nutzers gelten). */
export function visiblePresence(u: Pick<UserRow, 'presence_visibility' | 'share_server'>, p: Presence | null): PresenceView | null {
  if (!p || u.presence_visibility !== 'friends') return null
  let game = p.game
  if (game && (u.share_server !== 1 || p.state !== 'in-game') && game.server !== undefined) {
    const { server: _s, ...rest } = game
    game = rest
  }
  return { state: p.state, game, updatedAt: iso(p.updatedAt) }
}

export function friendUuids(ctx: AppContext, uuid: string): string[] {
  return all<{ other: string }>(
    ctx.db,
    'SELECT b AS other FROM friendships WHERE a = ? UNION ALL SELECT a AS other FROM friendships WHERE b = ?',
    uuid, uuid,
  ).map((r) => r.other)
}

export function listFriends(ctx: AppContext, uuid: string): FriendsList {
  const friends = all<UserRow & { since: number }>(
    ctx.db,
    `SELECT u.*, f.created_at AS since FROM friendships f
     JOIN users u ON u.uuid = CASE WHEN f.a = ? THEN f.b ELSE f.a END
     WHERE (f.a = ? OR f.b = ?) AND u.uuid NOT IN (SELECT uuid FROM bans)
     ORDER BY u.name_lower`,
    uuid, uuid, uuid,
  )
  const incoming = all<{ uuid: string, name: string, created_at: number }>(
    ctx.db,
    `SELECT u.uuid, u.name, r.created_at FROM friend_requests r JOIN users u ON u.uuid = r.from_uuid
     WHERE r.to_uuid = ? AND u.uuid NOT IN (SELECT uuid FROM bans) ORDER BY r.created_at DESC`,
    uuid,
  )
  const outgoing = all<{ uuid: string, name: string, created_at: number }>(
    ctx.db,
    `SELECT u.uuid, u.name, r.created_at FROM friend_requests r JOIN users u ON u.uuid = r.to_uuid
     WHERE r.from_uuid = ? ORDER BY r.created_at DESC`,
    uuid,
  )
  return {
    friends: friends.map((f) => ({
      uuid: f.uuid,
      name: f.name,
      since: iso(f.since),
      presence: visiblePresence(f, ctx.presence.get(f.uuid)),
    })),
    requests: {
      incoming: incoming.map((r) => ({ uuid: r.uuid, name: r.name, createdAt: iso(r.created_at) })),
      outgoing: outgoing.map((r) => ({ uuid: r.uuid, name: r.name, createdAt: iso(r.created_at) })),
    },
  }
}

/**
 * Ziel auflösen. Nur TRS-Nutzer (die sich schon einmal angemeldet haben).
 * Wer uns blockiert hat oder gesperrt ist, sieht aus wie „nicht gefunden“.
 */
function resolveTarget(ctx: AppContext, me: string, target: { uuid: string } | { name: string }): UserRow {
  const u = 'uuid' in target ? getUser(ctx, target.uuid) : getUserByName(ctx, target.name)
  if (!u || isBanned(ctx, u.uuid) || hasBlocked(ctx, u.uuid, me)) {
    throw notFound('player_not_found', 'No TRS user with this name or UUID')
  }
  if (u.uuid === me) throw badRequest('cannot_target_self', 'You cannot do this with yourself')
  return u
}

const publish = (ctx: AppContext, uuid: string, e: ApiEvent) => ctx.events.publish(uuid, e)

export type SendResult = { status: 'sent' | 'accepted', user: { uuid: string, name: string } }

export function sendRequest(ctx: AppContext, me: UserRow, target: { uuid: string } | { name: string }): SendResult {
  const other = resolveTarget(ctx, me.uuid, target)
  if (hasBlocked(ctx, me.uuid, other.uuid)) throw conflict('blocked', 'Unblock this player first')
  if (areFriends(ctx, me.uuid, other.uuid)) throw conflict('already_friends', 'You are already friends')
  const t = ctx.now()
  const lim = ctx.config.limits
  const result = tx(ctx.db, (): SendResult['status'] => {
    // Hat der andere uns schon angefragt → direkt Freunde.
    const reverse = run(ctx.db, 'DELETE FROM friend_requests WHERE from_uuid = ? AND to_uuid = ?', other.uuid, me.uuid)
    if (reverse > 0) {
      assertFriendCapacity(ctx, me.uuid, other.uuid)
      const [a, b] = pair(me.uuid, other.uuid)
      run(ctx.db, 'INSERT INTO friendships (a, b, created_at) VALUES (?, ?, ?)', a, b, t)
      return 'accepted'
    }
    if (one(ctx.db, 'SELECT 1 AS x FROM friend_requests WHERE from_uuid = ? AND to_uuid = ?', me.uuid, other.uuid)) {
      throw conflict('already_requested', 'You already sent a request to this player')
    }
    const out = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM friend_requests WHERE from_uuid = ?', me.uuid)!.n
    if (out >= lim.maxOutgoingRequests) throw conflict('too_many_requests', 'You have too many pending friend requests')
    const inc = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM friend_requests WHERE to_uuid = ?', other.uuid)!.n
    if (inc >= lim.maxIncomingRequests) throw conflict('target_inbox_full', 'This player cannot receive more friend requests right now')
    assertFriendCapacity(ctx, me.uuid, other.uuid)
    run(ctx.db, 'INSERT INTO friend_requests (from_uuid, to_uuid, created_at) VALUES (?, ?, ?)', me.uuid, other.uuid, t)
    return 'sent'
  })
  if (result === 'accepted') {
    publish(ctx, other.uuid, { type: 'friend_added', friend: { uuid: me.uuid, name: me.name } })
    publish(ctx, me.uuid, { type: 'friend_added', friend: { uuid: other.uuid, name: other.name } })
  } else {
    publish(ctx, other.uuid, { type: 'friend_request', from: { uuid: me.uuid, name: me.name } })
  }
  return { status: result, user: { uuid: other.uuid, name: other.name } }
}

function friendCount(ctx: AppContext, uuid: string): number {
  return one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM friendships WHERE a = ? OR b = ?', uuid, uuid)!.n
}

function assertFriendCapacity(ctx: AppContext, me: string, other: string): void {
  const max = ctx.config.limits.maxFriends
  if (friendCount(ctx, me) >= max) throw conflict('friend_limit', 'You have reached the maximum number of friends')
  if (friendCount(ctx, other) >= max) throw conflict('target_friend_limit', 'This player has reached the maximum number of friends')
}

export function acceptRequest(ctx: AppContext, me: UserRow, from: string): FriendView {
  const other = getUser(ctx, from)
  const t = ctx.now()
  tx(ctx.db, () => {
    const n = run(ctx.db, 'DELETE FROM friend_requests WHERE from_uuid = ? AND to_uuid = ?', from, me.uuid)
    if (n === 0 || !other || isBanned(ctx, from)) throw notFound('request_not_found', 'No pending request from this player')
    assertFriendCapacity(ctx, me.uuid, from)
    const [a, b] = pair(me.uuid, from)
    run(ctx.db, 'INSERT INTO friendships (a, b, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING', a, b, t)
  })
  publish(ctx, from, { type: 'friend_added', friend: { uuid: me.uuid, name: me.name } })
  return { uuid: other!.uuid, name: other!.name, since: iso(t), presence: visiblePresence(other!, ctx.presence.get(from)) }
}

export function declineRequest(ctx: AppContext, me: string, from: string): void {
  const n = run(ctx.db, 'DELETE FROM friend_requests WHERE from_uuid = ? AND to_uuid = ?', from, me)
  if (n === 0) throw notFound('request_not_found', 'No pending request from this player')
  // Der Absender erfährt nichts von der Ablehnung (seine Anfrage verschwindet nur).
}

export function cancelRequest(ctx: AppContext, me: string, to: string): void {
  const n = run(ctx.db, 'DELETE FROM friend_requests WHERE from_uuid = ? AND to_uuid = ?', me, to)
  if (n === 0) throw notFound('request_not_found', 'No pending request to this player')
  publish(ctx, to, { type: 'friend_request_cancelled', uuid: me })
}

export function removeFriend(ctx: AppContext, me: string, other: string): void {
  const [a, b] = pair(me, other)
  const n = run(ctx.db, 'DELETE FROM friendships WHERE a = ? AND b = ?', a, b)
  if (n === 0) throw notFound('friend_not_found', 'This player is not your friend')
  publish(ctx, other, { type: 'friend_removed', uuid: me })
}

export function block(ctx: AppContext, me: string, target: { uuid: string } | { name: string }): { uuid: string, name: string } {
  const u = 'uuid' in target ? getUser(ctx, target.uuid) : getUserByName(ctx, target.name)
  if (!u) throw notFound('player_not_found', 'No TRS user with this name or UUID')
  if (u.uuid === me) throw badRequest('cannot_target_self', 'You cannot do this with yourself')
  const [a, b] = pair(me, u.uuid)
  const wasFriend = tx(ctx.db, () => {
    run(ctx.db, 'INSERT INTO blocks (blocker, blocked, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING', me, u.uuid, ctx.now())
    const f = run(ctx.db, 'DELETE FROM friendships WHERE a = ? AND b = ?', a, b)
    run(
      ctx.db,
      'DELETE FROM friend_requests WHERE (from_uuid = ? AND to_uuid = ?) OR (from_uuid = ? AND to_uuid = ?)',
      me, u.uuid, u.uuid, me,
    )
    return f > 0
  })
  // Der Blockierte sieht nur, dass die Freundschaft endet – nicht die Blockade.
  if (wasFriend) publish(ctx, u.uuid, { type: 'friend_removed', uuid: me })
  return { uuid: u.uuid, name: u.name }
}

export function unblock(ctx: AppContext, me: string, other: string): void {
  const n = run(ctx.db, 'DELETE FROM blocks WHERE blocker = ? AND blocked = ?', me, other)
  if (n === 0) throw notFound('block_not_found', 'This player is not blocked')
}

export function listBlocks(ctx: AppContext, me: string): { blocked: { uuid: string, name: string, since: string }[] } {
  const rows = all<{ uuid: string, name: string, created_at: number }>(
    ctx.db,
    'SELECT u.uuid, u.name, b.created_at FROM blocks b JOIN users u ON u.uuid = b.blocked WHERE b.blocker = ? ORDER BY u.name_lower',
    me,
  )
  return { blocked: rows.map((r) => ({ uuid: r.uuid, name: r.name, since: iso(r.created_at) })) }
}

/** Präsenzänderung an alle Freunde verteilen, die sie sehen dürfen. */
export function broadcastPresence(ctx: AppContext, uuid: string): void {
  const u = getUser(ctx, uuid)
  if (!u) return
  const view = visiblePresence(u, ctx.presence.get(uuid))
  // Auch „nobody“-Nutzer schicken `null` (= offline) – einmal ausgeblendet, bleibt es dabei.
  for (const f of friendUuids(ctx, uuid)) {
    if (ctx.events.isListening(f)) publish(ctx, f, { type: 'presence', uuid, presence: view })
  }
}
