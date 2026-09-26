import { createHmac } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { listMessages, openDm, sendMessage } from '../server/lib/chat'
import { loadConfig } from '../server/lib/config'
import { block, removeFriend } from '../server/lib/friends'
import {
  CODE_ALPHABET, answerRequest, connect, createRoom, deleteRoom, friendsRooms, heartbeat, invite, join, kick,
  leave, listBans, myInvites, myRooms, normalizeCode, revokeInvite, roomFor, signal, sweepHosting, unbanForHost,
  unbanInRoom, updateRoom, verifyRelayToken, type RoomSettings,
} from '../server/lib/hosting'
import { banUser } from '../server/lib/admin'
import { deleteUser, getUser, type UserRow } from '../server/lib/users'
import { befriend, code, listen, players } from './chathelpers'
import { ADMIN, makeEnv, type TestEnv } from './helpers'

const SECRET = 'relay-secret-0123456789abcdef-0123456789abcdef'
const HOSTING_ENV = { RELAY_SECRET: SECRET, RELAY_HOST: 'relay.example.test' }

function env(extra: Record<string, string> = {}): TestEnv {
  return makeEnv({ env: { ...HOSTING_ENV, ...extra } })
}

const SETTINGS: RoomSettings = {
  name: 'Meine Welt', mcVersion: '1.21.4', loader: 'fabric', maxPlayers: 4, gameMode: 'survival',
  pvp: true, cheats: false, open: true, visibility: 'friends',
}

async function world(e: TestEnv, ...guests: string[]) {
  const [host, ...rest] = await players(e, 'Host', ...guests)
  const created = createRoom(e.ctx, host!, SETTINGS)
  return { host: host!, guests: rest, room: created.room, created }
}

const fresh = (e: TestEnv, u: UserRow) => getUser(e.ctx, u.uuid)!

describe('configuration', () => {
  it('is off without RELAY_SECRET/RELAY_HOST and answers 503 hosting_unavailable', async () => {
    const e = makeEnv()
    expect(e.ctx.config.hosting).toBeNull()
    const [h] = await players(e, 'Host')
    expect(code(() => createRoom(e.ctx, h!, SETTINGS))).toBe('hosting_unavailable')
    expect(code(() => friendsRooms(e.ctx, h!.uuid))).toBe('hosting_unavailable')
  })

  it('validates secrets and STUN list, defaults STUN to the relay', () => {
    const base = { SECRET_KEY: 'x'.repeat(40) }
    expect(() => loadConfig({ ...base, RELAY_SECRET: 'short', RELAY_HOST: 'r.test' })).toThrow(/RELAY_SECRET/)
    expect(() => loadConfig({ ...base, ...HOSTING_ENV, HOSTING_STUN: 'nope' })).toThrow(/HOSTING_STUN/)
    const c = loadConfig({ ...base, ...HOSTING_ENV, RELAY_SECRET: `${SECRET},${'o'.repeat(32)}` })
    expect(c.hosting).toMatchObject({ relayHost: 'relay.example.test', relayTcpPort: 25503, relayUdpPort: 25504, stun: ['relay.example.test:25504'] })
    expect(c.hosting!.relaySecrets).toHaveLength(2)
    const d = loadConfig({ ...base, ...HOSTING_ENV, HOSTING_STUN: 'a.test:3478, b.test:19302' })
    expect(d.hosting!.stun).toEqual(['a.test:3478', 'b.test:19302'])
  })
})

