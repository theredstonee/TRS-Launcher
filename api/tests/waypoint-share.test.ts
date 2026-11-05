import { describe, expect, it } from 'vitest'
import { banUser } from '../server/lib/admin'
import { all } from '../server/lib/db'
import { block, removeFriend } from '../server/lib/friends'
import { RULES, RateLimiter } from '../server/lib/ratelimit'
import { addFilterWord } from '../server/lib/safety'
import { wpsharePositionBody, wpshareSessionBody, wpshareWaypointsBody, wpshareWorldQuery } from '../server/lib/schemas'
import { deleteUser } from '../server/lib/users'
import {
  WPSHARE_RETENTION_MS, computePeers, endSession, friendsWaypoints, grant, listGrants, normalizeWorld, ownWaypoints,
  ownWorlds, postPosition, putWaypoints, revoke, sessionCount, setSession, sweepWpshare, sweepWpshareSessions, worldKey,
  type WpshareWaypointInput,
} from '../server/lib/waypoint-share'
import type { WaypointWorld } from '../server/lib/chat'
import { befriend, code, listen, players } from './chathelpers'
import { ADMIN, makeEnv, type TestEnv } from './helpers'

const SERVER: WaypointWorld = { type: 'server', address: 'Play.Example.net' }
const OTHER: WaypointWorld = { type: 'server', address: 'other.example.net' }
const HOSTED: WaypointWorld = { type: 'world', id: '0123456789abcdef' }
const OW = 'minecraft:overworld'

const WP: WpshareWaypointInput = { id: 'base', name: 'Base', x: 100, y: 64, z: -20, dimension: OW, color: 0xe0281e }
const POS = { x: 10.123, y: 70, z: -5.5, dimension: OW, yaw: 450 }

/** A, B befreundet; C Fremder. */
async function trio(e: TestEnv) {
  const [a, b, c] = await players(e, 'Alex', 'Bob', 'Carl')
  befriend(e, a!, b!)
  return { a: a!, b: b!, c: c! }
}

