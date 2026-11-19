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

/** Funktions-Schalter der TRS API: ein Bereich erscheint nur, wenn sein Schalter an ist. */
export const FEATURE_KEYS = [
  'files',
  'properties',
  'addons',
  'worlds',
  'worldDownload',
  'ownAddress',
  'wakeOnJoin',
  'versionChange',
  'backups',
  'create',
  'console',
] as const
export type FeatureKey = (typeof FEATURE_KEYS)[number]
export type CraftingFeatures = Record<FeatureKey, boolean>
export const NO_FEATURES: CraftingFeatures = Object.fromEntries(FEATURE_KEYS.map((k) => [k, false])) as CraftingFeatures
export const NOTICE_LEVELS = ['info', 'warning', 'success'] as const
const noticeObject = z.object({
  markdown: z.string().min(1).max(4000),
  level: z.enum(NOTICE_LEVELS).catch('info'),
  updatedAt: z.string().max(40).nullish().catch(null),
})
const noticeSchema = z
  .union([noticeObject, z.string().min(1).max(4000).transform((markdown) => ({ markdown, level: 'info' as const, updatedAt: null }))])
  .nullable()
  .catch(null)

const featuresSchema = z
  .object(Object.fromEntries(FEATURE_KEYS.map((k) => [k, z.boolean().catch(false)])) as Record<FeatureKey, z.ZodCatch<z.ZodBoolean>>)
  .catch(NO_FEATURES)

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
  features: featuresSchema,
  /** Hinweis oben im Hosting-Reiter (Markdown; alte API: Klartext). */
  notice: noticeSchema,
  /** Wartung durch das TRS-Team: Anlegen, Starten und Beitreten sind aus. */
  maintenance: z.object({ active: z.boolean(), message: z.string().max(500).nullable() }).catch({ active: false, message: null }),
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
  /** Vom TRS-Team gesperrt: Starten und Beitreten sind aus. */
  blocked: z.object({ reason: z.string().max(300), since: time }).nullish().catch(null),
})

/** Feste zweite MOTD-Zeile, setzt die TRS-API. */
export const MOTD_FOOTER = 'Free by craftinghosting.free × trs-launcher.de'
export const MOTD_MAX = 58

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
  phase: z.enum(['zip', 'upload', 'import', 'list', 'download']),
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

// --- Dateien, alle Einstellungen, Plugins/Mods ---------------------------------------------

export const SERVER_PATH_MAX = 512
/** Größter Text, den der Editor speichert (wie im Kern). */
export const FILE_CONTENT_MAX = 1024 * 1024
export const PROPERTY_VALUE_MAX = 500
export const PROPERTY_KEY_RE = /^[a-z0-9.-]{1,64}$/
export const DELETE_MAX = 50

export const FILE_LOCKS = ['system', 'trs', 'access', 'other'] as const
export type FileLock = (typeof FILE_LOCKS)[number]

const serverPath = z.string().startsWith('/').max(SERVER_PATH_MAX)
const fileName = z.string().min(1).max(255)
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f]/

export const serverFileSchema = z.object({
  name: fileName,
  path: serverPath,
  type: z.enum(['file', 'directory']),
  size: z.number().min(0),
  modifiedAt: time,
  /** Darf gespeichert werden. */
  editable: z.boolean(),
  /** Darf (nur lesend) geöffnet werden. */
  viewable: z.boolean(),
  protected: z.boolean(),
  /** Grund der Sperre – entscheidet allein die API; Unbekanntes gilt als allgemeine Sperre. */
  lock: z.enum(FILE_LOCKS).nullable().catch('other'),
})
export const serverDirSchema = z.object({ dir: serverPath, writable: z.boolean(), entries: z.array(serverFileSchema).max(5000) })
export const serverFileContentSchema = z.object({ path: serverPath, content: z.string() })
export const savedFileSchema = z.object({ path: serverPath, size: z.number().min(0) })
export const serverPropertiesSchema = z.object({
  properties: z.record(z.string().regex(PROPERTY_KEY_RE), z.string().max(PROPERTY_VALUE_MAX)),
  locked: z.array(z.string().regex(PROPERTY_KEY_RE)),
})
export const serverAddonSchema = z.object({ file: fileName, size: z.number().min(0), modifiedAt: time, protected: z.boolean() })
export const serverAddonsSchema = z.object({
  kind: z.enum(['plugins', 'mods']).nullable(),
  dir: serverPath,
  items: z.array(serverAddonSchema).max(5000),
})