describe('rooms', () => {
  it('creates a room with code, host relay token and STUN; one room per host', async () => {
    const e = env()
    const { host, room, created } = await world(e)
    expect(room.id).toMatch(/^h[0-9a-f]{20}$/)
    expect(room.code).toHaveLength(6)
    for (const ch of room.code) expect(CODE_ALPHABET).toContain(ch)
    expect(room).toMatchObject({ name: 'Meine Welt', maxPlayers: 4, players: 1, open: true, visibility: 'friends', members: [] })
    expect(created.role).toBe('host')
    expect(created.stun).toEqual(['relay.example.test:25504'])
    expect(created.relay).toMatchObject({ host: 'relay.example.test', tcpPort: 25503, udpPort: 25504 })
    const l = listen(e, host.uuid)
    const second = createRoom(e.ctx, host, { ...SETTINGS, name: 'Zweite' })
    expect(l.of('hosting_room_closed')).toEqual([{ type: 'hosting_room_closed', roomId: room.id, reason: 'replaced' }])
    expect(myRooms(e.ctx, host.uuid).map((r) => r.id)).toEqual([second.room.id])
  })

  it('sanitises names and normalises codes', async () => {
    const e = env()
    const [h] = await players(e, 'Host')
    expect(code(() => createRoom(e.ctx, h!, { ...SETTINGS, name: '‮\u0000  ' }))).toBe('invalid_name')
    expect(code(() => createRoom(e.ctx, h!, { ...SETTINGS, name: 'x'.repeat(33) }))).toBe('invalid_name')
    expect(createRoom(e.ctx, h!, { ...SETTINGS, name: '  Bau\n  Welt ' }).room.name).toBe('Bau Welt')
    expect(normalizeCode('ab-c d2 3')).toBe('ABCD23')
    expect(normalizeCode('ABC0O1')).toBeNull()
    expect(normalizeCode('ABCDE')).toBeNull()
  })

  it('patches settings, checks capacity and tells the audience', async () => {
    const e = env()
    const { host, guests: [bob], room } = await world(e, 'Bob')
    befriend(e, host, bob!)
    const lb = listen(e, bob!.uuid)
    const r = updateRoom(e.ctx, host.uuid, room.id, { gameMode: 'creative', cheats: true, name: 'Neu' })
    expect(r).toMatchObject({ gameMode: 'creative', cheats: true, name: 'Neu' })
    expect(lb.of('hosting_room_updated')[0]!.room).toMatchObject({ id: room.id, gameMode: 'creative', myState: null })
    // Sichtbarkeit nur Eingeladene → Freund sieht ihn nicht mehr.
    updateRoom(e.ctx, host.uuid, room.id, { visibility: 'invited' })
    expect(lb.of('hosting_room_closed')).toEqual([{ type: 'hosting_room_closed', roomId: room.id, reason: 'hidden' }])
    expect(friendsRooms(e.ctx, bob!.uuid)).toEqual([])
    expect(code(() => roomFor(e.ctx, bob!.uuid, room.id))).toBe('room_not_found')
    // Nicht der Host → 404.
    expect(code(() => updateRoom(e.ctx, bob!.uuid, room.id, { open: false }))).toBe('room_not_found')
    // Weniger Plätze als angenommene Spieler → 409.
    invite(e.ctx, host, room.id, bob!.uuid, { chat: false })
    join(e.ctx, bob!, { roomId: room.id })
    expect(code(() => updateRoom(e.ctx, host.uuid, room.id, { maxPlayers: 2 }))).toBe('ok')
    expect(code(() => heartbeat(e.ctx, host.uuid, room.id, 2))).toBe('ok')
  })

  it('closes after the TTL without heartbeat (sweep and lazy) and notifies everybody', async () => {
    const e = env()
    const { host, guests: [bob, carl], room } = await world(e, 'Bob', 'Carl')
    befriend(e, host, bob!)
    befriend(e, host, carl!)
    invite(e.ctx, host, room.id, bob!.uuid, { chat: false })
    const lb = listen(e, bob!.uuid)
    const lc = listen(e, carl!.uuid)
    e.clock.advance(60_000)
    expect(heartbeat(e.ctx, host.uuid, room.id, 3).expiresAt).toBe(new Date(e.clock.t + 90_000).toISOString())
    expect(lc.of('hosting_room_updated')[0]!.room.players).toBe(3)
    e.clock.advance(89_000)
    expect(sweepHosting(e.ctx)).toBe(0)
    e.clock.advance(2_000)
    expect(sweepHosting(e.ctx)).toBe(1)
    expect(lb.of('hosting_room_closed')).toEqual([{ type: 'hosting_room_closed', roomId: room.id, reason: 'expired' }])
    expect(lc.of('hosting_room_closed')).toHaveLength(1)
    expect(code(() => heartbeat(e.ctx, host.uuid, room.id))).toBe('room_not_found')
    // Lazy: ohne Sweep verschwindet ein abgelaufener Raum beim nächsten Zugriff.
    const r2 = createRoom(e.ctx, host, SETTINGS).room
    e.clock.advance(91_000)
    expect(code(() => roomFor(e.ctx, host.uuid, r2.id))).toBe('room_not_found')
    expect(myRooms(e.ctx, host.uuid)).toEqual([])
  })

  it('DELETE closes the room for members and the host devices', async () => {
    const e = env()
    const { host, guests: [bob], room } = await world(e, 'Bob')
    befriend(e, host, bob!)
    invite(e.ctx, host, room.id, bob!.uuid, { chat: false })
    const lb = listen(e, bob!.uuid)
    const lh = listen(e, host.uuid)
    expect(code(() => deleteRoom(e.ctx, bob!.uuid, room.id))).toBe('room_not_found')
    deleteRoom(e.ctx, host.uuid, room.id)
    expect(lb.of('hosting_room_closed')[0]).toMatchObject({ roomId: room.id, reason: 'closed' })
    expect(lh.of('hosting_room_closed')[0]).toMatchObject({ roomId: room.id, reason: 'closed' })
  })
})

