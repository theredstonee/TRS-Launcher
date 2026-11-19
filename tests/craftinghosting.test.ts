import { describe, expect, it } from 'vitest'
import {
  type CraftingInfo,
  type WizardState,
  POLL_FAST_MS,
  addressOptions,
  cleanSubdomainInput,
  closestVersion,
  modsMatch,
  parseOrigins,
  planFromInstance,
  runtimePercent,
  serverAddress,
  stateTone,
  suggestServerName,
  suggestSubdomain,
  usagePercent,
  validSubdomain,
  wizardStepValid,
  POLL_SLOW_MS,
  cleanHostingCommand,
  craftingServerSchema,
  craftingInfoSchema,
  NO_FEATURES,
  hostingBlockedReason,
  defaultServerType,
  hostingCountdown,
  inCooldown,
  joinVersion,
  modrinthProjects,
  plainLogLine,
  pollInterval,
  settingsPatch,
  remainingSeconds,
  secondsUntil,
  validServerName,
} from '../app/utils/craftinghosting'

const base = {
  id: 'x',
  name: 'Meine Welt',
  playerId: 'P7KXGQXL',
  state: 'running',
  address: 'node1.example.com:25568',
  serverType: 'paper',
  version: 'latest',
  resources: { memoryMb: 4096, cpuPercent: 200, diskMb: 10240 },
  eulaAccepted: true,
  access: 'friends',
  session: {
    active: true,
    startedAt: null,
    lastHeartbeatAt: null,
    heartbeatTimeoutSeconds: 180,
    runSeconds: 60,
    maxRunSeconds: 86400,
    remainingSeconds: 3600,
    cooldownUntil: null,
    lastStopReason: null,
    hostPresent: true,
  },
  createdAt: null,
  lastStartAt: null,
  deleteAt: '2026-12-18T15:59:20.000Z',
}
const server = craftingServerSchema.parse(base)

describe('CraftingHosting-Helfer', () => {
  it('prüft Namen wie der Kern', () => {
    expect(validServerName(' Meine Welt ')).toBe(true)
    expect(validServerName("Bob's_SMP-2.0")).toBe(true)
    expect(validServerName('Grüne Insel')).toBe(true)
    expect(validServerName('ab')).toBe(false)
    expect(validServerName('x'.repeat(23))).toBe(false)
    expect(validServerName('SMP #1')).toBe(false)
  })

  it('säubert Konsolenbefehle', () => {
    expect(cleanHostingCommand('/say hi')).toBe('say hi')
    expect(cleanHostingCommand('  list ')).toBe('list')
    expect(cleanHostingCommand('/')).toBeNull()
    expect(cleanHostingCommand('say a\nop me')).toBeNull()
    expect(cleanHostingCommand('a'.repeat(257))).toBeNull()
  })

  it('fragt bei Zustandswechseln öfter nach', () => {
    expect(pollInterval(null)).toBe(POLL_SLOW_MS)
    expect(pollInterval(server)).toBe(POLL_SLOW_MS)
    expect(pollInterval({ ...server, state: 'starting' })).toBe(POLL_FAST_MS)
    expect(pollInterval({ ...server, state: 'installing' })).toBe(POLL_FAST_MS)
  })

  it('zählt Laufzeit und Pause herunter', () => {
    const now = Date.parse('2026-11-18T12:00:00Z')
    expect(remainingSeconds(server, now - 10_000, now)).toBe(3590)
    expect(remainingSeconds({ ...server, session: { ...server.session, active: false } }, 0, now)).toBe(3600)
    const cooling = { ...server, session: { ...server.session, cooldownUntil: '2026-11-18T13:00:00.000Z' } }
    expect(secondsUntil(cooling.session.cooldownUntil, now)).toBe(3600)
    expect(inCooldown(cooling, now)).toBe(true)
    expect(inCooldown(cooling, now + 3_600_000)).toBe(false)
    expect(hostingCountdown(3725)).toBe('1:02:05')
    expect(hostingCountdown(65)).toBe('1:05')
  })

  it('wählt die Version zum Beitreten', () => {
    const list = [{ id: 'a', gameVersion: '1.21.11' }, { id: 'b', gameVersion: '26.3' }, { id: 'c', gameVersion: '26w14a' }]
    expect(joinVersion('1.20.1', list)).toBe('1.20.1')
    expect(joinVersion('latest', list)).toBe('26.3')
    expect(joinVersion('latest', [{ id: 'c', gameVersion: '26w14a' }])).toBeNull()
  })

  it('nimmt Paper als Vorgabe', () => {
    expect(defaultServerType(null)).toEqual({ serverType: '', version: '' })
    const info = {
      plan: { name: '', memoryMb: 0, cpuPercent: 0, diskMb: 0 },
      serverTypes: [
        { id: 'vanilla', name: 'Vanilla', versions: ['1.21.11'] },
        { id: 'paper', name: 'Paper', versions: ['latest', '1.21.11'] },
      ],
      limits: { maxServersPerPlayer: 1, sessionMaxHours: 24, cooldownHours: 2, heartbeatTimeoutSeconds: 180, deleteAfterIdleDays: 30, serverNameMaxLength: 22 },
      eulaUrl: 'https://www.minecraft.net/eula',
      full: false,
    }
    expect(defaultServerType(info)).toEqual({ serverType: 'paper', version: 'latest' })
  })

  it('lehnt unbekannte Zustände ab', () => {
    expect(craftingServerSchema.safeParse({ ...base, state: 'exploded' }).success).toBe(false)
  })
})

