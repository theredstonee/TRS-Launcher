import { describe, expect, it } from 'vitest'
import { acceptRequest, block, listFriends, sendRequest } from '../server/lib/friends'
import { lookupPlayers } from '../server/lib/lookup'
import { afterPresenceChange, reportPresence } from '../server/lib/playerevents'
import { PresenceStore } from '../server/lib/presence'
import { parseWith } from '../server/lib/http'
import { presenceBody, type PresenceBody } from '../server/lib/schemas'
import { getUser, updateSettings } from '../server/lib/users'
import type { PlayerEvent } from '../server/lib/watch'
import type { ApiEvent } from '../server/lib/events'
import { login, makeEnv, type TestEnv } from './helpers'

const GAME = { version: '1.21.11', loader: 'fabric' }

async function players(env: TestEnv, ...names: string[]) {
  const out: string[] = []
  for (const n of names) out.push((await login(env, n)).user.uuid)
  return out
}

function report(env: TestEnv, uuid: string, body: PresenceBody) {
  reportPresence(env.ctx, getUser(env.ctx, uuid)!, parseWith(presenceBody, body))
}

const badgeOf = (env: TestEnv, viewer: string, subject: string) =>
  lookupPlayers(env.ctx, viewer, [subject]).players.find((p) => p.uuid === subject)?.badge ?? false

function watch(env: TestEnv, viewer: string, uuids: string[]) {
  const got: PlayerEvent[] = []
  const sub = env.ctx.watch.subscribe(viewer, uuids, (e) => got.push(e), () => {})
  if (!sub) throw new Error('subscribe refused')
  return got
}

const badges = (got: PlayerEvent[]) => got.filter((e) => e.type === 'badge')

describe('presence store with two sources', () => {
  it('launcher and client report separately; in-game wins, the client first', () => {
    const p = new PresenceStore(180_000, () => 0)
    expect(p.set('u', 'online', null, 'launcher')).toBe(true)
    expect(p.set('u', 'in-game', { ...GAME, server: 'play.example.net' }, 'client')).toBe(true)
    expect(p.get('u')).toMatchObject({ state: 'in-game', via: 'client', game: { server: 'play.example.net' } })
    // Der Launcher meldet dasselbe Spiel ohne Server: die Sicht bleibt beim Mod.
    expect(p.set('u', 'in-game', GAME, 'launcher')).toBe(false)
    expect(p.get('u')!.game!.server).toBe('play.example.net')
    // Mod verlässt die Welt → das vom Launcher gestartete Spiel läuft weiter.
    expect(p.clear('u', 'client')).toBe(true)
    expect(p.get('u')).toMatchObject({ state: 'in-game', via: 'launcher', game: GAME })
    expect(p.isInGame('u')).toBe(true)
    expect(p.clear('u', 'client')).toBe(false)
    expect(p.clear('u', 'launcher')).toBe(true)
    expect(p.get('u')).toBeNull()
    expect(p.count()).toBe(0)
  })

  it('each source expires on its own; the sweep reports what friends saw before', () => {
    let t = 0
    const p = new PresenceStore(180_000, () => t)
    p.set('u', 'in-game', GAME, 'client')
    t = 100_000
    p.set('u', 'online', null, 'launcher')
    t = 180_000
    // Abgelaufen, aber noch nicht aufgeräumt: gilt schon nicht mehr.
    expect(p.get('u')).toMatchObject({ state: 'online', via: 'launcher' })
    expect(p.sweepChanges()).toEqual([{ uuid: 'u', wasInGame: true }])
    expect(p.sweepChanges()).toEqual([])
    expect(p.count()).toBe(1)
    t = 280_000
    expect(p.sweep()).toEqual(['u'])
    expect(p.count()).toBe(0)
  })

  it('stripServer removes the address from every source', () => {
    const p = new PresenceStore(180_000, () => 0)
    p.set('u', 'in-game', { ...GAME, server: 'a.example' }, 'client')
    p.set('u', 'in-game', { ...GAME, server: 'b.example' }, 'launcher')
    p.stripServer('u')
    p.clear('u', 'client')
    expect(p.get('u')!.game).toEqual(GAME)
  })
})