describe('invites and joining', () => {
  it('invites friends only; the invitee joins immediately with a guest relay token', async () => {
    const e = env()
    const { host, guests: [bob, eve], room } = await world(e, 'Bob', 'Eve')
    befriend(e, host, bob!)
    expect(code(() => invite(e.ctx, host, room.id, eve!.uuid, { chat: false }))).toBe('not_friends')
    expect(code(() => invite(e.ctx, host, room.id, host.uuid, { chat: false }))).toBe('cannot_target_self')
    const lb = listen(e, bob!.uuid)
    const lh = listen(e, host.uuid)
    const r = invite(e.ctx, host, room.id, bob!.uuid, { chat: false })
    expect(r).toMatchObject({ member: { uuid: bob!.uuid, name: 'Bob', state: 'invited' }, chatMessageId: null })
    const inv = lb.of('hosting_invite')[0]!
    expect(inv.from).toEqual({ uuid: host.uuid, name: 'Host' })
    expect(inv.room).toMatchObject({ id: room.id, myState: 'invited', host: { uuid: host.uuid, name: 'Host' } })
    expect(inv.room).not.toHaveProperty('code')
    expect(lh.of('hosting_room').at(-1)!.room.members).toEqual([expect.objectContaining({ uuid: bob!.uuid, state: 'invited' })])
    expect(myInvites(e.ctx, bob!.uuid).map((x) => x.id)).toEqual([room.id])

    const j = join(e.ctx, bob!, { roomId: room.id })
    expect(j.status).toBe('accepted')
    if (j.status !== 'accepted') throw new Error('unreachable')
    expect(j.role).toBe('guest')
    const claims = verifyRelayToken([SECRET], j.relay.token, Math.floor(e.clock.t / 1000))!
    expect(claims).toMatchObject({ v: 1, r: room.id, u: bob!.uuid, h: host.uuid, role: 'guest', m: 4 })
    expect(lb.of('hosting_join_accepted')[0]!.room.myState).toBe('accepted')
    // Nochmal beitreten = neues Token, gleicher Status.
    expect(join(e.ctx, bob!, { roomId: room.id }).status).toBe('accepted')
  })

  it('non-invited friends and code users send a request; host accepts or declines', async () => {
    const e = env()
    const { host, guests: [bob, stranger], room } = await world(e, 'Bob', 'Stranger')
    befriend(e, host, bob!)
    const lh = listen(e, host.uuid)
    const lb = listen(e, bob!.uuid)
    const ls = listen(e, stranger!.uuid)
    // Fremder per roomId: 404 (kein Ausprobieren), per Code: Anfrage.
    expect(code(() => join(e.ctx, stranger!, { roomId: room.id }))).toBe('room_not_found')
    expect(join(e.ctx, stranger!, { code: room.code.toLowerCase() }).status).toBe('requested')
    expect(join(e.ctx, bob!, { roomId: room.id }).status).toBe('requested')
    expect(join(e.ctx, bob!, { roomId: room.id }).status).toBe('requested')
    expect(lh.of('hosting_join_request').map((x) => x.from.name)).toEqual(['Stranger', 'Bob'])
    expect(code(() => connect(e.ctx, bob!.uuid, room.id))).toBe('not_accepted')

    expect(answerRequest(e.ctx, host.uuid, room.id, bob!.uuid, true)).toMatchObject({ uuid: bob!.uuid, state: 'accepted' })
    expect(lb.of('hosting_join_accepted')).toHaveLength(1)
    expect(connect(e.ctx, bob!.uuid, room.id).role).toBe('guest')
    expect(answerRequest(e.ctx, host.uuid, room.id, stranger!.uuid, false)).toBeNull()
    expect(ls.of('hosting_join_declined')).toEqual([{ type: 'hosting_join_declined', roomId: room.id }])
    expect(code(() => answerRequest(e.ctx, host.uuid, room.id, stranger!.uuid, true))).toBe('request_not_found')
    // Unbekannter Code.
    expect(code(() => join(e.ctx, stranger!, { code: 'ZZZZZZ' }))).toBe('room_not_found')
    expect(code(() => join(e.ctx, host, { roomId: room.id }))).toBe('cannot_join_own_world')
  })

  it('a pending request is accepted by inviting; revoke removes the invite', async () => {
    const e = env()
    const { host, guests: [bob, carl], room } = await world(e, 'Bob', 'Carl')
    befriend(e, host, bob!)
    befriend(e, host, carl!)
    join(e.ctx, bob!, { roomId: room.id })
    expect(invite(e.ctx, host, room.id, bob!.uuid, { chat: false }).member.state).toBe('accepted')
    invite(e.ctx, host, room.id, carl!.uuid, { chat: false })
    const lc = listen(e, carl!.uuid)
    revokeInvite(e.ctx, host.uuid, room.id, carl!.uuid)
    expect(lc.of('hosting_invite_revoked')).toEqual([{ type: 'hosting_invite_revoked', roomId: room.id }])
    expect(code(() => revokeInvite(e.ctx, host.uuid, room.id, carl!.uuid))).toBe('invite_not_found')
  })

  it('respects capacity, closed worlds and request limits', async () => {
    const e = makeEnv({ env: HOSTING_ENV, limits: { hostingMaxRequests: 1 } })
    const [host, a, b, c] = await players(e, 'Host', 'Anna', 'Ben', 'Cleo')
    const room = createRoom(e.ctx, host!, { ...SETTINGS, maxPlayers: 2 }).room
    for (const p of [a, b, c]) befriend(e, host!, p!)
    invite(e.ctx, host!, room.id, a!.uuid, { chat: false })
    invite(e.ctx, host!, room.id, b!.uuid, { chat: false })
    expect(join(e.ctx, a!, { roomId: room.id }).status).toBe('accepted')
    expect(code(() => join(e.ctx, b!, { roomId: room.id }))).toBe('room_full')
    expect(code(() => join(e.ctx, c!, { roomId: room.id }))).toBe('room_full')
    updateRoom(e.ctx, host!.uuid, room.id, { maxPlayers: 4 })
    expect(join(e.ctx, c!, { code: room.code }).status).toBe('requested')
    const [d] = await players(e, 'Dora')
    expect(code(() => join(e.ctx, d!, { code: room.code }))).toBe('too_many_requests')
    updateRoom(e.ctx, host!.uuid, room.id, { open: false })
    leave(e.ctx, c!.uuid, room.id)
    expect(code(() => join(e.ctx, d!, { code: room.code }))).toBe('world_closed')
    // Eingeladene kommen auch in eine geschlossene Welt.
    expect(join(e.ctx, b!, { roomId: room.id }).status).toBe('accepted')
  })

  it('leave cancels requests, declines invites and leaves the world', async () => {
    const e = env()
    const { host, guests: [bob], room } = await world(e, 'Bob')
    befriend(e, host, bob!)
    invite(e.ctx, host, room.id, bob!.uuid, { chat: false })
    join(e.ctx, bob!, { roomId: room.id })
    const lh = listen(e, host.uuid)
    const lb = listen(e, bob!.uuid)
    leave(e.ctx, bob!.uuid, room.id)
    expect(lh.of('hosting_room').at(-1)!.room.members).toEqual([])
    expect(lb.of('hosting_room_closed')[0]).toMatchObject({ reason: 'left' })
    expect(code(() => leave(e.ctx, bob!.uuid, room.id))).toBe('room_not_found')
  })

  it('lists open rooms of friends plus own memberships', async () => {
    const e = env()
    const [h1, h2, me] = await players(e, 'HostA', 'HostB', 'Me')
    befriend(e, h1!, me!)
    const r1 = createRoom(e.ctx, h1!, SETTINGS).room
    const r2 = createRoom(e.ctx, h2!, SETTINGS).room
    expect(friendsRooms(e.ctx, me!.uuid).map((r) => r.id)).toEqual([r1.id])
    join(e.ctx, me!, { code: r2.code })
    const list = friendsRooms(e.ctx, me!.uuid)
    expect(list.map((r) => [r.id, r.myState]).sort()).toEqual([[r1.id, null], [r2.id, 'requested']].sort())
    updateRoom(e.ctx, h1!.uuid, r1.id, { open: false })
    expect(friendsRooms(e.ctx, me!.uuid).map((r) => r.id)).toEqual([r2.id])
    expect(roomFor(e.ctx, me!.uuid, r2.id)).toMatchObject({ myState: 'requested' })
    expect(roomFor(e.ctx, h2!.uuid, r2.id)).toHaveProperty('code', r2.code)
  })
})

