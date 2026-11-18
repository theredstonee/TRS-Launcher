import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { CraftingInfo, CraftingServer } from '../app/utils/craftinghosting'

// Partner-Hosting (CraftingHosting): Laden, Anlegen, Start mit EULA, Beitreten, Konsole, Fehler.

function server(patch: Partial<CraftingServer> = {}, session: Partial<CraftingServer['session']> = {}): CraftingServer {
  return {
    id: '0b6f6c0e-1111',
    name: 'Meine Welt',
    playerId: 'P7KXGQXL',
    state: 'offline',
    address: 'node1.example.com:25568',
    serverType: 'paper',
    version: '1.21.11',
    resources: { memoryMb: 4096, cpuPercent: 200, diskMb: 10240 },
    eulaAccepted: true,
    access: 'friends',
    session: {
      active: false,
      startedAt: null,
      lastHeartbeatAt: null,
      heartbeatTimeoutSeconds: 180,
      runSeconds: 0,
      maxRunSeconds: 86400,
      remainingSeconds: 86400,
      cooldownUntil: null,
      lastStopReason: null,
      hostPresent: true,
      ...session,
    },
    createdAt: null,
    lastStartAt: null,
    deleteAt: null,
    ...patch,
  }
}

const INFO: CraftingInfo = {
  plan: { name: 'Free 4 GB', memoryMb: 4096, cpuPercent: 200, diskMb: 10240 },
  serverTypes: [{ id: 'paper', name: 'Paper', versions: ['latest', '1.21.11'] }],
  limits: { maxServersPerPlayer: 1, sessionMaxHours: 24, cooldownHours: 2, heartbeatTimeoutSeconds: 180, deleteAfterIdleDays: 30, serverNameMaxLength: 22 },
  eulaUrl: 'https://www.minecraft.net/eula',
  full: false,
}

const craftinghosting = {
  info: vi.fn(async () => INFO),
  server: vi.fn(async (): Promise<CraftingServer | null> => null),
  create: vi.fn(async () => server({ state: 'installing', eulaAccepted: false })),
  setAccess: vi.fn(async (access: 'friends' | 'open') => server({ access })),
  start: vi.fn(async () => server({ state: 'starting', eulaAccepted: true }, { active: true })),
  stop: vi.fn(async () => server({ state: 'stopping' })),
  command: vi.fn(async () => {}),
  remove: vi.fn(async () => {}),
  rename: vi.fn(async (name: string) => server({ name })),
  mods: vi.fn(async (projects: string[]) => ({ installed: projects.slice(0, 1), skipped: [] })),
  uploadWorld: vi.fn(async (_i: string, _f: string, onProgress: (p: { phase: string; percent: number }) => void) => {
    onProgress({ phase: 'upload', percent: 50 })
    return server()
  }),
}
const listContent = vi.fn(async () => [
  { fileName: 'lithium.jar', title: 'Lithium', enabled: true, source: { projectId: 'gvQqBUqZ', versionId: 'v' } },
  { fileName: 'jei.jar', title: 'JEI', enabled: true, source: { projectId: '238222', versionId: 'f', platform: 'curseforge' } },
  { fileName: 'own.jar', title: null, enabled: true, source: null },
])

vi.mock('../app/utils/backend', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../app/utils/backend')>()),
  backend: { craftinghosting, listContent },
}))

type Phase = 'idle' | 'running'
const phases: Record<string, Phase> = {}
const instanceItems = [
  { id: 'neu', name: 'Neu', gameVersion: '26.3', loader: { kind: 'fabric' } },
  { id: 'alt', name: 'Alt', gameVersion: '1.21.11', loader: { kind: 'vanilla' } },
  { id: 'alt2', name: 'Alt 2', gameVersion: '1.21.11', loader: { kind: 'fabric' } },
  { id: 'snap', name: 'Snapshot', gameVersion: '27w01a', loader: { kind: 'vanilla' } },
]
const instances = { items: [...instanceItems], load: vi.fn(async () => {}) }
const games = { state: (id: string) => ({ phase: phases[id] ?? 'idle' }), launch: vi.fn(async () => true) }
const join = { request: vi.fn(async () => {}) }
const toasts = { ok: vi.fn(), info: vi.fn(), error: vi.fn() }

