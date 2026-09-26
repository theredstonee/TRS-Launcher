import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { CARD, HOST, ROOM_ID, room } from './fixtures/hosting'
import type { HostingDelivery, HostingJoinResult } from '../app/utils/hosting'

// Welt-Hosting auf der Launcher-Seite: Beitreten/Anfragen, Warten auf den Host,
// passende Instanz, Start mit der Anweisung ans Spiel und die Hinweise dazu.

const hosting = {
  friendsRooms: vi.fn(async () => [room()]),
  myRooms: vi.fn(async () => []),
  room: vi.fn(async () => null),
  join: vi.fn(async (): Promise<HostingJoinResult> => ({ status: 'accepted', room: room(ROOM_ID, { myState: 'accepted' }) })),
  leave: vi.fn(async () => {}),
  delivery: vi.fn(async (): Promise<HostingDelivery> => ({ state: 'delivered', roomId: ROOM_ID })),
}
const launchInstance = vi.fn(async () => 1234)
const social = { quietHours: vi.fn(async () => false), notifyNative: vi.fn(async () => {}), focusWindow: vi.fn(async () => {}) }

vi.mock('../app/utils/backend', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../app/utils/backend')>()),
  backend: { hosting, launchInstance, social },
}))
vi.mock('../app/utils/sound', () => ({ playNotificationSound: vi.fn() }))

// Stores mit Nuxt-Auto-Imports: als Attrappen bereitgestellt.
type Phase = 'idle' | 'preparing' | 'running'
const phases: Record<string, Phase> = {}
const instanceItems = [
  { id: 'fabric-neu', name: 'Fabric neu', gameVersion: '1.21.11', loader: { kind: 'fabric' }, overrides: {}, lastPlayed: '2026-09-25T10:00:00Z' },
  { id: 'forge', name: 'Forge', gameVersion: '1.21.11', loader: { kind: 'forge' }, overrides: {}, lastPlayed: null },
]
const instances = {
  items: [...instanceItems],
  loaded: true,
  load: vi.fn(async () => {}),
  create: vi.fn(async (n: { name: string; gameVersion: string; loader: { kind: string } }) => {
    const created = { id: 'neu', name: n.name, gameVersion: n.gameVersion, loader: n.loader, overrides: {}, lastPlayed: null }
    instances.items.unshift(created)
    return created
  }),
}
const games = {
  state: (id: string) => ({ phase: phases[id] ?? 'idle' }),
  launch: vi.fn(async () => true),
}
const toasts = { ok: vi.fn(), info: vi.fn(), error: vi.fn() }
const router = { push: vi.fn(async () => {}) }

beforeAll(() => {
  vi.stubGlobal('useInstancesStore', () => instances)
  vi.stubGlobal('useGamesStore', () => games)
  vi.stubGlobal('useToasts', () => toasts)
  vi.stubGlobal('useRouter', () => router)
})

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  instances.items = [...instanceItems]
  for (const k of Object.keys(phases)) delete phases[k]
})

afterEach(() => {
  vi.useRealTimers()
})

async function store() {
  const { useHostingStore } = await import('../app/stores/hosting')
  return useHostingStore()
}

async function socialToasts() {
  const { useSocialToasts } = await import('../app/stores/socialToasts')
  return useSocialToasts()
}

