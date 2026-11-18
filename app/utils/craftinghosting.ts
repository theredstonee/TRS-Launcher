import { z } from 'zod'

// Kostenloser Server beim Partner CraftingHosting (API §43, docs/craftinghosting.md).
// Der Launcher spricht nur mit der TRS API; Daten kommen gesäubert aus dem Kern.

export const SERVER_STATES = ['installing', 'offline', 'starting', 'running', 'stopping', 'failed', 'suspended'] as const
export const STOP_REASONS = ['host_stopped', 'host_timeout', 'session_limit', 'admin'] as const
export const ACCESS_MODES = ['friends', 'open'] as const
export const NAME_MIN = 3
export const NAME_MAX = 22
export const COMMAND_MAX = 256
export const SUBDOMAIN_MIN = 3
export const SUBDOMAIN_MAX = 24
export const DOMAIN_SUFFIX = '.trs-launcher.de'
export const SUBDOMAIN_REASONS = ['invalid', 'reserved', 'taken'] as const
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
  /** Eigene Adresse `<name>.trs-launcher.de`; fehlt bzw. `null` = noch keine. */
  domain: z
    .string()
    .regex(/^[a-z0-9-]{3,24}\.trs-launcher\.de$/)
    .nullish()
    .catch(null),
  /** Node als `IPv4:Port`. */
  ip: z
    .string()
    .regex(/^\d{1,3}(\.\d{1,3}){3}:\d{1,5}$/)
    .nullish()
    .catch(null),
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

