import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  type LocalServer,
  type ServerMod,
  type ServerRulesOffer,
  appendLog,
  cleanCommand,
  defaultServerMods,
  localServerEventSchema,
  serverExportOptionsSchema,
  serverExportPlanSchema,
  serverRulesHint,
  splitMods,
  withServerRulesDeps,
} from '../app/utils/serverExport'
import { t } from '../app/utils/i18n'
import { isNewer, parseJarName } from '../scripts/publish-server-plugin.mjs'

// „Als Server exportieren“: Vorauswahl der Mods, Formularregeln (wie im Kern),
// Prüfung der Kern-Antworten und der Store der lokalen Server (Log + Zustand).

const localServers = {
  list: vi.fn(async (): Promise<LocalServer[]> => [server('mein-server')]),
  logs: vi.fn(async () => ['[12:00:00] [Server thread/INFO]: Starting minecraft server']),
  start: vi.fn(),
  stop: vi.fn(async () => {}),
  kill: vi.fn(async () => {}),
  restart: vi.fn(),
  command: vi.fn(async () => {}),
  openFolder: vi.fn(async () => {}),
  remove: vi.fn(async () => {}),
}
vi.mock('../app/utils/backend', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../app/utils/backend')>()),
  backend: { localServers },
}))
const toasts = { ok: vi.fn(), info: vi.fn(), error: vi.fn() }
vi.mock('../app/stores/toasts', () => ({ useToasts: () => toasts }))

function mod(fileName: string, patch: Partial<ServerMod> = {}): ServerMod {
  return { fileName, name: fileName.replace('.jar', ''), side: 'both', source: 'modrinth', included: true, locked: false, size: 10, ...patch }
}

function server(id: string, state: LocalServer['status']['state'] = 'stopped'): LocalServer {
  return {
    id,
    name: 'Mein Server',
    gameVersion: '1.21.1',
    loader: 'fabric',
    loaderVersion: '0.16.9',
    port: 25565,
    ramMb: 4096,
    javaMajor: 21,
    eulaAccepted: true,
    status: { state, players: null, playerNames: [], maxPlayers: 20, startedAt: null, exitCode: null },
  }
}

const options = {
  name: 'Mein Server',
  mods: ['a.jar'],
  includeConfigs: true,
  world: null,
  port: 25565,
  motd: 'Hallo',
  maxPlayers: 20,
  onlineMode: true,
  ramMb: 4096,
  eulaAccepted: false,
  zip: true,
  local: false,
  serverRules: false,
}

const rules: ServerRulesOffer = {
  available: true,
  reason: null,
  version: '1.0.0',
  file: 'trs-server-rules-fabric-1.21.11-1.0.0.jar',
  needsFabricApi: true,
  fabricApiFile: 'fabric-api-0.140.jar',
}

describe('Mod-Auswahl', () => {
  const mods = [
    mod('sodium.jar', { side: 'client', included: false, source: 'list' }),
    mod('lithium.jar'),
    mod('old.jar', { side: 'unknown', source: 'none' }),
    mod('trsclient.jar', { side: 'client', included: false, locked: true, source: 'trs' }),
  ]

  it('wählt ab Werk alles außer Client-Mods und dem TRS Client', () => {
    expect(defaultServerMods(mods)).toEqual(['lithium.jar', 'old.jar'])
  })

  it('teilt in „auf dem Server“ und „weggelassen“ (sortiert nach Name)', () => {
    const { server: on, leftOut } = splitMods(mods, ['old.jar', 'lithium.jar'])
    expect(on.map((m) => m.fileName)).toEqual(['lithium.jar', 'old.jar'])
    expect(leftOut.map((m) => m.fileName)).toEqual(['sodium.jar', 'trsclient.jar'])
  })
})

describe('Formular', () => {
  it('passt zu den Regeln des Kerns', () => {
    expect(serverExportOptionsSchema.safeParse(options).success).toBe(true)
    expect(serverExportOptionsSchema.safeParse({ ...options, name: '  ' }).success).toBe(false)
    expect(serverExportOptionsSchema.safeParse({ ...options, port: 80 }).success).toBe(false)
    expect(serverExportOptionsSchema.safeParse({ ...options, maxPlayers: 0 }).success).toBe(false)
    expect(serverExportOptionsSchema.safeParse({ ...options, zip: false, local: false }).success).toBe(false)
    // Lokal starten nur mit akzeptierter EULA – nie stillschweigend.
    expect(serverExportOptionsSchema.safeParse({ ...options, local: true }).success).toBe(false)
    expect(serverExportOptionsSchema.safeParse({ ...options, local: true, eulaAccepted: true }).success).toBe(true)
  })

  it('bereinigt Konsolen-Befehle wie der Kern', () => {
    expect(cleanCommand(' /say Hallo ')).toBe('say Hallo')
    expect(cleanCommand('   ')).toBeNull()
    expect(cleanCommand('stop\nop Eve')).toBeNull()
    expect(cleanCommand('a'.repeat(257))).toBeNull()
  })

  it('kürzt das Log auf die neuesten Zeilen', () => {
    expect(appendLog(['a', 'b'], ['c', 'd'], 3)).toEqual(['b', 'c', 'd'])
    expect(appendLog([], ['x'])).toEqual(['x'])
  })
})