describe('grants (§41.1)', () => {
  it('are directional: A → B lets B see A, not the other way round', async () => {
    const e = makeEnv()
    const { a, b } = await trio(e)
    grant(e.ctx, a.uuid, b.uuid)
    grant(e.ctx, a.uuid, b.uuid) // idempotent
    expect(listGrants(e.ctx, a.uuid)).toEqual({ grants: [b.uuid], grantedToMe: [] })
    expect(listGrants(e.ctx, b.uuid)).toEqual({ grants: [], grantedToMe: [a.uuid] })
    setSession(e.ctx, a.uuid, SERVER, OW)
    const pb = setSession(e.ctx, b.uuid, SERVER, OW)
    expect(pb).toEqual({ send: 0, receive: [a.uuid] })
    expect(computePeers(e.ctx, a.uuid)).toEqual({ send: 1, receive: [] })
  })

  it('refuses strangers, self and blocked players with 403', async () => {
    const e = makeEnv()
    const { a, b, c } = await trio(e)
    expect(code(() => grant(e.ctx, a.uuid, c.uuid))).toBe('not_friends')
    expect(code(() => grant(e.ctx, a.uuid, a.uuid))).toBe('cannot_target_self')
    block(e.ctx, a.uuid, { uuid: b.uuid })
    expect(code(() => grant(e.ctx, a.uuid, b.uuid))).toBe('blocked')
    // B wurde blockiert: für ihn sieht es nur wie „keine Freunde“ aus.
    expect(code(() => grant(e.ctx, b.uuid, a.uuid))).toBe('not_friends')
    try {
      grant(e.ctx, a.uuid, c.uuid)
    } catch (err) {
      expect((err as { status: number }).status).toBe(403)
    }
  })

  it('revoking sends wpshare_revoked (not ephemeral) and updates peers', async () => {
    const e = makeEnv()
    const { a, b } = await trio(e)
    grant(e.ctx, a.uuid, b.uuid)
    setSession(e.ctx, a.uuid, SERVER, OW)
    setSession(e.ctx, b.uuid, SERVER, OW)
    const lb = listen(e, b.uuid)
    const la = listen(e, a.uuid)
    revoke(e.ctx, a.uuid, b.uuid)
    expect(lb.of('wpshare_revoked')).toEqual([{ type: 'wpshare_revoked', uuid: a.uuid }])
    expect(lb.events.find((x) => x.e.type === 'wpshare_revoked')!.id).not.toBeNull()
    expect(lb.of('wpshare_peers')).toEqual([{ type: 'wpshare_peers', send: 0, receive: [] }])
    expect(la.of('wpshare_peers')).toEqual([{ type: 'wpshare_peers', send: 0, receive: [] }])
    lb.clear()
    revoke(e.ctx, a.uuid, b.uuid) // nichts mehr da → still
    expect(lb.events).toEqual([])
  })

  it('unfriending, blocking and account deletion clean up both directions', async () => {
    const e = makeEnv()
    const [a, b, d] = await players(e, 'Alex', 'Bob', 'Dora')
    befriend(e, a!, b!)
    befriend(e, a!, d!)
    grant(e.ctx, a!.uuid, b!.uuid)
    grant(e.ctx, b!.uuid, a!.uuid)
    grant(e.ctx, a!.uuid, d!.uuid)
    grant(e.ctx, d!.uuid, a!.uuid)
    putWaypoints(e.ctx, a!.uuid, SERVER, undefined, [WP])
    const la = listen(e, a!.uuid)
    const lb = listen(e, b!.uuid)
    removeFriend(e.ctx, a!.uuid, b!.uuid)
    expect(lb.of('wpshare_revoked')).toEqual([{ type: 'wpshare_revoked', uuid: a!.uuid }])
    expect(la.of('wpshare_revoked')).toEqual([{ type: 'wpshare_revoked', uuid: b!.uuid }])
    expect(listGrants(e.ctx, b!.uuid)).toEqual({ grants: [], grantedToMe: [] })

    const ld = listen(e, d!.uuid)
    block(e.ctx, d!.uuid, { uuid: a!.uuid })
    expect(ld.of('wpshare_revoked')).toEqual([{ type: 'wpshare_revoked', uuid: a!.uuid }])
    expect(listGrants(e.ctx, a!.uuid)).toEqual({ grants: [], grantedToMe: [] })

    // Kontolöschung: Freigaben, Listen und Wegpunkte weg, Empfänger löschen lokal.
    befriend(e, a!, b!)
    grant(e.ctx, a!.uuid, b!.uuid)
    setSession(e.ctx, a!.uuid, SERVER, OW)
    lb.clear()
    deleteUser(e.ctx, a!.uuid)
    expect(lb.of('wpshare_revoked')).toEqual([{ type: 'wpshare_revoked', uuid: a!.uuid }])
    for (const t of ['wpshare_grants', 'wpshare_worlds', 'wpshare_waypoints']) {
      expect(all(e.ctx.db, `SELECT * FROM ${t}`)).toEqual([])
    }
    expect(sessionCount(e.ctx)).toBe(0)
  })

  it('caps grants at 200', async () => {
    const e = makeEnv()
    const [a, b] = await players(e, 'Alex', 'Bob')
    befriend(e, a!, b!)
    const t = e.ctx.now()
    // Freigaben direkt anlegen (200 echte Freunde wären teuer); die Grenze zählt nur die Zeilen.
    for (let i = 0; i < 200; i++) {
      const u = i.toString(16).padStart(32, 'f')
      e.ctx.db.prepare('INSERT INTO users (uuid, name, name_lower, created_at, last_login_at) VALUES (?, ?, ?, ?, ?)').run(u, `p${i}`, `p${i}`, t, t)
      e.ctx.db.prepare('INSERT INTO wpshare_grants (owner, friend, created_at) VALUES (?, ?, ?)').run(a!.uuid, u, t)
    }
    expect(code(() => grant(e.ctx, a!.uuid, b!.uuid))).toBe('too_many_grants')
  })
})

