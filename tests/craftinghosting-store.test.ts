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
  features: { files: true, properties: true, addons: true, worlds: true, worldDownload: true, ownAddress: true, wakeOnJoin: false, versionChange: false, backups: false, create: true, console: true },
  notice: null,
  maintenance: { active: false, message: null },
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
  checkSubdomain: vi.fn(async (name: string) => ({ name, available: name !== 'play', reason: name === 'play' ? 'reserved' : null })),
  setSubdomain: vi.fn(async (name: string) => server({ domain: `${name}.trs-launcher.de` })),
  mods: vi.fn(async (projects: string[]) => ({ installed: projects.slice(0, 1), skipped: [] as { project: string; reason: 'client_only' }[] })),
  settings: vi.fn(async () => ({ motd: 'A', maxPlayers: 8, gamemode: 'survival', difficulty: 'normal', hardcore: false, pvp: true, allowFlight: false, seed: '', levelType: 'normal', spawnProtection: 16, viewDistance: 10, simulationDistance: 10, whitelist: true })),
  saveSettings: vi.fn(async () => ({ motd: 'B', maxPlayers: 8, gamemode: 'survival', difficulty: 'normal', hardcore: false, pvp: true, allowFlight: false, seed: '', levelType: 'normal', spawnProtection: 16, viewDistance: 10, simulationDistance: 10, whitelist: true })),
  properties: vi.fn(async () => ({ properties: { pvp: 'true', motd: 'A', 'server-port': '25568' }, locked: ['server-port'] })),
  saveProperties: vi.fn(async (changes: Record<string, string>) => ({ properties: { pvp: 'true', motd: 'A', 'server-port': '25568', ...changes }, locked: ['server-port'] })),
  addons: vi.fn(async () => ({
    kind: 'plugins' as const,
    dir: '/plugins',
    items: [
      { file: 'TRS-Server-Rules.jar', size: 1, modifiedAt: null, protected: true },
      { file: 'LuckPerms-5.4.jar', size: 2, modifiedAt: null, protected: false },
    ],
  })),
  removeAddon: vi.fn(async () => {}),
  changeVersion: vi.fn(async (change: { serverType: string; version: string }) => server({ state: 'installing', serverType: change.serverType, version: change.version })),
  backups: vi.fn(async () => ({ max: 3, backups: [{ id: 'b1', name: 'Vor dem Update', size: 10, createdAt: null }] })),
  createBackup: vi.fn(async () => {}),
  restoreBackup: vi.fn(async () => {}),
  deleteBackup: vi.fn(async () => {}),
  downloadBackup: vi.fn(async () => 'Meine-Welt-b1.zip'),
  installVersions: vi.fn(async (versions: { project: string; version: string }[]) => ({ installed: versions.map((v) => v.project), skipped: [] })),
  worlds: vi.fn(async () => ({
    active: 'world',
    exists: true,
    worlds: [
      { name: 'world', active: true, dimensions: ['overworld'] },
      { name: 'insel', active: false, dimensions: ['overworld'] },
    ],
  })),
  setActiveWorld: vi.fn(async (name: string) => ({ active: name, exists: true, worlds: [{ name, active: true, dimensions: [] }] })),
  deleteWorld: vi.fn(async () => ({ active: 'world', exists: false, worlds: [] })),
  downloadWorld: vi.fn(async (_n: string, _s: string, onProgress: (p: { phase: string; percent: number }) => void) => {
    onProgress({ phase: 'download', percent: 50 })
    return { fileName: 'Meine-Welt-world.zip', files: 3, bytes: 100, truncated: false }
  }),
  uploadWorldDisk: vi.fn(async () => server()),
  uploadMods: vi.fn(async () => ({ files: ['lithium.jar', 'own.jar'] })),
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

const cancelTask = vi.fn(async () => true)