describe('TRS Server Rules', () => {
  const fabric = { gameVersion: '1.21.11', loader: 'fabric' as const }

  it('nimmt Fabric API beim Einschalten mit', () => {
    expect(withServerRulesDeps(rules, ['a.jar'])).toEqual(['a.jar', 'fabric-api-0.140.jar'])
    expect(withServerRulesDeps(rules, ['fabric-api-0.140.jar'])).toEqual(['fabric-api-0.140.jar'])
    expect(withServerRulesDeps({ ...rules, needsFabricApi: false }, ['a.jar'])).toEqual(['a.jar'])
    expect(withServerRulesDeps({ ...rules, fabricApiFile: null }, ['a.jar'])).toEqual(['a.jar'])
  })

  it('erklärt, warum die Option fehlt, und warnt ohne Fabric API', () => {
    const off = (reason: ServerRulesOffer['reason']) => ({ ...rules, available: false, reason, file: null })
    expect(serverRulesHint(off('vanilla'), { gameVersion: '1.21.11', loader: 'vanilla' }, false, [])).toEqual({
      text: t('serverExport.serverRules.unavailable.vanilla'),
      warn: false,
    })
    expect(serverRulesHint(off('version'), { gameVersion: '1.12.2', loader: 'forge' }, false, [])?.text).toBe(
      t('serverExport.serverRules.unavailable.version', { version: '1.12.2', loader: 'Forge' }),
    )
    expect(serverRulesHint(off('offline'), fabric, false, [])?.text).toBe(t('serverExport.serverRules.unavailable.offline'))
    // Verfügbar, aber aus: kein Hinweis.
    expect(serverRulesHint(rules, fabric, false, [])).toBeNull()
    expect(serverRulesHint(rules, fabric, true, ['fabric-api-0.140.jar'])).toEqual({ text: t('serverExport.serverRules.fabricApiIncluded'), warn: false })
    expect(serverRulesHint(rules, fabric, true, [])?.warn).toBe(true)
    expect(serverRulesHint({ ...rules, fabricApiFile: null }, fabric, true, [])).toEqual({ text: t('serverExport.serverRules.fabricApiMissing'), warn: true })
    expect(serverRulesHint({ ...rules, needsFabricApi: false }, { gameVersion: '1.21.1', loader: 'neoforge' }, true, [])).toBeNull()
  })

  it('Veröffentlichen liest Plattform und Minecraft-Version aus den Jar-Namen', () => {
    expect(parseJarName('trs-server-rules-bukkit-1.0.0.jar', '1.0.0')).toEqual({ platform: 'bukkit' })
    expect(parseJarName('trs-server-rules-velocity-1.0.0.jar', '1.0.0')).toEqual({ platform: 'velocity' })
    expect(parseJarName('trs-server-rules-fabric-1.21.11-1.0.0.jar', '1.0.0')).toEqual({ platform: 'fabric', mc: '1.21.11' })
    expect(parseJarName('trs-server-rules-neoforge-1.21.1-1.0.0.jar', '1.0.0')).toEqual({ platform: 'neoforge', mc: '1.21.1' })
    expect(parseJarName('trs-server-rules-forge-1.20.1-1.0.0.jar', '1.0.0')).toEqual({ platform: 'forge', mc: '1.20.1' })
    // Andere Version (alter Build im Ordner), unbekannte Plattform, Plugin mit Version, Mod ohne.
    expect(parseJarName('trs-server-rules-bukkit-0.9.0.jar', '1.0.0')).toBeNull()
    expect(parseJarName('trs-server-rules-minestom-1.0.0.jar', '1.0.0')).toBeNull()
    expect(parseJarName('trs-server-rules-bukkit-1.21-1.0.0.jar', '1.0.0')).toBeNull()
    expect(parseJarName('trs-server-rules-fabric-1.0.0.jar', '1.0.0')).toBeNull()
    expect(parseJarName('other-1.0.0.jar', '1.0.0')).toBeNull()
    expect(isNewer('1.0.1', '1.0.0')).toBe(true)
    expect(isNewer('1.0.0', '1.0.0')).toBe(false)
    expect(isNewer('1.0.0', '1.0.0-beta.1')).toBe(true)
  })

  it('gehört zu den Formular-Optionen', () => {
    expect(serverExportOptionsSchema.safeParse({ ...options, serverRules: true }).success).toBe(true)
    expect(serverExportOptionsSchema.safeParse({ ...options, serverRules: 'ja' }).success).toBe(false)
  })
})