describe('presence heartbeat (POST /v1/presence)', () => {
  it('accepts `via` and keeps old bodies valid', () => {
    expect(parseWith(presenceBody, { state: 'in-game', via: 'launcher', game: GAME }).via).toBe('launcher')
    expect(parseWith(presenceBody, { state: 'offline', via: 'client' }).via).toBe('client')
    expect(parseWith(presenceBody, { state: 'online' }).via).toBeUndefined()
    expect(() => parseWith(presenceBody, { state: 'online', via: 'website' })).toThrow()
  })

  it('old clients: `online` clears a leftover mod report, `offline` clears everything', async () => {
    const env = makeEnv()
    const [u] = await players(env, 'Steve')
    report(env, u!, { state: 'in-game', game: GAME }) // alter Mod (ohne via)
    expect(env.ctx.presence.get(u!)).toMatchObject({ state: 'in-game', via: 'client' })
    report(env, u!, { state: 'online' }) // alter Launcher nach dem Spielende
    expect(env.ctx.presence.get(u!)).toMatchObject({ state: 'online', via: 'launcher' })
    report(env, u!, { state: 'in-game', game: GAME })
    report(env, u!, { state: 'offline' })
    expect(env.ctx.presence.get(u!)).toBeNull()
  })

  it('new clients: `offline` with `via` only clears that source', async () => {
    const env = makeEnv()
    const [u] = await players(env, 'Steve')
    report(env, u!, { state: 'in-game', via: 'launcher', game: GAME })
    report(env, u!, { state: 'in-game', via: 'client', game: GAME })
    report(env, u!, { state: 'offline', via: 'client' })
    expect(env.ctx.presence.get(u!)).toMatchObject({ state: 'in-game', via: 'launcher' })
    // Launcher mit `via` und `online` lässt eine Meldung des Mods stehen.
    report(env, u!, { state: 'in-game', via: 'client', game: GAME })
    report(env, u!, { state: 'online', via: 'launcher' })
    expect(env.ctx.presence.get(u!)).toMatchObject({ state: 'in-game', via: 'client' })
    report(env, u!, { state: 'offline', via: 'client' })
    expect(env.ctx.presence.get(u!)).toMatchObject({ state: 'online', via: 'launcher' })
  })

  it('the launcher never stores a server address, the mod only with shareServer', async () => {
    const env = makeEnv()
    const [u] = await players(env, 'Steve')
    updateSettings(env.ctx, u!, { shareServer: true })
    report(env, u!, { state: 'in-game', via: 'client', game: { ...GAME, server: 'Play.Example.net' } })
    expect(env.ctx.presence.get(u!)!.game!.server).toBe('play.example.net')
  })
})

describe('live badge in the lookup', () => {
  it('only while the player is in game, and only for viewers who are in game themselves', async () => {
    const env = makeEnv()
    const [viewer, subject] = await players(env, 'Viewer', 'Subject')
    // Registriert, Abzeichen an, aber nicht im Spiel (Launcher nur offen) → kein Abzeichen, kein Eintrag.
    report(env, subject!, { state: 'online', via: 'launcher' })
    report(env, viewer!, { state: 'in-game', via: 'client', game: GAME })
    expect(lookupPlayers(env.ctx, viewer!, [subject!]).players).toEqual([])

    // Mit dem TRS Client in einer Welt → Abzeichen.
    report(env, subject!, { state: 'in-game', via: 'client', game: GAME })
    expect(badgeOf(env, viewer!, subject!)).toBe(true)

    // Wer selbst nicht im Spiel ist (z. B. nur Launcher offen), erfährt das nicht.
    report(env, viewer!, { state: 'offline', via: 'client' })
    expect(lookupPlayers(env.ctx, viewer!, [subject!]).players).toEqual([])
    report(env, viewer!, { state: 'online', via: 'launcher' })
    expect(badgeOf(env, viewer!, subject!)).toBe(false)
    // Nur ein vom Launcher gestartetes Spiel zählt für den Betrachter ebenso.
    report(env, viewer!, { state: 'in-game', via: 'launcher', game: GAME })
    expect(badgeOf(env, viewer!, subject!)).toBe(true)
  })

  it('a game started by the TRS Launcher counts, a merely open launcher does not', async () => {
    const env = makeEnv()
    const [viewer, subject] = await players(env, 'Viewer', 'Subject')
    report(env, viewer!, { state: 'in-game', via: 'client', game: GAME })
    report(env, subject!, { state: 'online', via: 'launcher' })
    expect(badgeOf(env, viewer!, subject!)).toBe(false)
    report(env, subject!, { state: 'in-game', via: 'launcher', game: GAME })
    expect(badgeOf(env, viewer!, subject!)).toBe(true)
    // Ein alter Mod (ohne via, meldet in-game) zählt ebenfalls.
    report(env, subject!, { state: 'offline', via: 'launcher' })
    report(env, subject!, { state: 'in-game', game: GAME })
    expect(badgeOf(env, viewer!, subject!)).toBe(true)
  })

  it('respects showBadge, blocks and expiry; the own badge needs no second player', async () => {
    const env = makeEnv()
    const [viewer, subject] = await players(env, 'Viewer', 'Subject')
    report(env, viewer!, { state: 'in-game', via: 'client', game: GAME })
    report(env, subject!, { state: 'in-game', via: 'client', game: GAME })
    // Eigenes Abzeichen: nur solange man selbst spielt.
    expect(badgeOf(env, subject!, subject!)).toBe(true)
    updateSettings(env.ctx, subject!, { showBadge: false })
    expect(badgeOf(env, viewer!, subject!)).toBe(false)
    expect(badgeOf(env, subject!, subject!)).toBe(false)
    updateSettings(env.ctx, subject!, { showBadge: true })
    // presenceVisibility betrifft nur die Freundesliste, nicht das Abzeichen.
    updateSettings(env.ctx, subject!, { presenceVisibility: 'nobody' })
    expect(badgeOf(env, viewer!, subject!)).toBe(true)
    block(env.ctx, subject!, { uuid: viewer! })
    expect(lookupPlayers(env.ctx, viewer!, [subject!]).players).toEqual([])
    env.clock.advance(180_000)
    expect(badgeOf(env, subject!, subject!)).toBe(false)
  })
})