describe('Einstellungen, Mods, Konsole', () => {
  it('schickt nur geänderte Einstellungen, nie die Whitelist', () => {
    const before = {
      motd: 'A', maxPlayers: 8, gamemode: 'survival', difficulty: 'normal', hardcore: false, pvp: true, allowFlight: false,
      seed: '', levelType: 'normal', spawnProtection: 16, viewDistance: 10, simulationDistance: 10, whitelist: true,
    } as const
    expect(settingsPatch(before, { ...before })).toEqual({})
    expect(settingsPatch(before, { ...before, motd: 'B', pvp: false, whitelist: false })).toEqual({ motd: 'B', pvp: false })
  })

  it('trennt Modrinth-Mods von den übrigen', () => {
    const items = [
      { fileName: 'a.jar', title: 'A', enabled: true, source: { projectId: 'AANobbMI' } },
      { fileName: 'a2.jar', title: 'A2', enabled: true, source: { projectId: 'AANobbMI', platform: 'modrinth' } },
      { fileName: 'b.jar', title: 'B', enabled: true, source: { projectId: '123', platform: 'curseforge' } },
      { fileName: 'c.jar', enabled: true, source: null },
      { fileName: 'd.jar', title: 'D', enabled: false, source: { projectId: 'P7dR8mSH' } },
    ]
    expect(modrinthProjects(items)).toEqual({ projects: ['AANobbMI'], other: ['B', 'c.jar'] })
  })

  it('entfernt Farbcodes aus Log-Zeilen', () => {
    expect(plainLogLine('§aGrün §lfett§r normal')).toBe('Grün fett normal')
  })
})

const INFO: CraftingInfo = {
  plan: { name: 'Free 4 GB', memoryMb: 4096, cpuPercent: 200, diskMb: 10240 },
  serverTypes: [
    { id: 'paper', name: 'Paper', versions: ['latest', '26.3', '1.21.11', '1.21.4', '1.20.1', '1.8.9'] },
    { id: 'fabric', name: 'Fabric', versions: ['latest', '26.3', '1.21.11', '1.20.1', '1.16.5'] },
    { id: 'forge', name: 'Forge', versions: ['1.21.1', '1.20.1', '1.12.2'] },
  ],
  limits: { maxServersPerPlayer: 1, sessionMaxHours: 24, cooldownHours: 2, heartbeatTimeoutSeconds: 180, deleteAfterIdleDays: 30, serverNameMaxLength: 22 },
  eulaUrl: 'https://www.minecraft.net/eula',
  full: false,
  features: NO_FEATURES,
  notice: null,
  maintenance: { active: false, message: null },
}
const inst = (kind: string, gameVersion: string) => ({ gameVersion, loader: { kind } })

