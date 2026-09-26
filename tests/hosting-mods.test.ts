import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { HOST, ROOM_ID, room } from './fixtures/hosting'
import {
  canConfirmMods,
  canJoinWithout,
  defaultModSelection,
  downloadSize,
  hostingRoomSchema,
  missingRequired,
  modRows,
  modsTag,
  modsToInstall,
  needsTrust,
  rankByMods,
  type RoomContent,
  type SharedMod,
} from '../app/utils/hosting'

// Welt mit Mods (API §21.10): Logik des Dialogs beim Beitreten – Liste, Vorauswahl,
// „Ohne Mods“ nur ohne fehlende Pflicht-Mods, Warn-Pflicht bei Dateien direkt vom Host.

const sha = (c: string) => c.repeat(40)

function mod(name: string, extra: Partial<SharedMod> = {}): SharedMod {
  return { name, version: '1.0', file: `${name}.jar`, size: 1000, required: false, source: 'modrinth', projectId: 'AANobbMI', fileId: 'Yp8wLY1P', sha1: sha(name[0]!), ...extra }
}

const REQ_HOST = mod('blocks', { required: true, source: 'host', projectId: undefined, fileId: undefined, sha1: sha('1'), size: 50_000 })
const REQ_STORE = mod('fabricapi', { required: true, sha1: sha('2'), size: 2_000_000 })
const OPT_STORE = mod('zoom', { sha1: sha('3') })
const OPT_HOST = mod('extra', { source: 'host', projectId: undefined, fileId: undefined, sha1: sha('4') })
const MANUAL = mod('secret', { source: 'manual', projectId: undefined, fileId: undefined, sha1: sha('5'), required: true })

const CONTENT: RoomContent = { roomId: ROOM_ID, mods: [OPT_STORE, REQ_HOST, OPT_HOST, REQ_STORE, MANUAL], pack: null }
const none = new Set<string>()

describe('Anzeige', () => {
  it('Kurzform in Liste und Karte', () => {
    expect(modsTag(null)).toBeNull()
    expect(modsTag({ mods: 12, required: 5, fromHost: 2, manual: 0, pack: null })).toBe('With mods (12, 5 required)')
    expect(modsTag({ mods: 0, required: 0, fromHost: 0, manual: 0, pack: { name: 'P', size: 1, sha1: sha('a') } })).toBe('With resource pack')
    expect(modsTag({ mods: 1, required: 1, fromHost: 0, manual: 0, pack: { name: 'P', size: 1, sha1: sha('a') } })).toBe('With mods (1, 1 required) + resource pack')
  })

  it('ältere Kerne ohne Feld: content = null', () => {
    const { content: _, ...old } = room()
    expect(hostingRoomSchema.parse(old).content).toBeNull()
    const summary = { mods: 3, required: 1, fromHost: 1, manual: 0, pack: null }
    expect(hostingRoomSchema.parse({ ...room(), content: summary }).content).toEqual(summary)
  })

  it('Pflicht zuerst, „vorhanden“ je Instanz, Größen', () => {
    const rows = modRows(CONTENT, new Set([REQ_STORE.sha1]))
    expect(rows.map((r) => r.mod.name)).toEqual(['blocks', 'fabricapi', 'secret', 'extra', 'zoom'])
    expect(rows.find((r) => r.mod.name === 'fabricapi')?.present).toBe(true)
  })
})