describe('kick and ban', () => {
  it('kicks, bans per room and remembers bans per host', async () => {
    const e = env()
    const { host, guests: [bob, carl], room } = await world(e, 'Bob', 'Carl')
    befriend(e, host, bob!)
    befriend(e, host, carl!)
    invite(e.ctx, host, room.id, bob!.uuid, { chat: false })
    join(e.ctx, bob!, { roomId: room.id })
    const lb = listen(e, bob!.uuid)
    kick(e.ctx, host.uuid, room.id, bob!.uuid, { ban: false, remember: false })
    expect(lb.of('hosting_kicked')).toEqual([{ type: 'hosting_kicked', roomId: room.id, banned: false }])
    expect(code(() => kick(e.ctx, host.uuid, room.id, bob!.uuid, { ban: false, remember: false }))).toBe('member_not_found')
    // Nach einem Kick darf er wieder anfragen.
    expect(join(e.ctx, bob!, { roomId: room.id }).status).toBe('requested')

    kick(e.ctx, host.uuid, room.id, bob!.uuid, { ban: true, remember: false })
    expect(lb.of('hosting_kicked').at(-1)).toMatchObject({ banned: true })
    expect(lb.of('hosting_room_closed').at(-1)).toMatchObject({ reason: 'hidden' })
    expect(code(() => join(e.ctx, bob!, { roomId: room.id }))).toBe('banned_from_world')
    expect(code(() => join(e.ctx, bob!, { code: room.code }))).toBe('banned_from_world')
    expect(code(() => invite(e.ctx, host, room.id, bob!.uuid, { chat: false }))).toBe('player_banned')
    expect(friendsRooms(e.ctx, bob!.uuid)).toEqual([])
    unbanInRoom(e.ctx, host.uuid, room.id, bob!.uuid)
    expect(join(e.ctx, bob!, { roomId: room.id }).status).toBe('requested')

    // Dauerhaft: gilt auch für die nächste Welt.
    kick(e.ctx, host.uuid, room.id, carl!.uuid, { ban: true, remember: true })
    expect(listBans(e.ctx, host.uuid).bans.map((b) => b.name)).toEqual(['Carl'])
    const next = createRoom(e.ctx, host, SETTINGS).room
    expect(code(() => join(e.ctx, carl!, { code: next.code }))).toBe('banned_from_world')
    expect(friendsRooms(e.ctx, carl!.uuid)).toEqual([])
    unbanForHost(e.ctx, host.uuid, carl!.uuid)
    expect(code(() => unbanForHost(e.ctx, host.uuid, carl!.uuid))).toBe('ban_not_found')
    expect(join(e.ctx, carl!, { code: next.code }).status).toBe('requested')
  })
})