describe('Eigene Adresse', () => {
  it('prüft und säubert Adressen wie der Kern', () => {
    expect(validSubdomain('meinewelt')).toBe(true)
    expect(validSubdomain('bob-smp-2')).toBe(true)
    for (const bad of ['ab', '-abc', 'abc-', 'a_b', 'Mein', 'x'.repeat(25)]) expect(validSubdomain(bad)).toBe(false)
    expect(cleanSubdomainInput('Mein Welt_2!')).toBe('mein-welt-2')
    expect(cleanSubdomainInput('x'.repeat(30))).toHaveLength(24)
  })

  it('schlägt eine Adresse aus dem Namen vor', () => {
    expect(suggestSubdomain('Grüne Insel')).toBe('gruene-insel')
    expect(suggestSubdomain("Bob's SMP #1")).toBe('bob-s-smp-1')
    expect(suggestSubdomain('  Café  Straße ')).toBe('cafe-strasse')
    expect(suggestSubdomain('ab')).toBe('')
    expect(suggestSubdomain('a'.repeat(23) + ' b')).toBe('a'.repeat(23))
  })

  it('nimmt die eigene Domain vor der Node-Adresse und kennt die Ansichten', () => {
    expect(serverAddress({ domain: 'meinewelt.trs-launcher.de', address: 'node1:1' })).toBe('meinewelt.trs-launcher.de')
    expect(serverAddress({ domain: null, address: 'node1:1' })).toBe('node1:1')
    expect(serverAddress(null)).toBeNull()
    expect(addressOptions({ domain: 'a-b.trs-launcher.de', ip: '1.2.3.4:25011', address: 'node02:25011' }).map((o) => o.view)).toEqual([
      'domain',
      'ip',
      'node',
    ])
    expect(addressOptions({ domain: null, ip: null, address: 'node02:25011' })).toEqual([{ view: 'node', value: 'node02:25011' }])
  })

  it('Schema: ungültige Domain und IP werden null', () => {
    const ok = craftingServerSchema.parse({ ...base, domain: 'meinewelt.trs-launcher.de', ip: '1.2.3.4:25011' })
    expect([ok.domain, ok.ip]).toEqual(['meinewelt.trs-launcher.de', '1.2.3.4:25011'])
    const bad = craftingServerSchema.parse({ ...base, domain: 'evil.com', ip: 'node:1' })
    expect([bad.domain, bad.ip]).toEqual([null, null])
  })
})

describe('Server aus einer Instanz', () => {
  it('Fabric und Forge bleiben, Vanilla wird Paper', () => {
    expect(planFromInstance(inst('fabric', '1.21.11'), INFO)).toEqual({
      serverType: 'fabric',
      version: '1.21.11',
      versionExact: true,
      modsTransferable: true,
      note: null,
    })
    expect(planFromInstance(inst('forge', '1.20.1'), INFO)).toMatchObject({ serverType: 'forge', modsTransferable: true })
    expect(planFromInstance(inst('vanilla', '1.21.4'), INFO)).toMatchObject({ serverType: 'paper', version: '1.21.4', note: 'vanilla', modsTransferable: false })
  })

  it('NeoForge/Quilt: Paper mit Hinweis, keine Mods', () => {
    for (const kind of ['neoforge', 'quilt']) {
      expect(planFromInstance(inst(kind, '1.21.11'), INFO)).toMatchObject({ serverType: 'paper', note: 'loaderUnsupported', modsTransferable: false })
    }
    // Typ fehlt beim Partner: ebenso Paper.
    const noForge = { ...INFO, serverTypes: INFO.serverTypes.filter((t) => t.id !== 'forge') }
    expect(planFromInstance(inst('forge', '1.20.1'), noForge)).toMatchObject({ serverType: 'paper', note: 'loaderUnsupported' })
    expect(planFromInstance(inst('fabric', '1.21.11'), null)).toBeNull()
  })

  it('fehlende Version: die nächste nicht neuere, sonst die älteste', () => {
    expect(planFromInstance(inst('fabric', '1.21.5'), INFO)).toMatchObject({ version: '1.20.1', versionExact: false })
    expect(planFromInstance(inst('forge', '1.21.11'), INFO)).toMatchObject({ version: '1.21.1', versionExact: false })
    expect(closestVersion('1.7.10', ['1.21.11', '1.8.9'])).toBe('1.8.9')
    expect(closestVersion('27w01a', ['latest', '26.3'])).toBe('latest')
    expect(closestVersion('27w01a', ['26.3', '1.21.11'])).toBe('26.3')
  })

  it('Mods passen nur zum gleichen Loader', () => {
    expect(modsMatch('fabric', 'fabric')).toBe(true)
    expect(modsMatch('forge', 'forge')).toBe(true)
    expect(modsMatch('fabric', 'paper')).toBe(false)
    expect(modsMatch('vanilla', 'vanilla')).toBe(false)
  })

  it('Servername aus dem Instanznamen', () => {
    expect(suggestServerName('Fabric 1.21 #2')).toBe('Fabric 1.21 2')
    expect(suggestServerName('Mein sehr langer Instanzname')).toBe('Mein sehr langer Insta')
    expect(suggestServerName('#!')).toBe('')
  })
})

