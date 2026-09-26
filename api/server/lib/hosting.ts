import { createHmac, randomBytes, randomInt, timingSafeEqual } from 'node:crypto'
import { openDm, sendMessage } from './chat'
import type { AppContext } from './context'
import type { HostingConfig } from './config'
import { all, one, placeholders, run, tx } from './db'
import { ApiError, badRequest, conflict, forbidden, notFound, unavailable } from './errors'
import type { ApiEvent, PlayerRef } from './events'
import { areFriends, friendUuids, hasBlocked } from './friends'
import { applyWordFilter, sanitizeText, textLength } from './safety'
import { getUser, isBanned, type UserRow } from './users'
import { assertNotSanctioned } from './sanctions'

/**
 * Welt-Hosting (API.md §21): Ein Spieler öffnet seine Einzelspielerwelt für Freunde. Die API macht nur
 * Zugang (Einladen, Anfragen, Kicken/Sperren) und Signalisierung (ICE über `/v1/events/me`) und stellt
 * kurzlebige Tokens für das eigene Relay aus. Spieldaten laufen nie über die API.
 */

export type Loader = 'vanilla' | 'fabric' | 'forge' | 'neoforge' | 'quilt'
export type GameMode = 'survival' | 'creative' | 'adventure' | 'spectator'
export type Visibility = 'friends' | 'invited'
export type MemberState = 'invited' | 'requested' | 'accepted' | 'banned'
export type SignalKind = 'offer' | 'answer' | 'candidate' | 'bye'
/**
 * `closed` Host hat geschlossen, `expired` kein Herzschlag mehr, `replaced` Host hat einen neuen Raum geöffnet,
 * `host_unavailable` Host gesperrt/gelöscht, `hidden` für dich nicht mehr sichtbar (Sichtbarkeit, Freundschaft,
 * Blockade), `left` du hast ihn (auf einem anderen Gerät) verlassen.
 */
export type RoomCloseReason = 'closed' | 'expired' | 'replaced' | 'host_unavailable' | 'hidden' | 'left'

export const LOADERS: readonly Loader[] = ['vanilla', 'fabric', 'forge', 'neoforge', 'quilt']
export const GAME_MODES: readonly GameMode[] = ['survival', 'creative', 'adventure', 'spectator']
export const ROOM_ID = /^h[0-9a-f]{20}$/
/** Join-Code: 6 Zeichen ohne verwechselbare (kein 0/O, 1/I/L). */
export const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'
export const CODE_LENGTH = 6
/** Höchstens 10 Spieler je Welt (Host eingeschlossen) – so auch im Relay. */
export const MAX_PLAYERS = 10

interface RoomRow {
  id: string
  host_uuid: string
  code: string
  name: string
  mc_version: string
  loader: Loader
  max_players: number
  game_mode: GameMode
  pvp: number
  cheats: number
  open: number
  visibility: Visibility
  players: number
  created_at: number
  heartbeat_at: number
}

interface MemberRow {
  room_id: string
  uuid: string
  state: MemberState
  created_at: number
  updated_at: number
}

export interface RoomSettings {
  name: string
  mcVersion: string
  loader: Loader
  maxPlayers: number
  gameMode: GameMode
  pvp: boolean
  cheats: boolean
  open: boolean
  visibility: Visibility
}

export interface MemberView {
  uuid: string
  name: string
  state: MemberState
  since: string
}

interface RoomBase {
  id: string
  name: string
  host: PlayerRef
  mcVersion: string
  loader: Loader
  maxPlayers: number
  gameMode: GameMode
  pvp: boolean
  cheats: boolean
  open: boolean
  /** Spieler gerade in der Welt (Host eingeschlossen), vom Host per Herzschlag gemeldet. */
  players: number
  createdAt: string
}

/** Was der Host sieht (inkl. Code und Mitgliederliste). */
export interface HostRoomView extends RoomBase {
  code: string
  visibility: Visibility
  expiresAt: string
  members: MemberView[]
}

/** Was Freunde/Eingeladene sehen. */
export interface RoomView extends RoomBase {
  myState: Exclude<MemberState, 'banned'> | null
}

export interface RelayInfo {
  host: string
  tcpPort: number
  udpPort: number
  token: string
  expiresAt: string
}

export interface ConnectInfo {
  role: 'host' | 'guest'
  relay: RelayInfo
  /** STUN-Server `host:port` für die eigene öffentliche Adresse (ICE-Kandidaten). */
  stun: string[]
}

/** Weltkarte im Chat (§18.1 `world`). */
export interface WorldCardView {
  roomId: string
  /** Join-Code: „Beitreten“ = `POST /v1/hosting/join {code}` (eingeladen → sofort drin, sonst Anfrage). */
  code: string
  name: string
  mcVersion: string
  loader: Loader
  host: PlayerRef
}

/** Verschlüsselt gespeicherter Teil einer Chat-Nachricht mit Weltkarte. */
export interface WorldCardBody {
  r: string
  c: string
  n: string
  v: string
  l: Loader
}

const iso = (t: number) => new Date(t).toISOString()
const newRoomId = () => `h${randomBytes(10).toString('hex')}`

function newCode(): string {
  let s = ''
  for (let i = 0; i < CODE_LENGTH; i++) s += CODE_ALPHABET[randomInt(CODE_ALPHABET.length)]
  return s
}

/** Eingabe → Code (groß, ohne Leer-/Trennzeichen), sonst `null`. */
export function normalizeCode(input: string): string | null {
  const s = input.toUpperCase().replace(/[\s-]/g, '')
  if (s.length !== CODE_LENGTH) return null
  for (const ch of s) if (!CODE_ALPHABET.includes(ch)) return null
  return s
}