describe('live badge events', () => {
  it('in-game watchers get the badge when a player starts and stops playing', async () => {
    const env = makeEnv()
    const [viewer, idle, subject] = await players(env, 'Viewer', 'Idle', 'Subject')
    report(env, viewer!, { state: 'in-game', via: 'client', game: GAME })
    report(env, idle!, { state: 'online', via: 'launcher' })
    const seen = watch(env, viewer!, [subject!])
    const notPlaying = watch(env, idle!, [subject!])
    const own = watch(env, subject!, [subject!])

    report(env, subject!, { state: 'online', via: 'launcher' })
    expect(badges(seen)).toEqual([])
    report(env, subject!, { state: 'in-game', via: 'client', game: GAME })
    expect(badges(seen)).toEqual([{ type: 'badge', uuid: subject, badge: true }])
    expect(badges(notPlaying)).toEqual([], 'nicht im Spiel → kein Live-Abzeichen')
    expect(badges(own)).toEqual([{ type: 'badge', uuid: subject, badge: true }])
    // Heartbeat ohne Änderung → kein neues Ereignis.
    report(env, subject!, { state: 'in-game', via: 'client', game: GAME })
    expect(badges(seen)).toHaveLength(1)

    report(env, subject!, { state: 'offline', via: 'client' })
    const off = { type: 'badge', uuid: subject, badge: false }
    expect(badges(seen)).toEqual([{ type: 'badge', uuid: subject, badge: true }, off])
    expect(badges(notPlaying)).toEqual([off], '„aus“ bekommen alle')
  })

  it('showBadge changes and expiry are sent; blocked viewers get nothing', async () => {
    const env = makeEnv()
    const [viewer, blocked, subject] = await players(env, 'Viewer', 'Blocked', 'Subject')
    for (const u of [viewer!, blocked!, subject!]) report(env, u, { state: 'in-game', via: 'client', game: GAME })
    block(env.ctx, subject!, { uuid: blocked! })
    const seen = watch(env, viewer!, [subject!])
    const hidden = watch(env, blocked!, [subject!])

    updateSettings(env.ctx, subject!, { showBadge: false })
    // Die Route PATCH /v1/me schickt das Ereignis; hier direkt wie dort.
    const { emitBadge } = await import('../server/lib/playerevents')
    emitBadge(env.ctx, subject!)
    expect(badges(seen)).toEqual([{ type: 'badge', uuid: subject, badge: false }])
    updateSettings(env.ctx, subject!, { showBadge: true })
    emitBadge(env.ctx, subject!)
    expect(badges(seen).at(-1)).toEqual({ type: 'badge', uuid: subject, badge: true })

    // Kein Heartbeat mehr → nach Ablauf „aus“ (Sweep wie im Server-Plugin).
    env.clock.advance(180_000)
    for (const { uuid, wasInGame } of env.ctx.presence.sweepChanges()) afterPresenceChange(env.ctx, uuid, true, wasInGame)
    expect(badges(seen).at(-1)).toEqual({ type: 'badge', uuid: subject, badge: false })
    expect(hidden).toEqual([])
  })

  it('friends still get presence events as before', async () => {
    const env = makeEnv()
    const [a, b] = await players(env, 'Alex', 'Bob')
    sendRequest(env.ctx, getUser(env.ctx, a!)!, { uuid: b! })
    acceptRequest(env.ctx, getUser(env.ctx, b!)!, a!)
    const got: ApiEvent[] = []
    env.ctx.events.subscribe(a!, (e) => got.push(e), () => {})
    report(env, b!, { state: 'in-game', via: 'launcher', game: GAME })
    report(env, b!, { state: 'in-game', via: 'client', game: GAME })
    expect(got.filter((e) => e.type === 'presence')).toHaveLength(1)
    expect(listFriends(env.ctx, a!).friends[0]!.presence).toMatchObject({ state: 'in-game', game: GAME })
  })
})
