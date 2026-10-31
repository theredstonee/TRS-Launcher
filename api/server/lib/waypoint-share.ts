import { createHash } from 'node:crypto'
import { cleanWaypointName, type WaypointWorld } from './chat'
import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { ApiError, badRequest, conflict, forbidden, tooMany } from './errors'
import type { ApiEvent } from './events'
import { areFriends, hasBlocked } from './friends'
import { RULES } from './ratelimit'
import { assertNotSanctioned } from './sanctions'
import { ACTIVE_BANS } from './users'

/**
 * Live-Wegpunkte (API.md §41): Spieler geben einzelnen Freunden frei (gerichtet: A → B heißt „B sieht A“).
 * Sind beide in derselben Welt (gleicher Server bzw. gleiche gehostete Welt), bekommt B A's Live-Position und
 * A's geteilte Wegpunkte für diese Welt.
 *
 * - **Sitzungen und Positionen nur im RAM** (wie Präsenz): Sitzung läuft 3 min nach dem letzten `PUT /session` ab,
 *   die letzte Position hängt an der Sitzung. Nie in die Datenbank, nie ins Log.
 * - Die Welt eines anderen wird nie herausgegeben – nur, ob er gerade in **derselben** Welt ist wie du.
 *   Weltwechsel sind gebremst ({@link RULES.wpWorldChangeUser}), damit man Freunde nicht durch Ausprobieren von
 *   Server-Adressen „orten“ kann.
 * - Blockaden (egal in welche Richtung) und Entfreunden löschen beide Freigaben ({@link wpshareOnUnfriend}).
 * - Geteilte Wegpunkt-Listen (je Besitzer und Welt) verfallen 180 Tage nach der letzten Änderung ({@link sweepWpshare}).
 */

export const WPSHARE_SESSION_TTL_MS = 3 * 60_000
/** Letzte Position in `GET /friends` nur, wenn sie jünger ist. */
export const WPSHARE_POSITION_FRESH_MS = 30_000
export const WPSHARE_MAX_GRANTS = 200
/** Höchstens so viele Welten mit geteilten Wegpunkten je Konto. */
export const WPSHARE_MAX_WORLDS = 50
export const WPSHARE_RETENTION_MS = 180 * 24 * 60 * 60_000

export interface WpshareWaypointInput {
  id: string
  name: string
  x: number
  y: number
  z: number
  dimension: string
  color?: number
  death?: boolean
}

export interface WpshareWaypointView {
  id: string
  name: string
  x: number
  y: number
  z: number
  dimension: string
  /** 0xRRGGBB oder `null`. */
  color: number | null
  death: boolean
}

export interface PeersView {
  send: number
  receive: string[]
}

export interface PositionView {
  x: number
  y: number
  z: number
  dimension: string
  yaw: number
  t: string
}

export interface PositionInput {
  x: number
  y: number
  z: number
  dimension: string
  yaw: number
}

export interface FriendOwnerView {
  uuid: string
  name: string
  rev: number
  waypoints: WpshareWaypointView[]
  position?: PositionView
}

interface Session {
  worldKey: string
  dimension: string
  expiresAt: number
  pos: { x: number, y: number, z: number, dimension: string, yaw: number, at: number } | null
}

interface State {
  sessions: Map<string, Session>
  /** Zuletzt gemeldete Peers je Nutzer (für `wpshare_peers` nur bei Änderung). */
  lastPeers: Map<string, string>
}

const states = new WeakMap<AppContext, State>()

function state(ctx: AppContext): State {
  let s = states.get(ctx)
  if (!s) {
    s = { sessions: new Map(), lastPeers: new Map() }
    states.set(ctx, s)
  }
  return s
}

const iso = (t: number) => new Date(t).toISOString()

// ---------------------------------------------------------------- Welt-Schlüssel

/** Adresse wie §18.10, dazu kleingeschrieben und ohne Standardport (25565 = kein Port). */
export function normalizeWorld(w: WaypointWorld): WaypointWorld {
  if (w.type === 'world') return { type: 'world', id: w.id.toLowerCase() }
  return { type: 'server', address: w.address.toLowerCase().replace(/:25565$/, '') }
}

