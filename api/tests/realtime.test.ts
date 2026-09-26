import { describe, expect, it } from 'vitest'
import { openDm, sendMessage, setTyping } from '../server/lib/chat'
import type { ApiEvent } from '../server/lib/events'
import { EventHub } from '../server/lib/events'
import { reportPresence } from '../server/lib/playerevents'
import { MAX_BUFFERED_BYTES, openUserStream, type SseSink } from '../server/lib/sse'
import { deleteUser } from '../server/lib/users'
import { befriend, code, listen, players } from './chathelpers'
import { makeEnv, type TestEnv } from './helpers'

/** Nimmt einen Stream wie `GET /v1/events/me` auf und zerlegt die SSE-Blöcke. */
function fakeStream(env: TestEnv, uuid: string, lastEventId?: string, buffered = () => 0) {
  const chunks: string[] = []
  let ended = false
  let began = false
  const sink: SseSink = { write: (c) => chunks.push(c), buffered, end: () => (ended = true) }
  const stream = openUserStream(env.ctx, uuid, lastEventId, sink, () => (began = true))
  const frames = () => chunks.map((c) => {
    const id = /^id: (.+)$/m.exec(c)?.[1] ?? null
    const event = /^event: (.+)$/m.exec(c)![1]!
    const data = JSON.parse(/^data: (.+)$/m.exec(c)![1]!) as Record<string, unknown>
    return { id, event, data }
  })
  return { stream, frames, chunks, ended: () => ended, began: () => began }
}

describe('event hub', () => {
  it('assigns ids, buffers per user and replays after Last-Event-ID', () => {
    let t = 1000
    const hub = new EventHub(3, 100, { maxPerUserMe: 2, bufferSize: 5, windowMs: 60_000, now: () => t })
    const got: { e: ApiEvent, id: string | null }[] = []
    const sub = hub.subscribe('u1', (e, id) => got.push({ e, id }), () => {}, 'me')!
    hub.publish('u1', { type: 'friend_removed', uuid: 'a' })
    hub.publish('u1', { type: 'friends_changed' })
    const firstId = got[0]!.id!
    expect(firstId).toMatch(new RegExp(`^${hub.epoch}\\.\\d+$`))
    sub.close()
    // Getrennt: weiter gepuffert (für die Wiederaufnahme).
    hub.publish('u1', { type: 'friend_removed', uuid: 'b' })
    const r = hub.replay('u1', firstId)
    expect(r.ok && r.events.map((x) => x.e)).toEqual([{ type: 'friends_changed' }, { type: 'friend_removed', uuid: 'b' }])
    // Flüchtige Ereignisse (Tippen) haben keine ID und werden nicht gepuffert.
    hub.publish('u1', { type: 'chat_typing', conversationId: 'c', uuid: 'x', typing: true, expiresInMs: 8000 }, { ephemeral: true })
    const r2 = hub.replay('u1', firstId)
    expect(r2.ok && r2.events).toHaveLength(2)
    // Andere Epoche (Neustart) / Unsinn.
    expect(hub.replay('u1', 'zzz.1')).toEqual({ ok: false, reason: 'restart' })
    expect(hub.replay('u1', 'kaputt')).toEqual({ ok: false, reason: 'invalid' })
    expect(hub.replay('u1', `${hub.epoch}.999999`)).toEqual({ ok: false, reason: 'invalid' })
    // Ringpuffer übergelaufen → Lücke.
    for (let i = 0; i < 6; i++) hub.publish('u1', { type: 'friends_changed' })
    expect(hub.replay('u1', firstId)).toEqual({ ok: false, reason: 'gap' })
    // Zu alt → Lücke; ohne Stream verfällt der Puffer ganz.
    t += 120_000
    hub.sweep()
    expect(hub.bufferedUsers).toBe(0)
    expect(hub.replay('u1', firstId)).toEqual({ ok: false, reason: 'gap' })
  })

  it('never buffers for users without a recent stream; legacy streams only get legacy types', () => {
    const hub = new EventHub(3, 100)
    hub.publish('nobody', { type: 'friends_changed' })
    expect(hub.bufferedUsers).toBe(0)
    const legacy: ApiEvent[] = []
    hub.subscribe('u', (e) => legacy.push(e), () => {})
    hub.publish('u', { type: 'friends_changed' })
    hub.publish('u', { type: 'friend_removed', uuid: 'x' })
    hub.publish('u', { type: 'friend_removed', uuid: 'y' }, { meOnly: true })
    expect(legacy).toEqual([{ type: 'friend_removed', uuid: 'x' }])
    // Grenzen je Art getrennt.
    expect(hub.subscribe('u', () => {}, () => {})).not.toBeNull()
    expect(hub.subscribe('u', () => {}, () => {})).not.toBeNull()
    expect(hub.subscribe('u', () => {}, () => {})).toBeNull()
    expect(hub.subscribe('u', () => {}, () => {}, 'me')).not.toBeNull()
  })
})