describe('sessions and peers (§41.2)', () => {
  it('only counts friends in the same world and pushes ephemeral wpshare_peers on changes', async () => {
    const e = makeEnv()
    const { a, b } = await trio(e)
    grant(e.ctx, a.uuid, b.uuid)
    grant(e.ctx, b.uuid, a.uuid)
    const la = listen(e, a.uuid)
    expect(setSession(e.ctx, a.uuid, SERVER, OW)).toEqual({ send: 0, receive: [] })
    // B auf einem anderen Server: nichts – und A erfährt nicht, wo B ist.
    expect(setSession(e.ctx, b.uuid, OTHER, OW)).toEqual({ send: 0, receive: [] })
    expect(la.of('wpshare_peers')).toEqual([])
    // B kommt dazu (gleiche Adresse, andere Schreibweise + Standardport).
    expect(setSession(e.ctx, b.uuid, { type: 'server', address: 'play.example.net:25565' }, OW)).toEqual({ send: 1, receive: [a.uuid] })
    expect(la.of('wpshare_peers')).toEqual([{ type: 'wpshare_peers', send: 1, receive: [b.uuid] }])
    expect(la.events.every((x) => x.id === null)).toBe(true)
    // Herzschlag / Dimensionswechsel ohne Änderung: kein neues Ereignis.
    setSession(e.ctx, b.uuid, SERVER, 'minecraft:the_nether')
    expect(la.of('wpshare_peers')).toHaveLength(1)
    // B geht.
    endSession(e.ctx, b.uuid)
    expect(la.of('wpshare_peers').at(-1)).toEqual({ type: 'wpshare_peers', send: 0, receive: [] })
    // Peers-Ereignisse werden nicht gepuffert (keine Wiederholung nach Reconnect).
    expect(e.ctx.events.replay(a.uuid, e.ctx.events.currentId()).ok).toBe(true)
  })

  it('expires after 3 minutes without heartbeat (sweep notifies peers)', async () => {
    const e = makeEnv()
    const { a, b } = await trio(e)
    grant(e.ctx, a.uuid, b.uuid)
    setSession(e.ctx, a.uuid, HOSTED, OW)
    setSession(e.ctx, b.uuid, HOSTED, OW)
    const lb = listen(e, b.uuid)
    e.clock.advance(2 * 60_000)
    setSession(e.ctx, b.uuid, HOSTED, OW)
    e.clock.advance(61_000)
    expect(computePeers(e.ctx, b.uuid)).toEqual({ send: 0, receive: [] })
    expect(sweepWpshareSessions(e.ctx)).toBe(1)
    expect(lb.of('wpshare_peers')).toEqual([{ type: 'wpshare_peers', send: 0, receive: [] }])
    expect(sessionCount(e.ctx)).toBe(1)
  })

  it('rate-limits switching to other worlds (no probing of server addresses)', async () => {
    const e = makeEnv()
    const [a] = await players(e, 'Alex')
    for (let i = 0; i < RULES.wpWorldChangeUser.limit; i++) setSession(e.ctx, a!.uuid, { type: 'server', address: `s${i}.example.net` }, OW)
    expect(code(() => setSession(e.ctx, a!.uuid, SERVER, OW))).toBe('rate_limited')
    // Herzschlag in derselben Welt bleibt erlaubt.
    expect(code(() => setSession(e.ctx, a!.uuid, { type: 'server', address: 's11.example.net' }, OW))).toBe('ok')
  })

  it('ends the session on an account ban', async () => {
    const e = makeEnv()
    const { a, b } = await trio(e)
    grant(e.ctx, a.uuid, b.uuid)
    setSession(e.ctx, a.uuid, SERVER, OW)
    setSession(e.ctx, b.uuid, SERVER, OW)
    const lb = listen(e, b.uuid)
    banUser(e.ctx, ADMIN, a.uuid, 'test')
    expect(lb.of('wpshare_peers')).toEqual([{ type: 'wpshare_peers', send: 0, receive: [] }])
    expect(postPosition(e.ctx, a.uuid, POS)).toBe(0)
  })
})

