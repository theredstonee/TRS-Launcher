import { z } from 'zod'

// Kostenloser Server beim Partner CraftingHosting (API §43, docs/craftinghosting.md).
// Der Launcher spricht nur mit der TRS API; Daten kommen gesäubert aus dem Kern.

export const SERVER_STATES = ['installing', 'offline', 'starting', 'running', 'stopping', 'failed', 'suspended'] as const
export const STOP_REASONS = ['host_stopped', 'host_timeout', 'session_limit', 'admin'] as const
export const ACCESS_MODES = ['friends', 'open'] as const
export const NAME_MIN = 3
export const NAME_MAX = 22
export const COMMAND_MAX = 256
/** Abfrage-Takt der Seite: schnell, solange sich der Zustand gerade ändert. */
export const POLL_FAST_MS = 5_000
export const POLL_SLOW_MS = 30_000

const time = z.string().max(40).nullable()
const count = z.number().int().min(0)

export const craftingInfoSchema = z.object({
  plan: z.object({ name: z.string().max(40), memoryMb: count, cpuPercent: count, diskMb: count }),
  serverTypes: z.array(
    z.object({
      id: z.string().regex(/^[a-z0-9_-]{1,32}$/),
      name: z.string().min(1).max(40),
      versions: z.array(z.string().regex(/^[0-9A-Za-z._+-]{1,32}$/)).min(1),
    }),
  ),
  limits: z.object({
    maxServersPerPlayer: count,
    sessionMaxHours: count,
    cooldownHours: count,
    heartbeatTimeoutSeconds: count,
    deleteAfterIdleDays: count,
    serverNameMaxLength: count,
  }),
  eulaUrl: z.string().startsWith('https://').max(300),
  full: z.boolean(),
})

export const craftingServerSchema = z.object({
  id: z.string().min(1).max(64),
  name: z.string().max(NAME_MAX),
  playerId: z.string().max(16),
  state: z.enum(SERVER_STATES),
  address: z.string().max(260).nullable(),
  serverType: z.string().max(32),
  version: z.string().max(32),
  resources: z.object({ memoryMb: count, cpuPercent: count, diskMb: count }),
  eulaAccepted: z.boolean(),
  access: z.enum(ACCESS_MODES),
  session: z.object({
    active: z.boolean(),
    startedAt: time,
    lastHeartbeatAt: time,
    heartbeatTimeoutSeconds: count,
    runSeconds: count,
    maxRunSeconds: count,
    remainingSeconds: count,
    cooldownUntil: time,
    lastStopReason: z.enum(STOP_REASONS).nullable(),
    /** `false` = der Launcher meldet sich nicht mehr, der Server läuft nur noch wegen Spielern weiter. */
    hostPresent: z.boolean(),
  }),
  createdAt: time,
  lastStartAt: time,
  /** Wird ohne Start bis dahin gelöscht; jeder Start schiebt es nach hinten. */
  deleteAt: time,
  /** Ersteinrichtung nach dem ersten Start (Plugin, MOTD); `restarting` = startet dafür einmal neu. */
  setup: z.enum(['pending', 'restarting', 'done']).catch('done'),
})

/** Feste zweite MOTD-Zeile, setzt die TRS-API. */
export const MOTD_FOOTER = 'Free server: trs-launcher.de × craftinghosting.free'
export const MOTD_MAX = 60

export const GAMEMODES = ['survival', 'creative', 'adventure', 'spectator'] as const
export const DIFFICULTIES = ['peaceful', 'easy', 'normal', 'hard'] as const
export const LEVEL_TYPES = ['normal', 'flat', 'large_biomes', 'amplified'] as const

export const craftingSettingsSchema = z.object({
  motd: z.string().max(120),
  maxPlayers: z.number().int().min(1).max(100),
  gamemode: z.enum(GAMEMODES),
  difficulty: z.enum(DIFFICULTIES),
  hardcore: z.boolean(),
  pvp: z.boolean(),
  allowFlight: z.boolean(),
  seed: z.string().max(64),
  levelType: z.enum(LEVEL_TYPES),
  spawnProtection: z.number().int().min(0).max(64),
  viewDistance: z.number().int().min(3).max(16),
  simulationDistance: z.number().int().min(3).max(16),
  whitelist: z.boolean(),
})

const bytes = z.number().min(0).nullable()
export const craftingStatsSchema = z.object({
  cpuPercent: z.number().min(0).nullable(),
  memory: z.object({ usedBytes: bytes, limitBytes: bytes }),
  disk: z.object({ usedBytes: bytes, limitBytes: bytes }),
  uptimeSeconds: z.number().min(0).nullable(),
  players: z.object({ online: z.number().int().min(0), max: z.number().int().min(0) }).nullable(),
})

export const craftingModsResultSchema = z.object({
  installed: z.array(z.string().max(64)),
  skipped: z.array(z.object({ project: z.string().max(64), reason: z.enum(['client_only', 'not_found', 'unsupported']) })),
})

export const worldUploadProgressSchema = z.object({
  phase: z.enum(['zip', 'upload', 'import']),
  percent: z.number().int().min(0).max(100),
})

export type CraftingInfo = z.infer<typeof craftingInfoSchema>
export type CraftingServer = z.infer<typeof craftingServerSchema>
export type CraftingState = CraftingServer['state']
export type CraftingAccess = CraftingServer['access']
export type StopReason = (typeof STOP_REASONS)[number]
export type CraftingSettings = z.infer<typeof craftingSettingsSchema>
export type CraftingSettingsPatch = Partial<Omit<CraftingSettings, 'whitelist'>>
export type CraftingStats = z.infer<typeof craftingStatsSchema>
export type CraftingModsResult = z.infer<typeof craftingModsResultSchema>
export type WorldUploadProgress = z.infer<typeof worldUploadProgressSchema>