export function requireHosting(ctx: AppContext): HostingConfig {
  if (!ctx.config.hosting) throw unavailable('hosting_unavailable', 'World hosting is not available on this server')
  return ctx.config.hosting
}

/** Weltname: gesäubert, eine Zeile, 1–32 Zeichen, Wortfilter (Maskieren; Sperrwörter → 422). */
export function cleanRoomName(ctx: AppContext, raw: string): string {
  const name = sanitizeText(raw).replace(/\s+/g, ' ').trim()
  if (name.length === 0 || textLength(name) > 32) throw badRequest('invalid_name', 'World names must be 1-32 characters')
  return applyWordFilter(ctx, name)
}

// ---------------------------------------------------------------- Lesen

function expiresAt(ctx: AppContext, r: RoomRow): number {
  return r.heartbeat_at + ctx.config.limits.hostingRoomTtlMs
}

/** Raum laden; abgelaufene werden dabei geschlossen (sonst übernimmt das der Sweep). */
export function getRoom(ctx: AppContext, id: string): RoomRow | undefined {
  const r = one<RoomRow>(ctx.db, 'SELECT * FROM hosting_rooms WHERE id = ?', id)
  if (r && expiresAt(ctx, r) <= ctx.now()) {
    closeRoom(ctx, r, 'expired')
    return undefined
  }
  return r
}

function getMember(ctx: AppContext, roomId: string, uuid: string): MemberRow | undefined {
  return one<MemberRow>(ctx.db, 'SELECT * FROM hosting_members WHERE room_id = ? AND uuid = ?', roomId, uuid)
}

function members(ctx: AppContext, roomId: string): (MemberRow & { name: string })[] {
  return all<MemberRow & { name: string }>(
    ctx.db,
    `SELECT m.*, u.name FROM hosting_members m JOIN users u ON u.uuid = m.uuid
     WHERE m.room_id = ? ORDER BY m.created_at, m.uuid`,
    roomId,
  )
}

function countState(ctx: AppContext, roomId: string, state: MemberState): number {
  return one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM hosting_members WHERE room_id = ? AND state = ?', roomId, state)!.n
}

function hostBanned(ctx: AppContext, host: string, uuid: string): boolean {
  return one(ctx.db, 'SELECT 1 AS x FROM hosting_bans WHERE host_uuid = ? AND uuid = ?', host, uuid) !== undefined
}

function blockedEither(ctx: AppContext, a: string, b: string): boolean {
  return hasBlocked(ctx, a, b) || hasBlocked(ctx, b, a)
}

function hostRef(ctx: AppContext, r: RoomRow): PlayerRef {
  return { uuid: r.host_uuid, name: getUser(ctx, r.host_uuid)?.name ?? '' }
}

function base(ctx: AppContext, r: RoomRow): RoomBase {
  return {
    id: r.id,
    name: r.name,
    host: hostRef(ctx, r),
    mcVersion: r.mc_version,
    loader: r.loader,
    maxPlayers: r.max_players,
    gameMode: r.game_mode,
    pvp: r.pvp === 1,
    cheats: r.cheats === 1,
    open: r.open === 1,
    players: r.players,
    createdAt: iso(r.created_at),
  }
}

export function hostView(ctx: AppContext, r: RoomRow): HostRoomView {
  return {
    ...base(ctx, r),
    code: r.code,
    visibility: r.visibility,
    expiresAt: iso(expiresAt(ctx, r)),
    members: members(ctx, r.id).map((m) => ({ uuid: m.uuid, name: m.name, state: m.state, since: iso(m.updated_at) })),
  }
}

export function roomView(ctx: AppContext, r: RoomRow, viewer: string): RoomView {
  const m = getMember(ctx, r.id, viewer)
  return { ...base(ctx, r), myState: m && m.state !== 'banned' ? m.state : null }
}

/**
 * Wer den Raum sehen darf (Ereignisse, `GET`): Mitglieder (nicht gesperrt) und – bei offener Welt mit
 * Sichtbarkeit `friends` – die Freunde des Hosts. Gesperrte und Blockaden (beide Richtungen) nie.
 */
function audience(ctx: AppContext, r: RoomRow): Set<string> {
  const out = new Set<string>()
  const banned = new Set<string>()
  for (const m of all<{ uuid: string, state: MemberState }>(ctx.db, 'SELECT uuid, state FROM hosting_members WHERE room_id = ?', r.id)) {
    if (m.state === 'banned') banned.add(m.uuid)
    else out.add(m.uuid)
  }
  if (r.visibility === 'friends' && r.open === 1) {
    const friends = friendUuids(ctx, r.host_uuid).filter((f) => !banned.has(f))
    const hostBans = friends.length
      ? new Set(all<{ uuid: string }>(ctx.db, `SELECT uuid FROM hosting_bans WHERE host_uuid = ? AND uuid IN (${placeholders(friends.length)})`, r.host_uuid, ...friends).map((x) => x.uuid))
      : new Set<string>()
    for (const f of friends) if (!hostBans.has(f)) out.add(f)
  }
  for (const u of [...out]) if (blockedEither(ctx, r.host_uuid, u) || isBanned(ctx, u)) out.delete(u)
  return out
}

function canSee(ctx: AppContext, r: RoomRow, uuid: string): boolean {
  if (uuid === r.host_uuid) return true
  const m = getMember(ctx, r.id, uuid)
  if (m?.state === 'banned' || hostBanned(ctx, r.host_uuid, uuid) || blockedEither(ctx, r.host_uuid, uuid)) return false
  if (m) return true
  return r.visibility === 'friends' && r.open === 1 && areFriends(ctx, r.host_uuid, uuid)
}