describe('Antworten des Kerns', () => {
  it('prüft den Plan und die Ereignisse', () => {
    const plan = {
      suggestedName: 'Test',
      gameVersion: '1.21.1',
      loader: 'fabric',
      loaderVersion: null,
      supported: true,
      javaMajor: 21,
      defaultRamMb: 4096,
      mods: [mod('a.jar')],
      worlds: [{ folder: 'Welt', name: 'Meine Welt' }],
      configDirs: ['config'],
      offline: false,
      serverRules: rules,
    }
    expect(serverExportPlanSchema.safeParse(plan).success).toBe(true)
    expect(serverExportPlanSchema.safeParse({ ...plan, serverRules: { ...rules, reason: 'kaputt' } }).success).toBe(false)
    expect(serverExportPlanSchema.safeParse({ ...plan, serverRules: { ...rules, file: '' } }).success).toBe(false)
    expect(serverExportPlanSchema.safeParse({ ...plan, loader: 'optifine' }).success).toBe(false)
    expect(localServerEventSchema.safeParse({ type: 'logs', id: 'mein-server', lines: ['x'] }).success).toBe(true)
    expect(localServerEventSchema.safeParse({ type: 'logs', id: '../x', lines: [] }).success).toBe(false)
  })
})

describe('Store der lokalen Server', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('übernimmt Log-Zeilen und Zustand aus den Ereignissen', async () => {
    const { useLocalServersStore } = await import('../app/stores/localServers')
    const store = useLocalServersStore()
    await store.refresh()
    expect(store.items.map((s) => s.id)).toEqual(['mein-server'])

    store.onEvent({ type: 'status', id: 'mein-server', status: { ...server('x', 'running').status, players: 2, playerNames: ['Alex', 'Steve'] } })
    expect(store.get('mein-server')?.status.state).toBe('running')
    expect(store.running.map((s) => s.id)).toEqual(['mein-server'])

    store.onEvent({ type: 'logs', id: 'mein-server', lines: ['eins', 'zwei'] })
    store.onEvent({ type: 'logs', id: 'mein-server', lines: ['drei'] })
    expect(store.logs['mein-server']).toEqual(['eins', 'zwei', 'drei'])

    // Kaputte Ereignisse werden ignoriert.
    store.onEvent({ type: 'logs', id: 'MEIN SERVER', lines: ['x'] })
    store.onEvent({ type: 'unbekannt' })
    expect(store.logs['mein-server']).toHaveLength(3)

    // Unbekannter Server (gerade angelegt) → Liste neu laden.
    store.onEvent({ type: 'status', id: 'neu', status: server('neu').status })
    expect(localServers.list).toHaveBeenCalledTimes(2)
  })

  it('übernimmt das ganze Log des Kerns einmal (ohne doppelte Zeilen)', async () => {
    const { useLocalServersStore } = await import('../app/stores/localServers')
    const store = useLocalServersStore()
    store.onEvent({ type: 'logs', id: 'mein-server', lines: ['[12:00:00] [Server thread/INFO]: Starting minecraft server'] })
    await store.loadLogs('mein-server')
    expect(store.logs['mein-server']).toEqual(['[12:00:00] [Server thread/INFO]: Starting minecraft server'])
    store.onEvent({ type: 'logs', id: 'mein-server', lines: ['neu'] })
    expect(store.logs['mein-server']).toHaveLength(2)
    await store.loadLogs('mein-server')
    expect(localServers.logs).toHaveBeenCalledTimes(1)
  })

  it('meldet Fehler beim Befehl und behält den Text', async () => {
    const { useLocalServersStore } = await import('../app/stores/localServers')
    const store = useLocalServersStore()
    localServers.command.mockRejectedValueOnce(new Error('kaputt'))
    expect(await store.command('mein-server', 'list')).toBe(false)
    expect(toasts.error).toHaveBeenCalledTimes(1)
    expect(await store.command('mein-server', 'list')).toBe(true)
  })
})