export interface NewCraftingServer {
  name: string
  serverType: string
  version: string
  access: CraftingAccess
}

/** Wie im Kern: 3–22 Zeichen aus Buchstaben, Ziffern, Leerzeichen und `. _ ' -`. */
export function validServerName(name: string): boolean {
  const n = name.trim()
  const len = [...n].length
  return len >= NAME_MIN && len <= NAME_MAX && /^[\p{L}\p{N} ._'-]+$/u.test(n)
}

/** Konsolenbefehl wie im Kern: ein `/` vorne fällt weg, eine Zeile, 1–256 Zeichen. `null` = ungültig. */
export function cleanHostingCommand(input: string): string | null {
  const trimmed = input.trim()
  const cmd = (trimmed.startsWith('/') ? trimmed.slice(1) : trimmed).trim()
  const len = [...cmd].length
  if (!len || len > COMMAND_MAX || /[\u0000-\u001f\u007f-\u009f]/.test(cmd)) return null
  return cmd
}

/** Zustände, in denen sich gleich etwas ändert (Seite fragt dann öfter). */
export function isTransitional(state: CraftingState | null | undefined): boolean {
  return state === 'installing' || state === 'starting' || state === 'stopping'
}

export function pollInterval(server: CraftingServer | null): number {
  return isTransitional(server?.state) || server?.setup === 'restarting' ? POLL_FAST_MS : POLL_SLOW_MS
}

/** Restliche Sekunden bis `iso` (0, wenn vorbei oder unbekannt). */
export function secondsUntil(iso: string | null | undefined, now = Date.now()): number {
  if (!iso) return 0
  const at = Date.parse(iso)
  return Number.isFinite(at) ? Math.max(0, Math.ceil((at - now) / 1000)) : 0
}

/** Pflichtpause läuft noch? */
export function inCooldown(server: CraftingServer | null, now = Date.now()): boolean {
  return secondsUntil(server?.session.cooldownUntil, now) > 0
}

/**
 * Verbleibende Laufzeit: Server-Wert, seit der letzten Abfrage heruntergezählt.
 * `loadedAt` = Zeitpunkt der Abfrage.
 */
export function remainingSeconds(server: CraftingServer | null, loadedAt: number, now = Date.now()): number {
  if (!server?.session.active) return server?.session.remainingSeconds ?? 0
  return Math.max(0, server.session.remainingSeconds - Math.floor((now - loadedAt) / 1000))
}

/** „5:03:09“ bzw. „4:09“. */
export function hostingCountdown(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = String(s % 60).padStart(2, '0')
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}

interface InstanceLike {
  id: string
  gameVersion: string
}

function releaseParts(version: string): number[] | null {
  return /^\d+(\.\d+){1,2}$/.test(version) ? version.split('.').map(Number) : null
}

function compareRelease(a: number[], b: number[]): number {
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const d = (a[i] ?? 0) - (b[i] ?? 0)
    if (d) return d
  }
  return 0
}

/**
 * Version, mit der beigetreten wird. `latest` = die neueste Release-Version unter den Instanzen
 * (Snapshots zählen nicht). `null`, wenn es keine passende gibt.
 */
export function joinVersion(serverVersion: string, instances: InstanceLike[]): string | null {
  if (serverVersion !== 'latest') return serverVersion
  let best: { version: string; parts: number[] } | null = null
  for (const i of instances) {
    const parts = releaseParts(i.gameVersion)
    if (parts && (!best || compareRelease(parts, best.parts) > 0)) best = { version: i.gameVersion, parts }
  }
  return best?.version ?? null
}

/** Erster Servertyp und seine erste Version als Vorgabe im Formular. */
export function defaultServerType(info: CraftingInfo | null): { serverType: string; version: string } {
  const type = info?.serverTypes.find((t) => t.id === 'paper') ?? info?.serverTypes[0]
  return { serverType: type?.id ?? '', version: type?.versions[0] ?? '' }
}

/** Nur geänderte Einstellungen (für PATCH). `whitelist` folgt dem Zugang und fehlt immer. */
export function settingsPatch(before: CraftingSettings, after: CraftingSettings): CraftingSettingsPatch {
  const patch: Record<string, unknown> = {}
  for (const key of Object.keys(after) as (keyof CraftingSettings)[]) {
    if (key !== 'whitelist' && after[key] !== before[key]) patch[key] = after[key]
  }
  return patch as CraftingSettingsPatch
}

interface ContentLike {
  fileName: string
  title?: string | null
  enabled: boolean
  source?: { projectId: string; platform?: string | null } | null
}

/**
 * Mods einer Instanz für den Server: Modrinth-Projekte (ohne doppelte) und alles,
 * was (noch) nicht übertragbar ist – CurseForge oder ohne bekannte Quelle. Ausgeschaltete fehlen.
 */
export function modrinthProjects(items: ContentLike[]): { projects: string[]; other: string[] } {
  const projects: string[] = []
  const other: string[] = []
  for (const item of items) {
    if (!item.enabled) continue
    const id = item.source?.projectId
    const modrinth = item.source && (!item.source.platform || item.source.platform === 'modrinth')
    if (modrinth && id && /^[A-Za-z0-9_-]{1,64}$/.test(id)) {
      if (!projects.includes(id)) projects.push(id)
    } else {
      other.push(item.title || item.fileName)
    }
  }
  return { projects: projects.slice(0, 100), other }
}

/** Log-Zeile für die Anzeige: Minecraft-Farbcodes (§x) entfernen. */
export function plainLogLine(line: string): string {
  return line.replace(/§[0-9a-fk-orx]/gi, '')
}