// ---------------------------------------------------------------- Ereignisse

const publish = (ctx: AppContext, uuid: string, e: ApiEvent) => ctx.events.publish(uuid, e, { meOnly: true })

/** Host (alle Geräte): voller Zustand. */
function publishHost(ctx: AppContext, r: RoomRow): void {
  if (ctx.events.wants(r.host_uuid)) publish(ctx, r.host_uuid, { type: 'hosting_room', room: hostView(ctx, r) })
}

/** Änderung an alle, die den Raum sehen; wer ihn nicht mehr sieht (`before`), bekommt `hidden`. */
function publishUpdate(ctx: AppContext, r: RoomRow, before?: Set<string>): void {
  const now = audience(ctx, r)
  for (const u of now) if (ctx.events.wants(u)) publish(ctx, u, { type: 'hosting_room_updated', room: roomView(ctx, r, u) })
  if (before) for (const u of before) if (!now.has(u) && ctx.events.wants(u)) publish(ctx, u, { type: 'hosting_room_closed', roomId: r.id, reason: 'hidden' })
  publishHost(ctx, r)
}

function reload(ctx: AppContext, id: string): RoomRow {
  return one<RoomRow>(ctx.db, 'SELECT * FROM hosting_rooms WHERE id = ?', id)!
}

// ---------------------------------------------------------------- Relay-Token

/**
 * Relay-Token `trsr1.<payload>.<sig>`: payload = base64url(JSON {v,r,u,h,role,m,iat,exp,n}),
 * sig = base64url(HMAC-SHA256(RELAY_SECRET, "trsr1." + payload)). Gilt nur zum Verbinden (≤ 2 min).
 */
export function relayToken(ctx: AppContext, r: RoomRow, uuid: string, role: 'host' | 'guest'): RelayInfo {
  const h = requireHosting(ctx)
  const iat = Math.floor(ctx.now() / 1000)
  const exp = iat + Math.max(10, Math.min(120, Math.floor(ctx.config.limits.relayTokenTtlMs / 1000)))
  const payload = { v: 1, r: r.id, u: uuid, h: r.host_uuid, role, m: r.max_players, iat, exp, n: randomBytes(8).toString('hex') }
  const p = Buffer.from(JSON.stringify(payload), 'utf8').toString('base64url')
  const sig = createHmac('sha256', h.relaySecrets[0]!).update(`trsr1.${p}`).digest('base64url')
  return { host: h.relayHost, tcpPort: h.relayTcpPort, udpPort: h.relayUdpPort, token: `trsr1.${p}.${sig}`, expiresAt: iso(exp * 1000) }
}

export interface RelayClaims {
  v: 1
  r: string
  u: string
  h: string
  role: 'host' | 'guest'
  m: number
  iat: number
  exp: number
  n: string
}

/** Gegenstück zum Relay (für Tests und Werkzeuge): prüft Signatur und Ablauf. */
export function verifyRelayToken(secrets: string[], token: string, nowSec: number): RelayClaims | null {
  const m = /^trsr1\.([A-Za-z0-9_-]{1,400})\.([A-Za-z0-9_-]{43})$/.exec(token)
  if (!m) return null
  const got = Buffer.from(m[2]!, 'base64url')
  const ok = secrets.some((s) => {
    const want = createHmac('sha256', s).update(`trsr1.${m[1]}`).digest()
    return want.length === got.length && timingSafeEqual(want, got)
  })
  if (!ok) return null
  try {
    const c = JSON.parse(Buffer.from(m[1]!, 'base64url').toString('utf8')) as RelayClaims
    return c.exp > nowSec ? c : null
  } catch {
    return null
  }
}

function connectInfo(ctx: AppContext, r: RoomRow, uuid: string, role: 'host' | 'guest'): ConnectInfo {
  return { role, relay: relayToken(ctx, r, uuid, role), stun: [...requireHosting(ctx).stun] }
}

// ---------------------------------------------------------------- Host: Raum

/** Neuer Raum. Ein Host hat höchstens einen: ein bestehender wird dabei geschlossen (`replaced`). */
export function createRoom(ctx: AppContext, host: UserRow, s: RoomSettings): { room: HostRoomView } & ConnectInfo {
  requireHosting(ctx)
  assertNotSanctioned(ctx, host.uuid, 'hosting_ban')
  const name = cleanRoomName(ctx, s.name)
  for (const old of all<RoomRow>(ctx.db, 'SELECT * FROM hosting_rooms WHERE host_uuid = ?', host.uuid)) closeRoom(ctx, old, 'replaced')
  const t = ctx.now()
  const id = newRoomId()
  for (let attempt = 0; ; attempt++) {
    try {
      run(
        ctx.db,
        `INSERT INTO hosting_rooms (id, host_uuid, code, name, mc_version, loader, max_players, game_mode, pvp, cheats, open, visibility, players, created_at, heartbeat_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)`,
        id, host.uuid, newCode(), name, s.mcVersion, s.loader, s.maxPlayers, s.gameMode, s.pvp ? 1 : 0, s.cheats ? 1 : 0,
        s.open ? 1 : 0, s.visibility, t, t,
      )
      break
    } catch (err) {
      // Code schon vergeben (UNIQUE) → neu würfeln.
      if (attempt >= 5 || !/UNIQUE/i.test((err as Error).message)) throw err
    }
  }
  const r = reload(ctx, id)
  publishUpdate(ctx, r)
  return { room: hostView(ctx, r), ...connectInfo(ctx, r, host.uuid, 'host') }
}

function ownRoom(ctx: AppContext, host: string, id: string): RoomRow {
  requireHosting(ctx)
  const r = getRoom(ctx, id)
  if (!r || r.host_uuid !== host) throw notFound('room_not_found', 'World not found')
  return r
}