describe('GET /v1/events/me', () => {
  it('hello carries an id; reconnecting with it replays missed chat events', async () => {
    const env = makeEnv()
    const [a, b] = await players(env, 'Alex', 'Bob')
    befriend(env, a!, b!)
    const dm = openDm(env.ctx, a!.uuid, b!.uuid)
    const s1 = fakeStream(env, b!.uuid)
    expect(s1.began()).toBe(true)
    const hello = s1.frames()[0]!
    expect(hello).toMatchObject({ event: 'hello', data: { type: 'hello', keepaliveSec: 20, resumed: false } })
    expect(hello.id).toBe(env.ctx.events.currentId())
    sendMessage(env.ctx, a!.uuid, dm.id, { text: 'eins' })
    const lastSeen = s1.frames().at(-1)!
    expect(lastSeen).toMatchObject({ event: 'chat_message' })
    s1.stream.close()
    expect(s1.ended()).toBe(true)

    // Offline verpasst …
    sendMessage(env.ctx, a!.uuid, dm.id, { text: 'zwei' })
    setTyping(env.ctx, a!.uuid, dm.id, true)
    sendMessage(env.ctx, a!.uuid, dm.id, { text: 'drei' })
    // … und mit Last-Event-ID nachgeholt (ohne das flüchtige Tippen).
    const s2 = fakeStream(env, b!.uuid, lastSeen.id!)
    const f = s2.frames()
    expect(f[0]).toMatchObject({ event: 'hello', id: null, data: { resumed: true } })
    const texts = f.filter((x) => x.event === 'chat_message').map((x) => (x.data.message as { text: string }).text)
    expect(texts).toEqual(['zwei', 'drei'])
    expect(f.some((x) => x.event === 'chat_typing')).toBe(false)
    // Live geht es nahtlos weiter.
    sendMessage(env.ctx, a!.uuid, dm.id, { text: 'vier' })
    expect(s2.frames().at(-1)!.data.message).toMatchObject({ text: 'vier' })
    s2.stream.close()
  })

  it('sends resync when the gap cannot be filled', async () => {
    const env = makeEnv({ limits: { replayBufferSize: 2 } })
    const [a, b] = await players(env, 'Alex', 'Bob')
    befriend(env, a!, b!)
    const dm = openDm(env.ctx, a!.uuid, b!.uuid)
    const s1 = fakeStream(env, b!.uuid)
    const id = s1.frames()[0]!.id!
    s1.stream.close()
    for (let i = 0; i < 4; i++) sendMessage(env.ctx, a!.uuid, dm.id, { text: `m${i}` })
    const s2 = fakeStream(env, b!.uuid, id)
    expect(s2.frames().map((x) => x.event)).toEqual(['hello', 'resync'])
    expect(s2.frames()[1]!.data).toEqual({ type: 'resync', reason: 'gap' })
    // hello trägt dann wieder eine frische ID.
    expect(s2.frames()[0]!.id).toBe(env.ctx.events.currentId())
    const s3 = fakeStream(env, b!.uuid, 'epochalt.5')
    expect(s3.frames()[1]!.data).toEqual({ type: 'resync', reason: 'restart' })
  })

  it('limits streams per user, closes slow clients and on account deletion', async () => {
    const env = makeEnv({ limits: { maxUserStreamsPerUser: 2 } })
    const [a, b] = await players(env, 'Alex', 'Bob')
    befriend(env, a!, b!)
    const dm = openDm(env.ctx, a!.uuid, b!.uuid)
    let backlog = 0
    const slow = fakeStream(env, b!.uuid, undefined, () => backlog)
    fakeStream(env, b!.uuid)
    let began = false
    expect(code(() => openUserStream(env.ctx, b!.uuid, undefined, { write: () => {}, buffered: () => 0, end: () => {} }, () => (began = true)))).toBe('too_many_streams')
    expect(began).toBe(false)
    backlog = MAX_BUFFERED_BYTES + 1
    sendMessage(env.ctx, a!.uuid, dm.id, { text: 'zu langsam' })
    expect(slow.ended()).toBe(true)
    // Platz wieder frei.
    const s = fakeStream(env, b!.uuid)
    deleteUser(env.ctx, b!.uuid)
    expect(s.ended()).toBe(true)
  })

  it('delivers friend presence including a friend_online notice', async () => {
    const env = makeEnv()
    const [a, b, c] = await players(env, 'Alex', 'Bob', 'Carl')
    befriend(env, a!, b!)
    const evB = listen(env, b!.uuid)
    const evC = listen(env, c!.uuid)
    reportPresence(env.ctx, a!, { state: 'online', via: 'launcher' })
    reportPresence(env.ctx, a!, { state: 'in-game', via: 'client', game: { version: '1.21.4', loader: 'fabric' } })
    expect(evB.of('friend_online')).toEqual([
      { type: 'friend_online', friend: { uuid: a!.uuid, name: 'Alex' }, presence: expect.objectContaining({ state: 'online' }) },
    ])
    expect(evB.of('presence').map((p) => p.presence?.state)).toEqual(['online', 'in-game'])
    reportPresence(env.ctx, a!, { state: 'offline' })
    reportPresence(env.ctx, a!, { state: 'online', via: 'launcher' })
    expect(evB.of('friend_online')).toHaveLength(2)
    expect(evC.events).toEqual([])
  })
})