export type ServerFile = z.infer<typeof serverFileSchema>
export type ServerDir = z.infer<typeof serverDirSchema>
export type ServerProperties = z.infer<typeof serverPropertiesSchema>
export type ServerAddon = z.infer<typeof serverAddonSchema>
export type ServerAddons = z.infer<typeof serverAddonsSchema>
export type AddonKind = NonNullable<ServerAddons['kind']>

/** Wie im Kern: absolut, ohne `..`/`.`, ohne `\` und Steuerzeichen, ≤ 512 Zeichen. */
export function validServerPath(path: string): boolean {
  if (!path.startsWith('/') || [...path].length > SERVER_PATH_MAX || path.includes('\\') || CONTROL.test(path)) return false
  const trimmed = path.replace(/\/+$/, '')
  if (!trimmed) return true
  return trimmed
    .slice(1)
    .split('/')
    .every((s) => s !== '' && s !== '.' && s !== '..' && s.trim() === s)
}

/** Neuer Ordnername: kein `/`, `\`, `.`/`..`, keine Steuerzeichen, höchstens 100 Zeichen. */
export function validEntryName(name: string): boolean {
  const n = name.trim()
  return !!n && n === name && n.length <= 100 && n !== '.' && n !== '..' && !/[/\\]/.test(n) && !CONTROL.test(n)
}

export function joinServerPath(dir: string, name: string): string {
  return dir === '/' ? `/${name}` : `${dir.replace(/\/+$/, '')}/${name}`
}

export function parentServerPath(path: string): string {
  const trimmed = path.replace(/\/+$/, '')
  const cut = trimmed.lastIndexOf('/')
  return cut <= 0 ? '/' : trimmed.slice(0, cut)
}

/** Brotkrumen: `/plugins/Foo` → `/`, `/plugins`, `/plugins/Foo`. */
export function serverCrumbs(dir: string): { name: string; path: string }[] {
  const parts = dir.split('/').filter(Boolean)
  return [{ name: '/', path: '/' }, ...parts.map((name, i) => ({ name, path: `/${parts.slice(0, i + 1).join('/')}` }))]
}

/** Ordner zuerst, dann nach Name (natürlich sortiert). */
export function sortServerFiles(entries: ServerFile[], collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })): ServerFile[] {
  return [...entries].sort((a, b) => (a.type === b.type ? collator.compare(a.name, b.name) : a.type === 'directory' ? -1 : 1))
}

export type FileIcon = 'folder' | 'config' | 'text' | 'log' | 'archive' | 'jar' | 'image' | 'data' | 'file'

