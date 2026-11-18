import { describe, expect, it } from 'vitest'
import {
  POLL_FAST_MS,
  POLL_SLOW_MS,
  cleanHostingCommand,
  craftingServerSchema,
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
