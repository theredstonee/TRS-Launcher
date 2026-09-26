import { describe, expect, it } from 'vitest'
import { chatMessageSchema, liveEventSchema, messageSummary } from '../app/utils/chat'
import {
  canJoinWith,
  formatJoinCode,
  hostedWorldFrom,
  hostingDeliverySchema,
  hostingRoomSchema,
  loadersCompatible,
  matchingInstances,
  parseJoinCode,
  quickInstanceName,
  roomFull,
  sortRooms,
  upsertRoom,
  worldVersionLabel,
  type HostingInstanceLike,
} from '../app/utils/hosting'

import { CARD, HOST, room } from './fixtures/hosting'

function instance(id: string, version: string, kind: HostingInstanceLike['loader']['kind'], extra: Partial<HostingInstanceLike> = {}): HostingInstanceLike {
  return { id, gameVersion: version, loader: { kind }, overrides: { trsClient: null, boost: null }, lastPlayed: null, ...extra }
}

describe('Beitrittscodes', () => {
  it('zeigt und liest Codes wie die API (ohne 0/O/1/I/L, Striche egal)', () => {
    expect(formatJoinCode('K7QM2X')).toBe('K7Q-M2X')
    expect(formatJoinCode(null)).toBe('')
    expect(parseJoinCode(' k7q-m2x ')).toBe('K7QM2X')
    expect(parseJoinCode('K7QM2')).toBeNull()
    expect(parseJoinCode('K7QM20')).toBeNull()
    expect(parseJoinCode('LOL123')).toBeNull()
  })
})

describe('Passende Instanz', () => {
  const world = { mcVersion: '1.21.11', loader: 'fabric' as const }

  it('gleiche Version, passender Loader, TRS Client an', () => {
    expect(canJoinWith(instance('a', '1.21.11', 'fabric'), world)).toBe(true)
    expect(canJoinWith(instance('b', '1.21.10', 'fabric'), world)).toBe(false)
    expect(canJoinWith(instance('c', '1.21.11', 'forge'), world)).toBe(false)
    expect(canJoinWith(instance('d', '1.21.11', 'fabric', { overrides: { trsClient: false } }), world)).toBe(false)
    // Vanilla läuft mit der TRS-Optimierung als Fabric – ohne sie gibt es keinen TRS Client.
    expect(canJoinWith(instance('e', '1.21.11', 'vanilla'), world)).toBe(true)
    expect(canJoinWith(instance('f', '1.21.11', 'vanilla', { overrides: { boost: false } }), world)).toBe(false)
    expect(loadersCompatible('vanilla', 'fabric') && !loadersCompatible('neoforge', 'forge')).toBe(true)
  })

  it('beste zuerst: gleicher Loader, dann zuletzt gespielt', () => {
    const list = [
      instance('vanilla', '1.21.11', 'vanilla', { lastPlayed: '2026-09-26T12:00:00Z' }),
      instance('alt', '1.21.11', 'fabric', { lastPlayed: '2026-09-01T12:00:00Z' }),
      instance('neu', '1.21.11', 'fabric', { lastPlayed: '2026-09-25T12:00:00Z' }),
      instance('forge', '1.21.11', 'forge'),
    ]
    expect(matchingInstances(list, world).map((i) => i.id)).toEqual(['neu', 'alt', 'vanilla'])
    expect(matchingInstances(list, { mcVersion: '1.20.1', loader: 'fabric' })).toEqual([])
  })

  it('Name einer schnell angelegten Instanz', () => {
    expect(quickInstanceName({ name: 'Insel', mcVersion: '1.21.11', loader: 'fabric' })).toBe('Insel (1.21.11 Fabric)')
    expect(quickInstanceName({ name: '<>|', mcVersion: '1.21.11', loader: 'neoforge' })).toBe('1.21.11 NeoForge')
    expect(quickInstanceName({ name: 'x'.repeat(80), mcVersion: '1.21.11', loader: 'fabric' }).length).toBeLessThanOrEqual(48)
  })
})