/** Interner Schlüssel: SHA-256 des normalisierten JSON (die Adresse selbst steht nur bei geteilten Wegpunkten). */
export function worldKey(w: WaypointWorld): string {
  return createHash('sha256').update(JSON.stringify(normalizeWorld(w))).digest('hex')
}

// ---------------------------------------------------------------- Berechtigung

/** Darf `owner` an `friend` teilen? Freunde und keine Blockade in irgendeine Richtung. */
function allowed(ctx: AppContext, owner: string, friend: string): boolean {
  return areFriends(ctx, owner, friend) && !hasBlocked(ctx, owner, friend) && !hasBlocked(ctx, friend, owner)
}

/** Gültige Freigaben (Freundschaft besteht, keine Blockade) – Aufräum-Hooks halten das ohnehin sauber. */
const VALID_GRANT = `EXISTS (SELECT 1 FROM friendships f
    WHERE f.a = CASE WHEN g.owner < g.friend THEN g.owner ELSE g.friend END
      AND f.b = CASE WHEN g.owner < g.friend THEN g.friend ELSE g.owner END)
  AND NOT EXISTS (SELECT 1 FROM blocks b
    WHERE (b.blocker = g.owner AND b.blocked = g.friend) OR (b.blocker = g.friend AND b.blocked = g.owner))`

function grantees(ctx: AppContext, owner: string): string[] {
  return all<{ u: string }>(ctx.db, `SELECT g.friend AS u FROM wpshare_grants g WHERE g.owner = ? AND ${VALID_GRANT}`, owner).map((r) => r.u)
}

function granters(ctx: AppContext, friend: string): string[] {
  return all<{ u: string }>(ctx.db, `SELECT g.owner AS u FROM wpshare_grants g WHERE g.friend = ? AND ${VALID_GRANT}`, friend).map((r) => r.u)
}

// ---------------------------------------------------------------- Sitzungen + Peers

function liveSession(ctx: AppContext, uuid: string): Session | null {
  const s = state(ctx).sessions.get(uuid)
  return s && s.expiresAt > ctx.now() ? s : null
}

function sameWorld(ctx: AppContext, a: Session, other: string): boolean {
  return liveSession(ctx, other)?.worldKey === a.worldKey
}

/** Empfänger meiner Positionen: Freunde mit meiner Freigabe in derselben Welt. */
function recipients(ctx: AppContext, me: string): string[] {
  const s = liveSession(ctx, me)
  if (!s) return []
  return grantees(ctx, me).filter((u) => sameWorld(ctx, s, u))
}

export function computePeers(ctx: AppContext, me: string): PeersView {
  const s = liveSession(ctx, me)
  if (!s) return { send: 0, receive: [] }
  return {
    send: recipients(ctx, me).length,
    receive: granters(ctx, me).filter((u) => sameWorld(ctx, s, u)).sort(),
  }
}

const peersSig = (p: PeersView) => `${p.send}|${p.receive.join(',')}`

/** Peers neu berechnen und bei Änderung flüchtig melden (nur an Nutzer mit Sitzung). */
function refreshPeers(ctx: AppContext, uuid: string): void {
  const st = state(ctx)
  if (!liveSession(ctx, uuid)) {
    st.lastPeers.delete(uuid)
    return
  }
  const p = computePeers(ctx, uuid)
  const sig = peersSig(p)
  if (st.lastPeers.get(uuid) === sig) return
  st.lastPeers.set(uuid, sig)
  ctx.events.publish(uuid, { type: 'wpshare_peers', send: p.send, receive: p.receive }, { ephemeral: true })
}

/** Alle, die mit `uuid` über eine Freigabe verbunden sind (beide Richtungen, ohne Prüfung der Freundschaft). */
function related(ctx: AppContext, uuid: string): string[] {
  return all<{ u: string }>(
    ctx.db,
    'SELECT friend AS u FROM wpshare_grants WHERE owner = ? UNION SELECT owner AS u FROM wpshare_grants WHERE friend = ?',
    uuid, uuid,
  ).map((r) => r.u)
}

