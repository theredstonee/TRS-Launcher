import { describe, expect, it } from 'vitest'
import {
  adminRooms, createRoom, friendsRooms, invite, join, myRooms, roomContent, roomFor, setRoomContent, type RoomSettings,
} from '../server/lib/hosting'
import { MIGRATIONS } from '../server/lib/migrations'
import { HOSTING_CONTENT, hostingContentBody } from '../server/lib/schemas'
import { befriend, code, listen, players } from './chathelpers'
import { makeEnv, type TestEnv } from './helpers'

const HOSTING_ENV = { RELAY_SECRET: 'relay-secret-0123456789abcdef-0123456789abcdef', RELAY_HOST: 'relay.example.test' }
const env = (): TestEnv => makeEnv({ env: HOSTING_ENV })

const SETTINGS: RoomSettings = {
  name: 'Modded', mcVersion: '1.21.11', loader: 'fabric', maxPlayers: 4, gameMode: 'survival',
  pvp: true, cheats: false, open: true, visibility: 'friends',
}

const h = (c: string, n: number) => c.repeat(n)

const STORE = {
  name: 'Sodium', version: '0.6.0', file: 'sodium-fabric-0.6.0.jar', size: 1_200_000, required: false,
  source: 'modrinth', projectId: 'AANobbMI', fileId: 'Yp8wLY1P', sha1: h('a', 40), sha512: h('b', 128),
}
const CF = {
  name: 'JEI', version: '19.0', file: 'jei-1.21.11-19.0.jar', size: 2_000_000, required: true,
  source: 'curseforge', projectId: '238222', fileId: '5846800', sha1: h('c', 40), fingerprint: 123456789,
}
const HOST = {
  name: 'Eigene Blöcke', version: '1.0', file: 'eigene-bloecke-1.0.jar', size: 50_000, required: true,
  source: 'host', sha1: h('d', 40), sha256: h('e', 64),
}
const MANUAL = { name: 'Geheim', version: '', file: 'geheim.jar', size: 10, required: false, source: 'manual', sha1: h('f', 40) }
const PACK = { name: 'Schöne Texturen', size: 3_000_000, sha1: h('1', 40), sha256: h('2', 64) }

const parse = (v: unknown) => hostingContentBody.safeParse(v)

describe('content schema (§21.10)', () => {
  it('accepts store, host and manual mods plus a pack', () => {
    const r = parse({ mods: [STORE, CF, HOST, MANUAL], pack: PACK })
    expect(r.success).toBe(true)
    expect(parse({}).data).toEqual({ mods: [], pack: null })
  })

  it('limits count, sizes and totals', () => {
    const many = Array.from({ length: HOSTING_CONTENT.maxMods + 1 }, (_, i) => ({ ...MANUAL, sha1: i.toString(16).padStart(40, '0') }))
    expect(parse({ mods: many }).success).toBe(false)
    expect(parse({ mods: many.slice(0, HOSTING_CONTENT.maxMods) }).success).toBe(true)
    expect(parse({ mods: [{ ...HOST, size: 64 * 1024 * 1024 + 1 }] }).success).toBe(false)
    const nine = Array.from({ length: 9 }, (_, i) => ({ ...HOST, size: 60 * 1024 * 1024, sha1: i.toString(16).padStart(40, '0') }))
    expect(parse({ mods: nine }).success).toBe(false)
    expect(parse({ mods: nine.slice(0, 8) }).success).toBe(true)
    expect(parse({ pack: { ...PACK, size: 250 * 1024 * 1024 + 1 } }).success).toBe(false)
    expect(parse({ mods: [STORE, { ...CF, sha1: STORE.sha1 }] }).success).toBe(false)
  })

  it('needs the right ids and hashes per source', () => {
    expect(parse({ mods: [{ ...STORE, sha512: undefined }] }).success).toBe(false)
    expect(parse({ mods: [{ ...STORE, projectId: 'short' }] }).success).toBe(false)
    expect(parse({ mods: [{ ...CF, fileId: 'abc' }] }).success).toBe(false)
    expect(parse({ mods: [{ ...HOST, sha256: undefined }] }).success).toBe(false)
    expect(parse({ mods: [{ ...HOST, projectId: '1' }] }).success).toBe(false)
    expect(parse({ mods: [{ ...HOST, sha1: h('A', 40) }] }).success).toBe(false)
    expect(parse({ mods: [{ ...HOST, source: 'url' }] }).success).toBe(false)
    expect(parse({ mods: [{ ...HOST, url: 'https://x' }] }).success).toBe(false)
    expect(parse({ pack: { ...PACK, url: 'https://x' } }).success).toBe(false)
  })

  it('rejects paths, traversal and control characters', () => {
    for (const file of ['../evil.jar', 'mods/evil.jar', 'C:\\evil.jar', 'evil.zip', '.hidden.jar', 'a..b.jar', 'evil.jar\u0000.jar']) {
      expect(parse({ mods: [{ ...HOST, file }] }).success, file).toBe(false)
    }
    expect(parse({ mods: [{ ...HOST, name: 'Böse\u202eName' }] }).success).toBe(false)
    expect(parse({ mods: [{ ...HOST, name: '' }] }).success).toBe(false)
    expect(parse({ mods: [{ ...HOST, version: 'x'.repeat(65) }] }).success).toBe(false)
  })
})