export function myRooms(ctx: AppContext, host: string): HostRoomView[] {
  requireHosting(ctx)
  const ids = all<{ id: string }>(ctx.db, 'SELECT id FROM hosting_rooms WHERE host_uuid = ? ORDER BY created_at DESC', host)
  return ids.map(({ id }) => getRoom(ctx, id)).filter((r): r is RoomRow => !!r).map((r) => hostView(ctx, r))
}

export function updateRoom(ctx: AppContext, host: string, id: string, patch: Partial<RoomSettings>): HostRoomView {
  const r = ownRoom(ctx, host, id)
  const before = audience(ctx, r)
  const sets: string[] = []
  const params: (string | number)[] = []
  const set = (col: string, v: string | number) => {
    sets.push(`${col} = ?`)
    params.push(v)
  }
  if (patch.name !== undefined) set('name', cleanRoomName(ctx, patch.name))
  if (patch.maxPlayers !== undefined) {
    if (patch.maxPlayers < countState(ctx, r.id, 'accepted') + 1) {
      throw conflict('room_too_small', 'More players are already accepted – kick someone first')
    }
    set('max_players', patch.maxPlayers)
  }
  if (patch.gameMode !== undefined) set('game_mode', patch.gameMode)
  if (patch.pvp !== undefined) set('pvp', patch.pvp ? 1 : 0)
  if (patch.cheats !== undefined) set('cheats', patch.cheats ? 1 : 0)
  if (patch.open !== undefined) set('open', patch.open ? 1 : 0)
  if (patch.visibility !== undefined) set('visibility', patch.visibility)
  if (patch.mcVersion !== undefined) set('mc_version', patch.mcVersion)
  if (patch.loader !== undefined) set('loader', patch.loader)
  // Spaltennamen nur aus der festen Liste oben, Werte als Parameter.
  if (sets.length > 0) run(ctx.db, `UPDATE hosting_rooms SET ${sets.join(', ')}, heartbeat_at = ? WHERE id = ?`, ...params, ctx.now(), r.id)
  const next = reload(ctx, r.id)
  publishUpdate(ctx, next, before)
  return hostView(ctx, next)
}

/** Herzschlag (alle ~30 s). `players` = Spieler in der Welt inkl. Host (1–10). */
export function heartbeat(ctx: AppContext, host: string, id: string, players?: number): { expiresAt: string } {
  const r = ownRoom(ctx, host, id)
  const t = ctx.now()
  const p = players === undefined ? r.players : Math.max(1, Math.min(MAX_PLAYERS, players))
  run(ctx.db, 'UPDATE hosting_rooms SET heartbeat_at = ?, players = ? WHERE id = ?', t, p, r.id)
  if (p !== r.players) publishUpdate(ctx, reload(ctx, r.id))
  return { expiresAt: iso(t + ctx.config.limits.hostingRoomTtlMs) }
}

/** Schließt einen Raum: alle, die ihn sahen, bekommen `hosting_room_closed`. */
export function closeRoom(ctx: AppContext, r: RoomRow, reason: RoomCloseReason): void {
  const aud = audience(ctx, r)
  run(ctx.db, 'DELETE FROM hosting_rooms WHERE id = ?', r.id)
  const e: ApiEvent = { type: 'hosting_room_closed', roomId: r.id, reason }
  for (const u of aud) if (ctx.events.wants(u)) publish(ctx, u, e)
  if (ctx.events.wants(r.host_uuid)) publish(ctx, r.host_uuid, e)
}

export function deleteRoom(ctx: AppContext, host: string, id: string): void {
  closeRoom(ctx, ownRoom(ctx, host, id), 'closed')
}

/** Abgelaufene Räume schließen (Hintergrund-Timer). */
export function sweepHosting(ctx: AppContext): number {
  const limit = ctx.now() - ctx.config.limits.hostingRoomTtlMs
  const rows = all<RoomRow>(ctx.db, 'SELECT * FROM hosting_rooms WHERE heartbeat_at <= ?', limit)
  for (const r of rows) closeRoom(ctx, r, 'expired')
  return rows.length
}

// ---------------------------------------------------------------- Host: Einladen, Anfragen, Kicken

function targetUser(ctx: AppContext, host: string, uuid: string): UserRow {
  if (uuid === host) throw badRequest('cannot_target_self', 'You cannot do this with yourself')
  const u = getUser(ctx, uuid)
  if (!u || isBanned(ctx, uuid)) throw notFound('player_not_found', 'No TRS user with this UUID')
  return u
}

function assertCapacity(ctx: AppContext, r: RoomRow): void {
  if (countState(ctx, r.id, 'accepted') >= r.max_players - 1) throw conflict('room_full', 'This world is full')
}

function setState(ctx: AppContext, roomId: string, uuid: string, state: MemberState): void {
  const t = ctx.now()
  run(
    ctx.db,
    `INSERT INTO hosting_members (room_id, uuid, state, created_at, updated_at) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(room_id, uuid) DO UPDATE SET state = excluded.state, updated_at = excluded.updated_at`,
    roomId, uuid, state, t, t,
  )
}

function accepted(ctx: AppContext, r: RoomRow, uuid: string): void {
  setState(ctx, r.id, uuid, 'accepted')
  const next = reload(ctx, r.id)
  if (ctx.events.wants(uuid)) publish(ctx, uuid, { type: 'hosting_join_accepted', room: roomView(ctx, next, uuid) })
  publishHost(ctx, next)
}

export interface InviteResult {
  member: MemberView
  /** Id der Weltkarte in der DM (nur mit `chat: true` und wenn die DM beschreibbar ist). */
  chatMessageId: string | null
}