function refreshMany(ctx: AppContext, uuids: Iterable<string>): void {
  for (const u of new Set(uuids)) refreshPeers(ctx, u)
}

/** `PUT /session`: beim Betreten, Welt-/Dimensionswechsel und alle 60 s. */
export function setSession(ctx: AppContext, me: string, world: WaypointWorld, dimension: string): PeersView {
  const st = state(ctx)
  const key = worldKey(world)
  const prev = liveSession(ctx, me)
  const moved = prev?.worldKey !== key
  if (moved) {
    // Gegen das „Orten“ von Freunden durch Ausprobieren vieler Welten (Herzschläge in derselben Welt zählen nicht).
    const r = ctx.limiter.take(`wpworld:${me}`, RULES.wpWorldChangeUser)
    if (!r.ok) throw tooMany(r.retryAfter)
  }
  st.sessions.set(me, {
    worldKey: key,
    dimension,
    expiresAt: ctx.now() + WPSHARE_SESSION_TTL_MS,
    pos: moved ? null : prev!.pos,
  })
  const peers = computePeers(ctx, me)
  st.lastPeers.set(me, peersSig(peers))
  if (moved) refreshMany(ctx, related(ctx, me))
  return peers
}

/** `DELETE /session` (Welt verlassen), Sperre oder Kontolöschung. */
export function endSession(ctx: AppContext, me: string): void {
  const st = state(ctx)
  const had = st.sessions.delete(me)
  st.lastPeers.delete(me)
  if (had) refreshMany(ctx, related(ctx, me))
}

const round2 = (v: number) => Math.round(v * 100) / 100

/**
 * `POST /position`: verteilt die Position flüchtig an Freunde mit Freigabe in derselben Welt.
 * Ohne Sitzung oder Empfänger passiert nichts (die Route antwortet trotzdem 204).
 * Rückgabe: Zahl der Empfänger (nur für Tests).
 */
export function postPosition(ctx: AppContext, me: string, p: PositionInput): number {
  const s = liveSession(ctx, me)
  if (!s) return 0
  const to = recipients(ctx, me)
  if (to.length === 0) {
    s.pos = null
    return 0
  }
  const t = ctx.now()
  const yaw = Math.round(((((p.yaw % 360) + 540) % 360) - 180) * 10) / 10
  const pos = { x: round2(p.x), y: round2(p.y), z: round2(p.z), dimension: p.dimension, yaw, at: t }
  s.pos = pos
  s.dimension = p.dimension
  // Positionen halten die Sitzung am Leben (der Herzschlag per PUT /session bleibt trotzdem nötig für Weltwechsel).
  s.expiresAt = Math.max(s.expiresAt, t + WPSHARE_SESSION_TTL_MS)
  const e: ApiEvent = { type: 'wpshare_position', uuid: me, x: pos.x, y: pos.y, z: pos.z, dimension: pos.dimension, yaw, t: iso(t) }
  for (const u of to) ctx.events.publish(u, e, { ephemeral: true })
  return to.length
}

/** Abgelaufene Sitzungen entfernen und Betroffene benachrichtigen (alle 15 s). */
export function sweepWpshareSessions(ctx: AppContext): number {
  const st = state(ctx)
  const t = ctx.now()
  const gone: string[] = []
  for (const [u, s] of st.sessions) {
    if (s.expiresAt <= t) {
      st.sessions.delete(u)
      st.lastPeers.delete(u)
      gone.push(u)
    }
  }
  const affected = new Set<string>()
  for (const u of gone) for (const r of related(ctx, u)) affected.add(r)
  refreshMany(ctx, affected)
  return gone.length
}

/** Nur für Tests/Diagnose: Zahl der offenen Sitzungen. */
export function sessionCount(ctx: AppContext): number {
  return state(ctx).sessions.size
}

// ---------------------------------------------------------------- Freigaben