describe('positions (§41.3)', () => {
  it('go only to friends with a grant in the same world, ephemeral, never stored', async () => {
    const e = makeEnv()
    const [a, b, c, d] = await players(e, 'Alex', 'Bob', 'Carl', 'Dora')
    befriend(e, a!, b!)
    befriend(e, a!, d!)
    grant(e.ctx, a!.uuid, b!.uuid)
    grant(e.ctx, a!.uuid, d!.uuid)
    const lb = listen(e, b!.uuid)
    const lc = listen(e, c!.uuid)
    const ld = listen(e, d!.uuid)
    // Ohne Sitzung: verworfen.
    expect(postPosition(e.ctx, a!.uuid, POS)).toBe(0)
    setSession(e.ctx, a!.uuid, SERVER, OW)
    setSession(e.ctx, b!.uuid, SERVER, OW)
    setSession(e.ctx, c!.uuid, SERVER, OW) // Fremder in derselben Welt
    setSession(e.ctx, d!.uuid, OTHER, OW) // Freund mit Freigabe, aber woanders
    expect(postPosition(e.ctx, a!.uuid, POS)).toBe(1)
    const ev = lb.events.filter((x) => x.e.type === 'wpshare_position')
    expect(ev).toEqual([{
      e: { type: 'wpshare_position', uuid: a!.uuid, x: 10.12, y: 70, z: -5.5, dimension: OW, yaw: 90, t: new Date(e.ctx.now()).toISOString() },
      id: null,
    }])
    expect(lc.of('wpshare_position')).toEqual([])
    expect(ld.of('wpshare_position')).toEqual([])
    // B gibt A nicht frei → A bekommt B's Position nicht.
    const la = listen(e, a!.uuid)
    expect(postPosition(e.ctx, b!.uuid, POS)).toBe(0)
    expect(la.of('wpshare_position')).toEqual([])
    // Nichts davon steht in der Datenbank.
    const dump = JSON.stringify(all(e.ctx.db, 'SELECT * FROM wpshare_grants')) + JSON.stringify(all(e.ctx.db, 'SELECT * FROM wpshare_worlds'))
    expect(dump).not.toContain('10.12')
    expect(all(e.ctx.db, "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'wpshare_%' ORDER BY name").map((r) => (r as { name: string }).name))
      .toEqual(['wpshare_grants', 'wpshare_waypoints', 'wpshare_worlds'])
  })

  it('the last fresh position shows up in GET /friends (< 30 s)', async () => {
    const e = makeEnv()
    const { a, b } = await trio(e)
    grant(e.ctx, a.uuid, b.uuid)
    setSession(e.ctx, a.uuid, SERVER, OW)
    setSession(e.ctx, b.uuid, SERVER, OW)
    postPosition(e.ctx, a.uuid, POS)
    const owners = friendsWaypoints(e.ctx, b.uuid, SERVER).owners
    expect(owners).toEqual([{ uuid: a.uuid, name: 'Alex', rev: 0, waypoints: [], position: { x: 10.12, y: 70, z: -5.5, dimension: OW, yaw: 90, t: new Date(e.ctx.now()).toISOString() } }])
    e.clock.advance(31_000)
    expect(friendsWaypoints(e.ctx, b.uuid, SERVER).owners[0]!.position).toBeUndefined()
  })

  it('validates the body and the rate rule is 30/min', () => {
    expect(wpsharePositionBody.safeParse(POS).success).toBe(true)
    for (const bad of [{ ...POS, x: 3e7 + 1 }, { ...POS, y: Number.NaN }, { ...POS, dimension: 'Overworld' }, { ...POS, extra: 1 }, { ...POS, yaw: '0' }]) {
      expect(wpsharePositionBody.safeParse(bad).success, JSON.stringify(bad)).toBe(false)
    }
    expect(RULES.wpPositionUser).toEqual({ limit: 30, windowMs: 60_000 })
    const rl = new RateLimiter(() => 0)
    for (let i = 0; i < 30; i++) expect(rl.take('wppos:x', RULES.wpPositionUser).ok).toBe(true)
    expect(rl.take('wppos:x', RULES.wpPositionUser)).toEqual({ ok: false, retryAfter: 2 })
  })
})