beforeAll(() => {
  vi.stubGlobal('useInstancesStore', () => instances)
  vi.stubGlobal('useGamesStore', () => games)
  vi.stubGlobal('useJoinStore', () => join)
  vi.stubGlobal('useToasts', () => toasts)
})

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  instances.items = [...instanceItems]
  craftinghosting.server.mockImplementation(async () => null)
})

afterEach(() => {
  vi.useRealTimers()
})

/** Fehler wie aus dem Kern (Klasse erst nach dem Mock laden). */
async function apiError(code: string, params?: Record<string, string>) {
  const { BackendError } = await import('../app/utils/backend')
  return new BackendError('trs_api', 'x', `trsApi.${code}`, params, code)
}

async function store() {
  const { useCraftingHostingStore } = await import('../app/stores/craftinghosting')
  return useCraftingHostingStore()
}

describe('Laden und Anlegen', () => {
  it('lädt Info und Server, legt an und übernimmt den neuen Server', async () => {
    const s = await store()
    await s.load()
    expect(s.loaded).toBe(true)
    expect(s.info?.plan.memoryMb).toBe(4096)
    expect(s.server).toBeNull()
    expect(await s.create({ name: 'Meine Welt', serverType: 'paper', version: 'latest', access: 'friends' })).toBe(true)
    expect(s.server?.state).toBe('installing')
  })

  it('zeigt „nicht verfügbar“ als Hinweis statt Toast', async () => {
    craftinghosting.info.mockRejectedValueOnce(await apiError('craftinghosting_maintenance'))
    const s = await store()
    await s.load()
    expect(s.loaded).toBe(false)
    expect(s.problem?.code).toBe('craftinghosting_maintenance')
    expect(toasts.error).not.toHaveBeenCalled()
  })
})

describe('Starten', () => {
  it('fragt vor dem ersten Start nach der EULA und startet dann mit acceptEula', async () => {
    craftinghosting.server.mockImplementation(async () => server({ eulaAccepted: false }))
    const s = await store()
    await s.load()
    expect(await s.requestStart()).toBe(false)
    expect(s.eulaOpen).toBe(true)
    expect(craftinghosting.start).not.toHaveBeenCalled()
    await s.confirmEula()
    expect(craftinghosting.start).toHaveBeenCalledWith(true)
    expect(s.eulaOpen).toBe(false)
    expect(s.server?.session.active).toBe(true)
  })

  it('Pflichtpause: Meldung mit Uhrzeit, Stand wird neu geladen', async () => {
    craftinghosting.server.mockImplementation(async () => server())
    craftinghosting.start.mockRejectedValueOnce(await apiError('cooldown', { cooldownUntil: '2026-11-18T20:00:00.000Z' }))
    const s = await store()
    await s.load()
    expect(await s.requestStart()).toBe(false)
    expect(craftinghosting.start).toHaveBeenCalledWith(false)
    expect(toasts.error).toHaveBeenCalledOnce()
    expect(String(toasts.error.mock.calls[0]![0])).not.toContain('2026-11-18T20')
    await vi.waitFor(() => expect(craftinghosting.server).toHaveBeenCalledTimes(2))
  })
})