export function fileIcon(entry: Pick<ServerFile, 'name' | 'type'>): FileIcon {
  if (entry.type === 'directory') return 'folder'
  const name = entry.name.toLowerCase()
  if (/\.log(\.gz)?$/.test(name)) return 'log'
  const ext = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1) : ''
  if (['yml', 'yaml', 'properties', 'toml', 'json', 'json5', 'conf', 'cfg', 'ini'].includes(ext)) return 'config'
  if (['zip', 'gz', 'tar', '7z'].includes(ext)) return 'archive'
  if (ext === 'jar') return 'jar'
  if (['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(ext)) return 'image'
  if (['dat', 'mca', 'nbt', 'db', 'sqlite', 'dat_old'].includes(ext)) return 'data'
  if (!ext || ['txt', 'md', 'sk', 'js', 'mcfunction', 'csv', 'lock'].includes(ext)) return 'text'
  return 'file'
}

// --- server.properties ---

export const PROPERTY_GROUPS = ['general', 'gameplay', 'world', 'players', 'network', 'other'] as const
export type PropertyGroup = (typeof PROPERTY_GROUPS)[number]

export type PropertyField =
  | { kind: 'bool' }
  | { kind: 'number'; min?: number; max?: number }
  | { kind: 'select'; options: readonly string[] }
  | { kind: 'motd' }
  | { kind: 'text' }
  | { kind: 'readonly' }

interface KnownProperty {
  group: PropertyGroup
  field: PropertyField
}

const BOOL: PropertyField = { kind: 'bool' }
const TEXT: PropertyField = { kind: 'text' }
const num = (min?: number, max?: number): PropertyField => ({ kind: 'number', min, max })

export const PROPERTY_GAMEMODES = ['survival', 'creative', 'adventure', 'spectator'] as const
export const PROPERTY_DIFFICULTIES = ['peaceful', 'easy', 'normal', 'hard'] as const
export const PROPERTY_LEVEL_TYPES = [
  'minecraft:normal',
  'minecraft:flat',
  'minecraft:large_biomes',
  'minecraft:amplified',
  'minecraft:single_biome_surface',
] as const

/** Bekannte Schlüssel mit Gruppe und Eingabeart; Beschriftung über `craftinghosting.props.keys.<key>`. */
export const KNOWN_PROPERTIES = {
  motd: { group: 'general', field: { kind: 'motd' } },
  'max-players': { group: 'general', field: num(1, 100) },
  'white-list': { group: 'general', field: { kind: 'readonly' } },
  'enforce-whitelist': { group: 'general', field: { kind: 'readonly' } },
  'online-mode': { group: 'general', field: BOOL },
  'hide-online-players': { group: 'general', field: BOOL },
  gamemode: { group: 'gameplay', field: { kind: 'select', options: PROPERTY_GAMEMODES } },
  'force-gamemode': { group: 'gameplay', field: BOOL },
  difficulty: { group: 'gameplay', field: { kind: 'select', options: PROPERTY_DIFFICULTIES } },
  hardcore: { group: 'gameplay', field: BOOL },
  pvp: { group: 'gameplay', field: BOOL },
  'allow-flight': { group: 'gameplay', field: BOOL },
  'spawn-monsters': { group: 'gameplay', field: BOOL },
  'spawn-animals': { group: 'gameplay', field: BOOL },
  'spawn-npcs': { group: 'gameplay', field: BOOL },
  'enable-command-block': { group: 'gameplay', field: BOOL },
  'player-idle-timeout': { group: 'players', field: num(0) },
  'op-permission-level': { group: 'players', field: num(0, 4) },
  'function-permission-level': { group: 'players', field: num(1, 4) },
  'spawn-protection': { group: 'world', field: num(0) },
  'level-name': { group: 'world', field: TEXT },
  'level-seed': { group: 'world', field: TEXT },
  'level-type': { group: 'world', field: { kind: 'select', options: PROPERTY_LEVEL_TYPES } },
  'generate-structures': { group: 'world', field: BOOL },
  'allow-nether': { group: 'world', field: BOOL },
  'max-world-size': { group: 'world', field: num(1, 29999984) },
  'view-distance': { group: 'world', field: num(2, 32) },
  'simulation-distance': { group: 'world', field: num(2, 32) },
  'entity-broadcast-range-percentage': { group: 'world', field: num(10, 1000) },
  'resource-pack': { group: 'network', field: TEXT },
  'resource-pack-sha1': { group: 'network', field: TEXT },
  'resource-pack-prompt': { group: 'network', field: TEXT },
  'require-resource-pack': { group: 'network', field: BOOL },
  'network-compression-threshold': { group: 'network', field: num(-1) },
  'rate-limit': { group: 'network', field: num(0) },
  'enable-status': { group: 'network', field: BOOL },
  'enforce-secure-profile': { group: 'network', field: BOOL },
  'prevent-proxy-connections': { group: 'network', field: BOOL },
  'max-tick-time': { group: 'other', field: num(-1) },
  'sync-chunk-writes': { group: 'other', field: BOOL },
  'log-ips': { group: 'other', field: BOOL },
  'pause-when-empty-seconds': { group: 'other', field: num(0) },
} as const satisfies Record<string, KnownProperty>
export type KnownPropertyKey = keyof typeof KNOWN_PROPERTIES

/** Nicht über die Liste änderbar (folgt dem Zugang). */
const READONLY_KEYS: readonly string[] = ['white-list', 'enforce-whitelist']

/** Eingabeart eines Schlüssels: bekannte nach Tabelle, unbekannte als Text (`true`/`false` als Schalter). */
export function propertyField(key: string, value = ''): PropertyField {
  if (isKnownProperty(key)) return KNOWN_PROPERTIES[key].field
  return value === 'true' || value === 'false' ? BOOL : TEXT
}

export function propertyGroup(key: string): PropertyGroup {
  return isKnownProperty(key) ? KNOWN_PROPERTIES[key].group : 'other'
}

export function isKnownProperty(key: string): key is KnownPropertyKey {
  return Object.hasOwn(KNOWN_PROPERTIES, key)
}

export const LEVEL_TYPE_IDS = ['normal', 'flat', 'large_biomes', 'amplified', 'single_biome_surface'] as const
export type LevelTypeId = (typeof LEVEL_TYPE_IDS)[number]

/** `minecraft:flat` → `flat` (auch alte Schreibweisen wie `DEFAULT`), sonst `null`. */
export function levelTypeId(value: string): LevelTypeId | null {
  const raw = value.replace(/\\/g, '').replace(/^minecraft:/, '').toLowerCase()
  const id = raw === 'default' ? 'normal' : raw === 'largebiomes' ? 'large_biomes' : raw
  return LEVEL_TYPE_IDS.find((x) => x === id) ?? null
}

/** Wert passt zur Eingabeart (eine Zeile, ≤ 500 Zeichen; MOTD ≤ 58 ohne §-Codes). */
export function validPropertyValue(field: PropertyField, value: string): boolean {
  if ([...value].length > PROPERTY_VALUE_MAX || CONTROL.test(value)) return false
  switch (field.kind) {
    case 'bool':
      return value === 'true' || value === 'false'
    case 'number': {
      if (!/^-?\d{1,10}$/.test(value)) return false
      const n = Number(value)
      return (field.min === undefined || n >= field.min) && (field.max === undefined || n <= field.max)
    }
    case 'motd':
      return [...value].length <= MOTD_MAX && !value.includes('§')
    default:
      return true
  }
}

/** Nur geänderte, nicht gesperrte Schlüssel (für PATCH); `white-list` folgt dem Zugang und fehlt immer. */
export function propertiesPatch(
  before: Record<string, string>,
  after: Record<string, string>,
  locked: readonly string[] = [],
): Record<string, string> {
  const patch: Record<string, string> = {}
  for (const [key, value] of Object.entries(after)) {
    if (!PROPERTY_KEY_RE.test(key) || locked.includes(key) || READONLY_KEYS.includes(key)) continue
    if (before[key] !== value) patch[key] = value
  }
  return patch
}

/** Suche über Schlüssel, Beschriftung und Wert (ohne Groß-/Kleinschreibung). */
export function matchesProperty(query: string, key: string, label: string, value: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  return [key, label, value].some((s) => s.toLowerCase().includes(q))
}

// --- Plugins/Mods ---

/** Plugins (Paper/Purpur), Mods (Fabric/Forge/NeoForge) oder nichts (Vanilla). */
export function addonKind(serverType: string | null | undefined): AddonKind | null {
  if (!serverType) return null
  if (['paper', 'purpur', 'spigot', 'bukkit', 'folia'].includes(serverType)) return 'plugins'
  if (['fabric', 'forge', 'neoforge', 'quilt'].includes(serverType)) return 'mods'
  return null
}

function newestRelease(versions: string[]): string | null {
  let best: { v: string; parts: number[] } | null = null
  for (const v of versions) {
    const parts = releaseParts(v)
    if (parts && (!best || compareRelease(parts, best.parts) > 0)) best = { v, parts }
  }
  return best?.v ?? null
}

/** Minecraft-Version des Servers; `latest` = neueste Release des Servertyps (sonst aller Typen). */
export function serverGameVersion(serverType: string, version: string, info: CraftingInfo | null): string | null {
  if (version !== 'latest') return version
  const types = info?.serverTypes ?? []
  return newestRelease(types.find((ty) => ty.id === serverType)?.versions ?? []) ?? newestRelease(types.flatMap((ty) => ty.versions))
}

const squash = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, '')