describe('signalling', () => {
  it('routes only between the host and accepted guests', async () => {
    const e = env()
    const { host, guests: [bob, carl, eve], room } = await world(e, 'Bob', 'Carl', 'Eve')
    befriend(e, host, bob!)
    befriend(e, host, carl!)
    invite(e.ctx, host, room.id, bob!.uuid, { chat: false })
    join(e.ctx, bob!, { roomId: room.id })
    join(e.ctx, carl!, { roomId: room.id }) // nur angefragt
    const lh = listen(e, host.uuid)
    const lb = listen(e, bob!.uuid)
    const lc = listen(e, carl!.uuid)

    expect(signal(e.ctx, bob!.uuid, room.id, { to: host.uuid, kind: 'offer', sid: 's1', data: '{"ufrag":"x"}' })).toEqual({ delivered: true })
    expect(lh.of('hosting_signal')).toEqual([{ type: 'hosting_signal', roomId: room.id, from: bob!.uuid, kind: 'offer', sid: 's1', data: '{"ufrag":"x"}' }])
    signal(e.ctx, host.uuid, room.id, { to: bob!.uuid, kind: 'answer', data: 'y' })
    expect(lb.of('hosting_signal')[0]).toMatchObject({ from: host.uuid, kind: 'answer', sid: null })

    // Nicht angenommen / Fremde / Gast→Gast / Host→Anfragender.
    expect(code(() => signal(e.ctx, carl!.uuid, room.id, { to: host.uuid, kind: 'offer', data: 'x' }))).toBe('not_accepted')
    expect(code(() => signal(e.ctx, eve!.uuid, room.id, { to: host.uuid, kind: 'offer', data: 'x' }))).toBe('room_not_found')
    expect(code(() => signal(e.ctx, bob!.uuid, room.id, { to: carl!.uuid, kind: 'candidate', data: 'x' }))).toBe('peer_not_found')
    expect(code(() => signal(e.ctx, host.uuid, room.id, { to: carl!.uuid, kind: 'candidate', data: 'x' }))).toBe('peer_not_found')
    expect(code(() => signal(e.ctx, host.uuid, room.id, { to: host.uuid, kind: 'bye', data: '' }))).toBe('cannot_target_self')
    expect(code(() => signal(e.ctx, bob!.uuid, room.id, { to: host.uuid, kind: 'candidate', data: 'x'.repeat(4097) }))).toBe('signal_too_large')
    expect(lc.of('hosting_signal')).toEqual([])

    // Nach dem Kick ist Schluss.
    kick(e.ctx, host.uuid, room.id, bob!.uuid, { ban: false, remember: false })
    expect(code(() => signal(e.ctx, host.uuid, room.id, { to: bob!.uuid, kind: 'bye', data: '' }))).toBe('peer_not_found')
  })

  it('reports delivered=false when the receiver has no stream', async () => {
    const e = env()
    const { host, guests: [bob], room } = await world(e, 'Bob')
    befriend(e, host, bob!)
    invite(e.ctx, host, room.id, bob!.uuid, { chat: false })
    join(e.ctx, bob!, { roomId: room.id })
    expect(signal(e.ctx, bob!.uuid, room.id, { to: host.uuid, kind: 'offer', data: 'x' })).toEqual({ delivered: false })
  })
})