/**
 * Freund einladen. Hatte er schon angefragt → sofort angenommen. Nochmal einladen = nochmal
 * benachrichtigen. `chat` = zusätzlich eine Weltkarte in die DM schicken.
 */
export function invite(ctx: AppContext, host: UserRow, id: string, uuid: string, opts: { chat: boolean }): InviteResult {
  const r = ownRoom(ctx, host.uuid, id)
  assertNotSanctioned(ctx, host.uuid, 'hosting_ban')
  assertNotSanctioned(ctx, host.uuid, 'social_ban')
  const u = targetUser(ctx, host.uuid, uuid)
  if (!areFriends(ctx, host.uuid, u.uuid) || blockedEither(ctx, host.uuid, u.uuid)) throw forbidden('not_friends', 'You can only invite friends')
  const m = getMember(ctx, r.id, u.uuid)
  if (m?.state === 'banned' || hostBanned(ctx, host.uuid, u.uuid)) throw conflict('player_banned', 'This player is banned from your world – unban them first')
  if (m?.state === 'requested') {
    assertCapacity(ctx, r)
    accepted(ctx, r, u.uuid)
  } else if (m?.state !== 'accepted') {
    if (!m && countState(ctx, r.id, 'invited') >= ctx.config.limits.hostingMaxInvites) {
      throw conflict('too_many_invites', 'Too many open invites for this world')
    }
    setState(ctx, r.id, u.uuid, 'invited')
    const next = reload(ctx, r.id)
    if (ctx.events.wants(u.uuid)) publish(ctx, u.uuid, { type: 'hosting_invite', room: roomView(ctx, next, u.uuid), from: { uuid: host.uuid, name: host.name } })
    publishHost(ctx, next)
  }
  let chatMessageId: string | null = null
  if (opts.chat) {
    try {
      const conv = openDm(ctx, host.uuid, u.uuid)
      if (conv.canWrite) chatMessageId = sendMessage(ctx, host.uuid, conv.id, { world: { roomId: r.id } }).message.id
    } catch (err) {
      // Chat ist nur Zugabe (z. B. stummgeschaltet) – die Einladung selbst steht.
      if (!(err instanceof ApiError)) throw err
    }
  }
  const now = getMember(ctx, r.id, u.uuid)!
  return { member: { uuid: u.uuid, name: u.name, state: now.state, since: iso(now.updated_at) }, chatMessageId }
}

export function revokeInvite(ctx: AppContext, host: string, id: string, uuid: string): void {
  const r = ownRoom(ctx, host, id)
  const n = run(ctx.db, "DELETE FROM hosting_members WHERE room_id = ? AND uuid = ? AND state = 'invited'", r.id, uuid)
  if (n === 0) throw notFound('invite_not_found', 'No open invite for this player')
  if (ctx.events.wants(uuid)) {
    publish(ctx, uuid, { type: 'hosting_invite_revoked', roomId: r.id })
    if (!canSee(ctx, r, uuid)) publish(ctx, uuid, { type: 'hosting_room_closed', roomId: r.id, reason: 'hidden' })
  }
  publishHost(ctx, r)
}

export function answerRequest(ctx: AppContext, host: string, id: string, uuid: string, accept: boolean): MemberView | null {
  const r = ownRoom(ctx, host, id)
  const m = getMember(ctx, r.id, uuid)
  if (!m || m.state !== 'requested') throw notFound('request_not_found', 'No pending join request from this player')
  if (accept) {
    assertCapacity(ctx, r)
    accepted(ctx, r, uuid)
    const now = getMember(ctx, r.id, uuid)!
    return { uuid, name: getUser(ctx, uuid)?.name ?? '', state: 'accepted', since: iso(now.updated_at) }
  }
  run(ctx.db, 'DELETE FROM hosting_members WHERE room_id = ? AND uuid = ?', r.id, uuid)
  if (ctx.events.wants(uuid)) publish(ctx, uuid, { type: 'hosting_join_declined', roomId: r.id })
  publishHost(ctx, r)
  return null
}

/**
 * Entfernen (auch Eingeladene/Anfragende). `ban` = in diesem Raum sperren, `remember` = zusätzlich
 * dauerhaft für alle künftigen Welten dieses Hosts. Das Spiel des Hosts trennt die Verbindung selbst.
 */
export function kick(ctx: AppContext, host: string, id: string, uuid: string, opts: { ban: boolean, remember: boolean }): void {
  const r = ownRoom(ctx, host, id)
  targetUser(ctx, host, uuid)
  const prev = getMember(ctx, r.id, uuid)
  const before = audience(ctx, r)
  if (opts.ban || opts.remember) {
    tx(ctx.db, () => {
      setState(ctx, r.id, uuid, 'banned')
      if (opts.remember && !hostBanned(ctx, host, uuid)) {
        const n = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM hosting_bans WHERE host_uuid = ?', host)!.n
        if (n >= ctx.config.limits.hostingMaxBans) throw conflict('ban_limit', 'Your ban list is full')
        run(ctx.db, 'INSERT INTO hosting_bans (host_uuid, uuid, created_at) VALUES (?, ?, ?)', host, uuid, ctx.now())
      }
    })
  } else {
    if (!prev || prev.state === 'banned') throw notFound('member_not_found', 'This player is not in your world')
    run(ctx.db, 'DELETE FROM hosting_members WHERE room_id = ? AND uuid = ?', r.id, uuid)
  }
  if (prev && prev.state !== 'banned' && ctx.events.wants(uuid)) {
    publish(ctx, uuid, { type: 'hosting_kicked', roomId: r.id, banned: opts.ban || opts.remember })
  }
  // Wer gesperrt wurde, sieht den Raum nicht mehr.
  publishUpdate(ctx, reload(ctx, r.id), before)
}