export interface AddonSearchFacets {
  kind: 'plugin' | 'mod'
  /** ODER-verknüpft: Plugin-Plattformen bzw. der Modloader. */
  loaders: string[]
  gameVersions: string[]
  serverUsable: true
}

export const PLUGIN_CATEGORIES = ['paper', 'purpur', 'spigot', 'bukkit'] as const
/** Loader, die die Modrinth-Suche im Kern kennt. */
const SEARCH_LOADERS = ['fabric', 'quilt', 'forge', 'neoforge']
export const ADDON_SORTS = ['relevance', 'downloads', 'updated', 'newest'] as const
export type AddonSort = (typeof ADDON_SORTS)[number]

/** Modrinth-Filter für den Servertyp; `null` ohne Plugins/Mods (Vanilla). */
export function addonSearchFacets(serverType: string, version: string, info: CraftingInfo | null): AddonSearchFacets | null {
  const kind = addonKind(serverType)
  if (!kind) return null
  const game = serverGameVersion(serverType, version, info)
  const base = { gameVersions: game ? [game] : [], serverUsable: true as const }
  if (kind === 'plugins') return { ...base, kind: 'plugin', loaders: [...PLUGIN_CATEGORIES] }
  return { ...base, kind: 'mod', loaders: SEARCH_LOADERS.includes(serverType) ? [serverType] : [] }
}