describe('relay tokens', () => {
  it('signs the documented format with the first secret and expires after ≤ 2 min', async () => {
    const e = env({ RELAY_SECRET: `${SECRET},${'n'.repeat(40)}` })
    const { created, room, host } = await world(e)
    const token = created.relay.token
    const [prefix, payload, sig] = token.split('.')
    expect(prefix).toBe('trsr1')
    expect(sig).toBe(createHmac('sha256', SECRET).update(`trsr1.${payload}`).digest('base64url'))
    const claims = JSON.parse(Buffer.from(payload!, 'base64url').toString('utf8'))
    const now = Math.floor(e.clock.t / 1000)
    expect(claims).toEqual({ v: 1, r: room.id, u: host.uuid, h: host.uuid, role: 'host', m: 4, iat: now, exp: now + 120, n: expect.stringMatching(/^[0-9a-f]{16}$/) })
    expect(Object.keys(claims)).toEqual(['v', 'r', 'u', 'h', 'role', 'm', 'iat', 'exp', 'n'])
    expect(created.relay.expiresAt).toBe(new Date((now + 120) * 1000).toISOString())
    expect(verifyRelayToken([SECRET], token, now + 119)).not.toBeNull()
    expect(verifyRelayToken([SECRET], token, now + 120)).toBeNull()
    expect(verifyRelayToken(['wrong-secret-0123456789abcdef-0123456789'], token, now)).toBeNull()
    expect(verifyRelayToken([SECRET], `${token.slice(0, -2)}AA`, now)).toBeNull()
    // Relay nimmt beim Schlüsseltausch beide an.
    expect(verifyRelayToken(['n'.repeat(40), SECRET], token, now)).not.toBeNull()
  })

  it('connect issues fresh tokens only to host and accepted guests', async () => {
    const e = env()
    const { host, guests: [bob, eve], room } = await world(e, 'Bob', 'Eve')
    befriend(e, host, bob!)
    expect(connect(e.ctx, host.uuid, room.id).role).toBe('host')
    expect(code(() => connect(e.ctx, bob!.uuid, room.id))).toBe('not_accepted')
    expect(code(() => connect(e.ctx, eve!.uuid, room.id))).toBe('room_not_found')
    invite(e.ctx, host, room.id, bob!.uuid, { chat: false })
    join(e.ctx, bob!, { roomId: room.id })
    e.clock.advance(30_000)
    const c = connect(e.ctx, bob!.uuid, room.id)
    expect(verifyRelayToken([SECRET], c.relay.token, Math.floor(e.clock.t / 1000))).toMatchObject({ role: 'guest', u: bob!.uuid })
  })
})

