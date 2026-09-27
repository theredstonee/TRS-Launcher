import { describe, expect, it } from 'vitest'
import { addMembers, createGroup, editMessage, listMessages, openDm, sendMessage, type WaypointInput } from '../server/lib/chat'
import { block } from '../server/lib/friends'
import { adminReportDetail, createReport } from '../server/lib/moderation'
import { addFilterWord } from '../server/lib/safety'
import { sendMessageBody } from '../server/lib/schemas'
import { befriend, code, listen, players } from './chathelpers'
import { ADMIN, login, makeEnv } from './helpers'

const BASE: WaypointInput = {
  name: 'Base',
  x: 100,
  y: 64,
  z: -20,
  dimension: 'minecraft:overworld',
  world: { type: 'server', address: 'Play.Example.net:25566' },
  color: 0xe0281e,
}

const parse = (waypoint: unknown) => sendMessageBody.safeParse({ waypoint })

describe('waypoint cards (§18.10)', () => {
  it('validates strictly: numbers, ranges, dimension, world, no extra fields', () => {
    expect(parse(BASE).success).toBe(true)
    expect(parse({ ...BASE, world: { type: 'world', id: '0123456789abcdef' } }).success).toBe(true)
    expect(parse({ ...BASE, color: undefined }).success).toBe(true)
    for (const bad of [
      { ...BASE, x: 1.5 },
      { ...BASE, x: 30_000_001 },
      { ...BASE, z: -30_000_001 },
      { ...BASE, y: 4097 },
      { ...BASE, y: -2049 },
      { ...BASE, x: '100' },
      { ...BASE, dimension: 'overworld' },
      { ...BASE, dimension: 'Minecraft:Overworld' },
      { ...BASE, dimension: 'minecraft:the nether' },
      { ...BASE, world: { type: 'server', address: 'http://evil.example' } },
      { ...BASE, world: { type: 'server', address: 'a b' } },
      { ...BASE, world: { type: 'world', id: 'MyWorld' } },
      { ...BASE, world: { type: 'world', id: '0123456789abcdef', name: 'Welt' } },
      { ...BASE, world: { type: 'lan' } },
      { ...BASE, color: 0x1000000 },
      { ...BASE, note: 'Freitext' },
      { ...BASE, name: 'x'.repeat(201) },
    ]) expect(parse(bad).success, JSON.stringify(bad)).toBe(false)
  })

  it('is sent in a DM, sanitised, shown to both and in reply previews', async () => {
    const env = makeEnv()
    const [a, b] = await players(env, 'Alex', 'Bob')
    befriend(env, a!, b!)
    const dm = openDm(env.ctx, a!.uuid, b!.uuid)
    const ev = listen(env, b!.uuid)
    const m = sendMessage(env.ctx, a!.uuid, dm.id, { waypoint: { ...BASE, name: '  Ba‮se\n 1 ' } }).message
    expect(m.text).toBeNull()
    expect(m.waypoint).toEqual({
      name: 'Base 1', x: 100, y: 64, z: -20, dimension: 'minecraft:overworld',
      world: { type: 'server', address: 'play.example.net:25566' }, color: 0xe0281e,
    })
    expect(ev.of('chat_message')[0]!.message.waypoint?.name).toBe('Base 1')
    const sp = sendMessage(env.ctx, a!.uuid, dm.id, { text: 'hier', waypoint: { ...BASE, world: { type: 'world', id: '0123456789abcdef' }, color: undefined } }).message
    expect(sp.waypoint).toMatchObject({ world: { type: 'world', id: '0123456789abcdef' }, color: null })
    expect(sp.text).toBe('hier')
    const reply = sendMessage(env.ctx, b!.uuid, dm.id, { text: 'ok', replyTo: m.id }).message
    expect(reply.replyTo).toMatchObject({ waypoint: true, invite: false, preview: null })
    // Text darf beim Bearbeiten leer werden – die Karte bleibt.
    expect(editMessage(env.ctx, a!.uuid, sp.id, '').waypoint).not.toBeNull()
    expect(listMessages(env.ctx, b!.uuid, dm.id, { limit: 50 }).messages.map((x) => !!x.waypoint)).toEqual([true, true, false])
  })

  it('rejects bad names, conflicts and filtered words; hides it for blockers', async () => {
    const env = makeEnv()
    await login(env, 'Admin', ADMIN)
    const [a, b] = await players(env, 'Alex', 'Bob')
    befriend(env, a!, b!)
    const dm = openDm(env.ctx, a!.uuid, b!.uuid)
    expect(code(() => sendMessage(env.ctx, a!.uuid, dm.id, { waypoint: { ...BASE, name: ' ​ ' } }))).toBe('invalid_waypoint')
    expect(code(() => sendMessage(env.ctx, a!.uuid, dm.id, { waypoint: { ...BASE, name: 'x'.repeat(33) } }))).toBe('invalid_waypoint')
    expect(code(() => sendMessage(env.ctx, a!.uuid, dm.id, { waypoint: BASE, invite: { address: 'a.example' } }))).toBe('invite_conflict')
    addFilterWord(env.ctx, ADMIN, 'boese', 'word', 'block')
    expect(code(() => sendMessage(env.ctx, a!.uuid, dm.id, { waypoint: { ...BASE, name: 'boese Ecke' } }))).toBe('message_blocked')
    const m = sendMessage(env.ctx, a!.uuid, dm.id, { waypoint: BASE }).message
    const r = createReport(env.ctx, b!.uuid, { kind: 'message', reason: 'spam', messageId: m.id })
    expect(adminReportDetail(env.ctx, r.id).evidence?.messages.find((x) => x.id === m.id)?.waypoint?.name).toBe('Base')
    block(env.ctx, b!.uuid, { uuid: a!.uuid })
    const seen = listMessages(env.ctx, b!.uuid, dm.id, { limit: 50 }).messages.at(-1)!
    expect(seen).toMatchObject({ hidden: true, waypoint: null })
  })

  it('counts a server waypoint as an invite in groups; a singleplayer one does not', async () => {
    const env = makeEnv()
    const [owner, a, c] = await players(env, 'Owner', 'Alex', 'Carl')
    befriend(env, owner!, a!)
    befriend(env, owner!, c!)
    const g = createGroup(env.ctx, owner!.uuid, 'Crew', [a!.uuid])
    addMembers(env.ctx, owner!.uuid, g.id, [c!.uuid])
    // Alex ist nicht mit Carl befreundet.
    expect(code(() => sendMessage(env.ctx, a!.uuid, g.id, { waypoint: BASE }))).toBe('links_not_allowed')
    expect(sendMessage(env.ctx, a!.uuid, g.id, { waypoint: { ...BASE, world: { type: 'world', id: 'fedcba9876543210' } } }).message.waypoint).not.toBeNull()
    expect(sendMessage(env.ctx, owner!.uuid, g.id, { waypoint: BASE }).message.waypoint?.world).toEqual({ type: 'server', address: 'play.example.net:25566' })
  })
})