export function unbanInRoom(ctx: AppContext, host: string, id: string, uuid: string): void {
  const r = ownRoom(ctx, host, id)
  const n = run(ctx.db, "DELETE FROM hosting_members WHERE room_id = ? AND uuid = ? AND state = 'banned'", r.id, uuid)
  if (n === 0) throw notFound('ban_not_found', 'This player is not banned from this world')
  publishHost(ctx, r)
}

export function listBans(ctx: AppContext, host: string): { bans: { uuid: string, name: string, since: string }[] } {
  const rows = all<{ uuid: string, name: string, created_at: number }>(
    ctx.db,
    'SELECT b.uuid, u.name, b.created_at FROM hosting_bans b JOIN users u ON u.uuid = b.uuid WHERE b.host_uuid = ? ORDER BY u.name_lower',
    host,
  )
  return { bans: rows.map((r) => ({ uuid: r.uuid, name: r.name, since: iso(r.created_at) })) }
}

/** Dauerhafte Sperre aufheben (die Sperre im laufenden Raum bleibt, s. `unbanInRoom`). */
export function unbanForHost(ctx: AppContext, host: string, uuid: string): void {
  const n = run(ctx.db, 'DELETE FROM hosting_bans WHERE host_uuid = ? AND uuid = ?', host, uuid)
  if (n === 0) throw notFound('ban_not_found', 'This player is not on your ban list')
}

// ---------------------------------------------------------------- Gäste

export type JoinTarget = { roomId: string } | { code: string }

export type JoinResult =
  | ({ status: 'accepted', room: RoomView } & ConnectInfo)
  | { status: 'requested', room: RoomView }

/** Code unbekannt → zählt als Fehlversuch (gegen Durchprobieren). */
export class UnknownCode extends ApiError {
  constructor() {
    super(404, 'room_not_found', 'World not found')
  }
}

/**
 * Beitreten: Eingeladene (und schon Angenommene) sind sofort drin, alle anderen stellen eine Anfrage
 * an den Host. Per `roomId` nur für Eingeladene und Freunde (bei Sichtbarkeit `friends`), per Code für
 * jeden mit TRS-Konto (landet immer als Anfrage beim Host, außer man ist eingeladen).
 */
export function join(ctx: AppContext, me: UserRow, target: JoinTarget): JoinResult {
  requireHosting(ctx)
  assertNotSanctioned(ctx, me.uuid, 'hosting_ban')
  let r: RoomRow | undefined
  if ('code' in target) {
    const code = normalizeCode(target.code)
    const row = code ? one<{ id: string }>(ctx.db, 'SELECT id FROM hosting_rooms WHERE code = ?', code) : undefined
    r = row ? getRoom(ctx, row.id) : undefined
    if (!r) throw new UnknownCode()
  } else {
    r = getRoom(ctx, target.roomId)
    if (!r) throw notFound('room_not_found', 'World not found')
  }
  if (r.host_uuid === me.uuid) throw badRequest('cannot_join_own_world', 'This is your own world')
  if (blockedEither(ctx, r.host_uuid, me.uuid)) throw notFound('room_not_found', 'World not found')
  const m = getMember(ctx, r.id, me.uuid)
  if (m?.state === 'banned' || hostBanned(ctx, r.host_uuid, me.uuid)) throw forbidden('banned_from_world', 'You are banned from this world')
  if ('roomId' in target && !m && !canSee(ctx, r, me.uuid)) throw notFound('room_not_found', 'World not found')

  if (m?.state === 'accepted') return { status: 'accepted', room: roomView(ctx, r, me.uuid), ...connectInfo(ctx, r, me.uuid, 'guest') }
  if (m?.state === 'invited') {
    assertCapacity(ctx, r)
    accepted(ctx, r, me.uuid)
    return { status: 'accepted', room: roomView(ctx, r, me.uuid), ...connectInfo(ctx, r, me.uuid, 'guest') }
  }
  if (m?.state === 'requested') return { status: 'requested', room: roomView(ctx, r, me.uuid) }
  if (r.open !== 1) throw conflict('world_closed', 'This world does not accept new players right now')
  assertCapacity(ctx, r)
  if (countState(ctx, r.id, 'requested') >= ctx.config.limits.hostingMaxRequests) {
    throw conflict('too_many_requests', 'Too many players are waiting for this world – try again later')
  }
  setState(ctx, r.id, me.uuid, 'requested')
  const next = reload(ctx, r.id)
  if (ctx.events.wants(r.host_uuid)) publish(ctx, r.host_uuid, { type: 'hosting_join_request', roomId: r.id, from: { uuid: me.uuid, name: me.name } })
  publishHost(ctx, next)
  return { status: 'requested', room: roomView(ctx, next, me.uuid) }
}

/** Verlassen, Anfrage zurückziehen oder Einladung ablehnen. */
export function leave(ctx: AppContext, me: string, id: string): void {
  requireHosting(ctx)
  const r = getRoom(ctx, id)
  const m = r ? getMember(ctx, r.id, me) : undefined
  if (!r || !m || m.state === 'banned') throw notFound('room_not_found', 'World not found')
  run(ctx.db, 'DELETE FROM hosting_members WHERE room_id = ? AND uuid = ?', r.id, me)
  if (ctx.events.wants(me)) publish(ctx, me, { type: 'hosting_room_closed', roomId: r.id, reason: 'left' })
  publishHost(ctx, r)
}