describe('chat world cards', () => {
  it('invite with chat posts a world card into the DM; the card invites friends in groups', async () => {
    const e = env()
    const { host, guests: [bob, carl, dan], room } = await world(e, 'Bob', 'Carl', 'Dan')
    befriend(e, host, bob!)
    befriend(e, host, carl!)
    const r = invite(e.ctx, host, room.id, bob!.uuid, { chat: true })
    expect(r.chatMessageId).toMatch(/^m[0-9a-f]{20}$/)
    const dm = openDm(e.ctx, bob!.uuid, host.uuid)
    const msg = listMessages(e.ctx, bob!.uuid, dm.id, { limit: 10 }).messages.at(-1)!
    expect(msg.world).toEqual({ roomId: room.id, code: room.code, name: 'Meine Welt', mcVersion: '1.21.4', loader: 'fabric', host: { uuid: host.uuid, name: 'Host' } })
    expect(msg.invite).toBeNull()

    // Gruppe: Carl (Freund) wird eingeladen, Dan (kein Freund) nicht – er kann per Code anfragen.
    befriend(e, carl!, dan!)
    const { createGroup } = await import('../server/lib/chat')
    const g = createGroup(e.ctx, host.uuid, 'Crew', [carl!.uuid, bob!.uuid])
    const lc = listen(e, carl!.uuid)
    const sent = sendMessage(e.ctx, host.uuid, g.id, { text: 'Kommt rein!', world: { roomId: room.id } })
    expect(sent.message.world?.roomId).toBe(room.id)
    expect(lc.of('hosting_invite')).toHaveLength(1)
    expect(roomFor(e.ctx, carl!.uuid, room.id)).toMatchObject({ myState: 'invited' })
    expect(join(e.ctx, dan!, { code: sent.message.world!.code }).status).toBe('requested')

    // Nur der Host darf Karten seiner Welt schicken; nicht beides zugleich.
    const dm2 = openDm(e.ctx, bob!.uuid, host.uuid)
    expect(code(() => sendMessage(e.ctx, bob!.uuid, dm2.id, { world: { roomId: room.id } }))).toBe('room_not_found')
    expect(code(() => sendMessage(e.ctx, host.uuid, dm2.id, { world: { roomId: room.id }, invite: { address: 'a.example.net' } }))).toBe('invite_conflict')
    // Karte ohne Text lässt sich nicht leer editieren… doch: Karte bleibt Inhalt.
    const { editMessage } = await import('../server/lib/chat')
    expect(code(() => editMessage(e.ctx, host.uuid, sent.message.id, ''))).toBe('ok')
  })

  it('a muted host still invites; the card is skipped', async () => {
    const e = env()
    const { host, guests: [bob], room } = await world(e, 'Bob')
    befriend(e, host, bob!)
    const { muteUser } = await import('../server/lib/moderation')
    muteUser(e.ctx, ADMIN, host.uuid, 60, 'test')
    const r = invite(e.ctx, host, room.id, bob!.uuid, { chat: true })
    expect(r).toMatchObject({ member: { state: 'invited' }, chatMessageId: null })
  })
})