describe('Beitreten', () => {
  it('eingeladen (200): startet die einzige passende Instanz mit der Anweisung – ohne Tokens', async () => {
    vi.useFakeTimers()
    const s = await store()
    await s.join({ card: CARD })
    expect(hosting.join).toHaveBeenCalledWith({ code: 'K7QM2X' })
    expect(games.launch).toHaveBeenCalledTimes(1)
    const [id, server, address, world] = games.launch.mock.calls[0] as unknown as [string, null, null, Record<string, unknown>]
    expect([id, server, address]).toEqual(['fabric-neu', null, null])
    expect(world).toEqual({ roomId: ROOM_ID, code: 'K7QM2X', name: 'Insel', host: HOST, mcVersion: '1.21.11', loader: 'fabric' })
    expect(JSON.stringify(world)).not.toMatch(/token|relay|stun/)
    expect(s.handoff?.state).toBe('starting')

    // Danach: Kern fragen, bis das Spiel den Beitritt hat.
    await vi.advanceTimersByTimeAsync(2_100)
    expect(hosting.delivery).toHaveBeenCalledWith('fabric-neu')
    expect(s.handoff?.state).toBe('delivered')
    expect(toasts.ok).toHaveBeenCalledWith(expect.stringContaining('Insel'))
  })

  it('nicht eingeladen (202): Anfrage gesendet, nach Zustimmung startet der Launcher selbst', async () => {
    hosting.join.mockResolvedValueOnce({ status: 'requested', room: room(ROOM_ID, { myState: 'requested' }) })
    const s = await store()
    await s.join({ code: 'K7QM2X' })
    expect(games.launch).not.toHaveBeenCalled()
    expect(s.waiting[ROOM_ID]?.code).toBe('K7QM2X')
    expect(toasts.info).toHaveBeenCalledWith(expect.stringContaining('Bob'))

    await s.onEvent({ type: 'hosting_join_accepted', room: room(ROOM_ID, { myState: 'accepted' }) })
    expect(s.waiting[ROOM_ID]).toBeUndefined()
    expect(games.launch).toHaveBeenCalledTimes(1)
    expect((games.launch.mock.calls[0] as unknown[])[3]).toMatchObject({ roomId: ROOM_ID, code: 'K7QM2X' })
  })

  it('abgelehnt: Hinweis, kein Start', async () => {
    hosting.join.mockResolvedValueOnce({ status: 'requested', room: room(ROOM_ID, { myState: 'requested' }) })
    const s = await store()
    await s.join({ code: 'K7QM2X' })
    await s.onEvent({ type: 'hosting_join_declined', roomId: ROOM_ID })
    expect(s.waiting[ROOM_ID]).toBeUndefined()
    expect(toasts.info).toHaveBeenLastCalledWith(expect.stringContaining('Bob'))
    expect(s.rooms.find((r) => r.id === ROOM_ID)?.myState).toBeNull()
    expect(games.launch).not.toHaveBeenCalled()
  })

  it('mehrere passende Instanzen → Auswahl; keine → neue anlegen und starten', async () => {
    instances.items.push({ id: 'fabric-alt', name: 'Fabric alt', gameVersion: '1.21.11', loader: { kind: 'fabric' }, overrides: {}, lastPlayed: '2026-09-01T10:00:00Z' })
    const s = await store()
    await s.join({ card: CARD })
    expect(games.launch).not.toHaveBeenCalled()
    expect(s.choice?.instanceIds).toEqual(['fabric-neu', 'fabric-alt'])
    await s.choose('fabric-alt')
    expect((games.launch.mock.calls[0] as unknown[])[0]).toBe('fabric-alt')

    instances.items = instances.items.filter((i) => i.loader.kind === 'forge')
    await s.join({ card: CARD })
    expect(s.choice?.instanceIds).toEqual([])
    await s.choose(null)
    expect(instances.create).toHaveBeenCalledWith({ name: 'Insel (1.21.11 Fabric)', gameVersion: '1.21.11', loader: { kind: 'fabric', version: null } })
    expect((games.launch.mock.calls[1] as unknown[])[0]).toBe('neu')
  })

  it('Spiel läuft schon: Anweisung geht direkt ans laufende Spiel', async () => {
    phases['fabric-neu'] = 'running'
    const s = await store()
    await s.join({ room: room(ROOM_ID, { myState: 'invited' }) })
    expect(hosting.join).toHaveBeenCalledWith({ roomId: ROOM_ID })
    expect(games.launch).not.toHaveBeenCalled()
    expect(launchInstance).toHaveBeenCalledWith('fabric-neu', null, expect.any(Function), null, null, expect.objectContaining({ roomId: ROOM_ID }))
  })

  it('TRS Client zu alt: klare Meldung statt endlosem Warten', async () => {
    vi.useFakeTimers()
    hosting.delivery.mockResolvedValueOnce({ state: 'unsupported', roomId: ROOM_ID })
    const s = await store()
    await s.join({ card: CARD })
    await vi.advanceTimersByTimeAsync(2_100)
    expect(s.handoff?.state).toBe('unsupported')
    expect(toasts.error).toHaveBeenCalledTimes(1)
  })

  it('Fehler der API landen als Meldung, der Knopf wird wieder frei', async () => {
    hosting.join.mockRejectedValueOnce(new Error('room_full'))
    const s = await store()
    await s.join({ card: CARD })
    expect(toasts.error).toHaveBeenCalledTimes(1)
    expect(s.busy).toEqual({})
  })
})