describe('Beitreten', () => {
  it('startet, wartet auf „running“ und nimmt die einzige Instanz mit gleicher Version', async () => {
    vi.useFakeTimers()
    craftinghosting.server.mockImplementationOnce(async () => server())
    const s = await store()
    await s.load()
    instances.items = instanceItems.filter((i) => i.id !== 'alt2')
    craftinghosting.server.mockImplementation(async () => server({ state: 'running' }, { active: true }))
    const done = s.join()
    await vi.advanceTimersByTimeAsync(5_000)
    await done
    expect(craftinghosting.start).toHaveBeenCalledWith(false)
    expect(games.launch).toHaveBeenCalledWith('alt', null, 'node1.example.com:25568')
    expect(join.request).not.toHaveBeenCalled()
  })

  it('mehrere passende Instanzen: fragt der Beitreten-Dialog', async () => {
    craftinghosting.server.mockImplementation(async () => server({ state: 'running' }, { active: true }))
    const s = await store()
    await s.load()
    await s.join()
    expect(craftinghosting.start).not.toHaveBeenCalled()
    expect(join.request).toHaveBeenCalledWith('node1.example.com:25568', '1.21.11')
  })

  it('„latest“: die neueste Release-Instanz', async () => {
    craftinghosting.server.mockImplementation(async () => server({ state: 'running', version: 'latest' }, { active: true }))
    const s = await store()
    await s.load()
    await s.join()
    expect(games.launch).toHaveBeenCalledWith('neu', null, 'node1.example.com:25568')
  })

  it('Server geht beim Warten aus: Fehlermeldung, kein Spielstart', async () => {
    vi.useFakeTimers()
    craftinghosting.server.mockImplementationOnce(async () => server())
    const s = await store()
    await s.load()
    craftinghosting.server.mockImplementation(async () => server({ state: 'failed' }))
    const done = s.join()
    await vi.advanceTimersByTimeAsync(5_000)
    await done
    expect(toasts.error).toHaveBeenCalled()
    expect(games.launch).not.toHaveBeenCalled()
  })
})

describe('Konsole, Zugang, Löschen', () => {
  it('schickt nur gültige Befehle, ohne führendes /', async () => {
    craftinghosting.server.mockImplementation(async () => server({ state: 'running' }, { active: true }))
    const s = await store()
    await s.load()
    expect(await s.command('say a\nop me')).toBe(false)
    expect(craftinghosting.command).not.toHaveBeenCalled()
    expect(await s.command('/say Hallo')).toBe(true)
    expect(craftinghosting.command).toHaveBeenCalledWith('say Hallo')
  })

  it('ändert den Zugang und löscht den Server', async () => {
    craftinghosting.server.mockImplementation(async () => server())
    const s = await store()
    await s.load()
    await s.setAccess('friends')
    expect(craftinghosting.setAccess).not.toHaveBeenCalled()
    await s.setAccess('open')
    expect(s.server?.access).toBe('open')
    expect(await s.remove()).toBe(true)
    expect(s.server).toBeNull()
  })
})

describe('Umbenennen, Mods, Welt', () => {
  it('benennt um und übernimmt Modrinth-Mods einer Instanz', async () => {
    craftinghosting.server.mockImplementation(async () => server())
    const s = await store()
    await s.load()
    expect(await s.rename(' Neue Welt ')).toBe(true)
    expect(craftinghosting.rename).toHaveBeenCalledWith('Neue Welt')
    expect(s.server?.name).toBe('Neue Welt')

    const result = await s.transferMods('inst')
    expect(listContent).toHaveBeenCalledWith('inst', 'mod')
    expect(craftinghosting.mods).toHaveBeenCalledWith(['gvQqBUqZ'])
    expect(result?.other).toEqual(['JEI', 'own.jar'])
  })

  it('spielt eine Welt ein und meldet den Fortschritt', async () => {
    craftinghosting.server.mockImplementation(async () => server())
    const s = await store()
    await s.load()
    expect(await s.uploadWorld('inst', 'Welt')).toBe(true)
    expect(craftinghosting.uploadWorld).toHaveBeenCalledWith('inst', 'Welt', expect.any(Function))
    expect(s.worldProgress).toBeNull()
    expect(toasts.ok).toHaveBeenCalled()
  })
})