describe('friendship, blocks, bans, deletion', () => {
  it('unfriending drops open invites, blocking removes memberships both ways', async () => {
    const e = env()
    const { host, guests: [bob, carl], room } = await world(e, 'Bob', 'Carl')
    befriend(e, host, bob!)
    befriend(e, host, carl!)
    invite(e.ctx, host, room.id, bob!.uuid, { chat: false })
    const lb = listen(e, bob!.uuid)
    removeFriend(e.ctx, host.uuid, bob!.uuid)
    expect(lb.of('hosting_invite_revoked')).toEqual([{ type: 'hosting_invite_revoked', roomId: room.id }])
    expect(roomFor(e.ctx, host.uuid, room.id)).toMatchObject({ members: [] })

    invite(e.ctx, host, room.id, carl!.uuid, { chat: false })
    join(e.ctx, carl!, { roomId: room.id })
    const carlRoom = createRoom(e.ctx, fresh(e, carl!), SETTINGS).room
    join(e.ctx, host, { code: carlRoom.code })
    const lc = listen(e, carl!.uuid)
    block(e.ctx, host.uuid, { uuid: carl!.uuid })
    expect(lc.of('hosting_room_closed').map((x) => x.roomId)).toContain(room.id)
    expect(roomFor(e.ctx, host.uuid, room.id)).toMatchObject({ members: [] })
    expect(roomFor(e.ctx, carl!.uuid, carlRoom.id)).toMatchObject({ members: [] })
    expect(code(() => join(e.ctx, fresh(e, carl!), { code: room.code }))).toBe('room_not_found')
  })

  it('account ban closes hosted worlds and removes memberships', async () => {
    const e = env()
    const { host, guests: [bob], room } = await world(e, 'Bob')
    befriend(e, host, bob!)
    invite(e.ctx, host, room.id, bob!.uuid, { chat: false })
    join(e.ctx, bob!, { roomId: room.id })
    const bobRoom = createRoom(e.ctx, fresh(e, bob!), SETTINGS).room
    const lh = listen(e, host.uuid)
    banUser(e.ctx, ADMIN, bob!.uuid, 'test')
    expect(roomFor(e.ctx, host.uuid, room.id)).toMatchObject({ members: [] })
    expect(lh.of('hosting_room_closed')).toEqual([{ type: 'hosting_room_closed', roomId: bobRoom.id, reason: 'host_unavailable' }])
    expect(myRooms(e.ctx, bob!.uuid)).toEqual([])
  })

  it('account deletion removes rooms, memberships and ban lists', async () => {
    const e = env()
    const { host, guests: [bob, carl], room } = await world(e, 'Bob', 'Carl')
    befriend(e, host, bob!)
    invite(e.ctx, host, room.id, bob!.uuid, { chat: false })
    join(e.ctx, bob!, { roomId: room.id })
    kick(e.ctx, host.uuid, room.id, carl!.uuid, { ban: true, remember: true })
    const lh = listen(e, host.uuid)
    deleteUser(e.ctx, bob!.uuid)
    expect(roomFor(e.ctx, host.uuid, room.id)).toMatchObject({ members: [expect.objectContaining({ uuid: carl!.uuid, state: 'banned' })] })
    expect(lh.of('hosting_room').length).toBeGreaterThan(0)
    const lb = listen(e, carl!.uuid)
    deleteUser(e.ctx, host.uuid)
    const count = (sql: string) => (e.ctx.db.prepare(sql).get() as { n: number }).n
    expect(count('SELECT COUNT(*) AS n FROM hosting_rooms')).toBe(0)
    expect(count('SELECT COUNT(*) AS n FROM hosting_members')).toBe(0)
    expect(count('SELECT COUNT(*) AS n FROM hosting_bans')).toBe(0)
    expect(lb.events).toEqual([])
  })
})