describe('Echtzeit', () => {
  it('Einladung: Benachrichtigung mit „Beitreten“ und „Ablehnen“', async () => {
    const s = await store()
    const t = await socialToasts()
    await s.onEvent({ type: 'hosting_invite', room: room(ROOM_ID, { myState: 'invited' }), from: HOST })
    expect(s.invitedCount).toBe(1)
    expect(t.items).toHaveLength(1)
    const toast = t.items[0]!
    expect(toast.kind).toBe('invite')
    expect(toast.title).toContain('Bob')
    expect(toast.body).toBe('Insel · 1.21.11 Fabric')
    expect(toast.actions.map((a) => a.primary ?? false)).toEqual([true, false])
    toast.actions[1]!.run()
    await vi.waitFor(() => expect(hosting.leave).toHaveBeenCalledWith(ROOM_ID))
  })

  it('Zustimmung ohne eigene Anfrage (z. B. aus dem Spiel): nur Hinweis, kein zweites Spiel', async () => {
    const s = await store()
    const t = await socialToasts()
    await s.onEvent({ type: 'hosting_join_accepted', room: room(ROOM_ID, { myState: 'accepted' }) })
    expect(games.launch).not.toHaveBeenCalled()
    expect(t.items[0]?.actions[0]?.primary).toBe(true)
  })

  it('eigene Welt (nur lesen) und Schließen', async () => {
    const s = await store()
    await s.onEvent({ type: 'hosting_room', room: room('h00000000000000000009', { code: 'K7QM2X', host: { uuid: 'a'.repeat(32), name: 'Ich' } }) })
    expect(s.mine?.code).toBe('K7QM2X')
    await s.onEvent({ type: 'hosting_room_updated', room: room('h00000000000000000009', { players: 3, host: { uuid: 'a'.repeat(32), name: 'Ich' } }) })
    expect(s.mine?.players).toBe(3)
    expect(s.mine?.code).toBe('K7QM2X')
    await s.onEvent({ type: 'hosting_room_closed', roomId: 'h00000000000000000009', reason: 'closed' })
    expect(s.mine).toBeNull()
  })

  it('Welt zu, während man wartet: Hinweis; fremde Welten schließen still', async () => {
    hosting.join.mockResolvedValueOnce({ status: 'requested', room: room(ROOM_ID, { myState: 'requested' }) })
    const s = await store()
    await s.join({ code: 'K7QM2X' })
    toasts.info.mockClear()
    await s.onEvent({ type: 'hosting_room_updated', room: room('h00000000000000000005') })
    await s.onEvent({ type: 'hosting_room_closed', roomId: 'h00000000000000000005', reason: 'closed' })
    expect(toasts.info).not.toHaveBeenCalled()
    await s.onEvent({ type: 'hosting_room_closed', roomId: ROOM_ID, reason: 'expired' })
    expect(toasts.info).toHaveBeenCalledTimes(1)
    expect(s.rooms.find((r) => r.id === ROOM_ID)).toBeUndefined()
  })

  it('rausgeworfen/verbannt: Hinweis und aus der Liste', async () => {
    const s = await store()
    await s.load()
    expect(s.rooms).toHaveLength(1)
    await s.onEvent({ type: 'hosting_kicked', roomId: ROOM_ID, banned: true })
    expect(s.rooms).toHaveLength(0)
    expect(toasts.info).toHaveBeenCalledWith(expect.stringContaining('Insel'))
  })
})