describe('room content', () => {
  it('is set by the host only, summarised in every view and sent to the audience', async () => {
    const e = env()
    const [host, bob, eve] = await players(e, 'Host', 'Bob', 'Eve')
    befriend(e, host!, bob!)
    const room = createRoom(e.ctx, host!, SETTINGS).room
    expect(room.content).toBeNull()
    const lb = listen(e, bob!.uuid)
    const body = hostingContentBody.parse({ mods: [STORE, CF, HOST, MANUAL], pack: PACK })
    expect(code(() => setRoomContent(e.ctx, bob!.uuid, room.id, body))).toBe('room_not_found')
    const hv = setRoomContent(e.ctx, host!.uuid, room.id, body)
    const summary = { mods: 4, required: 2, fromHost: 1, manual: 1, pack: { name: PACK.name, size: PACK.size, sha1: PACK.sha1 } }
    expect(hv.content).toEqual(summary)
    expect(lb.of('hosting_room_updated')[0]!.room.content).toEqual(summary)
    expect(friendsRooms(e.ctx, bob!.uuid)[0]!.content).toEqual(summary)
    expect(myRooms(e.ctx, host!.uuid)[0]!.content).toEqual(summary)
    expect(adminRooms(e.ctx)[0]!.content).toEqual(summary)
    // Volle Liste: Freunde ja, Fremde nicht.
    const full = roomContent(e.ctx, bob!.uuid, room.id)
    expect(full.roomId).toBe(room.id)
    expect(full.mods.map((m) => m.source)).toEqual(['modrinth', 'curseforge', 'host', 'manual'])
    expect(full.mods[2]).toMatchObject({ file: HOST.file, sha256: HOST.sha256, size: HOST.size, required: true })
    expect(full.pack).toEqual(PACK)
    expect(code(() => roomContent(e.ctx, eve!.uuid, room.id))).toBe('room_not_found')
    // Unverändert → kein neues Ereignis.
    lb.clear()
    setRoomContent(e.ctx, host!.uuid, room.id, body)
    expect(lb.of('hosting_room_updated')).toEqual([])
    // Nichts mehr teilen.
    expect(setRoomContent(e.ctx, host!.uuid, room.id, null).content).toBeNull()
    expect(roomContent(e.ctx, bob!.uuid, room.id)).toEqual({ roomId: room.id, mods: [], pack: null })
    expect(lb.of('hosting_room_updated')[0]!.room.content).toBeNull()
    expect(setRoomContent(e.ctx, host!.uuid, room.id, { mods: [], pack: null }).content).toBeNull()
  })

  it('invited members see the content of invite-only worlds', async () => {
    const e = env()
    const [host, bob] = await players(e, 'Host', 'Bob')
    befriend(e, host!, bob!)
    const room = createRoom(e.ctx, host!, { ...SETTINGS, visibility: 'invited' }).room
    setRoomContent(e.ctx, host!.uuid, room.id, hostingContentBody.parse({ pack: PACK }))
    expect(code(() => roomContent(e.ctx, bob!.uuid, room.id))).toBe('room_not_found')
    invite(e.ctx, host!, room.id, bob!.uuid, { chat: false })
    expect(roomContent(e.ctx, bob!.uuid, room.id).pack).toEqual(PACK)
    join(e.ctx, bob!, { roomId: room.id })
    expect(roomFor(e.ctx, bob!.uuid, room.id).content).toMatchObject({ mods: 0, pack: { sha1: PACK.sha1 } })
  })

  it('cleans names and keeps nothing but metadata', async () => {
    const e = env()
    const [host] = await players(e, 'Host')
    const room = createRoom(e.ctx, host!, SETTINGS).room
    setRoomContent(e.ctx, host!.uuid, room.id, hostingContentBody.parse({ mods: [{ ...HOST, name: '  Viele   Leerzeichen ', version: ' 1.0 ' }] }))
    const c = roomContent(e.ctx, host!.uuid, room.id)
    expect(c.mods[0]).toMatchObject({ name: 'Viele Leerzeichen', version: '1.0' })
    expect(Object.keys(c.mods[0]!).sort()).toEqual(['file', 'name', 'required', 'sha1', 'sha256', 'size', 'source', 'version'])
    const row = e.ctx.db.prepare('SELECT content FROM hosting_rooms WHERE id = ?').get(room.id) as { content: string }
    expect(JSON.parse(row.content)).toEqual({ mods: c.mods, pack: null })
  })

  it('comes with migration 11 (content column)', () => {
    const m = MIGRATIONS.find((x) => x.version === 11)
    expect(m?.sql).toContain('hosting_rooms ADD COLUMN content')
    expect(MIGRATIONS.at(-1)!.version).toBe(11)
  })
})