describe('Auswahl', () => {
  it('Vorauswahl: alles Ladbare, nie „selbst besorgen“', () => {
    expect([...defaultModSelection(CONTENT)].sort()).toEqual([sha('1'), sha('2'), sha('3'), sha('4')])
  })

  it('Pflicht immer, optionale nach Wahl, Vorhandenes nicht erneut', () => {
    const sel = new Set([OPT_STORE.sha1])
    expect(modsToInstall(CONTENT, sel, none).map((m) => m.name).sort()).toEqual(['blocks', 'fabricapi', 'zoom'])
    expect(modsToInstall(CONTENT, sel, new Set([REQ_STORE.sha1])).map((m) => m.name).sort()).toEqual(['blocks', 'zoom'])
    expect(downloadSize(CONTENT, sel, none)).toBe(50_000 + 2_000_000 + 1000)
  })

  it('„Ohne Mods“ nur ohne fehlende Pflicht-Mods', () => {
    expect(canJoinWithout(CONTENT, none)).toBe(false)
    expect(missingRequired(CONTENT, none).map((m) => m.name).sort()).toEqual(['blocks', 'fabricapi', 'secret'])
    const all = new Set([REQ_HOST.sha1, REQ_STORE.sha1, MANUAL.sha1])
    expect(canJoinWithout(CONTENT, all)).toBe(true)
    const onlyOptional: RoomContent = { roomId: ROOM_ID, mods: [OPT_STORE, OPT_HOST], pack: null }
    expect(canJoinWithout(onlyOptional, none)).toBe(true)
  })

  it('Dateien vom Host: Warnung + Häkchen bei jedem Beitritt, sonst kein Weiter', () => {
    const sel = defaultModSelection(CONTENT)
    expect(needsTrust(CONTENT, 'new', sel, none)).toBe(true)
    expect(needsTrust(CONTENT, 'none', sel, none)).toBe(false)
    // Host-Mods schon vorhanden (Kopie) → nichts vom Host zu laden → keine Warnung.
    expect(needsTrust(CONTENT, 'copy', new Set([OPT_STORE.sha1]), new Set([REQ_HOST.sha1]))).toBe(false)
    // Optionale Host-Mod abgewählt, Pflicht-Host-Mod bleibt → weiter Warnung.
    const noOptHost = new Set([...sel].filter((s) => s !== OPT_HOST.sha1))
    expect(needsTrust(CONTENT, 'new', noOptHost, none)).toBe(true)
    const base = { content: CONTENT, baseInstanceId: null, selection: sel, present: none }
    expect(canConfirmMods({ ...base, mode: 'new', trust: false })).toBe(false)
    expect(canConfirmMods({ ...base, mode: 'new', trust: true })).toBe(true)
    // Kopie braucht eine Instanz, „Ohne Mods“ eine ohne fehlende Pflicht-Mods.
    expect(canConfirmMods({ ...base, mode: 'copy', trust: true })).toBe(false)
    expect(canConfirmMods({ ...base, mode: 'copy', baseInstanceId: 'a', trust: true })).toBe(true)
    expect(canConfirmMods({ ...base, mode: 'none', baseInstanceId: 'a', trust: true })).toBe(false)
    const all = new Set([REQ_HOST.sha1, REQ_STORE.sha1, MANUAL.sha1])
    expect(canConfirmMods({ ...base, mode: 'none', baseInstanceId: 'a', present: all, trust: false })).toBe(true)
  })

  it('Instanzen mit den wenigsten fehlenden Mods zuerst', () => {
    const inst = (id: string) => ({ id, gameVersion: '1.21.11', loader: { kind: 'fabric' as const } })
    const ranked = rankByMods([inst('leer'), inst('fast'), inst('opt')], CONTENT, {
      fast: [REQ_HOST.sha1, REQ_STORE.sha1, MANUAL.sha1],
      opt: [OPT_STORE.sha1, OPT_HOST.sha1],
    })
    expect(ranked.map((i) => i.id)).toEqual(['fast', 'opt', 'leer'])
  })
})

// --- Store: Beitritt zu einer Welt mit Mods ------------------------------------------

const hosting = {
  friendsRooms: vi.fn(async () => []),
  myRooms: vi.fn(async () => []),
  room: vi.fn(async () => room(ROOM_ID, { myState: 'accepted', content: { mods: 5, required: 3, fromHost: 2, manual: 1, pack: null } })),
  join: vi.fn(async () => ({
    status: 'accepted' as const,
    room: room(ROOM_ID, { myState: 'accepted', content: { mods: 5, required: 3, fromHost: 2, manual: 1, pack: null } }),
  })),
  leave: vi.fn(async () => {}),
  delivery: vi.fn(async () => ({ state: 'delivered' as const, roomId: ROOM_ID })),
  content: vi.fn(async () => CONTENT),
  instanceMods: vi.fn(async (id: string) => (id === 'mit-mods' ? [REQ_HOST.sha1, REQ_STORE.sha1, MANUAL.sha1] : [])),
  prepare: vi.fn(async (_plan: unknown, onProgress: (p: unknown) => void) => {
    onProgress({ step: 'host', done: 0, total: 1, name: 'blocks', bytes: 50_000, totalBytes: 50_000 })
    return { instanceId: 'insel-mods', installed: 3, already: 0 }
  }),
}
const launchInstance = vi.fn(async () => 1)
const social = { quietHours: vi.fn(async () => false), notifyNative: vi.fn(async () => {}), focusWindow: vi.fn(async () => {}) }

vi.mock('../app/utils/backend', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../app/utils/backend')>()),
  backend: { hosting, launchInstance, social },
}))
vi.mock('../app/utils/sound', () => ({ playNotificationSound: vi.fn() }))