describe('shared waypoints (§41.4)', () => {
  it('replaces the list, bumps rev, sends wpshare_changed only to grantees in the same world', async () => {
    const e = makeEnv()
    const [a, b, d] = await players(e, 'Alex', 'Bob', 'Dora')
    befriend(e, a!, b!)
    befriend(e, a!, d!)
    grant(e.ctx, a!.uuid, b!.uuid)
    grant(e.ctx, a!.uuid, d!.uuid)
    setSession(e.ctx, b!.uuid, SERVER, OW)
    setSession(e.ctx, d!.uuid, OTHER, OW)
    const lb = listen(e, b!.uuid)
    const ld = listen(e, d!.uuid)
    const r1 = putWaypoints(e.ctx, a!.uuid, SERVER, 0, [WP, { id: 'tod-1', name: 'Tod', x: 1, y: 2, z: 3, dimension: 'minecraft:the_nether', death: true }])
    expect(r1).toEqual({
      world: { type: 'server', address: 'play.example.net' },
      rev: 1,
      waypoints: [
        { id: 'base', name: 'Base', x: 100, y: 64, z: -20, dimension: OW, color: 0xe0281e, death: false },
        { id: 'tod-1', name: 'Tod', x: 1, y: 2, z: 3, dimension: 'minecraft:the_nether', color: null, death: true },
      ],
      updatedAt: new Date(e.ctx.now()).toISOString(),
    })
    expect(lb.of('wpshare_changed')).toEqual([{ type: 'wpshare_changed', uuid: a!.uuid, rev: 1 }])
    expect(lb.events.find((x) => x.e.type === 'wpshare_changed')!.id).not.toBeNull()
    expect(ld.of('wpshare_changed')).toEqual([])
    // B holt die Liste – A ist nicht da, also keine Position.
    expect(friendsWaypoints(e.ctx, b!.uuid, SERVER).owners).toEqual([{ uuid: a!.uuid, name: 'Alex', rev: 1, waypoints: r1.waypoints }])
    // Eigene Liste + Übersicht.
    expect(ownWaypoints(e.ctx, a!.uuid, { type: 'server', address: 'PLAY.example.net:25565' })).toEqual(r1)
    expect(ownWorlds(e.ctx, a!.uuid)).toEqual({ worlds: [{ world: r1.world, rev: 1, count: 2, updatedAt: r1.updatedAt }] })
    expect(ownWaypoints(e.ctx, a!.uuid, OTHER)).toEqual({ world: OTHER, rev: 0, waypoints: [], updatedAt: null })
    // Leere Liste löscht die Welt → rev 0.
    const r2 = putWaypoints(e.ctx, a!.uuid, SERVER, 1, [])
    expect(r2).toMatchObject({ rev: 0, waypoints: [], updatedAt: null })
    expect(lb.of('wpshare_changed').at(-1)).toEqual({ type: 'wpshare_changed', uuid: a!.uuid, rev: 0 })
    expect(all(e.ctx.db, 'SELECT * FROM wpshare_waypoints')).toEqual([])
  })

  it('rejects a stale rev with 409 rev_mismatch (details.rev = current)', async () => {
    const e = makeEnv()
    const [a] = await players(e, 'Alex')
    putWaypoints(e.ctx, a!.uuid, SERVER, undefined, [WP])
    putWaypoints(e.ctx, a!.uuid, SERVER, 1, [WP])
    try {
      putWaypoints(e.ctx, a!.uuid, SERVER, 1, [WP])
      expect.unreachable()
    } catch (err) {
      expect(err).toMatchObject({ status: 409, code: 'rev_mismatch', details: { rev: 2 } })
    }
    // Ohne rev: überschreibt.
    expect(putWaypoints(e.ctx, a!.uuid, SERVER, undefined, [WP]).rev).toBe(3)
  })

  it('validates like §18.10: names sanitised + word filter, ids, one death point, max 100', async () => {
    const e = makeEnv()
    const [a] = await players(e, 'Alex')
    const parse = (waypoints: unknown[], extra: Record<string, unknown> = {}) => wpshareWaypointsBody.safeParse({ world: SERVER, waypoints, ...extra })
    expect(parse([WP]).success).toBe(true)
    expect(parse([WP], { world: HOSTED }).success).toBe(true)
    for (const bad of [
      { ...WP, id: '' }, { ...WP, id: 'a b' }, { ...WP, id: 'x'.repeat(41) }, { ...WP, x: 1.5 }, { ...WP, y: 4097 },
      { ...WP, dimension: 'overworld' }, { ...WP, color: 0x1000000 }, { ...WP, world: SERVER }, { ...WP, note: 'x' }, { ...WP, death: 1 },
    ]) expect(parse([bad]).success, JSON.stringify(bad)).toBe(false)
    expect(parse(Array.from({ length: 101 }, (_, i) => ({ ...WP, id: `w${i}` }))).success).toBe(false)
    expect(parse([WP], { world: { type: 'server', address: 'http://evil' } }).success).toBe(false)
    expect(parse([WP], { rev: -1 }).success).toBe(false)

    expect(code(() => putWaypoints(e.ctx, a!.uuid, SERVER, undefined, [{ ...WP, name: '‮\u0000 ' }]))).toBe('invalid_waypoint')
    expect(code(() => putWaypoints(e.ctx, a!.uuid, SERVER, undefined, [{ ...WP, name: 'x'.repeat(33) }]))).toBe('invalid_waypoint')
    expect(code(() => putWaypoints(e.ctx, a!.uuid, SERVER, undefined, [WP, WP]))).toBe('invalid_waypoint')
    expect(code(() => putWaypoints(e.ctx, a!.uuid, SERVER, undefined, [{ ...WP, death: true }, { ...WP, id: 'b', death: true }]))).toBe('invalid_waypoint')
    expect(putWaypoints(e.ctx, a!.uuid, SERVER, undefined, [{ ...WP, name: '  Mein\n  Haus ' }]).waypoints[0]!.name).toBe('Mein Haus')
    addFilterWord(e.ctx, ADMIN, 'mist', 'word', 'mask')
    addFilterWord(e.ctx, ADMIN, 'verboten', 'word', 'block')
    expect(putWaypoints(e.ctx, a!.uuid, SERVER, undefined, [{ ...WP, name: 'Mist Haus' }]).waypoints[0]!.name).toBe('**** Haus')
    expect(code(() => putWaypoints(e.ctx, a!.uuid, SERVER, undefined, [{ ...WP, name: 'verboten' }]))).toBe('message_blocked')
  })

  it('GET /friends only for the world of the own session and only owners who granted me', async () => {
    const e = makeEnv()
    const { a, b, c } = await trio(e)
    befriend(e, b, c)
    putWaypoints(e.ctx, a.uuid, SERVER, undefined, [WP])
    putWaypoints(e.ctx, c.uuid, SERVER, undefined, [WP]) // C gibt B nicht frei
    grant(e.ctx, a.uuid, b.uuid)
    expect(code(() => friendsWaypoints(e.ctx, b.uuid, SERVER))).toBe('not_in_world')
    setSession(e.ctx, b.uuid, OTHER, OW)
    expect(code(() => friendsWaypoints(e.ctx, b.uuid, SERVER))).toBe('not_in_world')
    setSession(e.ctx, b.uuid, SERVER, OW)
    expect(friendsWaypoints(e.ctx, b.uuid, SERVER).owners.map((o) => o.uuid)).toEqual([a.uuid])
    // Gesperrter Besitzer verschwindet.
    banUser(e.ctx, ADMIN, a.uuid, 'test')
    expect(friendsWaypoints(e.ctx, b.uuid, SERVER).owners).toEqual([])
  })

  it('expires lists after 180 days without change and caps worlds per account', async () => {
    const e = makeEnv()
    const [a] = await players(e, 'Alex')
    putWaypoints(e.ctx, a!.uuid, SERVER, undefined, [WP])
    e.clock.advance(WPSHARE_RETENTION_MS - 1000)
    putWaypoints(e.ctx, a!.uuid, OTHER, undefined, [WP])
    e.clock.advance(2000)
    expect(sweepWpshare(e.ctx)).toBe(1)
    expect(ownWorlds(e.ctx, a!.uuid).worlds.map((w) => w.world)).toEqual([OTHER])
    expect(all(e.ctx.db, 'SELECT id FROM wpshare_waypoints')).toHaveLength(1)
    for (let i = 1; i < 50; i++) putWaypoints(e.ctx, a!.uuid, { type: 'server', address: `w${i}.example.net` }, undefined, [WP])
    expect(code(() => putWaypoints(e.ctx, a!.uuid, HOSTED, undefined, [WP]))).toBe('too_many_worlds')
  })
})