/** Raum für einen Betrachter: Host bekommt die volle Sicht, andere nur, wenn sie ihn sehen dürfen. */
export function roomFor(ctx: AppContext, me: string, id: string): HostRoomView | RoomView {
  requireHosting(ctx)
  const r = getRoom(ctx, id)
  if (!r || !canSee(ctx, r, me)) throw notFound('room_not_found', 'World not found')
  return r.host_uuid === me ? hostView(ctx, r) : roomView(ctx, r, me)
}

/** Frisches Relay-Token + STUN für Host oder angenommenen Gast. */
export function connect(ctx: AppContext, me: string, id: string): ConnectInfo {
  requireHosting(ctx)
  assertNotSanctioned(ctx, me, 'hosting_ban')
  const r = getRoom(ctx, id)
  if (!r || !canSee(ctx, r, me)) throw notFound('room_not_found', 'World not found')
  if (r.host_uuid === me) return connectInfo(ctx, r, me, 'host')
  if (getMember(ctx, r.id, me)?.state !== 'accepted') throw forbidden('not_accepted', 'The host has not let you in (yet)')
  return connectInfo(ctx, r, me, 'guest')
}

/** Offene Welten von Freunden (Sichtbarkeit `friends`) und alle Welten, in die ich eingeladen/angefragt/drin bin. */
export function friendsRooms(ctx: AppContext, me: string): RoomView[] {
  requireHosting(ctx)
  const friends = friendUuids(ctx, me)
  const ids = new Set<string>()
  for (let i = 0; i < friends.length; i += 400) {
    const part = friends.slice(i, i + 400)
    for (const r of all<{ id: string }>(
      ctx.db,
      `SELECT id FROM hosting_rooms WHERE visibility = 'friends' AND open = 1 AND host_uuid IN (${placeholders(part.length)})`,
      ...part,
    )) ids.add(r.id)
  }
  for (const r of all<{ room_id: string }>(ctx.db, "SELECT room_id FROM hosting_members WHERE uuid = ? AND state <> 'banned'", me)) ids.add(r.room_id)
  const out: RoomView[] = []
  for (const id of ids) {
    const r = getRoom(ctx, id)
    if (r && r.host_uuid !== me && canSee(ctx, r, me)) out.push(roomView(ctx, r, me))
  }
  return out.sort((a, b) => b.createdAt.localeCompare(a.createdAt) || a.id.localeCompare(b.id))
}

/** Offene Einladungen an mich (z. B. nach `resync`). */
export function myInvites(ctx: AppContext, me: string): RoomView[] {
  return friendsRooms(ctx, me).filter((r) => r.myState === 'invited')
}

// ---------------------------------------------------------------- Signalisierung

export interface SignalInput {
  to: string
  kind: SignalKind
  sid?: string
  data: string
}

/** ICE-Signal weiterreichen – nur Host ↔ angenommener Gast desselben Raums. */
export function signal(ctx: AppContext, me: string, id: string, s: SignalInput): { delivered: boolean } {
  requireHosting(ctx)
  if (s.data.length > ctx.config.limits.hostingMaxSignalData) throw badRequest('signal_too_large', `Signal data can be at most ${ctx.config.limits.hostingMaxSignalData} characters`)
  const r = getRoom(ctx, id)
  if (!r || !canSee(ctx, r, me)) throw notFound('room_not_found', 'World not found')
  if (s.to === me) throw badRequest('cannot_target_self', 'You cannot do this with yourself')
  if (r.host_uuid === me) {
    if (getMember(ctx, r.id, s.to)?.state !== 'accepted') throw notFound('peer_not_found', 'This player is not in your world')
  } else {
    if (getMember(ctx, r.id, me)?.state !== 'accepted') throw forbidden('not_accepted', 'The host has not let you in (yet)')
    if (s.to !== r.host_uuid) throw notFound('peer_not_found', 'Guests can only signal the host')
  }
  publish(ctx, s.to, { type: 'hosting_signal', roomId: r.id, from: me, kind: s.kind, sid: s.sid ?? null, data: s.data })
  return { delivered: ctx.events.isListening(s.to) }
}

// ---------------------------------------------------------------- Chat-Weltkarte

/** Für `POST …/messages {world}`: nur der Host eines offenen, laufenden Raums darf eine Karte schicken. */
export function worldCardBody(ctx: AppContext, me: string, roomId: string): WorldCardBody {
  requireHosting(ctx)
  const r = getRoom(ctx, roomId)
  if (!r || r.host_uuid !== me) throw notFound('room_not_found', 'World not found')
  return { r: r.id, c: r.code, n: r.name, v: r.mc_version, l: r.loader }
}

export function worldCardView(b: WorldCardBody, host: PlayerRef): WorldCardView {
  return { roomId: b.r, code: b.c, name: b.n, mcVersion: b.v, loader: b.l, host }
}

/**
 * Nach dem Senden einer Weltkarte: alle Empfänger, die mit dem Host befreundet sind und noch keinen
 * Status haben, werden eingeladen (`hosting_invite`). Andere können per Code anfragen.
 */
export function inviteFromCard(ctx: AppContext, host: UserRow, roomId: string, recipients: string[]): void {
  const r = getRoom(ctx, roomId)
  if (!r || r.host_uuid !== host.uuid) return
  let changed = false
  for (const u of recipients) {
    if (u === host.uuid || getMember(ctx, r.id, u) || hostBanned(ctx, host.uuid, u)) continue
    if (!areFriends(ctx, host.uuid, u) || blockedEither(ctx, host.uuid, u) || isBanned(ctx, u)) continue
    if (countState(ctx, r.id, 'invited') >= ctx.config.limits.hostingMaxInvites) break
    setState(ctx, r.id, u, 'invited')
    changed = true
    if (ctx.events.wants(u)) publish(ctx, u, { type: 'hosting_invite', room: roomView(ctx, reload(ctx, r.id), u), from: { uuid: host.uuid, name: host.name } })
  }
  if (changed) publishHost(ctx, reload(ctx, r.id))
}