export function listGrants(ctx: AppContext, me: string): { grants: string[], grantedToMe: string[] } {
  return {
    grants: all<{ u: string }>(ctx.db, 'SELECT friend AS u FROM wpshare_grants WHERE owner = ? ORDER BY friend', me).map((r) => r.u),
    grantedToMe: all<{ u: string }>(
      ctx.db,
      `SELECT g.owner AS u FROM wpshare_grants g WHERE g.friend = ? AND ${VALID_GRANT} AND g.owner NOT IN (${ACTIVE_BANS}) ORDER BY g.owner`,
      me, ctx.now(),
    ).map((r) => r.u),
  }
}

export function grant(ctx: AppContext, me: string, friend: string): void {
  if (friend === me) throw badRequest('cannot_target_self', 'You cannot do this with yourself')
  assertNotSanctioned(ctx, me, 'social_ban')
  if (hasBlocked(ctx, me, friend)) throw forbidden('blocked', 'Unblock this player first')
  // Wer mich blockiert hat, ist kein Freund mehr – die Blockade selbst wird nicht verraten.
  if (!allowed(ctx, me, friend)) throw forbidden('not_friends', 'You can only share with friends')
  const added = tx(ctx.db, () => {
    if (one(ctx.db, 'SELECT 1 AS x FROM wpshare_grants WHERE owner = ? AND friend = ?', me, friend)) return false
    const n = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM wpshare_grants WHERE owner = ?', me)!.n
    if (n >= WPSHARE_MAX_GRANTS) throw conflict('too_many_grants', `You can share with at most ${WPSHARE_MAX_GRANTS} friends`)
    run(ctx.db, 'INSERT INTO wpshare_grants (owner, friend, created_at) VALUES (?, ?, ?)', me, friend, ctx.now())
    return true
  })
  if (added) refreshMany(ctx, [me, friend])
}

export function revoke(ctx: AppContext, me: string, friend: string): void {
  const n = run(ctx.db, 'DELETE FROM wpshare_grants WHERE owner = ? AND friend = ?', me, friend)
  if (n === 0) return
  ctx.events.publish(friend, { type: 'wpshare_revoked', uuid: me }, { meOnly: true })
  refreshMany(ctx, [me, friend])
}

/** Freundschaft beendet oder Blockade (egal wer wen): beide Freigaben fallen weg, Betroffene löschen lokal. */
export function wpshareOnUnfriend(ctx: AppContext, a: string, b: string): void {
  let changed = false
  for (const [owner, friend] of [[a, b], [b, a]] as const) {
    const n = run(ctx.db, 'DELETE FROM wpshare_grants WHERE owner = ? AND friend = ?', owner, friend)
    if (n > 0) {
      changed = true
      ctx.events.publish(friend, { type: 'wpshare_revoked', uuid: owner }, { meOnly: true })
    }
  }
  if (changed) refreshMany(ctx, [a, b])
}

export const wpshareOnBlock = wpshareOnUnfriend

/** Kontolöschung: Sitzung weg, Freigaben und Listen weg (auch per FK), Empfänger löschen lokal. */
export function wpshareOnAccountDelete(ctx: AppContext, uuid: string): void {
  const rel = related(ctx, uuid)
  const receivers = all<{ u: string }>(ctx.db, 'SELECT friend AS u FROM wpshare_grants WHERE owner = ?', uuid).map((r) => r.u)
  const st = state(ctx)
  st.sessions.delete(uuid)
  st.lastPeers.delete(uuid)
  tx(ctx.db, () => {
    run(ctx.db, 'DELETE FROM wpshare_grants WHERE owner = ? OR friend = ?', uuid, uuid)
    run(ctx.db, 'DELETE FROM wpshare_worlds WHERE owner = ?', uuid)
  })
  for (const f of receivers) ctx.events.publish(f, { type: 'wpshare_revoked', uuid }, { meOnly: true })
  refreshMany(ctx, rel)
}

// ---------------------------------------------------------------- Geteilte Wegpunkte

interface WaypointRow {
  owner: string
  id: string
  name: string
  x: number
  y: number
  z: number
  dimension: string
  color: number | null
  death: number
}