describe('Welten und Karten', () => {
  it('Anweisung fürs Spiel: nur Raum-ID, Code und Eckdaten', () => {
    const fromCard = hostedWorldFrom(CARD)
    expect(fromCard).toEqual({ roomId: CARD.roomId, code: 'K7QM2X', name: 'Insel', host: HOST, mcVersion: '1.21.11', loader: 'fabric' })
    // Gäste sehen den Code einer Welt nicht – dann nur die Raum-ID (oder der bekannte Code der Karte).
    expect(hostedWorldFrom(room('h00000000000000000001')).code).toBeNull()
    expect(hostedWorldFrom(room('h00000000000000000001'), 'K7QM2X').code).toBe('K7QM2X')
    expect(worldVersionLabel(CARD)).toBe('1.21.11 Fabric')
  })

  it('Liste: Einladungen und Anfragen vor fremden Welten, Ereignisse ersetzen', () => {
    const list = sortRooms([
      room('h00000000000000000001', { createdAt: '2026-09-26T11:00:00Z' }),
      room('h00000000000000000002', { myState: 'invited', createdAt: '2026-09-26T09:00:00Z' }),
      room('h00000000000000000003', { myState: 'accepted' }),
    ])
    expect(list.map((r) => r.id.slice(-1))).toEqual(['3', '2', '1'])
    const updated = upsertRoom(list, room('h00000000000000000001', { players: 4 }))
    expect(updated).toHaveLength(3)
    expect(roomFull(updated.find((r) => r.id.endsWith('1'))!)).toBe(true)
  })

  it('Schemas passen zu dem, was der Kern schickt', () => {
    expect(hostingRoomSchema.safeParse(room('h0123456789abcdef0123', { code: 'K7QM2X', members: [{ uuid: HOST.uuid, name: 'Bob', state: 'requested', since: null }] })).success).toBe(true)
    expect(hostingRoomSchema.safeParse(room('h../x')).success).toBe(false)
    expect(hostingDeliverySchema.parse({ state: 'pending', roomId: CARD.roomId }).state).toBe('pending')
    expect(hostingDeliverySchema.parse({ state: 'none' }).state).toBe('none')
  })

  it('Weltkarten im Chat (ältere Kerne ohne Feld → null) und in der Vorschau', () => {
    const base = {
      id: 'm0a1b2c3d4e5f60718293', conversationId: 'c1f0e2d3c4b5a6978899a', seq: 7, kind: 'text', sender: HOST, text: null,
      invite: null, attachments: [], replyTo: null, system: null, reactions: [], createdAt: null, editedAt: null,
      deleted: false, deletedBy: null, hidden: false, nonce: null,
    }
    expect(chatMessageSchema.parse(base).world).toBeNull()
    const withCard = chatMessageSchema.parse({ ...base, world: CARD })
    expect(withCard.world?.code).toBe('K7QM2X')
    expect(messageSummary(withCard)).toContain('Insel')
    expect(chatMessageSchema.safeParse({ ...base, world: { ...CARD, code: 'k7' } }).success).toBe(false)
  })

  it('Echtzeit: hosting_* kommt durch, hosting_signal nicht', () => {
    const invite = liveEventSchema.parse({ type: 'hosting_invite', room: room(CARD.roomId, { myState: 'invited' }), from: HOST })
    expect(invite.type).toBe('hosting_invite')
    expect(liveEventSchema.parse({ type: 'hosting_room_closed', roomId: CARD.roomId, reason: 'expired' }).type).toBe('hosting_room_closed')
    expect(liveEventSchema.parse({ type: 'hosting_kicked', roomId: CARD.roomId, banned: true }).type).toBe('hosting_kicked')
    expect(liveEventSchema.safeParse({ type: 'hosting_signal', roomId: CARD.roomId, data: '{}' }).success).toBe(false)
  })
})