// ---------------------------------------------------------------- Freunde, Blockaden, Sperren, Löschen

/** Freundschaft beendet: offene Einladungen zwischen beiden fallen weg; wer schon drin ist, bleibt. */
export function hostingOnUnfriend(ctx: AppContext, a: string, b: string): void {
  for (const [host, other] of [[a, b], [b, a]] as const) {
    for (const r of all<RoomRow>(ctx.db, 'SELECT * FROM hosting_rooms WHERE host_uuid = ?', host)) {
      const n = run(ctx.db, "DELETE FROM hosting_members WHERE room_id = ? AND uuid = ? AND state = 'invited'", r.id, other)
      if (n > 0 && ctx.events.wants(other)) publish(ctx, other, { type: 'hosting_invite_revoked', roomId: r.id })
      if (!canSee(ctx, r, other) && ctx.events.wants(other)) publish(ctx, other, { type: 'hosting_room_closed', roomId: r.id, reason: 'hidden' })
      if (n > 0) publishHost(ctx, r)
    }
  }
}

/** Blockade (egal wer wen): der andere verschwindet aus den Räumen des einen – ohne Sperre, ohne Hinweis. */
export function hostingOnBlock(ctx: AppContext, a: string, b: string): void {
  for (const [host, other] of [[a, b], [b, a]] as const) {
    for (const r of all<RoomRow>(ctx.db, 'SELECT * FROM hosting_rooms WHERE host_uuid = ?', host)) {
      const n = run(ctx.db, "DELETE FROM hosting_members WHERE room_id = ? AND uuid = ? AND state <> 'banned'", r.id, other)
      if (ctx.events.wants(other)) publish(ctx, other, { type: 'hosting_room_closed', roomId: r.id, reason: 'hidden' })
      if (n > 0) publishHost(ctx, r)
    }
  }
}

/** Konto gesperrt oder gelöscht: eigene Räume schließen, aus fremden Räumen austragen. */
export function endHostingFor(ctx: AppContext, uuid: string): void {
  for (const r of all<RoomRow>(ctx.db, 'SELECT * FROM hosting_rooms WHERE host_uuid = ?', uuid)) closeRoom(ctx, r, 'host_unavailable')
  const rooms = all<{ room_id: string }>(ctx.db, "SELECT room_id FROM hosting_members WHERE uuid = ? AND state <> 'banned'", uuid)
  run(ctx.db, "DELETE FROM hosting_members WHERE uuid = ? AND state <> 'banned'", uuid)
  for (const { room_id } of rooms) {
    const r = one<RoomRow>(ctx.db, 'SELECT * FROM hosting_rooms WHERE id = ?', room_id)
    if (r) publishHost(ctx, r)
  }
}

// ---------------------------------------------------------------- Team (§22.6: Welten)

export interface AdminRoomView {
  id: string
  code: string
  name: string
  host: PlayerRef
  mcVersion: string
  loader: Loader
  maxPlayers: number
  players: number
  open: boolean
  visibility: Visibility
  members: { accepted: number, invited: number, requested: number, banned: number }
  createdAt: string
  heartbeatAt: string
}

/** Offene Welten für das Team (neueste zuerst), optional nur die eines Spielers (Host oder Mitglied). */
export function adminRooms(ctx: AppContext, opts: { uuid?: string, limit: number } = { limit: 200 }): AdminRoomView[] {
  const rows = opts.uuid
    ? all<RoomRow & { host_name: string | null }>(
      ctx.db,
      `SELECT r.*, u.name AS host_name FROM hosting_rooms r LEFT JOIN users u ON u.uuid = r.host_uuid
       WHERE r.host_uuid = ? OR EXISTS (SELECT 1 FROM hosting_members m WHERE m.room_id = r.id AND m.uuid = ? AND m.state = 'accepted')
       ORDER BY r.created_at DESC LIMIT ?`,
      opts.uuid, opts.uuid, opts.limit,
    )
    : all<RoomRow & { host_name: string | null }>(
      ctx.db,
      'SELECT r.*, u.name AS host_name FROM hosting_rooms r LEFT JOIN users u ON u.uuid = r.host_uuid ORDER BY r.created_at DESC LIMIT ?',
      opts.limit,
    )
  return rows.map((r) => {
    const members = { accepted: 0, invited: 0, requested: 0, banned: 0 }
    for (const m of all<{ state: MemberState, n: number }>(ctx.db, 'SELECT state, COUNT(*) AS n FROM hosting_members WHERE room_id = ? GROUP BY state', r.id)) {
      members[m.state] = m.n
    }
    return {
      id: r.id,
      code: r.code,
      name: r.name,
      host: { uuid: r.host_uuid, name: r.host_name ?? '' },
      mcVersion: r.mc_version,
      loader: r.loader,
      maxPlayers: r.max_players,
      players: r.players,
      open: r.open === 1,
      visibility: r.visibility,
      members,
      createdAt: iso(r.created_at),
      heartbeatAt: iso(r.heartbeat_at),
    }
  })
}

/** Team schließt eine Welt (Host und Gäste bekommen `hosting_room_closed` mit `closed`). */
export function adminCloseRoom(ctx: AppContext, id: string): { host: string } {
  const r = getRoom(ctx, id)
  if (!r) throw notFound('room_not_found', 'World not found')
  closeRoom(ctx, r, 'closed')
  return { host: r.host_uuid }
}
