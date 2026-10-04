import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  type ServerAddresses,
  type ShareStatus,
  inviteAddress,
  inviteCard,
  inviteErrorText,
  inviteMethods,
  inviteOutcomeSchema,
  offShare,
  serverAddressesSchema,
  shareErrorText,
  shareStatusSchema,
  shareUpdateSchema,
} from '../app/utils/serverExport'

// Lokale Server teilen: Einladungswege und -karten, Prüfung der Kern-Antworten
// und der Teilen-Stand im Store (TRS Relay, e4mc).

const localServers = {
  shareStatus: vi.fn(async (): Promise<ShareStatus> => offShare),
  share: vi.fn(async (): Promise<ShareStatus> => relayOnline),
  unshare: vi.fn(async (): Promise<ShareStatus> => offShare),
}
vi.mock('../app/utils/backend', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../app/utils/backend')>()),
  backend: { localServers },
}))
const toasts = { ok: vi.fn(), info: vi.fn(), error: vi.fn() }
vi.mock('../app/stores/toasts', () => ({ useToasts: () => toasts }))

const relayOnline: ShareStatus = { ...offShare, relay: { state: 'online', roomId: 'h0123456789abcdef0123', address: null, error: null } }
const e4mcOnline: ShareStatus = { ...offShare, e4mc: { state: 'online', roomId: null, address: 'calm-fox.eu.e4mc.link', error: null } }
const addresses: ServerAddresses = { port: 25565, local: 'localhost:25565', lan: '192.168.1.20:25565', public: '93.184.216.34:25565' }

describe('Einladungswege', () => {
  it('Relay zuerst, dann e4mc und die öffentliche Adresse – nur, wenn es sie gibt', () => {
    expect(inviteMethods(offShare, null)).toEqual(['relay'])
    expect(inviteMethods(offShare, addresses)).toEqual(['relay', 'public'])
    expect(inviteMethods(e4mcOnline, addresses)).toEqual(['relay', 'e4mc', 'public'])
    // e4mc verbindet noch: keine Adresse.
    const connecting: ShareStatus = { ...offShare, e4mc: { ...e4mcOnline.e4mc, state: 'reconnecting' } }
    expect(inviteMethods(connecting, null)).toEqual(['relay'])
    // Server-Karten nehmen keine IPv6-Adressen.
    expect(inviteMethods(offShare, { ...addresses, public: '[2001:db8::1]:25565' })).toEqual(['relay'])
  })

  it('liefert die Adresse je Weg', () => {
    expect(inviteAddress('e4mc', e4mcOnline, addresses)).toBe('calm-fox.eu.e4mc.link')
    expect(inviteAddress('public', offShare, addresses)).toBe('93.184.216.34:25565')
    expect(inviteAddress('relay', relayOnline, addresses)).toBeNull()
    expect(inviteAddress('public', offShare, null)).toBeNull()
  })

  it('baut die Server-Karte wie die API sie will', () => {
    expect(inviteCard('Calm-Fox.EU.e4mc.link', '  Mein\n  Server ', 'n1')).toEqual({
      invite: { address: 'calm-fox.eu.e4mc.link', name: 'Mein Server' },
      nonce: 'n1',
    })
    expect(inviteCard('1.2.3.4:25565', 'x'.repeat(40), 'n2').invite.name).toHaveLength(32)
    expect(inviteCard('1.2.3.4:25565', '   ', 'n3').invite.name).toBeNull()
  })

  it('übersetzt Fehlercodes (unbekannte allgemein)', () => {
    expect(shareErrorText(null)).toBeNull()
    expect(shareErrorText('replaced')).not.toBe(shareErrorText('weird_code'))
    expect(inviteErrorText('not_friends')).not.toBe(inviteErrorText(null))
  })
})

describe('Antworten des Kerns', () => {
  it('prüft Adressen, Stand und Einladungsergebnisse', () => {
    expect(serverAddressesSchema.safeParse(addresses).success).toBe(true)
    expect(serverAddressesSchema.safeParse({ ...addresses, port: 0 }).success).toBe(false)
    expect(shareStatusSchema.safeParse(e4mcOnline).success).toBe(true)
    expect(shareStatusSchema.safeParse({ ...offShare, e4mc: { ...e4mcOnline.e4mc, address: 'evil/../x' } }).success).toBe(false)
    expect(shareStatusSchema.safeParse({ ...offShare, relay: { ...relayOnline.relay, state: 'hosting' } }).success).toBe(false)
    expect(shareUpdateSchema.safeParse({ id: 'mein-server', status: relayOnline }).success).toBe(true)
    expect(inviteOutcomeSchema.safeParse({ uuid: 'b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0', ok: false, code: 'not_friends' }).success).toBe(true)
    expect(inviteOutcomeSchema.safeParse({ uuid: 'Bob', ok: true, code: null }).success).toBe(false)
  })
})

describe('Teilen im Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('übernimmt den Stand nach dem Einschalten und meldet Fehler', async () => {
    const { useLocalServersStore } = await import('../app/stores/localServers')
    const store = useLocalServersStore()
    expect(store.shareOf('mein-server')).toEqual(offShare)
    expect(await store.setShared('mein-server', 'relay', true)).toBe(true)
    expect(localServers.share).toHaveBeenCalledWith('mein-server', 'relay')
    expect(store.shareOf('mein-server').relay.roomId).toBe('h0123456789abcdef0123')

    localServers.share.mockRejectedValueOnce(new Error('hosting_unavailable'))
    expect(await store.setShared('mein-server', 'e4mc', true)).toBe(false)
    expect(toasts.error).toHaveBeenCalledTimes(1)
    expect(localServers.shareStatus).toHaveBeenCalledWith('mein-server')

    expect(await store.setShared('mein-server', 'relay', false)).toBe(true)
    expect(localServers.unshare).toHaveBeenCalledWith('mein-server', 'relay')
  })
})