const wpView = (r: WaypointRow): WpshareWaypointView => ({
  id: r.id, name: r.name, x: r.x, y: r.y, z: r.z, dimension: r.dimension, color: r.color, death: r.death === 1,
})

function worldRev(ctx: AppContext, owner: string, key: string): { rev: number, updated_at: number } | undefined {
  return one(ctx.db, 'SELECT rev, updated_at FROM wpshare_worlds WHERE owner = ? AND world_key = ?', owner, key)
}

function waypointsOf(ctx: AppContext, owner: string, key: string): WpshareWaypointView[] {
  return all<WaypointRow>(
    ctx.db,
    'SELECT * FROM wpshare_waypoints WHERE owner = ? AND world_key = ? ORDER BY sort, id',
    owner, key,
  ).map(wpView)
}

export interface OwnWorldView {
  world: WaypointWorld
  rev: number
  waypoints: WpshareWaypointView[]
  updatedAt: string | null
}

/** `PUT /waypoints`: ersetzt die eigene Liste für diese Welt (leer = löschen). */
export function putWaypoints(
  ctx: AppContext,
  me: string,
  rawWorld: WaypointWorld,
  rev: number | undefined,
  input: WpshareWaypointInput[],
): OwnWorldView {
  assertNotSanctioned(ctx, me, 'social_ban')
  const world = normalizeWorld(rawWorld)
  const key = worldKey(world)
  const ids = new Set<string>()
  let deaths = 0
  const clean = input.map((w) => {
    if (ids.has(w.id)) throw badRequest('invalid_waypoint', `Duplicate waypoint id ${w.id}`)
    ids.add(w.id)
    if (w.death) deaths++
    return { ...w, name: cleanWaypointName(ctx, w.name) }
  })
  if (deaths > 1) throw badRequest('invalid_waypoint', 'At most one waypoint can be the death point')
  const t = ctx.now()
  const next = tx(ctx.db, () => {
    const cur = worldRev(ctx, me, key)
    const current = cur?.rev ?? 0
    if (rev !== undefined && rev !== current) {
      throw new ApiError(409, 'rev_mismatch', 'The shared list changed meanwhile, reload it', { rev: current })
    }
    if (clean.length === 0) {
      run(ctx.db, 'DELETE FROM wpshare_worlds WHERE owner = ? AND world_key = ?', me, key)
      return 0
    }
    if (!cur) {
      const n = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM wpshare_worlds WHERE owner = ?', me)!.n
      if (n >= WPSHARE_MAX_WORLDS) throw conflict('too_many_worlds', `You can share waypoints in at most ${WPSHARE_MAX_WORLDS} worlds`)
      run(
        ctx.db,
        'INSERT INTO wpshare_worlds (owner, world_key, world_json, rev, updated_at) VALUES (?, ?, ?, 1, ?)',
        me, key, JSON.stringify(world), t,
      )
    } else {
      run(ctx.db, 'UPDATE wpshare_worlds SET rev = rev + 1, updated_at = ? WHERE owner = ? AND world_key = ?', t, me, key)
    }
    run(ctx.db, 'DELETE FROM wpshare_waypoints WHERE owner = ? AND world_key = ?', me, key)
    clean.forEach((w, i) => {
      run(
        ctx.db,
        `INSERT INTO wpshare_waypoints (owner, world_key, id, sort, name, x, y, z, dimension, color, death, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        me, key, w.id, i, w.name, w.x, w.y, w.z, w.dimension, w.color ?? null, w.death ? 1 : 0, t,
      )
    })
    return current + 1
  })
  // Wer gerade mit Freigabe in derselben Welt ist, lädt neu; alle anderen holen die Liste beim nächsten Betreten.
  const ev: ApiEvent = { type: 'wpshare_changed', uuid: me, rev: next }
  for (const f of grantees(ctx, me)) {
    if (liveSession(ctx, f)?.worldKey === key) ctx.events.publish(f, ev, { meOnly: true })
  }
  return { world, rev: next, waypoints: next === 0 ? [] : waypointsOf(ctx, me, key), updatedAt: next === 0 ? null : iso(t) }
}

/** `GET /waypoints?world=`: eigene Liste (für den Abgleich nach einer Neuinstallation). */
export function ownWaypoints(ctx: AppContext, me: string, rawWorld: WaypointWorld): OwnWorldView {
  const world = normalizeWorld(rawWorld)
  const key = worldKey(world)
  const cur = worldRev(ctx, me, key)
  return {
    world,
    rev: cur?.rev ?? 0,
    waypoints: cur ? waypointsOf(ctx, me, key) : [],
    updatedAt: cur ? iso(cur.updated_at) : null,
  }
}

/** `GET /waypoints` ohne Welt: alle eigenen Welten mit geteilten Wegpunkten (Kurzform). */
export function ownWorlds(ctx: AppContext, me: string): { worlds: { world: WaypointWorld, rev: number, count: number, updatedAt: string }[] } {
  const rows = all<{ world_json: string, rev: number, updated_at: number, n: number }>(
    ctx.db,
    `SELECT w.world_json, w.rev, w.updated_at, (SELECT COUNT(*) FROM wpshare_waypoints p WHERE p.owner = w.owner AND p.world_key = w.world_key) AS n
     FROM wpshare_worlds w WHERE w.owner = ? ORDER BY w.updated_at DESC`,
    me,
  )
  return { worlds: rows.map((r) => ({ world: JSON.parse(r.world_json) as WaypointWorld, rev: r.rev, count: r.n, updatedAt: iso(r.updated_at) })) }
}

/**
 * `GET /friends?world=`: Freunde, die mir freigegeben haben, mit ihren Wegpunkten für diese Welt und – wenn sie gerade
 * mit mir dort sind – ihrer letzten Position (< 30 s). Nur für die Welt der eigenen Sitzung (`409 not_in_world`),
 * damit niemand fremde Welten abfragen kann.
 */
export function friendsWaypoints(ctx: AppContext, me: string, rawWorld: WaypointWorld): { owners: FriendOwnerView[] } {
  const key = worldKey(rawWorld)
  const s = liveSession(ctx, me)
  if (!s || s.worldKey !== key) throw conflict('not_in_world', 'Start a session in this world first (PUT /v1/waypoint-share/session)')
  const owners = all<{ uuid: string, name: string }>(
    ctx.db,
    `SELECT u.uuid, u.name FROM wpshare_grants g JOIN users u ON u.uuid = g.owner
     WHERE g.friend = ? AND ${VALID_GRANT} AND u.uuid NOT IN (${ACTIVE_BANS}) ORDER BY u.name_lower`,
    me, ctx.now(),
  )
  const t = ctx.now()
  const out: FriendOwnerView[] = []
  for (const o of owners) {
    const cur = worldRev(ctx, o.uuid, key)
    const os = liveSession(ctx, o.uuid)
    const here = os?.worldKey === key
    if (!cur && !here) continue
    const view: FriendOwnerView = { uuid: o.uuid, name: o.name, rev: cur?.rev ?? 0, waypoints: cur ? waypointsOf(ctx, o.uuid, key) : [] }
    if (here && os.pos && t - os.pos.at < WPSHARE_POSITION_FRESH_MS) {
      view.position = { x: os.pos.x, y: os.pos.y, z: os.pos.z, dimension: os.pos.dimension, yaw: os.pos.yaw, t: iso(os.pos.at) }
    }
    out.push(view)
  }
  return { owners: out }
}

/** Geteilte Listen, die 180 Tage nicht geändert wurden, löschen (Wegpunkte per FK). */
export function sweepWpshare(ctx: AppContext): number {
  return run(ctx.db, 'DELETE FROM wpshare_worlds WHERE updated_at < ?', ctx.now() - WPSHARE_RETENTION_MS)
}

/** Body-Grenze für `PUT /waypoints` (100 Wegpunkte passen nicht immer in die üblichen 16 KiB). */
export const WPSHARE_BODY_LIMIT = 48 * 1024