/** Kategorie-Chips: Modrinth-Kategorien (keine Loader), ohne doppelte, sortiert nach Anzeigename. */
export function addonCategoryNames(tags: { name: string; projectType: string; header: string }[], label: (name: string) => string): string[] {
  const loaders = new Set<string>([...PLUGIN_CATEGORIES, ...SEARCH_LOADERS, 'folia', 'sponge', 'velocity', 'bungeecord', 'waterfall', 'datapack', 'minecraft'])
  const names = new Set<string>()
  for (const tag of tags) {
    if (tag.header === 'categories' && (tag.projectType === 'mod' || tag.projectType === 'plugin') && !loaders.has(tag.name)) names.add(tag.name)
  }
  return [...names].sort((a, b) => label(a).localeCompare(label(b)))
}

interface VersionLike {
  id: string
  gameVersions: string[]
  loaders: string[]
  fileName: string
  versionType: string
}

/** Passt eine Modrinth-Version zum Server (Minecraft-Version und Loader bzw. Plugin-Plattform)? */
export function versionFitsServer(v: VersionLike, facets: Pick<AddonSearchFacets, 'gameVersions' | 'loaders'>): boolean {
  if (facets.gameVersions.length && !facets.gameVersions.some((g) => v.gameVersions.includes(g))) return false
  if (!facets.loaders.length) return true
  // Paper/Purpur laden auch Spigot/Bukkit-Plugins, Folia-Plugins laufen auf Paper meist nicht.
  return v.loaders.some((l) => facets.loaders.includes(l))
}

/** Versionen für die Liste: passende (oder alle), neueste zuerst wie von Modrinth geliefert. */
export function serverVersions<T extends VersionLike>(versions: T[], facets: Pick<AddonSearchFacets, 'gameVersions' | 'loaders'>, all: boolean): T[] {
  return all ? versions : versions.filter((v) => versionFitsServer(v, facets))
}

/** Neueste passende Version, Releases vor Betas/Alphas. */
export function newestServerVersion<T extends VersionLike>(versions: T[], facets: Pick<AddonSearchFacets, 'gameVersions' | 'loaders'>): T | null {
  const fitting = versions.filter((v) => versionFitsServer(v, facets))
  return fitting.find((v) => v.versionType === 'release') ?? fitting[0] ?? null
}

/** Installierte Datei, die zu einer Version des Projekts gehört (Dateiname, ohne Groß-/Kleinschreibung). */
export function installedVersion<T extends VersionLike>(versions: T[], files: readonly string[]): { version: T; file: string } | null {
  const lower = new Map(files.map((f) => [f.toLowerCase().replace(/\.disabled$/, ''), f]))
  for (const v of versions) {
    const file = lower.get(v.fileName.toLowerCase())
    if (file) return { version: v, file }
  }
  return null
}

/** Grobe Vermutung für die Trefferliste (ohne Versionen): Jar-Name beginnt mit Slug oder Titel. */
export function looksInstalled(hit: { slug: string; title: string }, files: readonly string[]): boolean {
  const keys = [squash(hit.slug), squash(hit.title)].filter((k) => k.length >= 3)
  return files.some((f) => {
    const base = squash(addonBaseName(f))
    return base.length >= 3 && keys.some((k) => base === k || base.startsWith(k) || k.startsWith(base))
  })
}

/** Name ohne `.jar` und Versionsteil: `LuckPerms-Bukkit-5.4.102.jar` → `LuckPerms-Bukkit`. */
export function addonBaseName(file: string): string {
  const stem = file.replace(/\.jar(\.disabled)?$/i, '')
  const cut = stem.search(/[-_ +]v?\d/i)
  const base = (cut > 0 ? stem.slice(0, cut) : stem).replace(/[-_ +]+$/, '')
  return base || stem
}

/** Passender Konfigurationsordner unter den vorhandenen: genau, sonst längster gemeinsamer Anfang. */
export function matchConfigFolder(file: string, folders: string[]): string | null {
  const base = addonBaseName(file)
  const exact = folders.find((f) => f.toLowerCase() === base.toLowerCase())
  if (exact) return exact
  const want = squash(base)
  if (want.length < 3) return null
  let best: string | null = null
  for (const f of folders) {
    const have = squash(f)
    if (have.length >= 3 && (want.startsWith(have) || have.startsWith(want)) && (!best || have.length > squash(best).length)) best = f
  }
  return best
}