const instanceItems = [
  { id: 'leer', name: 'Leer', gameVersion: '1.21.11', loader: { kind: 'fabric' }, overrides: {}, lastPlayed: '2026-09-25T10:00:00Z' },
  { id: 'mit-mods', name: 'Mit Mods', gameVersion: '1.21.11', loader: { kind: 'fabric' }, overrides: {}, lastPlayed: null },
  { id: 'alt', name: 'Alt', gameVersion: '1.20.1', loader: { kind: 'fabric' }, overrides: {}, lastPlayed: null },
]
const instances = { items: [...instanceItems], loaded: true, load: vi.fn(async () => {}), create: vi.fn() }
const games = { state: () => ({ phase: 'idle' }), launch: vi.fn(async () => true) }
const toasts = { ok: vi.fn(), info: vi.fn(), error: vi.fn() }

beforeAll(() => {
  vi.stubGlobal('useInstancesStore', () => instances)
  vi.stubGlobal('useGamesStore', () => games)
  vi.stubGlobal('useToasts', () => toasts)
  vi.stubGlobal('useRouter', () => ({ push: vi.fn() }))
})

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

async function store() {
  const { useHostingStore } = await import('../app/stores/hosting')
  return useHostingStore()
}

describe('Store', () => {
  it('Beitritt zu einer Welt mit Mods zeigt immer den Mod-Dialog statt sofort zu starten', async () => {
    const s = await store()
    await s.join({ room: room(ROOM_ID, { myState: 'invited' }) })
    expect(games.launch).not.toHaveBeenCalled()
    expect(s.modsChoice?.content).toEqual(CONTENT)
    // Nur passende Instanzen (Version/Loader), die mit allen Pflicht-Mods zuerst.
    expect(s.modsChoice?.instanceIds).toEqual(['mit-mods', 'leer'])
    expect(s.modsChoice?.present['mit-mods']).toContain(REQ_HOST.sha1)
    expect(hosting.instanceMods).not.toHaveBeenCalledWith('alt')
  })

  it('„Ohne Mods“ startet die gewählte Instanz', async () => {
    const s = await store()
    await s.join({ room: room(ROOM_ID, { myState: 'invited' }) })
    await s.confirmMods({ mode: 'none', baseInstanceId: 'mit-mods', selection: [], trust: false })
    expect(hosting.prepare).not.toHaveBeenCalled()
    expect(games.launch).toHaveBeenCalledWith('mit-mods', null, null, expect.objectContaining({ roomId: ROOM_ID, host: HOST }))
    expect(s.modsChoice).toBeNull()
  })

  it('Neue Instanz: Kern bereitet vor (Auswahl + Vertrauen), dann Start mit Beitritt', async () => {
    const s = await store()
    await s.join({ room: room(ROOM_ID, { myState: 'invited' }) })
    await s.confirmMods({ mode: 'new', baseInstanceId: 'leer', selection: [OPT_STORE.sha1], trust: true })
    expect(hosting.prepare).toHaveBeenCalledWith(
      { roomId: ROOM_ID, mode: 'new', baseInstanceId: null, name: null, mods: [OPT_STORE.sha1], trustHost: true },
      expect.any(Function),
    )
    expect(games.launch).toHaveBeenCalledWith('insel-mods', null, null, expect.objectContaining({ roomId: ROOM_ID }))
    expect(toasts.ok).toHaveBeenCalled()
    expect(s.preparing).toBeNull()
  })

  it('Kopie: Basis-Instanz geht mit; Fehler des Kerns → Dialog bleibt offen', async () => {
    const s = await store()
    await s.join({ room: room(ROOM_ID, { myState: 'invited' }) })
    hosting.prepare.mockRejectedValueOnce(new Error('trust'))
    await s.confirmMods({ mode: 'copy', baseInstanceId: 'leer', selection: [], trust: false })
    expect((hosting.prepare.mock.calls[0]![0] as { baseInstanceId: string }).baseInstanceId).toBe('leer')
    expect(toasts.error).toHaveBeenCalled()
    expect(games.launch).not.toHaveBeenCalled()
    expect(s.modsChoice).not.toBeNull()
  })

  it('„Im Launcher öffnen“ aus dem Spiel öffnet den Dialog der Welt', async () => {
    const s = await store()
    await s.openFromGame(ROOM_ID)
    expect(hosting.room).toHaveBeenCalledWith(ROOM_ID)
    expect(s.modsChoice?.world.roomId).toBe(ROOM_ID)
    expect(s.rooms.map((r) => r.id)).toContain(ROOM_ID)
  })

  it('Welt ohne Mods: normaler Weg ohne Dialog', async () => {
    hosting.join.mockResolvedValueOnce({ status: 'accepted', room: room(ROOM_ID, { myState: 'accepted', content: null }) })
    const s = await store()
    await s.join({ room: room(ROOM_ID, { myState: 'invited' }) })
    expect(s.modsChoice).toBeNull()
    expect(hosting.content).not.toHaveBeenCalled()
  })
})