describe('world keys and schemas', () => {
  it('normalise addresses (case, default port) and never mix server and hosted worlds', () => {
    expect(normalizeWorld({ type: 'server', address: 'Play.Example.NET:25565' })).toEqual({ type: 'server', address: 'play.example.net' })
    expect(normalizeWorld({ type: 'server', address: 'play.example.net:25566' })).toEqual({ type: 'server', address: 'play.example.net:25566' })
    expect(worldKey({ type: 'server', address: 'PLAY.example.net' })).toBe(worldKey({ type: 'server', address: 'play.example.net:25565' }))
    expect(worldKey(SERVER)).not.toBe(worldKey(OTHER))
    expect(worldKey(HOSTED)).toMatch(/^[0-9a-f]{64}$/)
    expect(wpshareSessionBody.safeParse({ world: HOSTED, dimension: OW }).success).toBe(true)
    expect(wpshareSessionBody.safeParse({ world: { type: 'world', id: 'MyWorld' }, dimension: OW }).success).toBe(false)
    expect(wpshareWorldQuery.safeParse({ world: JSON.stringify(SERVER) }).data).toEqual({ world: SERVER })
    expect(wpshareWorldQuery.safeParse({ world: '{nope' }).success).toBe(false)
    expect(wpshareWorldQuery.safeParse({ world: '{"type":"lan"}' }).success).toBe(false)
  })
})