vi.mock('../app/utils/backend', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../app/utils/backend')>()),
  backend: { craftinghosting, listContent, cancelTask },
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
const storage = new Map<string, string>()
const localStorageMock = {
  getItem: (k: string) => storage.get(k) ?? null,
  setItem: (k: string, v: string) => void storage.set(k, v),
  removeItem: (k: string) => void storage.delete(k),
}

beforeAll(() => {
  vi.stubGlobal('useInstancesStore', () => instances)
  vi.stubGlobal('useGamesStore', () => games)
  vi.stubGlobal('useJoinStore', () => join)
  vi.stubGlobal('useToasts', () => toasts)
  vi.stubGlobal('localStorage', localStorageMock)
})

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  instances.items = [...instanceItems]
  storage.clear()
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
  it('benennt um und übernimmt die Mod-Dateien einer Instanz 1:1', async () => {
    craftinghosting.server.mockImplementation(async () => server())
    const s = await store()
    await s.load()
    expect(await s.rename(' Neue Welt ')).toBe(true)
    expect(craftinghosting.rename).toHaveBeenCalledWith('Neue Welt')
    expect(s.server?.name).toBe('Neue Welt')

    const result = await s.transferMods('inst')
    expect(craftinghosting.uploadMods).toHaveBeenCalledWith('inst', expect.any(Function))
    expect(craftinghosting.mods).not.toHaveBeenCalled()
    expect(result?.result?.installed).toEqual(['lithium.jar', 'own.jar'])
    expect(result?.other).toEqual([])
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

describe('Eigene Adresse', () => {
  it('prüft die Adresse; dns_unavailable und andere Fehler ohne Toast', async () => {
    const s = await store()
    expect(await s.checkSubdomain('meinewelt')).toEqual({ kind: 'ok', check: { name: 'meinewelt', available: true, reason: null } })
    craftinghosting.checkSubdomain.mockRejectedValueOnce(await apiError('dns_unavailable'))
    expect(await s.checkSubdomain('meinewelt')).toEqual({ kind: 'dns' })
    craftinghosting.checkSubdomain.mockRejectedValueOnce(await apiError('craftinghosting_upstream'))
    expect(await s.checkSubdomain('meinewelt')).toEqual({ kind: 'error' })
    expect(toasts.error).not.toHaveBeenCalled()
  })

  it('ändert die Adresse und tritt über die Domain bei', async () => {
    craftinghosting.server.mockImplementation(async () => server({ state: 'running', domain: 'alt.trs-launcher.de' }, { active: true }))
    const s = await store()
    await s.load()
    instances.items = instanceItems.filter((i) => i.id !== 'alt2')
    await s.join()
    expect(games.launch).toHaveBeenCalledWith('alt', null, 'alt.trs-launcher.de')

    expect(await s.setSubdomain('neuewelt')).toBe(true)
    expect(craftinghosting.setSubdomain).toHaveBeenCalledWith('neuewelt')
    expect(s.server?.domain).toBe('neuewelt.trs-launcher.de')
    expect(toasts.ok).toHaveBeenCalled()
  })

  it('schickt die Wunsch-Adresse beim Anlegen mit', async () => {
    const s = await store()
    await s.load()
    await s.create({ name: 'Meine Welt', serverType: 'paper', version: '1.21.11', access: 'friends', subdomain: 'meinewelt' })
    expect(craftinghosting.create).toHaveBeenCalledWith(expect.objectContaining({ subdomain: 'meinewelt' }))
    expect(storage.size).toBe(0)
  })
})

describe('Inhalte aus der Instanz übernehmen', () => {
  it('merkt sich die Instanz beim Anlegen (lokal) und vergisst sie beim Löschen', async () => {
    const s = await store()
    await s.load()
    expect(await s.create({ name: 'Meine Welt', serverType: 'fabric', version: '1.21.11', access: 'friends' }, 'alt2')).toBe(true)
    expect(s.origin).toEqual({ instanceId: 'alt2', transferred: false })
    expect(JSON.parse(storage.get('trs.craftinghosting.origins')!)).toEqual({ '0b6f6c0e-1111': { instanceId: 'alt2', transferred: false } })

    // Nach einem Neustart des Launchers noch da.
    setActivePinia(createPinia())
    const again = await store()
    craftinghosting.server.mockImplementation(async () => server())
    await again.load()
    expect(again.origin?.instanceId).toBe('alt2')

    again.dismissOrigin()
    expect(again.origin?.transferred).toBe(true)
    expect(await again.remove()).toBe(true)
    expect(JSON.parse(storage.get('trs.craftinghosting.origins')!)).toEqual({})
  })

  it('stoppt, installiert Mods, spielt die Welt ein und startet wieder', async () => {
    craftinghosting.server.mockImplementation(async () => server({ state: 'running', serverType: 'fabric', setup: 'done' }, { active: true }))
    craftinghosting.stop.mockResolvedValueOnce(server({ state: 'offline', serverType: 'fabric' }))
    craftinghosting.start.mockResolvedValueOnce(server({ state: 'starting', serverType: 'fabric' }, { active: true }))
    const s = await store()
    await s.load()
    await s.create({ name: 'Meine Welt', serverType: 'fabric', version: '1.21.11', access: 'friends' }, 'alt2')
    craftinghosting.server.mockImplementation(async () => server({ state: 'running', serverType: 'fabric', setup: 'done' }, { active: true }))
    await s.refresh()

    expect(await s.transferFromInstance({ instanceId: 'alt2', mods: true, world: 'Welt' })).toBe(true)
    expect(s.transfer?.steps).toEqual({ stop: 'done', mods: 'done', world: 'done', start: 'done' })
    expect(s.transfer?.active).toBe(false)
    expect(s.transfer?.mods?.result?.installed).toEqual(['lithium.jar', 'own.jar'])
    expect(craftinghosting.stop).toHaveBeenCalled()
    expect(craftinghosting.uploadMods).toHaveBeenCalledWith('alt2', expect.any(Function))
    expect(craftinghosting.uploadWorld).toHaveBeenCalledWith('alt2', 'Welt', expect.any(Function))
    expect(craftinghosting.start).toHaveBeenCalledWith(false)
    expect(s.origin?.transferred).toBe(true)
  })

  it('ausgeschalteter Server, ohne Mods/Welt: nur starten; Fehler bricht ab', async () => {
    craftinghosting.server.mockImplementation(async () => server({ setup: 'done' }))
    const s = await store()
    await s.load()
    expect(await s.transferFromInstance({ instanceId: 'alt', mods: false, world: null })).toBe(true)
    expect(s.transfer?.steps).toEqual({ stop: 'skipped', mods: 'skipped', world: 'skipped', start: 'done' })
    expect(craftinghosting.stop).not.toHaveBeenCalled()

    await s.refresh()
    craftinghosting.uploadWorld.mockRejectedValueOnce(await apiError('server_running'))
    expect(await s.transferFromInstance({ instanceId: 'alt', mods: false, world: 'Welt' })).toBe(false)
    expect(s.transfer?.steps.world).toBe('failed')
    expect(s.transfer?.steps.start).toBe('pending')
    expect(craftinghosting.start).toHaveBeenCalledTimes(1)
  })
})

describe('Alle Einstellungen, Plugins und Dateien', () => {
  it('speichert nur Änderungen und holt die Schnell-Einstellungen nach', async () => {
    craftinghosting.server.mockImplementation(async () => server({ lastStartAt: '2026-11-18T10:00:00.000Z' }))
    const s = await store()
    await s.load()
    await s.loadSettings()
    await s.loadProperties()
    expect(s.properties?.locked).toEqual(['server-port'])
    craftinghosting.settings.mockClear()

    expect(await s.saveProperties({})).toBe(true)
    expect(craftinghosting.saveProperties).not.toHaveBeenCalled()
    expect(await s.saveProperties({ pvp: 'false' })).toBe(true)
    expect(craftinghosting.saveProperties).toHaveBeenCalledWith({ pvp: 'false' })
    expect(s.properties?.properties.pvp).toBe('false')
    expect(craftinghosting.settings).toHaveBeenCalledTimes(1)

    // Schnell-Einstellungen gespeichert: Liste wird frisch geholt.
    craftinghosting.properties.mockClear()
    await s.saveSettings({ motd: 'B' })
    expect(craftinghosting.properties).toHaveBeenCalledTimes(1)
  })

  it('vor dem ersten Start: Hinweis statt Fehler', async () => {
    craftinghosting.properties.mockRejectedValueOnce(await apiError('server_installing'))
    const s = await store()
    await s.loadProperties()
    expect(s.properties).toBeNull()
    expect(s.propertiesPending).toBe(true)
    expect(toasts.error).not.toHaveBeenCalled()
  })

  it('gesperrter Schlüssel: Fehler als Toast, Stand bleibt', async () => {
    const s = await store()
    await s.loadProperties()
    craftinghosting.saveProperties.mockRejectedValueOnce(await apiError('property_locked'))
    expect(await s.saveProperties({ 'server-port': '1' })).toBe(false)
    expect(toasts.error).toHaveBeenCalled()
    expect(s.properties?.properties['server-port']).toBe('25568')
  })

  it('listet, entfernt und installiert Plugins', async () => {
    const s = await store()
    await s.loadAddons()
    expect(s.addons?.items.map((a) => a.file)).toEqual(['TRS-Server-Rules.jar', 'LuckPerms-5.4.jar'])
    expect(await s.removeAddon('LuckPerms-5.4.jar')).toBe(true)
    expect(craftinghosting.removeAddon).toHaveBeenCalledWith('LuckPerms-5.4.jar')
    expect(s.addons?.items.map((a) => a.file)).toEqual(['TRS-Server-Rules.jar'])

    craftinghosting.mods.mockResolvedValueOnce({ installed: [], skipped: [{ project: 'sodium', reason: 'client_only' }] })
    const result = await s.installAddons(['sodium'])
    expect(result?.skipped[0]?.reason).toBe('client_only')
    expect(craftinghosting.addons).toHaveBeenCalledTimes(2)
  })

  it('springt im Dateien-Reiter in einen Ordner', async () => {
    const s = await store()
    const before = s.filesJump
    s.openFiles('/plugins/LuckPerms')
    expect(s.filesDir).toBe('/plugins/LuckPerms')
    expect(s.filesJump).toBe(before + 1)
    s.reset()
    expect(s.filesDir).toBe('/')
    expect(s.properties).toBeNull()
  })
})

describe('Welten', () => {
  it('setzt aktiv, setzt zurück und lädt herunter', async () => {
    craftinghosting.server.mockImplementation(async () => server())
    const s = await store()
    await s.load()
    await s.loadWorlds()
    expect(s.worlds?.worlds.map((w) => w.name)).toEqual(['world', 'insel'])

    expect(await s.setActiveWorld('insel')).toBe(true)
    expect(s.worlds?.active).toBe('insel')
    expect(await s.deleteWorld('world')).toBe(true)
    expect(craftinghosting.deleteWorld).toHaveBeenCalledWith('world')

    const result = await s.downloadWorld('world')
    expect(result?.fileName).toBe('Meine-Welt-world.zip')
    expect(craftinghosting.downloadWorld).toHaveBeenCalledWith('world', 'Meine Welt', expect.any(Function), expect.stringMatching(/^craftinghosting-world:/))
    expect(s.worldDownload).toBeNull()
    expect(toasts.ok).toHaveBeenCalled()
  })

  it('Abbrechen meldet keinen Fehler', async () => {
    craftinghosting.server.mockImplementation(async () => server())
    const s = await store()
    await s.load()
    const { BackendError } = await import('../app/utils/backend')
    let release: () => void = () => {}
    craftinghosting.downloadWorld.mockImplementationOnce(
      () => new Promise((_, reject) => (release = () => reject(new BackendError('cancelled', 'x', 'cancelled')))),
    )
    const running = s.downloadWorld('world')
    expect(s.worldDownload?.name).toBe('world')
    s.cancelWorldDownload()
    expect(cancelTask).toHaveBeenCalledWith(s.worldDownload?.taskId)
    release()
    expect(await running).toBeNull()
    expect(toasts.error).not.toHaveBeenCalled()
  })

  it('lädt eine benannte Welt hoch und gibt das Ziel weiter', async () => {
    craftinghosting.server.mockImplementation(async () => server())
    const s = await store()
    await s.load()
    await s.loadWorlds()
    expect(await s.uploadWorld('alt', 'Welt', { name: 'insel', activate: true })).toBe(true)
    expect(craftinghosting.uploadWorld).toHaveBeenCalledWith('alt', 'Welt', expect.any(Function), { name: 'insel', activate: true })
    expect(await s.uploadWorldDisk('zip', { name: 'neu', activate: false })).toBe(true)
    expect(craftinghosting.uploadWorldDisk).toHaveBeenCalledWith('zip', { name: 'neu', activate: false }, expect.any(Function))
  })

  it('stoppt und wartet, bevor Welten geändert werden', async () => {
    craftinghosting.server.mockImplementation(async () => server({ state: 'running' }, { active: true }))
    craftinghosting.stop.mockResolvedValueOnce(server({ state: 'offline' }))
    const s = await store()
    await s.load()
    expect(await s.stopAndWait()).toBe(true)
    expect(craftinghosting.stop).toHaveBeenCalled()
  })
})

describe('Feste Versionen und Welt-Namen', () => {
  it('installiert eine bestimmte Version und lädt die Liste neu', async () => {
    const s = await store()
    const result = await s.installVersion('lithium', 'Yp8wLY1P')
    expect(result?.installed).toEqual(['lithium'])
    expect(craftinghosting.installVersions).toHaveBeenCalledWith([{ project: 'lithium', version: 'Yp8wLY1P' }])
    expect(craftinghosting.addons).toHaveBeenCalled()
  })

  it('abgelehnter Weltname: klare Meldung statt „ungültige Anfrage“', async () => {
    craftinghosting.server.mockImplementation(async () => server())
    const s = await store()
    await s.load()
    craftinghosting.uploadWorld.mockRejectedValueOnce(await apiError('invalid_request'))
    expect(await s.uploadWorld('alt', 'Welt', { name: 'plugins', activate: false })).toBe(false)
    expect(toasts.error).toHaveBeenCalledWith(expect.stringContaining('world name'))
  })
})

describe('Funktions-Schalter, Versionswechsel und Backups', () => {
  it('Schalter kommen aus der Info, ohne Info ist alles aus', async () => {
    const s = await store()
    expect(s.features.files).toBe(false)
    await s.load()
    expect(s.features.files).toBe(true)
    expect(s.features.backups).toBe(false)
  })

  it('501 feature_unavailable: freundlicher Hinweis statt Fehler, Info neu laden', async () => {
    const s = await store()
    await s.load()
    craftinghosting.info.mockClear()
    craftinghosting.changeVersion.mockRejectedValueOnce(await apiError('feature_unavailable'))
    expect(await s.changeVersion({ serverType: 'purpur', version: '1.21.11', keepWorld: true })).toBe(false)
    expect(toasts.error).not.toHaveBeenCalled()
    expect(toasts.info).toHaveBeenCalledTimes(1)
    expect(craftinghosting.info).toHaveBeenCalled()
    // Kein Spam: der zweite Hinweis kurz danach bleibt aus.
    craftinghosting.createBackup.mockRejectedValueOnce(await apiError('feature_unavailable'))
    await s.createBackup()
    expect(toasts.info).toHaveBeenCalledTimes(1)

    craftinghosting.backups.mockRejectedValueOnce(await apiError('feature_unavailable'))
    await s.loadBackups()
    expect(s.backups).toBeNull()
    expect(toasts.error).not.toHaveBeenCalled()
  })

  it('wechselt die Version und übernimmt den Server', async () => {
    craftinghosting.server.mockImplementation(async () => server())
    const s = await store()
    await s.load()
    expect(await s.changeVersion({ serverType: 'purpur', version: '1.21.11', keepWorld: false })).toBe(true)
    expect(craftinghosting.changeVersion).toHaveBeenCalledWith({ serverType: 'purpur', version: '1.21.11', keepWorld: false })
    expect(s.server?.serverType).toBe('purpur')
    expect(s.server?.state).toBe('installing')
  })

  it('Backups anlegen, einspielen, löschen und herunterladen', async () => {
    craftinghosting.server.mockImplementation(async () => server())
    const s = await store()
    await s.load()
    await s.loadBackups()
    const b = s.backups!.backups[0]!
    expect(await s.createBackup()).toBe(true)
    expect(craftinghosting.backups).toHaveBeenCalledTimes(2)
    expect(await s.restoreBackup(b)).toBe(true)
    expect(craftinghosting.restoreBackup).toHaveBeenCalledWith('b1')
    expect(await s.downloadBackup(b)).toBe('Meine-Welt-b1.zip')
    expect(craftinghosting.downloadBackup).toHaveBeenCalledWith('b1', 'Vor dem Update', 'Meine Welt', expect.any(Function), expect.stringMatching(/^craftinghosting-backup:/))
    expect(await s.deleteBackup(b)).toBe(true)
    expect(s.backups?.backups).toEqual([])
  })
})

describe('Team-Steuerung im Store', () => {
  it('Wartung: kein Start, kein Beitreten, Hinweis statt Anfrage', async () => {
    craftinghosting.server.mockImplementation(async () => server())
    craftinghosting.info.mockResolvedValueOnce({ ...INFO, maintenance: { active: true, message: 'Update' } })
    const s = await store()
    await s.load()
    expect(await s.requestStart()).toBe(false)
    await s.join()
    expect(craftinghosting.start).not.toHaveBeenCalled()
    expect(toasts.error).toHaveBeenCalledTimes(2)
  })

  it('gesperrt vom Team: Fehler mit Grund, Server neu holen', async () => {
    craftinghosting.server.mockImplementation(async () => server())
    const s = await store()
    await s.load()
    craftinghosting.start.mockRejectedValueOnce(await apiError('server_blocked', { reason: 'Spam' }))
    craftinghosting.server.mockClear()
    expect(await s.start(false)).toBe(false)
    expect(toasts.error).toHaveBeenCalledWith(expect.stringContaining('Spam'))
    expect(craftinghosting.server).toHaveBeenCalled()
  })

  it('abgeschaltete Funktion bzw. Wartung: freundlich, Info neu holen', async () => {
    const s = await store()
    await s.load()
    craftinghosting.info.mockClear()
    craftinghosting.command.mockRejectedValueOnce(await apiError('feature_disabled', { feature: 'console' }))
    expect(await s.command('say hi')).toBe(false)
    expect(toasts.info).toHaveBeenCalled()
    craftinghosting.create.mockRejectedValueOnce(await apiError('hosting_maintenance', { message: 'bis 18 Uhr' }))
    expect(await s.create({ name: 'Meine Welt', serverType: 'paper', version: 'latest', access: 'friends' })).toBe(false)
    expect(toasts.error).toHaveBeenCalledWith(expect.stringContaining('bis 18 Uhr'))
    expect(craftinghosting.info).toHaveBeenCalledTimes(2)
  })
})