describe('Assistent', () => {
  const w = (patch: Partial<WizardState> = {}): WizardState => ({
    source: 'new',
    instanceId: '',
    serverType: 'paper',
    version: 'latest',
    name: 'Meine Welt',
    subdomain: 'meinewelt',
    subdomainEnabled: true,
    subdomainStatus: 'available',
    access: 'friends',
    eula: true,
    ...patch,
  })

  it('Schritt 1: Quelle, Typ und Version', () => {
    expect(wizardStepValid(1, w())).toBe(true)
    expect(wizardStepValid(1, w({ source: null }))).toBe(false)
    expect(wizardStepValid(1, w({ source: 'instance' }))).toBe(false)
    expect(wizardStepValid(1, w({ source: 'instance', instanceId: 'a' }))).toBe(true)
    expect(wizardStepValid(1, w({ version: '' }))).toBe(false)
  })

  it('Schritt 2: Name und freie Adresse', () => {
    expect(wizardStepValid(2, w())).toBe(true)
    expect(wizardStepValid(2, w({ name: 'ab' }))).toBe(false)
    for (const status of ['taken', 'reserved', 'invalid', 'checking', 'idle'] as const) {
      expect(wizardStepValid(2, w({ subdomainStatus: status }))).toBe(false)
    }
    // Prüfung fehlgeschlagen: die API prüft beim Anlegen.
    expect(wizardStepValid(2, w({ subdomainStatus: 'error' }))).toBe(true)
    // Ohne Adress-Funktion zählt nur der Name.
    expect(wizardStepValid(2, w({ subdomainEnabled: false, subdomain: '', subdomainStatus: 'idle' }))).toBe(true)
  })

  it('Schritt 3: Zugang', () => {
    expect(wizardStepValid(3, w({ access: 'open' }))).toBe(true)
    expect(wizardStepValid(3, w({ access: 'everyone' as never }))).toBe(false)
    // EULA ist Pflicht
    expect(wizardStepValid(3, w({ eula: false }))).toBe(false)
  })
})

describe('Anzeige', () => {
  it('Farbton je Zustand', () => {
    expect(stateTone({ state: 'running', setup: 'done' })).toBe('ok')
    expect(stateTone({ state: 'running', setup: 'restarting' })).toBe('busy')
    expect(stateTone({ state: 'starting', setup: 'done' })).toBe('busy')
    expect(stateTone({ state: 'offline', setup: 'done' })).toBe('idle')
    expect(stateTone({ state: 'suspended', setup: 'done' })).toBe('bad')
    expect(stateTone(null)).toBe('idle')
  })

  it('Balken: Auslastung und Laufzeit', () => {
    expect(usagePercent(1, 4)).toBe(25)
    expect(usagePercent(8, 4)).toBe(100)
    expect(usagePercent(null, 4)).toBeNull()
    expect(usagePercent(1, 0)).toBeNull()
    const now = 1_000_000
    const s = { ...server, session: { ...server.session, maxRunSeconds: 7200, remainingSeconds: 3600 } }
    expect(runtimePercent(s, now, now)).toBe(50)
    expect(runtimePercent(s, now, now + 1800_000)).toBe(75)
    expect(runtimePercent({ ...s, session: { ...s.session, active: false } }, now, now)).toBe(0)
  })

  it('gemerkte Herkunft: Kaputtes fällt weg', () => {
    expect(parseOrigins('{"a":{"instanceId":"i1","transferred":false},"b":{"x":1},"c":{"instanceId":"i2"}}')).toEqual({
      a: { instanceId: 'i1', transferred: false },
      c: { instanceId: 'i2', transferred: false },
    })
    expect(parseOrigins('kaputt')).toEqual({})
    expect(parseOrigins(null)).toEqual({})
  })
})