export const subdomainCheckSchema = z.object({
  name: z.string().max(SUBDOMAIN_MAX),
  available: z.boolean(),
  reason: z.enum(SUBDOMAIN_REASONS).nullable(),
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
export type SubdomainCheck = z.infer<typeof subdomainCheckSchema>

export interface NewCraftingServer {
  name: string
  serverType: string
  version: string
  access: CraftingAccess
  /** Wunsch-Adresse ohne `.trs-launcher.de`. */
  subdomain?: string
}

/** Wie im Kern: 3–22 Zeichen aus Buchstaben, Ziffern, Leerzeichen und `. _ ' -`. */
export function validServerName(name: string): boolean {
  const n = name.trim()
  const len = [...n].length
  return len >= NAME_MIN && len <= NAME_MAX && /^[\p{L}\p{N} ._'-]+$/u.test(n)
}

/** Servername aus einem Instanznamen: Unerlaubtes raus, gekürzt. Leer, wenn nichts Gültiges übrig bleibt. */
export function suggestServerName(name: string): string {
  const cleaned = [...name.replace(/[^\p{L}\p{N} ._'-]+/gu, ' ').replace(/\s+/g, ' ').trim()]
    .slice(0, NAME_MAX)
    .join('')
    .trim()
  return validServerName(cleaned) ? cleaned : ''
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

// --- Eigene Adresse ---------------------------------------------------------------------

/** Wie im Kern: 3–24 Zeichen `a-z 0-9 -`, kein `-` am Anfang oder Ende. */
export function validSubdomain(name: string): boolean {
  return name.length >= SUBDOMAIN_MIN && name.length <= SUBDOMAIN_MAX && /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(name)
}

/** Eingabe im Adressfeld: klein, Leerzeichen/Unterstrich → `-`, alles andere fliegt raus. */
export function cleanSubdomainInput(input: string): string {
  return input
    .toLowerCase()
    .replace(/[\s_]+/g, '-')
    .replace(/[^a-z0-9-]/g, '')
    .slice(0, SUBDOMAIN_MAX)
}

/** Vorschlag aus dem Servernamen: „Grüne Insel“ → `gruene-insel`. Leer, wenn zu kurz. */
export function suggestSubdomain(name: string): string {
  const slug = name
    .toLowerCase()
    .replace(/ä/g, 'ae')
    .replace(/ö/g, 'oe')
    .replace(/ü/g, 'ue')
    .replace(/ß/g, 'ss')
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, SUBDOMAIN_MAX)
    .replace(/-+$/, '')
  return validSubdomain(slug) ? slug : ''
}

/** Adresse zum Beitreten und Anzeigen: eigene Domain vor der Node-Adresse. */
export function serverAddress(server: Pick<CraftingServer, 'domain' | 'address'> | null | undefined): string | null {
  return server?.domain || server?.address || null
}

export const ADDRESS_VIEWS = ['domain', 'ip', 'node'] as const
export type AddressView = (typeof ADDRESS_VIEWS)[number]
export const ADDRESS_VIEW_KEY = 'trs.craftinghosting.addressView'

/** Anzeigbare Adressen in fester Reihenfolge: TRS-Adresse, IP & Port, CraftingHosting-Adresse. */
export function addressOptions(
  server: Pick<CraftingServer, 'domain' | 'ip' | 'address'> | null | undefined,
): { view: AddressView; value: string }[] {
  const all: [AddressView, string | null | undefined][] = [
    ['domain', server?.domain],
    ['ip', server?.ip],
    ['node', server?.address],
  ]
  return all.filter((e): e is [AddressView, string] => !!e[1]).map(([view, value]) => ({ view, value }))
}

// --- Aus einer Instanz ------------------------------------------------------------------

interface InstanceSource {
  gameVersion: string
  loader: { kind: string }
}

export interface InstancePlan {
  serverType: string
  /** Leer, wenn der Typ keine Versionen hat. */
  version: string
  /** Gibt es genau die Version der Instanz? */
  versionExact: boolean
  /** Mods der Instanz passen zum Servertyp (Fabric → Fabric, Forge → Forge). */
  modsTransferable: boolean
  /** `vanilla`: Paper statt Vanilla; `loaderUnsupported`: Loader gibt es nicht, Mods bleiben weg. */
  note: 'vanilla' | 'loaderUnsupported' | null
}

/** Nächste Version aus der Liste: die höchste nicht neuere Release, sonst die älteste neuere, sonst die erste. */
export function closestVersion(target: string, versions: string[]): string {
  const want = releaseParts(target)
  const releases = versions
    .map((v) => ({ v, parts: releaseParts(v) }))
    .filter((r): r is { v: string; parts: number[] } => !!r.parts)
    .sort((a, b) => compareRelease(b.parts, a.parts))
  if (!want || !releases.length) return versions.includes('latest') ? 'latest' : (versions[0] ?? '')
  return (releases.find((r) => compareRelease(r.parts, want) <= 0) ?? releases[releases.length - 1]!).v
}

/** Servertyp und Version für eine Instanz. `null` ohne Servertypen. */
export function planFromInstance(instance: InstanceSource, info: CraftingInfo | null): InstancePlan | null {
  const types = info?.serverTypes ?? []
  if (!types.length) return null
  const kind = instance.loader.kind
  const wanted = kind === 'fabric' || kind === 'forge' ? kind : 'paper'
  const type = types.find((t) => t.id === wanted) ?? types.find((t) => t.id === 'paper') ?? types[0]!
  const modsTransferable = type.id === wanted && wanted !== 'paper'
  const note = kind === 'vanilla' ? (type.id === 'vanilla' ? null : 'vanilla') : modsTransferable ? null : 'loaderUnsupported'
  const versionExact = type.versions.includes(instance.gameVersion)
  return {
    serverType: type.id,
    version: versionExact ? instance.gameVersion : closestVersion(instance.gameVersion, type.versions),
    versionExact,
    modsTransferable,
    note,
  }
}

/** Mods einer Instanz mit diesem Loader passen auf diesen Servertyp. */
export function modsMatch(loaderKind: string, serverType: string): boolean {
  return (loaderKind === 'fabric' || loaderKind === 'forge') && loaderKind === serverType
}

// --- Assistent --------------------------------------------------------------------------

export type WizardStep = 1 | 2 | 3
export type SubdomainStatus = 'idle' | 'checking' | 'available' | 'taken' | 'reserved' | 'invalid' | 'error'

export interface WizardState {
  source: 'instance' | 'new' | null
  instanceId: string
  serverType: string
  version: string
  name: string
  subdomain: string
  /** `false`, wenn die API keine eigenen Adressen anbietet (`dns_unavailable`). */
  subdomainEnabled: boolean
  subdomainStatus: SubdomainStatus
  access: CraftingAccess
}

/** Darf der Assistent von diesem Schritt weiter? */
export function wizardStepValid(step: WizardStep, w: WizardState): boolean {
  if (step === 1) {
    if (!w.serverType || !w.version) return false
    return w.source === 'new' || (w.source === 'instance' && !!w.instanceId)
  }
  if (step === 2) {
    if (!validServerName(w.name)) return false
    if (!w.subdomainEnabled) return true
    // Prüfung fehlgeschlagen: die API prüft beim Anlegen selbst noch einmal.
    return validSubdomain(w.subdomain) && (w.subdomainStatus === 'available' || w.subdomainStatus === 'error')
  }
  return (ACCESS_MODES as readonly string[]).includes(w.access)
}

// --- Anzeige ----------------------------------------------------------------------------

export type StateTone = 'ok' | 'busy' | 'idle' | 'bad'

/** Farbton der Kopfkarte: grün läuft, gelb im Wechsel, grau aus, rot kaputt/gesperrt. */
export function stateTone(server: Pick<CraftingServer, 'state' | 'setup'> | null | undefined): StateTone {
  if (!server) return 'idle'
  if (server.state === 'failed' || server.state === 'suspended') return 'bad'
  if (server.setup === 'restarting' || isTransitional(server.state)) return 'busy'
  return server.state === 'running' ? 'ok' : 'idle'
}

/** Anteil in Prozent (0–100), `null` ohne Werte. */
export function usagePercent(used: number | null | undefined, limit: number | null | undefined): number | null {
  if (used == null || !limit) return null
  return Math.min(100, Math.max(0, (used / limit) * 100))
}

/** Laufzeit-Balken der Sitzung: verbrauchter Anteil (0–100). */
export function runtimePercent(server: CraftingServer | null, loadedAt: number, now = Date.now()): number {
  const max = server?.session.maxRunSeconds ?? 0
  if (!server?.session.active || !max) return 0
  return usagePercent(max - remainingSeconds(server, loadedAt, now), max) ?? 0
}

// --- Herkunft (Instanz → Server), lokal gemerkt -------------------------------------------

export const ORIGINS_KEY = 'trs.craftinghosting.origins'

const originSchema = z.object({ instanceId: z.string().min(1).max(64), transferred: z.boolean().catch(false) })
export type HostingOrigin = z.infer<typeof originSchema>

/** Gespeicherte Zuordnung Server-ID → Instanz; Kaputtes fällt weg. */
export function parseOrigins(raw: string | null): Record<string, HostingOrigin> {
  try {
    const parsed = z.record(z.string().max(64), z.unknown()).parse(JSON.parse(raw ?? '{}'))
    const out: Record<string, HostingOrigin> = {}
    for (const [id, value] of Object.entries(parsed)) {
      const origin = originSchema.safeParse(value)
      if (origin.success) out[id] = origin.data
    }
    return out
  } catch {
    return {}
  }
}