/** Wo die Einstellungen eines Plugins/Mods liegen: `/plugins/<Ordner>`, sonst `/plugins` bzw. `/config`. */
export function addonConfigPath(kind: AddonKind, file: string, folders: string[]): string {
  if (kind === 'mods') return '/config'
  const folder = matchConfigFolder(file, folders)
  return folder ? `/plugins/${folder}` : '/plugins'
}

// --- Welten ---

export const WORLD_NAME_RE = /^[A-Za-z0-9_-]{1,32}$/
export const WORLD_NAME_MAX = 32

export const serverWorldsSchema = z.object({
  active: z.string().regex(WORLD_NAME_RE),
  exists: z.boolean(),
  worlds: z.array(
    z.object({
      name: z.string().regex(WORLD_NAME_RE),
      active: z.boolean(),
      dimensions: z.array(z.string().max(32)).max(8),
    }),
  ),
})
export const worldDownloadSchema = z.object({
  fileName: z.string().max(260),
  files: count,
  bytes: z.number().min(0),
  truncated: z.boolean(),
})

export type ServerWorlds = z.infer<typeof serverWorldsSchema>
export type ServerWorld = ServerWorlds['worlds'][number]
export type WorldDownload = z.infer<typeof worldDownloadSchema>
export interface WorldUploadTarget {
  name: string
  activate: boolean
}
export type WorldUploadSource = { kind: 'instance'; instanceId: string; folder: string } | { kind: 'zip' } | { kind: 'folder' }

/** Nur die Form (1–32 Zeichen `A-Za-z0-9_-`); reservierte Namen lehnt die API ab. */
export function validWorldName(name: string): boolean {
  return WORLD_NAME_RE.test(name)
}

/** Weltname aus einem Ordnernamen: „Meine Insel!“ → `Meine_Insel`. Leer → `world`. */
export function suggestWorldName(input: string): string {
  const name = input
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/\s+/g, '_')
    .replace(/[^A-Za-z0-9_-]/g, '')
    .slice(0, WORLD_NAME_MAX)
  return validWorldName(name) ? name : 'world'
}

/** `base`, sonst `base-2`, `base-3` … (höchstens 32 Zeichen). */
export function uniqueWorldName(base: string, taken: readonly string[]): string {
  if (!taken.includes(base)) return base
  for (let i = 2; i < 1000; i++) {
    const suffix = `-${i}`
    const name = `${base.slice(0, WORLD_NAME_MAX - suffix.length)}${suffix}`
    if (!taken.includes(name)) return name
  }
  return base
}

export type DimensionId = 'overworld' | 'nether' | 'end'

/** Dimension für die Anzeige; unbekannte bleiben `null` (dann roh anzeigen). */
export function dimensionId(value: string): DimensionId | null {
  const v = value.toLowerCase().replace(/^minecraft:/, '')
  if (v === 'overworld' || v === 'world') return 'overworld'
  if (v === 'nether' || v === 'the_nether' || v === 'dim-1') return 'nether'
  if (v === 'end' || v === 'the_end' || v === 'dim1') return 'end'
  return null
}

// --- Versionswechsel und Backups (nur mit Schalter) ---

export const backupsSchema = z.object({
  backups: z.array(
    z.object({
      id: z.string().regex(/^[A-Za-z0-9_-]{1,64}$/),
      name: z.string().max(100),
      size: z.number().min(0),
      createdAt: time,
    }),
  ),
  max: count,
})
export type CraftingBackups = z.infer<typeof backupsSchema>
export type CraftingBackup = CraftingBackups['backups'][number]

export interface VersionChange {
  serverType: string
  version: string
  keepWorld: boolean
}

/** Gibt es etwas zu wechseln (anderer Typ oder andere Version, beides angeboten)? */
export function versionChangeValid(change: VersionChange, current: { serverType: string; version: string }, info: CraftingInfo | null): boolean {
  const type = info?.serverTypes.find((ty) => ty.id === change.serverType)
  if (!type || !type.versions.includes(change.version)) return false
  return change.serverType !== current.serverType || change.version !== current.version
}

// --- Team-Steuerung ---

/** Darf gestartet bzw. beigetreten werden (keine Wartung, nicht gesperrt)? */
export function hostingBlockedReason(info: Pick<CraftingInfo, 'maintenance'> | null, server: Pick<CraftingServer, 'blocked'> | null): 'maintenance' | 'blocked' | null {
  if (info?.maintenance.active) return 'maintenance'
  if (server?.blocked) return 'blocked'
  return null
}