describe('Funktions-Schalter', () => {
  const raw = {
    plan: { name: 'Free', memoryMb: 1, cpuPercent: 1, diskMb: 1 },
    serverTypes: [{ id: 'paper', name: 'Paper', versions: ['latest'] }],
    limits: { maxServersPerPlayer: 1, sessionMaxHours: 24, cooldownHours: 2, heartbeatTimeoutSeconds: 180, deleteAfterIdleDays: 30, serverNameMaxLength: 22 },
    eulaUrl: 'https://www.minecraft.net/eula',
    full: false,
  }

  it('fehlende oder unbekannte Schalter sind aus', () => {
    const info = craftingInfoSchema.parse({ ...raw, features: { files: true, backups: 'ja', teleport: true }, notice: 'Wartung' })
    expect(info.features.files).toBe(true)
    expect(info.features.backups).toBe(false)
    expect(info.features.versionChange).toBe(false)
    expect('teleport' in info.features).toBe(false)
    expect(info.notice?.markdown).toBe('Wartung')
    const old = craftingInfoSchema.parse(raw)
    expect(old.features).toEqual(NO_FEATURES)
    expect(old.notice).toBeNull()
  })
})

describe('Team-Steuerung', () => {
  const raw = {
    plan: { name: 'Free', memoryMb: 1, cpuPercent: 1, diskMb: 1 },
    serverTypes: [{ id: 'paper', name: 'Paper', versions: ['latest'] }],
    limits: { maxServersPerPlayer: 1, sessionMaxHours: 24, cooldownHours: 2, heartbeatTimeoutSeconds: 180, deleteAfterIdleDays: 30, serverNameMaxLength: 22 },
    eulaUrl: 'https://www.minecraft.net/eula',
    full: false,
  }

  it('Hinweis als Objekt oder (alt) Klartext, unbekannte Stufe = info', () => {
    const obj = craftingInfoSchema.parse({ ...raw, notice: { markdown: '**Neu**', level: 'success', updatedAt: '2026-11-18T20:00:00.000Z' } })
    expect(obj.notice).toEqual({ markdown: '**Neu**', level: 'success', updatedAt: '2026-11-18T20:00:00.000Z' })
    expect(craftingInfoSchema.parse({ ...raw, notice: 'Alt' }).notice).toEqual({ markdown: 'Alt', level: 'info', updatedAt: null })
    expect(craftingInfoSchema.parse({ ...raw, notice: { markdown: 'x', level: 'panic' } }).notice?.level).toBe('info')
    expect(craftingInfoSchema.parse({ ...raw, notice: 42 }).notice).toBeNull()
  })

  it('Wartung und Sperre stoppen Starten/Beitreten', () => {
    const info = craftingInfoSchema.parse({ ...raw, maintenance: { active: true, message: 'Update' }, features: { create: true, console: true } })
    expect(info.maintenance).toEqual({ active: true, message: 'Update' })
    expect(info.features.create && info.features.console).toBe(true)
    expect(craftingInfoSchema.parse(raw).maintenance).toEqual({ active: false, message: null })
    expect(hostingBlockedReason(info, null)).toBe('maintenance')
    const calm = craftingInfoSchema.parse(raw)
    expect(hostingBlockedReason(calm, { blocked: { reason: 'Spam', since: null } })).toBe('blocked')
    expect(hostingBlockedReason(calm, { blocked: null })).toBeNull()
    expect(craftingServerSchema.parse({ ...base, blocked: { reason: 'Spam', since: null } }).blocked?.reason).toBe('Spam')
    expect(craftingServerSchema.parse(base).blocked ?? null).toBeNull()
  })
})
