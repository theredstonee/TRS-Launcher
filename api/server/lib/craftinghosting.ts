import { z } from 'zod'
import type { AppContext } from './context'
import { createHash, createHmac, timingSafeEqual } from 'node:crypto'
import { lookup } from 'node:dns/promises'
import { all, one, run } from './db'
import { MODRINTH_ID } from './modrinth'
import { ApiError, badRequest, conflict, forbidden, notFound, unavailable } from './errors'
import { friendUuids } from './friends'
import { assertNotSanctioned } from './sanctions'
import { getUser, isBanned, type UserRow } from './users'

/**
 * CraftingHosting-Partner (§43): ein kostenloser Server je TRS-Konto. Der Partner-Schlüssel bleibt hier,
 * der Launcher spricht nur mit uns. Zugang „friends“ = Whitelist aus Besitzer + TRS-Freunden, die wir bei
 * laufendem Server per Konsolenbefehl abgleichen (die Partner-API hat keine eigene Whitelist).
 */

const TIMEOUT_MS = 10_000
const INFO_TTL_MS = 10 * 60 * 1000
const MISS_TTL_MS = 60 * 1000
const MC_NAME = /^\w{1,16}$/
const MAX_MODS = 100
const MOD_SLUG = /^[\w.-]{2,64}$/
const WORLD_ZIP = 'trs-world.zip'
/** Feste zweite MOTD-Zeile (Plugin) bzw. Anhang an die einzige Zeile (ohne Plugin). */
// Muss in die 270 px der Serverliste passen, sonst bricht der Rest in eine unsichtbare 3. Zeile um (jetzt ~241 px).
export const MOTD_FOOTER = '§7Free by §bcraftinghosting.free §8× §ctrs-launcher.de'
const OLD_FOOTERS = ['§7Free server by §bcraftinghosting.free §8× §ctrs-launcher.de']
/** Unsere Fußzeile (auch frühere Fassungen) vom Ende der MOTD abschneiden. */
function cutFooter(motd: string): string {
  for (const f of [MOTD_FOOTER, ...OLD_FOOTERS]) if (motd.endsWith(` ${f}`)) return motd.slice(0, -f.length - 1)
  return motd
}
const MOTD_LINE_MAX = 58
const PLUGIN_MIN = [1, 1, 0]
const PLUGIN_MAX_BYTES = 5 * 1024 * 1024
const MANIFEST_TTL_MS = 60 * 60 * 1000
const RESTART_POLL_MS = 3000
const RESTART_WAIT_MS = 120_000
const UPLOAD_HOST = /(^|\.)craftinghosting\.free$/

export const ACCESS = ['friends', 'open'] as const
export type Access = (typeof ACCESS)[number]

const serverName = z.string().trim().min(3).max(22).regex(/^[\p{L}\p{N} ._'-]+$/u)
export const createServerBody = z.object({
  name: serverName,
  serverType: z.string().regex(/^[a-z0-9_-]{1,32}$/),
  version: z.string().regex(/^[A-Za-z0-9._-]{1,32}$/),
  access: z.enum(ACCESS).default('friends'),
  subdomain: z.string().trim().toLowerCase().max(40).optional(),
  /** Minecraft-EULA im Assistenten bestätigt – dann fragt der erste Start nicht mehr. */
  acceptEula: z.boolean().optional(),
}).strict()
export const patchServerBody = z.object({
  access: z.enum(ACCESS).optional(),
  name: serverName.optional(),
  subdomain: z.string().trim().toLowerCase().max(40).optional(),
}).strict().refine((b) => b.access !== undefined || b.name !== undefined || b.subdomain !== undefined)
export const subdomainQuery = z.object({ name: z.string().trim().toLowerCase().max(40) })
export const startServerBody = z.object({ acceptEula: z.boolean().default(false) }).strict().default({ acceptEula: false })
export const stopServerBody = z.object({ whenEmpty: z.boolean().default(false) }).strict().default({ whenEmpty: false })
export const settingsBody = z.object({
  motd: z.string().trim().max(MOTD_LINE_MAX).regex(/^[^\p{Cc}§]*$/u),
  maxPlayers: z.number().int().min(1).max(100),
  gamemode: z.enum(['survival', 'creative', 'adventure', 'spectator']),
  difficulty: z.enum(['peaceful', 'easy', 'normal', 'hard']),
  hardcore: z.boolean(),
  pvp: z.boolean(),
  allowFlight: z.boolean(),
  seed: z.string().max(64).regex(/^[^\p{Cc}]*$/u),
  levelType: z.enum(['normal', 'flat', 'large_biomes', 'amplified']),
  spawnProtection: z.number().int().min(0).max(64),
  viewDistance: z.number().int().min(3).max(16),
  simulationDistance: z.number().int().min(3).max(16),
}).partial().strict().refine((b) => Object.keys(b).length > 0)
export const logsQuery = z.object({ lines: z.coerce.number().int().min(1).max(1000).default(200) })
export const modsBody = z.object({
  projects: z.array(z.string().trim().refine((v) => MODRINTH_ID.test(v) || MOD_SLUG.test(v))).max(100).default([]),
  /** Bestimmte Modrinth-Versionen (Versions-ID), z. B. aus der Versionsliste im Launcher. */
  versions: z.array(z.object({
    project: z.string().trim().refine((v) => MODRINTH_ID.test(v) || MOD_SLUG.test(v)),
    version: z.string().regex(MODRINTH_ID),
  }).strict()).max(20).default([]),
}).strict().refine((b) => b.projects.length + b.versions.length > 0)
/** Weltordner – nie ein System- oder Inhaltsordner (sonst löscht „Welt zurücksetzen“ z. B. /libraries). */
const WORLD_NAME = /^(?!(?:plugins|mods|config|libraries|versions|cache|logs|crash-reports|bundler|defaultconfigs|kubejs|scripts)$)[A-Za-z0-9_-]{1,32}$/i
export const importBody = z.object({
  file: z.literal(WORLD_ZIP),
  /** Ordnername der Welt im ZIP (oberste Ebene); Standard `world`. */
  name: z.string().regex(WORLD_NAME).default('world'),
  activate: z.boolean().default(false),
}).strict()
export const worldNameQuery = z.object({ name: z.string().regex(WORLD_NAME) })
export const worldActivateBody = z.object({ active: z.string().regex(WORLD_NAME) }).strict()
export const downloadUrlsBody = z.object({ paths: z.array(z.string().max(512)).min(1).max(100) }).strict()
export const commandBody = z.object({
  command: z.string().trim().min(1).max(256).regex(/^[^\p{Cc}]+$/u),
}).strict()

const iso = z.string().max(40).nullish().transform((v) => v ?? null)
const num = z.number().finite().nullish().transform((v) => v ?? 0)

const sessionSchema = z.object({
  active: z.boolean().nullish(),
  startedAt: iso,
  lastHeartbeatAt: iso,
  heartbeatTimeoutSeconds: num,
  runSeconds: num,
  maxRunSeconds: num,
  remainingSeconds: num,
  cooldownUntil: iso,
  lastStopReason: z.string().max(40).nullish(),
  hostPresent: z.boolean().nullish(),
}).loose()

const serverSchema = z.object({
  id: z.string().min(1).max(64),
  name: z.string().max(80),
  player: z.object({ id: z.string().max(32).nullish(), externalId: z.string().max(80).nullish() }).loose().nullish(),
  state: z.string().max(20),
  address: z.string().max(200).nullish(),
  host: z.string().max(190).nullish(),
  port: z.number().int().min(1).max(65535).nullish(),
  serverType: z.string().max(40).nullish(),
  version: z.string().max(40).nullish(),
  resources: z.object({ memoryMb: num, cpuPercent: num, diskMb: num }).loose().nullish(),
  eulaAccepted: z.boolean().nullish(),
  session: sessionSchema.nullish(),
  createdAt: iso,
  lastStartAt: iso,
  deleteAt: iso,
  install: z.object({
    status: z.string().max(20).nullish(),
    serverType: z.string().max(40).nullish(),
    version: z.string().max(40).nullish(),
    step: num,
    steps: num,
    error: z.string().max(500).nullish(),
  }).loose().nullish(),
}).loose()
type PartnerServer = z.output<typeof serverSchema>

const meSchema = z.object({
  plan: z.object({ name: z.string().max(80), memoryMb: num, cpuPercent: num, diskMb: num }).loose(),
  serverTypes: z.array(z.object({
    id: z.string().max(40),
    name: z.string().max(80),
    versions: z.array(z.string().max(40)).max(200),
  }).loose()).max(50),
  limits: z.object({
    maxServers: num,
    usedServers: num,
    maxServersPerPlayer: num,
    sessionMaxHours: num,
    cooldownHours: num,
    heartbeatTimeoutSeconds: num,
    deleteAfterIdleDays: num,
    serverNameMaxLength: num,
  }).loose(),
  eulaUrl: z.string().max(200).nullish(),
}).loose()

const errorSchema = z.object({
  error: z.object({ code: z.string().max(60), message: z.string().max(500).nullish(), fields: z.record(z.string(), z.unknown()).nullish() }).loose(),
}).loose()

const STATES = new Set(['installing', 'offline', 'starting', 'running', 'stopping', 'failed', 'suspended'])
const STOP_REASONS = new Set(['host_stopped', 'host_timeout', 'session_limit', 'admin'])
const PASS_400 = new Set(['invalid_name', 'server_type_unavailable', 'version_unavailable', 'invalid_path', 'file_rejected'])
const PASS_409 = new Set(['server_installing', 'eula_required', 'cooldown', 'not_running', 'server_failed', 'server_suspended',
  'mods_unsupported', 'server_running', 'server_stopping', 'state_changed', 'backup_limit', 'backup_busy', 'backup_rejected',
  'no_webhook'])
/** Partner-Grenzen je Server (zu oft gesichert/umgestellt) → unser 429; panel_busy/rate_limited bleiben unser Problem (502). */
const PARTNER_TOO_OFTEN = new Set(['too_often', 'backup_rate_limited', 'too_many_installs', 'too_many_streams'])

export interface ServerView {
  id: string
  name: string
  playerId: string | null
  state: string
  address: string | null
  serverType: string | null
  version: string | null
  resources: { memoryMb: number, cpuPercent: number, diskMb: number }
  eulaAccepted: boolean
  access: Access
  session: {
    active: boolean
    startedAt: string | null
    lastHeartbeatAt: string | null
    heartbeatTimeoutSeconds: number
    runSeconds: number
    maxRunSeconds: number
    remainingSeconds: number
    cooldownUntil: string | null
    lastStopReason: string | null
    hostPresent: boolean
  }
  createdAt: string | null
  lastStartAt: string | null
  deleteAt: string | null
  /** Ersteinrichtung (Plugin, MOTD, Whitelist) nach dem ersten Start, danach ein Neustart. */
  setup: SetupState
  /** Eigene Adresse `<name>.trs-launcher.de` (SRV auf den Node), `null` ohne DNS-Eintrag. */
  domain: string | null
  /** Numerische Adresse `IPv4:Port` des Nodes (zum Abtippen, ohne DNS). */
  ip: string | null
  /** Vom Team gesperrt: Starten/Aufwecken geht nicht. */
  blocked: { reason: string, since: string | null } | null
  /** Einrichtung der Server-Software (nach Anlegen, Versionswechsel, Neuinstallation). */
  install: { status: string, step: number, steps: number, error: string | null } | null
  /** Laufzeit-Kontingent nach unseren Regeln (§43.17). */
  quota: Quota | null
  /** Fertige Anzeige für die Clients (§43.18) – Texte und Regeln ändern ohne Launcher-Update. */
  display: Display | null
}

/** Text je Sprache; Platzhalter `{countdown:<s>}` (läuft), `{duration:<s>}`, `{date:<ISO>}` setzt der Client ein. */
export type Localized = Record<string, string>
export interface Display {
  runtime: { label: Localized, value: Localized, progress: number, tone: 'normal' | 'warn' | 'cooldown' } | null
  lines: { text: Localized, tone: 'normal' | 'warn' }[]
  /** Automatische Ersteinrichtung läuft/ist gescheitert (§43.19): Client zeigt nur die Einrichtungs-Ansicht. */
  setup?: { phase: string, index: number, failed: boolean, title: Localized, hint: Localized, steps: Localized[], retry: Localized, locked: Localized, startedAt: string | null } | null
  /** Einmal als Toast zeigen (je `id`). */
  alerts: { id: string, text: Localized, level: 'info' | 'warning' }[]
}

export interface Quota {
  usedSeconds: number
  budgetSeconds: number
  remainingSeconds: number
  weekUsedSeconds: number
  weekCapSeconds: number
  weekResetsAt: string
  /** Ab hier volles Kontingent, wenn der Server bis dahin aus bleibt (nur bei angebrochenem Kontingent). */
  idleResetAt: string | null
  cooldownUntil: string | null
}

export type SetupState = 'pending' | 'restarting' | 'done'

interface Row {
  uuid: string
  server_id: string
  access: Access
  wl_key: string | null
  op_done: number
  setup: number
  motd: string | null
  plugin: string | null
  subdomain: string | null
  dns_record: string | null
  dns_target: string | null
  dns_a_record: string | null
  transfers: number
  mods: string | null
  eula_at: number | null
  run_used_s: number
  run_acct_at: number | null
  last_on_at: number | null
  cooldown_until: number | null
  week_key: string | null
  week_used_s: number
  warned: number
  rule_stop: string | null
  auto_setup: string | null
  auto_at: number | null
  blocked_reason: string | null
  blocked_at: number | null
  blocked_by: string | null
  created_at: number
}

const infoCache = new WeakMap<AppContext, { at: number, value: z.output<typeof meSchema> }>()
const misses = new WeakMap<AppContext, Map<string, number>>()
const syncing = new Set<string>()
/** Laufende Neustarts nach der Ersteinrichtung (Konto → Promise, für Tests abwartbar). */
const restarts = new Map<string, Promise<void>>()
/** Start läuft, Node antwortet aber noch mit 503: alle 10 s neu versuchen, höchstens 3 min. */
const pendingStarts = new Map<string, { until: number, timer: ReturnType<typeof setTimeout> | null }>()
const START_RETRY_MS = 10_000
const START_WAIT_MS = 3 * 60_000
const starting = (uuid: string) => restarts.has(uuid) || pendingStarts.has(uuid)

function startBody(row: Row, acceptEula: boolean) {
  return acceptEula || row.eula_at !== null ? { acceptEula: true } : {}
}

/** Start beim Partner; meldet die Node „noch nicht erreichbar“, übernimmt der Hintergrund. */
async function startOrQueue(ctx: AppContext, row: Row, acceptEula: boolean): Promise<unknown | null> {
  try {
    const res = await call(ctx, 'POST', path(row, '/start'), startBody(row, acceptEula))
    stopPending(row.uuid)
    return res
  } catch (e) {
    if (!isNotReady(e)) throw e
    queueStart(ctx, row.uuid)
    return null
  }
}

/** Laufendes Mod-Hochladen kurz abwarten; `false` = läuft noch. */
async function modsReady(uuid: string): Promise<boolean> {
  const job = modJobs.get(uuid)
  if (!job || job.done) return true
  let timer: ReturnType<typeof setTimeout> | undefined
  const late = new Promise<boolean>((r) => {
    timer = setTimeout(() => r(false), MOD_START_WAIT_MS)
  })
  const ok = await Promise.race([job.promise.then(() => true), late])
  clearTimeout(timer)
  return ok
}

/** Start vormerken (Anzeige „starting“) und nach dem Hochladen der Mods ausführen. */
function startAfterMods(ctx: AppContext, uuid: string) {
  const job = modJobs.get(uuid)
  if (!job) return
  stopPending(uuid)
  const entry = { until: ctx.now() + MOD_JOB_MAX_MS + START_WAIT_MS, timer: null as ReturnType<typeof setTimeout> | null }
  pendingStarts.set(uuid, entry)
  void job.promise.finally(() => {
    if (pendingStarts.get(uuid) !== entry) return
    entry.until = ctx.now() + START_WAIT_MS
    queueStart(ctx, uuid)
  })
}

function stopPending(uuid: string) {
  const p = pendingStarts.get(uuid)
  if (p?.timer) clearTimeout(p.timer)
  pendingStarts.delete(uuid)
}

function queueStart(ctx: AppContext, uuid: string) {
  const old = pendingStarts.get(uuid)
  const entry = { until: old?.until ?? ctx.now() + START_WAIT_MS, timer: null as ReturnType<typeof setTimeout> | null }
  if (old?.timer) clearTimeout(old.timer)
  pendingStarts.set(uuid, entry)
  const tick = async () => {
    if (pendingStarts.get(uuid) !== entry) return
    const row = rowOf(ctx, uuid)
    if (!row || ctx.now() > entry.until) {
      pendingStarts.delete(uuid)
      if (row) console.warn(`[trs-api] CraftingHosting start gave up after ${START_WAIT_MS / 1000} s (${row.server_id})`)
      return
    }
    try {
      const p = parseServer(await call(ctx, 'GET', path(row)))
      if (p.state === 'running' || p.state === 'starting' || p.session?.active) {
        pendingStarts.delete(uuid)
        return
      }
      await call(ctx, 'POST', path(row, '/start'), startBody(row, false))
      pendingStarts.delete(uuid)
      void syncDns(ctx, rowOf(ctx, uuid) ?? row, parseServer(await call(ctx, 'GET', path(row)))).catch(() => undefined)
      return
    } catch (e) {
      if (!isNotReady(e)) {
        pendingStarts.delete(uuid)
        if (!(e instanceof ApiError)) console.error('[trs-api] CraftingHosting start retry failed', e)
        return
      }
    }
    entry.timer = setTimeout(() => void tick(), START_RETRY_MS)
    entry.timer.unref?.()
  }
  entry.timer = setTimeout(() => void tick(), START_RETRY_MS)
  entry.timer.unref?.()
}
const manifests = new WeakMap<AppContext, { at: number, value: z.output<typeof manifestSchema> }>()
const jars = new Map<string, Uint8Array<ArrayBuffer>>()

export function craftingRestartDone(uuid: string): Promise<void> {
  return restarts.get(uuid) ?? Promise.resolve()
}

const upstream = () => new ApiError(502, 'craftinghosting_upstream', 'CraftingHosting is not reachable, try again later')
const noServer = () => notFound('no_server', 'You have no CraftingHosting server')

function config(ctx: AppContext) {
  const c = ctx.config.craftinghosting
  if (!c) throw unavailable('craftinghosting_unavailable', 'CraftingHosting is not available on this server')
  return c
}

/** Fehler des Partners auf unsere Codes abbilden (Partner-Texte gehen nicht durch). */
/** Partner-503 `service_unavailable`: Node fährt den Server noch hoch – kein echter Fehler. */
const notReadyErrors = new WeakSet<ApiError>()
const isNotReady = (e: unknown) => e instanceof ApiError && notReadyErrors.has(e)

function partnerError(status: number, body: unknown): ApiError {
  const parsed = errorSchema.safeParse(body)
  const code = parsed.success ? parsed.data.error.code : ''
  if (status === 503 && code === 'service_unavailable') {
    const e = upstream()
    notReadyErrors.add(e)
    return e
  }
  const fields = parsed.success ? parsed.data.error.fields ?? undefined : undefined
  if (status === 401 || status === 403 || code === 'invalid_api_key') {
    console.error(`[trs-api] CraftingHosting rejected the partner key (${status})`)
    return unavailable('craftinghosting_unavailable', 'CraftingHosting is not available on this server')
  }
  if (status === 503 && (code === 'maintenance' || code === 'unavailable' || code === 'panel_unavailable')) {
    return unavailable('craftinghosting_maintenance', 'CraftingHosting is under maintenance, try again later')
  }
  if (status === 404) {
    if (code === 'file_not_found' || code === 'mod_not_found' || code === 'backup_not_found') return notFound(code, code.replaceAll('_', ' '))
    return noServer()
  }
  if (status === 429 && PARTNER_TOO_OFTEN.has(code)) return new ApiError(429, 'rate_limited', 'Too many requests', { retryAfter: 60 }, { 'Retry-After': '60' })
  if (status === 409) {
    if (code === 'partner_limit_reached') return conflict('craftinghosting_full', 'No free servers left right now')
    if (code === 'player_limit_reached') return conflict('server_exists', 'You already have a server')
    if (PASS_409.has(code)) {
      const details: Record<string, unknown> = {}
      if (typeof fields?.cooldownUntil === 'string') details.cooldownUntil = fields.cooldownUntil.slice(0, 40)
      if (typeof fields?.reason === 'string' && STOP_REASONS.has(fields.reason)) details.reason = fields.reason
      return new ApiError(409, code, code.replaceAll('_', ' '), Object.keys(details).length ? details : undefined)
    }
  }
  if (status === 400) {
    if (PASS_400.has(code)) return badRequest(code, code.replaceAll('_', ' '))
    return badRequest('invalid_request', 'Invalid request')
  }
  return upstream()
}

interface Raw { type: string, data: string | Uint8Array<ArrayBuffer> }

async function call(ctx: AppContext, method: string, path: string, body?: unknown, raw?: Raw): Promise<unknown> {
  const c = config(ctx)
  const f = ctx.craftinghostingFetch ?? fetch
  let res: Response
  try {
    res = await f(c.url + path, {
      method,
      headers: {
        'Authorization': `Bearer ${c.key}`,
        'Accept': 'application/json',
        'Accept-Language': 'en',
        ...(raw ? { 'Content-Type': raw.type } : body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: raw ? raw.data : body === undefined ? undefined : JSON.stringify(body),
      redirect: 'error',
      signal: AbortSignal.timeout(TIMEOUT_MS),
    })
  } catch {
    throw upstream()
  }
  const text = await res.text().catch(() => '')
  let json: unknown = null
  if (text) {
    try {
      json = JSON.parse(text)
    } catch {
      json = null
    }
  }
  if (!res.ok) throw partnerError(res.status, json)
  return json
}

function parseServer(body: unknown): PartnerServer {
  const r = z.object({ server: serverSchema }).loose().safeParse(body)
  if (!r.success) throw upstream()
  return r.data.server
}

const setupOf = (row: Row | undefined): SetupState =>
  !row ? 'pending' : restarts.has(row.uuid) ? 'restarting' : row.setup ? 'done' : 'pending'

const nodeIps = new Map<string, { ip: string | null, at: number }>()

/** IPv4 des Nodes nachschlagen (10 min im RAM), damit `view` sie synchron mitgeben kann. */
async function warmIp(p: PartnerServer): Promise<void> {
  const host = p.host
  if (!host || !/^[A-Za-z0-9.-]{1,190}$/.test(host)) return
  const hit = nodeIps.get(host)
  if (hit && Date.now() - hit.at < 10 * 60 * 1000) return
  try {
    const r = await lookup(host, { family: 4 })
    nodeIps.set(host, { ip: r.address, at: Date.now() })
  } catch {
    nodeIps.set(host, { ip: null, at: Date.now() })
  }
}

function withBlock(v: ServerView, row: Row | undefined): ServerView {
  return row?.blocked_reason ? { ...v, blocked: { reason: row.blocked_reason, since: row.blocked_at ? new Date(row.blocked_at).toISOString() : null } } : v
}

function view(s: PartnerServer, access: Access, setup: SetupState = 'pending', domain: string | null = null): ServerView {
  const playerId = s.player?.id && /^[A-Z0-9]{1,32}$/.test(s.player.id) ? s.player.id : null
  const suffix = playerId ? ` #${playerId}` : ''
  const name = suffix && s.name.endsWith(suffix) ? s.name.slice(0, -suffix.length) : s.name
  const session = s.session
  return {
    id: s.id,
    name,
    playerId,
    state: STATES.has(s.state) ? s.state : 'offline',
    address: s.address && /^[A-Za-z0-9.-]{1,190}(:\d{1,5})?$/.test(s.address) ? s.address : null,
    serverType: s.serverType ?? null,
    version: s.version ?? null,
    resources: { memoryMb: s.resources?.memoryMb ?? 0, cpuPercent: s.resources?.cpuPercent ?? 0, diskMb: s.resources?.diskMb ?? 0 },
    eulaAccepted: s.eulaAccepted === true,
    access,
    session: {
      active: session?.active === true,
      startedAt: session?.startedAt ?? null,
      lastHeartbeatAt: session?.lastHeartbeatAt ?? null,
      heartbeatTimeoutSeconds: session?.heartbeatTimeoutSeconds ?? 0,
      runSeconds: session?.runSeconds ?? 0,
      maxRunSeconds: session?.maxRunSeconds ?? 0,
      remainingSeconds: session?.remainingSeconds ?? 0,
      cooldownUntil: session?.cooldownUntil ?? null,
      lastStopReason: session?.lastStopReason && STOP_REASONS.has(session.lastStopReason) ? session.lastStopReason : null,
      hostPresent: session?.hostPresent !== false,
    },
    createdAt: s.createdAt,
    lastStartAt: s.lastStartAt,
    deleteAt: s.deleteAt,
    setup,
    domain,
    ip: s.host && s.port && nodeIps.get(s.host)?.ip ? `${nodeIps.get(s.host)!.ip}:${s.port}` : null,
    blocked: null,
    install: s.install?.status
      ? { status: s.install.status, step: s.install.step, steps: s.install.steps, error: s.install.error ?? null }
      : null,
    quota: null,
    display: null,
  }
}

const rowOf = (ctx: AppContext, uuid: string) => one<Row>(ctx.db, 'SELECT * FROM craftinghosting_servers WHERE uuid = ?', uuid)
function forget(ctx: AppContext, uuid: string) {
  const row = rowOf(ctx, uuid)
  for (const rec of [row?.dns_record, row?.dns_a_record]) {
    if (rec) void dnsDelete(ctx, rec).catch((e) => console.error('[trs-api] DNS record not removed', e))
  }
  run(ctx.db, 'DELETE FROM craftinghosting_servers WHERE uuid = ?', uuid)
}

function missMap(ctx: AppContext): Map<string, number> {
  let m = misses.get(ctx)
  if (!m) misses.set(ctx, m = new Map())
  return m
}

async function partnerMe(ctx: AppContext) {
  const hit = infoCache.get(ctx)
  if (hit && ctx.now() - hit.at < INFO_TTL_MS) return hit.value
  const r = meSchema.safeParse(await call(ctx, 'GET', '/me'))
  if (!r.success) throw upstream()
  infoCache.set(ctx, { at: ctx.now(), value: r.data })
  return r.data
}

export async function craftingInfo(ctx: AppContext) {
  const me = await partnerMe(ctx)
  const l = me.limits
  return {
    plan: { name: me.plan.name, memoryMb: me.plan.memoryMb, cpuPercent: me.plan.cpuPercent, diskMb: me.plan.diskMb },
    serverTypes: me.serverTypes.map((t) => ({ id: t.id, name: t.name, versions: t.versions })),
    limits: {
      maxServersPerPlayer: l.maxServersPerPlayer,
      sessionMaxHours: rulesOf(ctx).enabled ? rulesOf(ctx).budgetHours : l.sessionMaxHours,
      cooldownHours: rulesOf(ctx).enabled ? rulesOf(ctx).cooldownHours : l.cooldownHours,
      heartbeatTimeoutSeconds: l.heartbeatTimeoutSeconds,
      deleteAfterIdleDays: l.deleteAfterIdleDays,
      serverNameMaxLength: l.serverNameMaxLength,
    },
    eulaUrl: me.eulaUrl && /^https:\/\/[^\s]+$/.test(me.eulaUrl) ? me.eulaUrl : 'https://www.minecraft.net/eula',
    full: l.maxServers > 0 && l.usedServers >= l.maxServers,
    /** Was der Launcher zeigen darf – neue Partner-Funktionen schalten wir hier frei, ohne Launcher-Update. */
    features: features(ctx),
    /** Hinweis oben im Hosting-Reiter: Markdown vom Team (Admin-Seite), sonst Klartext aus der Env. */
    notice: noticeOf(ctx),
    maintenance: maintenanceOf(ctx),
    /** Laufzeit-Regeln (§43.17), `null` = aus. */
    rules: rulesOf(ctx).enabled ? publicRules(ctx) : null,
    /** Regel-Kacheln als fertige Texte (§43.18). */
    tiles: tilesOf(ctx, l.deleteAfterIdleDays),
  }
}

/** Funktions-Schalter (Env `CRAFTINGHOSTING_FEATURES`, kommagetrennt) über den festen Grundfunktionen. */
export const FEATURE_KEYS = ['files', 'properties', 'addons', 'worlds', 'worldDownload', 'ownAddress', 'wakeOnJoin',
  'versionChange', 'backups', 'create', 'console'] as const
export type FeatureKey = (typeof FEATURE_KEYS)[number]

function features(ctx: AppContext): Record<FeatureKey, boolean> {
  void ctx.config.craftinghostingFeatures
  const base: Record<FeatureKey, boolean> = {
    files: true,
    properties: true,
    addons: true,
    worlds: true,
    worldDownload: true,
    ownAddress: !!ctx.config.dns,
    wakeOnJoin: !!ctx.config.dns?.wake,
    versionChange: true,
    backups: true,
    create: true,
    console: true,
  }
  // Team-Schalter (Admin-Seite) gehen vor; Adresse/Aufwecken nur, wenn technisch eingerichtet.
  const over = adminFeatures(ctx)
  for (const k of FEATURE_KEYS) {
    if (over[k] === undefined) continue
    if ((k === 'ownAddress' && !ctx.config.dns) || (k === 'wakeOnJoin' && !ctx.config.dns?.wake)) continue
    base[k] = over[k]!
  }
  return base
}

// ---- Team-Einstellungen (Ankündigung, Wartung, Schalter)

function adminGet(ctx: AppContext, key: string): string | null {
  return one<{ value: string }>(ctx.db, 'SELECT value FROM craftinghosting_admin WHERE key = ?', key)?.value ?? null
}
function adminSet(ctx: AppContext, key: string, value: string | null, by: string) {
  if (value === null) run(ctx.db, 'DELETE FROM craftinghosting_admin WHERE key = ?', key)
  else {
    run(ctx.db, `INSERT INTO craftinghosting_admin (key, value, updated_at, updated_by) VALUES (?, ?, ?, ?)
      ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at, updated_by = excluded.updated_by`,
    key, value, ctx.now(), by)
  }
}
function adminFeatures(ctx: AppContext): Partial<Record<FeatureKey, boolean>> {
  const raw = adminGet(ctx, 'features')
  if (!raw) return {}
  const parsed = z.record(z.string(), z.boolean()).catch({}).parse(JSON.parse(raw))
  return Object.fromEntries(Object.entries(parsed).filter(([k]) => (FEATURE_KEYS as readonly string[]).includes(k)))
}

type Notice = { markdown: string, level: 'info' | 'warning' | 'success', updatedAt: string | null } | null
function noticeOf(ctx: AppContext): Notice {
  const raw = adminGet(ctx, 'notice')
  if (raw) {
    const n = z.object({ markdown: z.string(), level: z.enum(['info', 'warning', 'success']) }).catch({ markdown: '', level: 'info' }).parse(JSON.parse(raw))
    const at = one<{ updated_at: number }>(ctx.db, "SELECT updated_at FROM craftinghosting_admin WHERE key = 'notice'")?.updated_at
    if (n.markdown.trim()) return { ...n, updatedAt: at ? new Date(at).toISOString() : null }
  }
  return ctx.config.craftinghostingNotice ? { markdown: ctx.config.craftinghostingNotice, level: 'info', updatedAt: null } : null
}
function maintenanceOf(ctx: AppContext): { active: boolean, message: string | null } {
  const raw = adminGet(ctx, 'maintenance')
  if (!raw) return { active: false, message: null }
  return z.object({ active: z.boolean(), message: z.string().nullable() }).catch({ active: false, message: null }).parse(JSON.parse(raw))
}

/** Wartung: Anlegen/Starten/Aufwecken/Versionswechsel gesperrt; laufende Server bleiben, Lesen geht weiter. */
function assertNoMaintenance(ctx: AppContext) {
  const m = maintenanceOf(ctx)
  if (m.active) throw new ApiError(503, 'hosting_maintenance', m.message || 'Hosting is under maintenance', m.message ? { message: m.message } : undefined)
}
function assertFeature(ctx: AppContext, key: FeatureKey) {
  if (!features(ctx)[key]) throw new ApiError(403, 'feature_disabled', 'This function is switched off', { feature: key })
}
function assertNotBlocked(row: Row) {
  if (row.blocked_reason !== null) throw new ApiError(403, 'server_blocked', 'This server is blocked by the TRS team', { reason: row.blocked_reason })
}

export const adminConfigBody = z.object({
  notice: z.object({ markdown: z.string().max(4000), level: z.enum(['info', 'warning', 'success']) }).nullable().optional(),
  maintenance: z.object({ active: z.boolean(), message: z.string().trim().max(300).nullable() }).optional(),
  /** `null` = Standard (Env/Technik). */
  features: z.record(z.enum(FEATURE_KEYS), z.boolean().nullable()).optional(),
  rules: z.object({
    enabled: z.boolean(),
    budgetHours: z.number().int().min(1).max(168),
    cooldownHours: z.number().int().min(0).max(48),
    idleResetDays: z.number().int().min(1).max(30),
    weeklyOfflineHours: z.number().int().min(0).max(48),
    warnMinutes: z.number().int().min(0).max(120),
  }).strict().optional(),
}).strict()
export const blockBody = z.object({ reason: z.string().trim().min(1).max(300) }).strict()

export function adminCraftingConfig(ctx: AppContext) {
  return {
    notice: noticeOf(ctx),
    maintenance: maintenanceOf(ctx),
    features: features(ctx),
    overrides: adminFeatures(ctx),
    available: { ownAddress: !!ctx.config.dns, wakeOnJoin: !!ctx.config.dns?.wake },
    rules: rulesOf(ctx),
  }
}

export function adminSetCraftingConfig(ctx: AppContext, by: string, body: z.output<typeof adminConfigBody>) {
  if (body.notice !== undefined) {
    adminSet(ctx, 'notice', body.notice && body.notice.markdown.trim() ? JSON.stringify(body.notice) : null, by)
  }
  if (body.maintenance) adminSet(ctx, 'maintenance', JSON.stringify(body.maintenance), by)
  if (body.features) {
    const cur = adminFeatures(ctx)
    for (const [k, v] of Object.entries(body.features)) {
      if (v === null) delete cur[k as FeatureKey]
      else cur[k as FeatureKey] = v
    }
    adminSet(ctx, 'features', Object.keys(cur).length ? JSON.stringify(cur) : null, by)
  }
  if (body.rules) adminSet(ctx, 'rules', JSON.stringify(body.rules), by)
  return adminCraftingConfig(ctx)
}

/** Server sperren: stoppt ihn sofort; Starten/Aufwecken bis zur Entsperrung verboten. */
export async function adminCraftingBlock(ctx: AppContext, serverId: string, by: string, reason: string | null): Promise<string | null> {
  config(ctx)
  const row = one<Row>(ctx.db, 'SELECT * FROM craftinghosting_servers WHERE server_id = ?', serverId)
  if (!row) throw notFound('no_server', 'Unknown server')
  run(ctx.db, 'UPDATE craftinghosting_servers SET blocked_reason = ?, blocked_at = ?, blocked_by = ? WHERE uuid = ?',
    reason, reason ? ctx.now() : null, reason ? by : null, row.uuid)
  if (reason) await call(ctx, 'POST', path(row, '/stop'), {}).catch(() => undefined)
  return row.uuid
}


export const versionBody = z.object({
  serverType: z.string().regex(/^[a-z0-9_-]{1,32}$/),
  version: z.string().regex(/^[A-Za-z0-9._-]{1,32}$/),
  /** Welt behalten (Standard) oder frisch anfangen. */
  keepWorld: z.boolean().default(true),
}).strict()
export const backupIdParam = z.string().regex(/^[A-Za-z0-9_-]{1,64}$/)

/**
 * Version/Servertyp wechseln (§43.13). Die Partner-API bietet das noch nicht an; sobald CraftingHosting es liefert,
 * wird hier der Aufruf ergänzt und `versionChange` in CRAFTINGHOSTING_FEATURES eingeschaltet.
 */
export async function craftingChangeVersion(ctx: AppContext, user: UserRow, body: z.output<typeof versionBody>): Promise<ServerView> {
  assertNoMaintenance(ctx)
  assertFeature(ctx, 'versionChange')
  return onServer(ctx, user, async (row) => {
    assertNotBlocked(row)
    const s = await fresh(ctx, row)
    assertStopped(s)
    if (!body.keepWorld) {
      let level = 'world'
      try {
        level = parseProps(await readText(ctx, row, '/server.properties')).get('level-name') || 'world'
      } catch { /* ohne Einstellungen: Standardname */ }
      await call(ctx, 'POST', path(row, '/files/delete'), { paths: DIMS.map((x) => `/${level}${x}`) })
    }
    const res = await call(ctx, 'PATCH', path(row), { serverType: body.serverType, version: body.version })
    // Neue Software: Ersteinrichtung (passendes TRS-Server-Rules-Jar, Fußzeile) beim nächsten Lauf erneut.
    run(ctx.db, 'UPDATE craftinghosting_servers SET setup = 0, plugin = NULL, transfers = 0 WHERE uuid = ?', user.uuid)
    infoCache.delete(ctx)
    return fresh(ctx, row, res)
  })
}

export async function craftingReinstall(ctx: AppContext, user: UserRow): Promise<ServerView> {
  assertNoMaintenance(ctx)
  assertFeature(ctx, 'versionChange')
  return onServer(ctx, user, async (row) => {
    assertNotBlocked(row)
    const res = await call(ctx, 'POST', path(row, '/reinstall'), {})
    run(ctx.db, 'UPDATE craftinghosting_servers SET setup = 0, plugin = NULL, transfers = 0 WHERE uuid = ?', user.uuid)
    return fresh(ctx, row, res)
  })
}

const backupSchema = z.object({
  id: z.string().regex(/^[A-Za-z0-9_-]{1,64}$/),
  name: z.string().max(200).nullish(),
  bytes: z.number().nullish(),
  state: z.string().max(20).nullish(),
  createdAt: z.string().max(40).nullish(),
  completedAt: z.string().max(40).nullish(),
}).loose()
const isoOrNull = (v: string | null | undefined) => (v && Number.isFinite(Date.parse(v)) ? new Date(Date.parse(v)).toISOString() : null)

export const backupCreateBody = z.object({ name: z.string().trim().max(80).optional() }).strict().optional()

export async function craftingBackups(ctx: AppContext, user: UserRow) {
  assertFeature(ctx, 'backups')
  return onServer(ctx, user, async (row) => {
    const r = z.object({ limit: z.number().int().nullish(), backups: z.array(backupSchema).max(100) }).loose()
      .safeParse(await call(ctx, 'GET', path(row, '/backups')))
    if (!r.success) throw upstream()
    return {
      backups: r.data.backups.map((b) => ({
        id: b.id,
        name: (b.name || 'Backup').slice(0, 100),
        size: Math.max(0, b.bytes ?? 0),
        createdAt: isoOrNull(b.createdAt),
        state: b.state === 'creating' || b.state === 'failed' ? b.state : 'ready',
      })),
      max: Math.max(0, r.data.limit ?? 0),
    }
  })
}

export async function craftingBackupAction(ctx: AppContext, user: UserRow, action: 'create' | 'restore' | 'delete' | 'download', id?: string, name?: string): Promise<unknown> {
  assertFeature(ctx, 'backups')
  if (action !== 'download') assertNoMaintenance(ctx)
  return onServer(ctx, user, async (row) => {
    if (action === 'create') {
      const res = z.object({ backup: backupSchema }).loose().safeParse(await call(ctx, 'POST', path(row, '/backups'), name ? { name } : {}))
      return { ok: true, id: res.success ? res.data.backup.id : null }
    }
    const bid = encodeURIComponent(id ?? '')
    if (action === 'restore') {
      assertNotBlocked(row)
      assertStopped(await fresh(ctx, row))
      await call(ctx, 'POST', path(row, `/backups/${bid}/restore`), { truncate: false })
      return { ok: true }
    }
    if (action === 'delete') {
      await call(ctx, 'DELETE', path(row, `/backups/${bid}`))
      return { ok: true }
    }
    const r = z.object({ url: z.string().max(2000), expiresInSeconds: z.number().nullish() }).loose()
      .safeParse(await call(ctx, 'GET', path(row, `/backups/${bid}/download-url`)))
    if (!r.success || !URL.canParse(r.data.url)) throw upstream()
    const u = new URL(r.data.url)
    if (u.protocol !== 'https:' || !UPLOAD_HOST.test(u.hostname)) throw upstream()
    return { url: u.href, expiresAt: new Date(ctx.now() + Math.min(600, r.data.expiresInSeconds ?? 300) * 1000).toISOString() }
  })
}

/** Eigener Server oder null. Fehlt die Zeile, beim Partner nachsehen (z. B. nach DB-Wiederherstellung). */
export async function craftingServer(ctx: AppContext, user: UserRow): Promise<ServerView | null> {
  config(ctx)
  const row = rowOf(ctx, user.uuid)
  if (row) {
    try {
      const p = parseServer(await call(ctx, 'GET', `/servers/${encodeURIComponent(row.server_id)}`))
      await warmIp(p)
      // Server von vor den eigenen Adressen bekommen beim ersten Abruf eine.
      if (ctx.config.dns && !row.subdomain) {
        const sub = await suggestSubdomain(ctx, user.uuid, p.name.replace(/ #[A-Z0-9]+$/, ''))
        if (sub) {
          try {
            run(ctx.db, 'UPDATE craftinghosting_servers SET subdomain = ? WHERE uuid = ?', sub, user.uuid)
          } catch { /* gerade vergeben, nächster Abruf */ }
        }
      }
      await syncDns(ctx, rowOf(ctx, user.uuid) ?? row, p)
      const s = adjust(ctx, withBlock(view(p, row.access, setupOf(row), domainOf(ctx, rowOf(ctx, user.uuid))), row), row)
      if (s.state === 'running' && (row.wl_key === null || !row.op_done || !row.setup)) {
        await syncAccess(ctx, user, s)
        return { ...s, setup: setupOf(rowOf(ctx, user.uuid)) }
      }
      return s
    } catch (e) {
      if (!(e instanceof ApiError && e.code === 'no_server')) throw e
      forget(ctx, user.uuid)
    }
  }
  const miss = missMap(ctx)
  if ((miss.get(user.uuid) ?? 0) > ctx.now()) return null
  const list = z.object({ servers: z.array(serverSchema).max(20) }).loose()
    .safeParse(await call(ctx, 'GET', `/servers?player=${encodeURIComponent(user.uuid)}&limit=5`))
  if (!list.success) throw upstream()
  const found = list.data.servers.find((s) => s.player?.externalId == null || s.player.externalId === user.uuid)
  if (!found) {
    miss.set(user.uuid, ctx.now() + MISS_TTL_MS)
    return null
  }
  run(ctx.db, `INSERT INTO craftinghosting_servers (uuid, server_id, access, created_at) VALUES (?, ?, 'friends', ?)
    ON CONFLICT(uuid) DO UPDATE SET server_id = excluded.server_id`, user.uuid, found.id, ctx.now())
  const fresh = rowOf(ctx, user.uuid)
  return view(found, fresh?.access ?? 'friends', setupOf(fresh), domainOf(ctx, fresh))
}

export async function craftingCreate(ctx: AppContext, user: UserRow, body: z.output<typeof createServerBody>): Promise<ServerView> {
  config(ctx)
  assertNoMaintenance(ctx)
  assertFeature(ctx, 'create')
  assertNotSanctioned(ctx, user.uuid, 'hosting_ban')
  if (await craftingServer(ctx, user)) throw conflict('server_exists', 'You already have a server')
  let sub: string | null = null
  if (ctx.config.dns) {
    sub = body.subdomain ? await claimable(ctx, user.uuid, body.subdomain) : await suggestSubdomain(ctx, user.uuid, body.name)
  }
  const res = await call(ctx, 'POST', '/servers', {
    player: { externalId: user.uuid, name: user.name },
    name: body.name,
    serverType: body.serverType,
    version: body.version,
  })
  const s = parseServer(res)
  run(ctx.db, `INSERT INTO craftinghosting_servers (uuid, server_id, access, created_at, eula_at) VALUES (?, ?, ?, ?, ?)
    ON CONFLICT(uuid) DO UPDATE SET server_id = excluded.server_id, access = excluded.access, wl_key = NULL,
      op_done = 0, setup = 0, motd = NULL, plugin = NULL, subdomain = NULL, dns_record = NULL, dns_target = NULL,
      dns_a_record = NULL, transfers = 0, eula_at = excluded.eula_at`,
  user.uuid, s.id, body.access, ctx.now(), body.acceptEula ? ctx.now() : null)
  stopPending(user.uuid)
  // Mit bestätigter EULA richtet die API den Server selbst ein (§43.19).
  run(ctx.db, 'UPDATE craftinghosting_servers SET auto_setup = ?, auto_at = ? WHERE uuid = ?', body.acceptEula ? 'install' : null, body.acceptEula ? ctx.now() : null, user.uuid)
  if (body.acceptEula) runAuto(ctx, user.uuid)
  if (sub) {
    try {
      run(ctx.db, 'UPDATE craftinghosting_servers SET subdomain = ? WHERE uuid = ?', sub, user.uuid)
    } catch {
      sub = null
    }
  }
  missMap(ctx).delete(user.uuid)
  infoCache.delete(ctx)
  const row = rowOf(ctx, user.uuid)
  if (row) await syncDns(ctx, row, s)
  const made = rowOf(ctx, user.uuid)
  const v = view(s, body.access, 'pending', domainOf(ctx, made))
  return made ? adjust(ctx, v, made) : v
}

async function own(ctx: AppContext, user: UserRow): Promise<Row> {
  config(ctx)
  const row = rowOf(ctx, user.uuid)
  if (row) return row
  if (await craftingServer(ctx, user)) return rowOf(ctx, user.uuid)!
  throw noServer()
}

/** Bei 404 vom Partner ist der Server weg: Zeile löschen und `no_server` melden. */
async function onServer<T>(ctx: AppContext, user: UserRow, fn: (row: Row) => Promise<T>): Promise<T> {
  const row = await own(ctx, user)
  try {
    return await fn(row)
  } catch (e) {
    if (e instanceof ApiError && e.code === 'no_server') forget(ctx, user.uuid)
    throw e
  }
}

const path = (row: Row, rest = '') => `/servers/${encodeURIComponent(row.server_id)}${rest}`
const isRunning = (s: ServerView) => s.state === 'running' && s.session.active

async function fresh(ctx: AppContext, row: Row, res?: unknown): Promise<ServerView> {
  const direct = z.object({ server: serverSchema }).loose().safeParse(res)
  const now = rowOf(ctx, row.uuid) ?? row
  const p = direct.success ? direct.data.server : parseServer(await call(ctx, 'GET', path(row)))
  await warmIp(p)
  return adjust(ctx, withBlock(view(p, now.access, setupOf(now), domainOf(ctx, now)), now), now)
}

function adjust(ctx: AppContext, v: ServerView, row: Row): ServerView {
  let out = v
  if (row.eula_at !== null && !out.eulaAccepted) out = { ...out, eulaAccepted: true }
  if (pendingStarts.has(row.uuid) && (out.state === 'offline' || out.state === 'stopping')) out = { ...out, state: 'starting' }
  const q = quotaOf(ctx, row)
  const flow = setupDisplay(row)
  const modAlerts = modsAlerts(row.uuid)
  if (!q) return flow || modAlerts.length ? { ...out, display: { runtime: null, lines: [], alerts: modAlerts, setup: flow } } : out
  const partnerCd = out.session.cooldownUntil ? Date.parse(out.session.cooldownUntil) : 0
  const ourCd = q.cooldownUntil ? Date.parse(q.cooldownUntil) : 0
  return {
    ...out,
    quota: q,
    display: { ...withAlerts(displayOf(ctx, out, q), modAlerts), setup: flow },
    session: {
      ...out.session,
      runSeconds: q.usedSeconds,
      maxRunSeconds: q.budgetSeconds,
      remainingSeconds: q.remainingSeconds,
      cooldownUntil: ourCd > partnerCd ? q.cooldownUntil : out.session.cooldownUntil,
      lastStopReason: row.rule_stop === 'session_limit' && !out.session.active ? 'session_limit' : out.session.lastStopReason,
    },
  }
}

export async function craftingUpdate(ctx: AppContext, user: UserRow, body: z.output<typeof patchServerBody>): Promise<ServerView> {
  return onServer(ctx, user, async (row) => {
    if (body.subdomain !== undefined && body.subdomain !== row.subdomain) {
      if (!ctx.config.dns) throw unavailable('dns_unavailable', 'Own addresses are not available on this server')
      const sub = await claimable(ctx, user.uuid, body.subdomain)
      const old = [row.dns_record, row.dns_a_record]
      try {
        run(ctx.db, `UPDATE craftinghosting_servers SET subdomain = ?, dns_record = NULL, dns_target = NULL, dns_a_record = NULL
          WHERE uuid = ?`, sub, user.uuid)
      } catch {
        throw conflict('subdomain_taken', 'This address is taken')
      }
      for (const rec of old) if (rec) await dnsDelete(ctx, rec).catch((e) => console.error('[trs-api] old DNS record not removed', e))
      await syncDns(ctx, rowOf(ctx, user.uuid)!, parseServer(await call(ctx, 'GET', path(row))))
    }
    if (body.name !== undefined) await call(ctx, 'PATCH', path(row), { name: body.name })
    if (body.access !== undefined && body.access !== row.access) {
      run(ctx.db, 'UPDATE craftinghosting_servers SET access = ?, wl_key = NULL WHERE uuid = ?', body.access, user.uuid)
    }
    const s = await fresh(ctx, rowOf(ctx, user.uuid) ?? row)
    await syncAccess(ctx, user, s)
    return s
  })
}

export async function craftingStart(ctx: AppContext, user: UserRow, acceptEula: boolean): Promise<ServerView> {
  assertNoMaintenance(ctx)
  assertNotSanctioned(ctx, user.uuid, 'hosting_ban')
  return onServer(ctx, user, async (row) => {
    assertNotBlocked(row)
    if (autoActive(row)) { runAuto(ctx, user.uuid); return fresh(ctx, row) }
    if (isAutoFailed(row)) {
      run(ctx.db, "UPDATE craftinghosting_servers SET auto_setup = 'install', auto_at = ? WHERE uuid = ?", ctx.now(), user.uuid)
      runAuto(ctx, user.uuid)
      return fresh(ctx, rowOf(ctx, user.uuid) ?? row)
    }
    await syncAccess(ctx, user, await fresh(ctx, row))
    if (row.setup && !row.transfers) await ensureTransfers(ctx, row)
    if (row.setup) await refreshFooter(ctx, row)
    assertQuota(ctx, rowOf(ctx, user.uuid) ?? row)
    if (acceptEula && row.eula_at === null) run(ctx.db, 'UPDATE craftinghosting_servers SET eula_at = ? WHERE uuid = ?', ctx.now(), user.uuid)
    if (!(await modsReady(user.uuid))) {
      startAfterMods(ctx, user.uuid)
      run(ctx.db, 'UPDATE craftinghosting_servers SET rule_stop = NULL WHERE uuid = ?', user.uuid)
      return fresh(ctx, rowOf(ctx, user.uuid) ?? row)
    }
    const res = await startOrQueue(ctx, rowOf(ctx, user.uuid) ?? row, acceptEula)
    run(ctx.db, 'UPDATE craftinghosting_servers SET rule_stop = NULL WHERE uuid = ?', user.uuid)
    return fresh(ctx, rowOf(ctx, user.uuid) ?? row, res ?? undefined)
  })
}

export async function craftingHeartbeat(ctx: AppContext, user: UserRow): Promise<ServerView> {
  return onServer(ctx, user, async (row) => {
    if (restarts.has(user.uuid)) return fresh(ctx, row)
    const s = await fresh(ctx, row, await call(ctx, 'POST', path(row, '/heartbeat')))
    await syncAccess(ctx, user, s)
    return s
  })
}

export async function craftingStop(ctx: AppContext, user: UserRow, whenEmpty: boolean): Promise<ServerView> {
  if (!whenEmpty) stopPending(user.uuid)
  return onServer(ctx, user, async (row) => fresh(ctx, row, await call(ctx, 'POST', path(row, '/stop'), whenEmpty ? { whenEmpty: true } : {})))
}

export async function craftingCommand(ctx: AppContext, user: UserRow, command: string): Promise<void> {
  assertFeature(ctx, 'console')
  const cmd = command.replace(/^\//, '').trim()
  if (!cmd) throw badRequest('invalid_request', 'Invalid request')
  await onServer(ctx, user, (row) => call(ctx, 'POST', path(row, '/command'), { command: cmd }))
}

export async function craftingDelete(ctx: AppContext, user: UserRow): Promise<void> {
  try {
    await onServer(ctx, user, (row) => call(ctx, 'DELETE', path(row)))
  } catch (e) {
    if (!(e instanceof ApiError && e.code === 'no_server')) throw e
  }
  forget(ctx, user.uuid)
  infoCache.delete(ctx)
}

// ---------------------------------------------------------------- Einstellungen, Konsole, Werte

const settingsOut = z.object({
  motd: z.string().max(400).catch(''),
  maxPlayers: z.number().int().catch(20),
  gamemode: z.string().max(20).catch('survival'),
  difficulty: z.string().max(20).catch('normal'),
  hardcore: z.boolean().catch(false),
  pvp: z.boolean().catch(true),
  allowFlight: z.boolean().catch(false),
  seed: z.string().max(64).catch(''),
  levelType: z.string().max(40).catch('normal'),
  spawnProtection: z.number().int().catch(16),
  viewDistance: z.number().int().catch(10),
  simulationDistance: z.number().int().catch(10),
  whitelist: z.boolean().catch(false),
})

const obj = (v: unknown) => (typeof v === 'object' && v !== null && !Array.isArray(v) ? v as Record<string, unknown> : undefined)

function parseSettings(body: unknown, row?: Row) {
  const o = obj(body) ?? {}
  const out = settingsOut.parse(obj(o.settings) ?? o)
  const shown = cutFooter(out.motd)
  return { ...out, motd: row?.motd ?? shown, levelType: out.levelType.replace(/^minecraft\\?:/, '') }
}

/** Ohne Plugin hängt die Fußzeile an der einzigen MOTD-Zeile. */
const motdFor = (row: Pick<Row, 'plugin'>, line: string) => (row.plugin ? line : `${line} ${MOTD_FOOTER}`.trim())

export async function craftingSettings(ctx: AppContext, user: UserRow) {
  return onServer(ctx, user, async (row) => parseSettings(await call(ctx, 'GET', path(row, '/settings')), row))
}

export async function craftingSetSettings(ctx: AppContext, user: UserRow, body: z.output<typeof settingsBody>) {
  return onServer(ctx, user, async (row) => {
    const patch = body.motd === undefined ? body : { ...body, motd: motdFor(row, body.motd) }
    const res = await call(ctx, 'PATCH', path(row, '/settings'), patch)
    if (body.motd !== undefined) run(ctx.db, 'UPDATE craftinghosting_servers SET motd = ? WHERE uuid = ?', body.motd, user.uuid)
    const now = rowOf(ctx, user.uuid) ?? row
    return Object.keys(obj(res) ?? {}).length ? parseSettings(res, now) : parseSettings(await call(ctx, 'GET', path(row, '/settings')), now)
  })
}

// eslint-disable-next-line no-control-regex
const CONTROL = /\u001B\[[0-9;]*[A-Za-z]|[\u0000-\u0008\u000B-\u001F\u007F]/g

const STARTING_LINE: Record<string, string> = { de: 'Server startet …', es: 'El servidor se está iniciando …', en: 'Server is starting …' }

export async function craftingLogs(ctx: AppContext, user: UserRow, lines: number, lang = 'en'): Promise<string[]> {
  return onServer(ctx, user, async (row) => {
    let body: unknown
    try {
      body = await call(ctx, 'GET', path(row, `/logs?lines=${lines}`))
    } catch (e) {
      // Node fährt noch hoch: ruhiger Hinweis statt Fehler.
      if (isNotReady(e)) return [STARTING_LINE[lang] ?? STARTING_LINE.en!]
      throw e
    }
    const o = obj(body) ?? {}
    const raw: unknown[] = Array.isArray(o.lines) ? o.lines
      : typeof o.log === 'string' ? o.log.split(/\r?\n/)
        : typeof o.logs === 'string' ? o.logs.split(/\r?\n/)
          : Array.isArray(o.logs) ? o.logs : []
    return raw.filter((l): l is string => typeof l === 'string').slice(-lines).map((l) => l.replace(CONTROL, '').slice(0, 2000))
  })
}

const num0 = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null)
function pick(o: Record<string, unknown> | undefined, ...keys: string[]): number | null {
  for (const k of keys) {
    const v = num0(o?.[k])
    if (v !== null) return v
  }
  return null
}

export async function craftingStats(ctx: AppContext, user: UserRow) {
  return onServer(ctx, user, async (row) => {
    const res = obj(await call(ctx, 'GET', path(row, '/stats')))
    const o = obj(res?.stats) ?? res
    const mem = obj(o?.memory)
    const disk = obj(o?.disk)
    const net = obj(o?.network)
    const players = obj(o?.players)
    return {
      cpuPercent: pick(o, 'cpuPercent', 'cpu'),
      memory: {
        usedBytes: pick(o, 'memoryBytes') ?? pick(mem, 'bytes', 'used'),
        limitBytes: pick(o, 'memoryLimitBytes') ?? pick(mem, 'limit'),
      },
      disk: {
        usedBytes: pick(o, 'diskBytes') ?? pick(disk, 'bytes', 'used'),
        limitBytes: pick(o, 'diskLimitBytes') ?? pick(disk, 'limit'),
      },
      network: {
        rxBytes: pick(o, 'networkRxBytes') ?? pick(net, 'rx', 'rxBytes'),
        txBytes: pick(o, 'networkTxBytes') ?? pick(net, 'tx', 'txBytes'),
      },
      uptimeSeconds: pick(o, 'uptimeSeconds', 'uptime'),
      players: players && num0(players.online) !== null ? { online: num0(players.online)!, max: num0(players.max) } : null,
    }
  })
}

// ---------------------------------------------------------------- Mods und Welt

const modrinthProject = z.object({ id: z.string(), slug: z.string(), server_side: z.string().nullish() }).loose()
const modrinthVersion = z.object({
  id: z.string().regex(MODRINTH_ID),
  project_id: z.string().regex(MODRINTH_ID),
  version_type: z.string().max(20).nullish(),
  files: z.array(z.object({
    url: z.string().max(1000),
    filename: z.string().max(300),
    primary: z.boolean().nullish(),
    size: z.number().int().nonnegative(),
    hashes: z.object({ sha512: z.string().regex(/^[0-9a-f]{128}$/) }).loose(),
  }).loose()).max(20),
  dependencies: z.array(z.object({
    project_id: z.string().nullish(),
    version_id: z.string().nullish(),
    dependency_type: z.string().max(20),
  }).loose()).max(200).nullish(),
}).loose()
type ModrinthVersion = z.output<typeof modrinthVersion>

const MODRINTH_API = 'https://api.modrinth.com/v2'
const MODRINTH_UA = 'theredstonee/TRS-Launcher (trs-launcher.theredstonee.de)'
/** Mod-Dateien lädt die API selbst hoch: `POST /mods` beim Partner geht nur ~5× je 10 min. */
const MOD_FILE_MAX = 64 * 1024 * 1024
const MOD_TOTAL_MAX = 768 * 1024 * 1024
const MOD_BATCH_BYTES = 48 * 1024 * 1024
const MOD_PARALLEL = 8
/** So lange wartet ein Start höchstens im Request auf das Hochladen, danach übernimmt der Hintergrund. */
const MOD_START_WAIT_MS = 12_000
const MOD_JOB_MAX_MS = 20 * 60_000
const MOD_FILENAME = /^[\w.+-]{1,150}\.jar$/

interface ModJob { promise: Promise<void>, at: number, done: boolean, failed: string[] }
/** Laufendes/letztes Hochladen je Konto. */
const modJobs = new Map<string, ModJob>()

async function modrinthGet(ctx: AppContext, url: string): Promise<unknown | null> {
  const f = ctx.modrinthFetch ?? fetch
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const res = await f(url, {
        headers: { 'User-Agent': MODRINTH_UA, 'Accept': 'application/json' },
        redirect: 'error',
        signal: AbortSignal.timeout(TIMEOUT_MS),
      })
      if (res.status === 429 && attempt === 0) {
        const wait = Math.min(10, Math.max(1, Number(res.headers.get('x-ratelimit-reset')) || 2))
        await new Promise((r) => setTimeout(r, wait * 1000))
        continue
      }
      if (!res.ok) return null
      return await res.json()
    } catch {
      return null
    }
  }
  return null
}

/** Reine Client-Mods (Modrinth server_side = unsupported) gar nicht erst schicken. `null` = Modrinth nicht erreichbar. */
async function modrinthProjects(ctx: AppContext, projects: string[]) {
  if (!projects.length) return []
  const body = await modrinthGet(ctx, `${MODRINTH_API}/projects?ids=${encodeURIComponent(JSON.stringify(projects))}`)
  const r = z.array(modrinthProject).max(200).safeParse(body)
  return r.success ? r.data : null
}

function modLoaders(serverType: string): string[] | null {
  switch (serverType) {
    case 'fabric': return ['fabric']
    case 'quilt': return ['quilt', 'fabric']
    case 'forge': return ['forge']
    case 'neoforge': return ['neoforge']
    case 'paper': return ['paper', 'spigot', 'bukkit']
    case 'purpur': return ['purpur', 'paper', 'spigot', 'bukkit']
    default: return null
  }
}

/** Neueste passende Version (Release vor Beta/Alpha), wie der Partner sie auch wählen würde. */
async function latestVersion(ctx: AppContext, project: string, loaders: string[], mc: string): Promise<ModrinthVersion | null> {
  const q = `loaders=${encodeURIComponent(JSON.stringify(loaders))}&game_versions=${encodeURIComponent(JSON.stringify([mc]))}`
  const r = z.array(modrinthVersion).max(500).safeParse(await modrinthGet(ctx, `${MODRINTH_API}/project/${encodeURIComponent(project)}/version?${q}`))
  if (!r.success || !r.data.length) return null
  return r.data.find((v) => v.version_type === 'release') ?? r.data[0]!
}

const GAME_VERSION = /^\d+\.\d+(\.\d+)?$/

/** Feste Minecraft-Version; „latest“ = neueste Release-Version laut Modrinth. */
async function gameVersion(ctx: AppContext, version: string): Promise<string | null> {
  if (GAME_VERSION.test(version)) return version
  const r = z.array(z.object({ version: z.string(), version_type: z.string() }).loose()).max(2000)
    .safeParse(await modrinthGet(ctx, `${MODRINTH_API}/tag/game_version`))
  const v = r.success ? r.data.find((x) => x.version_type === 'release' && GAME_VERSION.test(x.version))?.version : undefined
  return v ?? null
}

async function versionById(ctx: AppContext, id: string): Promise<ModrinthVersion | null> {
  if (!MODRINTH_ID.test(id)) return null
  const r = modrinthVersion.safeParse(await modrinthGet(ctx, `${MODRINTH_API}/version/${id}`))
  return r.success ? r.data : null
}

async function inBatches<T, R>(items: T[], fn: (item: T) => Promise<R>): Promise<R[]> {
  const out: R[] = new Array(items.length)
  let next = 0
  const worker = async () => {
    while (next < items.length) {
      const i = next++
      out[i] = await fn(items[i]!)
    }
  }
  await Promise.all(Array.from({ length: Math.min(MOD_PARALLEL, items.length) }, worker))
  return out
}

interface ModFile { project: string, url: string, filename: string, size: number, sha512: string }

function primaryFile(project: string, v: ModrinthVersion): ModFile | null {
  const f = v.files.find((x) => x.primary) ?? v.files.find((x) => x.filename.endsWith('.jar'))
  if (!f || f.size > MOD_FILE_MAX) return null
  let url: URL
  try {
    url = new URL(f.url)
  } catch {
    return null
  }
  if (url.protocol !== 'https:' || url.hostname !== 'cdn.modrinth.com' || url.username || url.password || url.port) return null
  const filename = MOD_FILENAME.test(f.filename) ? f.filename : `${project}-${v.id}.jar`
  return { project, url: url.href, filename, size: f.size, sha512: f.hashes.sha512 }
}

/**
 * Projekte samt Pflicht-Abhängigkeiten zu Dateien auflösen. Client-only-Abhängigkeiten bleiben weg,
 * fehlende Abhängigkeiten auch (der Server meldet sie beim Start selbst).
 */
async function resolveModFiles(ctx: AppContext, send: string[], pinned: Map<string, string>, loaders: string[], mc: string) {
  const files = new Map<string, ModFile>()
  const missing = new Set<string>()
  const seen = new Set<string>()
  let queue = send.map((p) => ({ project: p, version: pinned.get(p) ?? null, top: true }))
  for (let depth = 0; queue.length && depth < 4; depth++) {
    for (const q of queue) seen.add(q.project)
    const versions = await inBatches(queue, (q) => q.version ? versionById(ctx, q.version) : latestVersion(ctx, q.project, loaders, mc))
    const deps: { project: string, version: string | null }[] = []
    queue.forEach((q, i) => {
      const v = versions[i]
      const file = v ? primaryFile(q.project, v) : null
      if (!v || !file) {
        if (q.top) missing.add(q.project)
        return
      }
      seen.add(v.project_id)
      files.set(v.project_id, file)
      for (const d of v.dependencies ?? []) {
        if (d.dependency_type !== 'required') continue
        const id = d.project_id && MODRINTH_ID.test(d.project_id) ? d.project_id : null
        const ver = d.version_id && MODRINTH_ID.test(d.version_id) ? d.version_id : null
        if (id && !seen.has(id)) deps.push({ project: id, version: ver })
      }
    })
    const unique = [...new Map(deps.map((d) => [d.project, d])).values()].filter((d) => !seen.has(d.project)).slice(0, MAX_MODS)
    const info = await modrinthProjects(ctx, unique.map((d) => d.project))
    const clientOnly = new Set((info ?? []).filter((p) => p.server_side === 'unsupported').map((p) => p.id))
    queue = unique.filter((d) => !clientOnly.has(d.project)).map((d) => ({ ...d, top: false }))
  }
  return { files: [...files.values()], missing }
}

async function downloadMod(ctx: AppContext, file: ModFile): Promise<Uint8Array<ArrayBuffer> | null> {
  const f = ctx.modrinthFetch ?? fetch
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const res = await f(file.url, { headers: { 'User-Agent': MODRINTH_UA }, redirect: 'error', signal: AbortSignal.timeout(60_000) })
      if (!res.ok) continue
      const data = new Uint8Array(await res.arrayBuffer())
      if (data.length > MOD_FILE_MAX) return null
      if (createHash('sha512').update(data).digest('hex') !== file.sha512) continue
      return data
    } catch {
      // nächster Versuch
    }
  }
  return null
}

/** Dateien per Upload-Link (multipart „files“) in `dir` legen – ohne das Limit von `POST /mods`. */
async function uploadModBatch(ctx: AppContext, row: Row, dir: string, batch: { file: ModFile, data: Uint8Array<ArrayBuffer> }[]): Promise<boolean> {
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const res = z.object({ url: z.string().max(2000) }).loose().safeParse(await call(ctx, 'POST', path(row, '/files/upload-url'), { directory: dir }))
      if (!res.success || !URL.canParse(res.data.url)) continue
      const url = new URL(res.data.url)
      if (url.protocol !== 'https:' || !UPLOAD_HOST.test(url.hostname)) return false
      const form = new FormData()
      for (const b of batch) form.append('files', new Blob([b.data], { type: 'application/java-archive' }), b.file.filename)
      const f = ctx.craftinghostingFetch ?? fetch
      const up = await f(url.href, { method: 'POST', body: form, redirect: 'error', signal: AbortSignal.timeout(5 * 60_000) })
      if (up.ok) return true
    } catch {
      // nächster Versuch
    }
  }
  return false
}

async function uploadMods(ctx: AppContext, row: Row, dir: string, files: ModFile[], job: ModJob): Promise<string[]> {
  const existing = new Set<string>()
  try {
    for (const e of await listDir(ctx, row, dir)) existing.add(e.name)
  } catch {
    // Ordner gibt es noch nicht – der Upload legt ihn an.
  }
  const todo = files.filter((f) => !existing.has(f.filename))
  const done = files.filter((f) => existing.has(f.filename)).map((f) => f.project)
  let total = 0
  let batch: { file: ModFile, data: Uint8Array<ArrayBuffer> }[] = []
  let batchBytes = 0
  const flush = async () => {
    if (!batch.length) return
    if (await uploadModBatch(ctx, row, dir, batch)) done.push(...batch.map((b) => b.file.project))
    else job.failed.push(...batch.map((b) => b.file.filename))
    batch = []
    batchBytes = 0
  }
  for (let i = 0; i < todo.length; i += MOD_PARALLEL) {
    const part = todo.slice(i, i + MOD_PARALLEL)
    const data = await Promise.all(part.map((f) => downloadMod(ctx, f)))
    for (let k = 0; k < part.length; k++) {
      const file = part[k]!
      const bytes = data[k]
      if (!bytes || total + bytes.length > MOD_TOTAL_MAX) {
        job.failed.push(file.filename)
        continue
      }
      total += bytes.length
      if (batchBytes + bytes.length > MOD_BATCH_BYTES) await flush()
      batch.push({ file, data: bytes })
      batchBytes += bytes.length
    }
  }
  await flush()
  return done
}

function rememberMods(ctx: AppContext, uuid: string, projects: string[]) {
  if (!projects.length) return
  const now = rowOf(ctx, uuid)
  if (!now) return
  const known = new Set(z.array(z.string()).catch([]).parse(JSON.parse(now.mods ?? '[]')))
  for (const p of projects) known.add(p)
  run(ctx.db, 'UPDATE craftinghosting_servers SET mods = ? WHERE uuid = ?', JSON.stringify([...known].slice(0, 200)), uuid)
}

function startModJob(ctx: AppContext, row: Row, dir: string, files: ModFile[]): ModJob {
  const prev = modJobs.get(row.uuid)
  const job: ModJob = { promise: Promise.resolve(), at: ctx.now(), done: false, failed: [] }
  const work = async () => {
    if (prev && !prev.done) await prev.promise.catch(() => undefined)
    const timeout = new Promise<string[]>((r) => setTimeout(() => r([]), MOD_JOB_MAX_MS).unref?.())
    const done = await Promise.race([uploadMods(ctx, row, dir, files, job), timeout])
    rememberMods(ctx, row.uuid, done)
  }
  job.promise = work()
    .catch((e) => {
      console.error('[trs-api] CraftingHosting mod upload failed', e)
      if (!job.failed.length) job.failed.push(...files.map((f) => f.filename))
    })
    .finally(() => {
      job.done = true
    })
  modJobs.set(row.uuid, job)
  return job
}

/** Test-Hilfe: laufendes Hochladen abwarten. */
export async function craftingModsDone(uuid: string): Promise<void> {
  await modJobs.get(uuid)?.promise
}

type SkipReason = 'client_only' | 'not_found' | 'unsupported'

/**
 * Modrinth-Mods auf den Server (§43.10). Prüft und löst sofort auf (Antwort mit installed/skipped);
 * herunterladen und hochladen läuft danach im Hintergrund, ein Start wartet darauf.
 */
export async function craftingInstallMods(ctx: AppContext, user: UserRow, projects: string[], versions: { project: string, version: string }[] = []) {
  assertFeature(ctx, 'addons')
  return onServer(ctx, user, async (row) => {
    const pinned = new Map(versions.map((v) => [v.project, v.version]))
    const wanted = [...new Set([...projects, ...pinned.keys()])].slice(0, MAX_MODS)
    const skipped: { project: string, reason: SkipReason }[] = []
    const p = parseServer(await call(ctx, 'GET', path(row)))
    const serverType = p.serverType ?? ''
    const loaders = modLoaders(serverType)
    const dir = addonDir(serverType)
    if (!loaders || !dir) throw new ApiError(409, 'mods_unsupported', 'No plugins or mods on this server type')
    const known = await modrinthProjects(ctx, wanted)
    if (!known) throw unavailable('modrinth_unavailable', 'Modrinth is not reachable, try again later')
    const byKey = new Map<string, z.output<typeof modrinthProject>>()
    for (const k of known) {
      byKey.set(k.id, k)
      byKey.set(k.slug, k)
    }
    const send: string[] = []
    for (const w of wanted) {
      const m = byKey.get(w)
      if (!m) skipped.push({ project: w, reason: 'not_found' })
      else if (m.server_side === 'unsupported') skipped.push({ project: w, reason: 'client_only' })
      else send.push(w)
    }
    if (!send.length) return { installed: [], skipped }
    const mc = await gameVersion(ctx, p.version ?? '')
    if (!mc) throw unavailable('modrinth_unavailable', 'Modrinth is not reachable, try again later')
    // Auflösen über die Projekt-ID, Antwort aber mit dem Namen, den der Client geschickt hat.
    const idOf = new Map(send.map((s) => [byKey.get(s)!.id, s]))
    const pinnedById = new Map([...pinned].filter(([k]) => byKey.has(k)).map(([k, v]) => [byKey.get(k)!.id, v]))
    const { files, missing } = await resolveModFiles(ctx, [...idOf.keys()], pinnedById, loaders, mc)
    const installed: string[] = []
    for (const [id, asked] of idOf) {
      if (missing.has(id)) skipped.push({ project: asked, reason: 'unsupported' })
      else installed.push(asked)
    }
    if (files.length) startModJob(ctx, row, dir.dir, files)
    return { installed, skipped }
  })
}

export const modsUploadBody = z.object({ replace: z.boolean().default(false) }).strict().default({ replace: false })

/**
 * Upload-Link für Mod-/Plugin-Dateien, die der Client 1:1 aus seiner Instanz hochlädt (§43.10).
 * `replace` (nur bei gestopptem Server) löscht vorher alle Jars im Ordner außer TRS Server Rules.
 */
export async function craftingModsUpload(ctx: AppContext, user: UserRow, replace: boolean) {
  assertFeature(ctx, 'addons')
  return onServer(ctx, user, async (row) => {
    const p = parseServer(await call(ctx, 'GET', path(row)))
    const dir = addonDir(p.serverType ?? null)
    if (!dir) throw new ApiError(409, 'mods_unsupported', 'No plugins or mods on this server type')
    let removed = 0
    if (replace) {
      if ((p.state !== 'offline' && p.state !== 'failed') || p.session?.active) throw conflict('server_running', 'Stop the server first')
      const old = (await listDir(ctx, row, dir.dir).catch(() => []))
        .filter((e) => e.type === 'file' && /\.jar$/i.test(e.name) && !e.name.toLowerCase().startsWith('trs-server-rules'))
        .map((e) => e.path)
      for (let i = 0; i < old.length; i += 100) await call(ctx, 'POST', path(row, '/files/delete'), { paths: old.slice(i, i + 100) })
      removed = old.length
    }
    const res = z.object({ url: z.string().max(2000) }).loose()
      .safeParse(await call(ctx, 'POST', path(row, '/files/upload-url'), { directory: dir.dir }))
    if (!res.success || !URL.canParse(res.data.url)) throw upstream()
    const url = new URL(res.data.url)
    if (url.protocol !== 'https:' || !UPLOAD_HOST.test(url.hostname)) throw upstream()
    return { url: url.href, expiresAt: new Date(ctx.now() + 10 * 60 * 1000).toISOString(), directory: dir.dir, removed }
  })
}

export async function craftingWorldUpload(ctx: AppContext, user: UserRow): Promise<{ url: string, expiresAt: string }> {
  return onServer(ctx, user, async (row) => {
    const res = z.object({ url: z.string().max(2000) }).loose()
      .safeParse(await call(ctx, 'POST', path(row, '/files/upload-url'), { directory: '/' }))
    if (!res.success || !URL.canParse(res.data.url)) throw upstream()
    const url = new URL(res.data.url)
    if (url.protocol !== 'https:' || !UPLOAD_HOST.test(url.hostname)) throw upstream()
    return { url: url.href, expiresAt: new Date(ctx.now() + 10 * 60 * 1000).toISOString() }
  })
}

export async function craftingWorldImport(ctx: AppContext, user: UserRow, name = 'world', activate = false): Promise<ServerView> {
  return onServer(ctx, user, async (row) => {
    const s = await fresh(ctx, row)
    if (s.state === 'installing') throw conflict('server_installing', 'server installing')
    if (s.session.active || s.state === 'running' || s.state === 'starting' || s.state === 'stopping') {
      throw conflict('server_running', 'Stop the server first')
    }
    const list = z.object({ files: z.array(z.object({ name: z.string() }).loose()).max(5000) }).loose()
      .safeParse(await call(ctx, 'GET', path(row, '/files?dir=%2F')))
    if (!list.success) throw upstream()
    if (!list.data.files.some((f) => f.name === WORLD_ZIP)) throw notFound('file_not_found', 'Upload the world first')
    await call(ctx, 'POST', path(row, '/files/delete'), { paths: [`/${name}`, `/${name}_nether`, `/${name}_the_end`] })
    await call(ctx, 'POST', path(row, '/files/decompress'), { path: `/${WORLD_ZIP}` })
    await call(ctx, 'POST', path(row, '/files/delete'), { paths: [`/${WORLD_ZIP}`] }).catch(() => undefined)
    if (activate) await setLevelName(ctx, row, name)
    return fresh(ctx, row)
  })
}

// ---------------------------------------------------------------- Zugang

const dashed = (u: string) => `${u.slice(0, 8)}-${u.slice(8, 12)}-${u.slice(12, 16)}-${u.slice(16, 20)}-${u.slice(20)}`

/** Besitzer + Freunde (ohne gesperrte) als whitelist.json-Einträge. */
function whitelistEntries(ctx: AppContext, owner: UserRow): { uuid: string, name: string }[] {
  const ids = [owner.uuid, ...friendUuids(ctx, owner.uuid).filter((u) => !isBanned(ctx, u))]
  return all<{ uuid: string, name: string }>(
    ctx.db,
    `SELECT uuid, name FROM users WHERE uuid IN (${ids.map(() => '?').join(',')})`,
    ...ids,
  ).filter((r) => MC_NAME.test(r.name) && /^[0-9a-f]{32}$/.test(r.uuid))
    .map((r) => ({ uuid: dashed(r.uuid), name: r.name }))
    .sort((a, b) => a.name.localeCompare(b.name))
}

/**
 * Zugang anwenden: Whitelist-Schalter + whitelist.json (gilt ab dem nächsten Start), bei laufendem Server zusätzlich
 * per Befehl; OP für den Besitzer einmal, sobald er läuft. Fehler hier lassen Start/Heartbeat nicht scheitern.
 */
async function syncAccess(ctx: AppContext, owner: UserRow, s: ServerView): Promise<void> {
  if (s.state === 'installing' || s.state === 'failed' || s.state === 'suspended' || syncing.has(owner.uuid)) return
  const row = rowOf(ctx, owner.uuid)
  if (!row) return
  syncing.add(owner.uuid)
  try {
    const running = isRunning(s)
    const send = (command: string) => call(ctx, 'POST', path(row, '/command'), { command })
    const friends = row.access === 'friends'
    const entries = friends ? whitelistEntries(ctx, owner) : []
    const key = `${row.access}:${JSON.stringify(entries)}`
    if (row.wl_key !== key) {
      if (row.wl_key === null || row.wl_key.startsWith('friends:') !== friends) {
        await call(ctx, 'PATCH', path(row, '/settings'), { whitelist: friends })
      }
      if (friends) {
        await call(ctx, 'PUT', path(row, '/files/content?path=%2Fwhitelist.json'), undefined,
          { type: 'application/json', data: JSON.stringify(entries, null, 2) })
      }
      if (running && friends) {
        await send('whitelist reload')
        await send('whitelist on')
      } else if (running) {
        await send('whitelist off')
      }
      run(ctx.db, 'UPDATE craftinghosting_servers SET wl_key = ? WHERE uuid = ?', key, owner.uuid)
    }
    if (running && !row.op_done && MC_NAME.test(owner.name)) {
      await send(`op ${owner.name}`)
      run(ctx.db, 'UPDATE craftinghosting_servers SET op_done = 1 WHERE uuid = ?', owner.uuid)
    }
    if (running && !row.setup && !restarts.has(owner.uuid)) await firstSetup(ctx, owner, s, row)
  } catch (e) {
    if (!(e instanceof ApiError)) console.error('[trs-api] CraftingHosting access sync failed', e)
  } finally {
    syncing.delete(owner.uuid)
  }
}

// ---------------------------------------------------------------- Ersteinrichtung (TRS Server Rules + MOTD)

const manifestSchema = z.object({
  version: z.string().max(20),
  files: z.array(z.object({
    platform: z.string().max(20),
    mc: z.string().max(20).nullish(),
    file: z.string().regex(/^trs-server-rules-[a-z0-9.-]+\.jar$/),
    sha256: z.string().regex(/^[0-9a-f]{64}$/),
    size: z.number().int().min(1).max(PLUGIN_MAX_BYTES),
  }).loose()).max(100),
}).loose()

const atLeast = (v: string, min: number[]) => {
  const p = v.split('.').map((x) => Number.parseInt(x, 10) || 0)
  for (let i = 0; i < min.length; i++) if ((p[i] ?? 0) !== min[i]) return (p[i] ?? 0) > min[i]!
  return true
}

async function pluginManifest(ctx: AppContext) {
  const hit = manifests.get(ctx)
  if (hit && ctx.now() - hit.at < MANIFEST_TTL_MS) return hit.value
  const f = ctx.serverRulesFetch ?? fetch
  const res = await f(`${ctx.config.serverRulesUrl}/server-plugin.json`, { signal: AbortSignal.timeout(TIMEOUT_MS) })
  if (!res.ok) throw new Error(`server-plugin manifest ${res.status}`)
  const value = manifestSchema.parse(await res.json())
  manifests.set(ctx, { at: ctx.now(), value })
  return value
}

/** Passendes TRS-Server-Rules-Jar für Typ + Version, sonst null (Vanilla, nicht gebaute Versionen, altes Plugin). */
async function pluginFor(ctx: AppContext, s: ServerView) {
  const platform = s.serverType === 'paper' || s.serverType === 'purpur' ? 'bukkit'
    : s.serverType === 'fabric' || s.serverType === 'forge' || s.serverType === 'neoforge' ? s.serverType : null
  if (!platform) return null
  let mc = s.version
  if (mc === 'latest') mc = (await partnerMe(ctx)).serverTypes.find((t) => t.id === s.serverType)?.versions.find((v) => v !== 'latest') ?? null
  const manifest = await pluginManifest(ctx)
  if (!atLeast(manifest.version, PLUGIN_MIN)) return null
  const entry = manifest.files.find((e) => e.platform === platform && (platform === 'bukkit' || e.mc === mc))
  if (!entry) return null
  let data = jars.get(entry.sha256)
  if (!data) {
    const f = ctx.serverRulesFetch ?? fetch
    const res = await f(`${ctx.config.serverRulesUrl}/${entry.file}`, { signal: AbortSignal.timeout(30_000) })
    if (!res.ok) throw new Error(`server-plugin jar ${res.status}`)
    const buf = new Uint8Array(await res.arrayBuffer())
    if (buf.length !== entry.size || createHash('sha256').update(buf).digest('hex') !== entry.sha256) {
      throw new Error('server-plugin jar checksum mismatch')
    }
    data = buf
    jars.set(entry.sha256, data)
  }
  return platform === 'bukkit'
    ? { platform, data, jar: `/plugins/${entry.file}`, dir: '/plugins/TRSServerRules', file: '/plugins/TRSServerRules/hosting.yml' }
    : { platform, data, jar: `/mods/${entry.file}`, dir: '/config', file: '/config/trs-server-rules-hosting.yml' }
}

/**
 * Nach dem ersten Start: TRS Server Rules mit fester MOTD-Fußzeile aufspielen (sonst die Fußzeile an die einzige
 * Zeile hängen), MOTD-Zeile 1 = eigener Text oder Servername, dann einmal neu starten, damit alles greift.
 */
async function firstSetup(ctx: AppContext, owner: UserRow, s: ServerView, row: Row): Promise<void> {
  const enc = (p: string) => encodeURIComponent(p)
  let plugin: Awaited<ReturnType<typeof pluginFor>> = null
  try {
    plugin = await pluginFor(ctx, s)
  } catch (e) {
    console.error('[trs-api] TRS Server Rules for CraftingHosting not available', e)
  }
  if (plugin) {
    await call(ctx, 'POST', path(row, '/files/folder'), { path: plugin.dir }).catch(() => undefined)
    await call(ctx, 'PUT', path(row, `/files/content?path=${enc(plugin.jar)}`), undefined, { type: 'application/java-archive', data: plugin.data })
    await call(ctx, 'PUT', path(row, `/files/content?path=${enc(plugin.file)}`), undefined,
      { type: 'text/yaml', data: `# TRS Launcher – CraftingHosting\nmotd-footer: "${MOTD_FOOTER}"\n` })
    if (plugin.platform === 'fabric') await call(ctx, 'POST', path(row, '/mods'), { project: 'fabric-api' }).catch(() => undefined)
  }
  await ensureTransfers(ctx, row)
  const line = (row.motd ?? s.name).slice(0, MOTD_LINE_MAX)
  const pluginName = plugin?.platform ?? null
  await call(ctx, 'PATCH', path(row, '/settings'), { motd: motdFor({ plugin: pluginName }, line) })
  run(ctx.db, 'UPDATE craftinghosting_servers SET setup = 1, plugin = ? WHERE uuid = ?', pluginName, owner.uuid)
  // In der automatischen Einrichtung stoppt der Ablauf selbst – kein Neustart nötig.
  if (autoActive(rowOf(ctx, owner.uuid))) return
  restarts.set(owner.uuid, restartAfterSetup(ctx, row).finally(() => restarts.delete(owner.uuid)))
}

async function restartAfterSetup(ctx: AppContext, row: Row): Promise<void> {
  try {
    await call(ctx, 'POST', path(row, '/stop'), {})
    const until = Date.now() + RESTART_WAIT_MS
    for (;;) {
      const s = parseServer(await call(ctx, 'GET', path(row)))
      if (s.state === 'offline' || s.state === 'failed') break
      if (Date.now() > until) throw new Error('server did not stop in time')
      await new Promise((r) => setTimeout(r, RESTART_POLL_MS))
    }
    await call(ctx, 'POST', path(row, '/start'), {})
  } catch (e) {
    console.error('[trs-api] CraftingHosting restart after setup failed', e)
  }
}

// ---------------------------------------------------------------- Team-Übersicht

export const partnerServerId = z.string().regex(/^[0-9a-f-]{8,64}$/)

/** Alle Partner-Server (auch ohne Zeile bei uns) mit Besitzer, Zustand und Belegung. */
export async function adminCraftingServers(ctx: AppContext) {
  config(ctx)
  const me = await partnerMe(ctx)
  const list: PartnerServer[] = []
  for (let offset = 0; offset < 1000; offset += 100) {
    const page = z.object({ servers: z.array(serverSchema).max(100) }).loose()
      .safeParse(await call(ctx, 'GET', `/servers?limit=100&offset=${offset}`))
    if (!page.success) throw upstream()
    list.push(...page.data.servers)
    if (page.data.servers.length < 100) break
  }
  const rows = new Map(allRows(ctx).map((r) => [r.server_id, r]))
  return {
    usage: { used: me.limits.usedServers, max: me.limits.maxServers },
    servers: list.map((p) => {
      const row = rows.get(p.id)
      const base = withBlock(view(p, row?.access ?? 'friends', setupOf(row), domainOf(ctx, row)), row)
      const v = row ? adjust(ctx, base, row) : base
      return {
        ...v,
        owner: row ? { uuid: row.uuid, name: row.name } : null,
        externalId: p.player?.externalId ?? null,
        plugin: row?.plugin ?? null,
      }
    }).sort((a, b) => Number(b.session.active) - Number(a.session.active) || (b.lastStartAt ?? '').localeCompare(a.lastStartAt ?? '')),
  }
}

function allRows(ctx: AppContext) {
  return all<Row & { name: string | null }>(
    ctx.db,
    'SELECT c.*, u.name AS name FROM craftinghosting_servers c LEFT JOIN users u ON u.uuid = c.uuid',
  )
}

const ownerOf = (ctx: AppContext, serverId: string) =>
  one<{ uuid: string }>(ctx.db, 'SELECT uuid FROM craftinghosting_servers WHERE server_id = ?', serverId)?.uuid ?? null

export async function adminCraftingStop(ctx: AppContext, serverId: string): Promise<string | null> {
  config(ctx)
  await call(ctx, 'POST', `/servers/${encodeURIComponent(serverId)}/stop`, {})
  return ownerOf(ctx, serverId)
}

export async function adminCraftingDelete(ctx: AppContext, serverId: string): Promise<string | null> {
  config(ctx)
  const owner = ownerOf(ctx, serverId)
  try {
    await call(ctx, 'DELETE', `/servers/${encodeURIComponent(serverId)}`)
  } catch (e) {
    if (!(e instanceof ApiError && e.code === 'no_server')) throw e
  }
  const recs = one<{ dns_record: string | null, dns_a_record: string | null }>(ctx.db,
    'SELECT dns_record, dns_a_record FROM craftinghosting_servers WHERE server_id = ?', serverId)
  for (const rec of [recs?.dns_record, recs?.dns_a_record]) {
    if (rec) await dnsDelete(ctx, rec).catch((e) => console.error('[trs-api] DNS record not removed', e))
  }
  run(ctx.db, 'DELETE FROM craftinghosting_servers WHERE server_id = ?', serverId)
  infoCache.delete(ctx)
  return owner
}

// ---------------------------------------------------------------- Eigene Adresse (DNS bei Cloudflare)

const SUBDOMAIN = /^[a-z0-9](?:[a-z0-9-]{1,22})[a-z0-9]$/
export const RESERVED_SUBDOMAINS = new Set([
  'www', 'api', 'play', 'docs', 'wiki', 'mail', 'smtp', 'imap', 'pop', 'mx', 'ns', 'ns1', 'ns2', 'admin', 'root',
  'relay', 'proxy', 'status', 'cdn', 'static', 'assets', 'app', 'dl', 'download', 'downloads', 'blog', 'news', 'shop',
  'store', 'support', 'help', 'team', 'panel', 'dash', 'dashboard', 'auth', 'login', 'account', 'accounts', 'sso', 'dev',
  'test', 'staging', 'beta', 'alpha', 'preview', 'demo', 'partner', 'partners', 'creator', 'creators', 'discord', 'dc',
  'server', 'servers', 'hosting', 'host', 'minecraft', 'mc', 'launcher', 'client', 'trs', 'theredstonee', 'official',
  'craftinghosting', 'autodiscover', 'autoconfig', 'webmail', 'ftp', 'git', 'ci', 'vpn', 'localhost', 'mod', 'mods',
  'map', 'maps', 'update', 'updates', 'security', 'abuse', 'postmaster', 'noreply', 'no-reply', 'newsletter',
])
const CF = 'https://api.cloudflare.com/client/v4'
const zoneIds = new WeakMap<AppContext, string>()

const fqdn = (ctx: AppContext, sub: string) => `${sub}.${ctx.config.dns!.zone}`
const srvName = (ctx: AppContext, sub: string) => `_minecraft._tcp.${fqdn(ctx, sub)}`

function domainOf(ctx: AppContext, row: Row | undefined): string | null {
  return ctx.config.dns && row?.subdomain && row.dns_record ? fqdn(ctx, row.subdomain) : null
}

async function cf(ctx: AppContext, method: string, path: string, body?: unknown): Promise<unknown> {
  const dns = ctx.config.dns
  if (!dns) throw unavailable('dns_unavailable', 'Own addresses are not available on this server')
  const f = ctx.cloudflareFetch ?? fetch
  let res: Response
  try {
    res = await f(CF + path, {
      method,
      headers: { 'Authorization': `Bearer ${dns.token}`, 'Content-Type': 'application/json', 'Accept': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      redirect: 'error',
      signal: AbortSignal.timeout(TIMEOUT_MS),
    })
  } catch {
    throw unavailable('dns_unavailable', 'DNS provider not reachable')
  }
  const json = await res.json().catch(() => null) as { success?: boolean, result?: unknown } | null
  if (!res.ok || json?.success === false) {
    console.error(`[trs-api] Cloudflare ${method} ${path.split('?')[0]} -> ${res.status}`)
    throw unavailable('dns_unavailable', 'DNS provider error')
  }
  return json?.result
}

async function zoneId(ctx: AppContext): Promise<string> {
  const hit = zoneIds.get(ctx)
  if (hit) return hit
  const r = z.array(z.object({ id: z.string().regex(/^[0-9a-f]{32}$/) }).loose())
    .safeParse(await cf(ctx, 'GET', `/zones?name=${encodeURIComponent(ctx.config.dns!.zone)}`))
  if (!r.success || !r.data[0]) throw unavailable('dns_unavailable', 'DNS zone not found')
  zoneIds.set(ctx, r.data[0].id)
  return r.data[0].id
}

/** Gibt es unter dem Namen schon DNS-Einträge, die nicht von uns sind? */
async function dnsTaken(ctx: AppContext, sub: string, ownRecord: string | null): Promise<boolean> {
  const zone = await zoneId(ctx)
  for (const name of [fqdn(ctx, sub), srvName(ctx, sub)]) {
    const list = z.array(z.object({ id: z.string() }).loose())
      .catch([]).parse(await cf(ctx, 'GET', `/zones/${zone}/dns_records?name=${encodeURIComponent(name)}&per_page=5`))
    if (list.some((r) => r.id !== ownRecord)) return true
  }
  return false
}

type Check = { name: string, available: boolean, reason: null | 'invalid' | 'reserved' | 'taken' }

export async function craftingCheckSubdomain(ctx: AppContext, uuid: string, raw: string): Promise<Check> {
  config(ctx)
  if (!ctx.config.dns) throw unavailable('dns_unavailable', 'Own addresses are not available on this server')
  const name = raw.trim().toLowerCase()
  if (!SUBDOMAIN.test(name) || name.startsWith('xn--')) return { name, available: false, reason: 'invalid' }
  if (RESERVED_SUBDOMAINS.has(name)) return { name, available: false, reason: 'reserved' }
  const holder = one<{ uuid: string, dns_record: string | null }>(ctx.db,
    'SELECT uuid, dns_record FROM craftinghosting_servers WHERE subdomain = ?', name)
  if (holder && holder.uuid !== uuid) return { name, available: false, reason: 'taken' }
  if (await dnsTaken(ctx, name, holder?.dns_record ?? null)) return { name, available: false, reason: 'taken' }
  return { name, available: true, reason: null }
}

async function claimable(ctx: AppContext, uuid: string, raw: string): Promise<string> {
  const c = await craftingCheckSubdomain(ctx, uuid, raw)
  if (c.reason === 'invalid') throw badRequest('invalid_subdomain', 'Invalid address')
  if (c.reason === 'reserved') throw conflict('subdomain_reserved', 'This address is reserved')
  if (c.reason === 'taken') throw conflict('subdomain_taken', 'This address is taken')
  return c.name
}

/** Freien Namen aus dem Servernamen ableiten (meine-welt, meine-welt-2, …). */
async function suggestSubdomain(ctx: AppContext, uuid: string, serverName: string): Promise<string | null> {
  let base = serverName.toLowerCase().normalize('NFKD').replace(/[\u0300-\u036f]/g, '')
    .replace(/ß/g, 'ss').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 20)
  if (base.length < 3) base = `server-${base}`.replace(/-+$/, '')
  for (let i = 0; i < 20; i++) {
    const name = i === 0 ? base : `${base}-${i + 1}`
    try {
      if ((await craftingCheckSubdomain(ctx, uuid, name)).available) return name
    } catch {
      return null
    }
  }
  return null
}

const nodeOk = (p: PartnerServer) => !!p.host && !!p.port && /^[A-Za-z0-9.-]{1,190}$/.test(p.host)
const liveFor = (p: PartnerServer) => p.state === 'running' && p.session?.active === true

/**
 * DNS des Servers halten: SRV zeigt bei laufendem Server direkt auf den Node, sonst (mit Aufweck-Proxy) auf
 * `<name>.trs-launcher.de:WAKE_PORT`, dazu ein A-Eintrag auf den Relay. Fehler hier lassen nichts scheitern.
 */
async function syncDns(ctx: AppContext, row: Row, p: PartnerServer): Promise<void> {
  const dns = ctx.config.dns
  if (!dns || !row.subdomain || !nodeOk(p)) return
  const wake = dns.wake && !liveFor(p) ? dns.wake : null
  const target = wake ? `wake:${wake.port}` : `${p.host}:${p.port}`
  try {
    const zone = await zoneId(ctx)
    if (dns.wake && !row.dns_a_record) {
      const res = await cf(ctx, 'POST', `/zones/${zone}/dns_records`, {
        type: 'A', name: fqdn(ctx, row.subdomain), content: dns.wake.ip, ttl: 60, proxied: false,
        comment: `TRS Launcher hosting ${row.server_id}`,
      })
      const id = z.object({ id: z.string().regex(/^[0-9a-f]{32}$/) }).loose().parse(res).id
      run(ctx.db, 'UPDATE craftinghosting_servers SET dns_a_record = ? WHERE uuid = ?', id, row.uuid)
    }
    if (row.dns_record && row.dns_target === target) return
    const record = {
      type: 'SRV',
      name: srvName(ctx, row.subdomain),
      data: wake
        ? { priority: 0, weight: 5, port: wake.port, target: fqdn(ctx, row.subdomain) }
        : { priority: 0, weight: 5, port: p.port, target: p.host },
      ttl: 60,
      comment: `TRS Launcher hosting ${row.server_id}`,
    }
    const res = row.dns_record
      ? await cf(ctx, 'PUT', `/zones/${zone}/dns_records/${row.dns_record}`, record)
      : await cf(ctx, 'POST', `/zones/${zone}/dns_records`, record)
    const id = z.object({ id: z.string().regex(/^[0-9a-f]{32}$/) }).loose().parse(res).id
    run(ctx.db, 'UPDATE craftinghosting_servers SET dns_record = ?, dns_target = ? WHERE uuid = ?', id, target, row.uuid)
  } catch (e) {
    console.error('[trs-api] DNS record for CraftingHosting server not set', e instanceof ApiError ? e.message : e)
  }
}

async function dnsDelete(ctx: AppContext, record: string): Promise<void> {
  if (!ctx.config.dns) return
  const zone = await zoneId(ctx)
  await cf(ctx, 'DELETE', `/zones/${zone}/dns_records/${record}`)
}

// ---------------------------------------------------------------- Aufwecken beim Beitreten (TRS Wake im Relay)

/** `accepts-transfers=true` in server.properties, damit TRS Wake Spieler per Transfer weiterreichen kann. */
async function ensureTransfers(ctx: AppContext, row: Row): Promise<void> {
  try {
    const r = z.object({ content: z.string().max(200_000) }).loose()
      .safeParse(await call(ctx, 'GET', path(row, '/files/content?path=%2Fserver.properties')))
    if (!r.success) return
    const lines = r.data.content.split(/\r?\n/)
    const i = lines.findIndex((l) => l.startsWith('accepts-transfers='))
    if (i >= 0 && lines[i] === 'accepts-transfers=true') {
      run(ctx.db, 'UPDATE craftinghosting_servers SET transfers = 1 WHERE uuid = ?', row.uuid)
      return
    }
    if (i >= 0) lines[i] = 'accepts-transfers=true'
    else lines.splice(lines.length && lines.at(-1) === '' ? lines.length - 1 : lines.length, 0, 'accepts-transfers=true')
    await call(ctx, 'PUT', path(row, '/files/content?path=%2Fserver.properties'), undefined, { type: 'text/plain', data: lines.join('\n') })
    run(ctx.db, 'UPDATE craftinghosting_servers SET transfers = 1 WHERE uuid = ?', row.uuid)
  } catch (e) {
    if (!(e instanceof ApiError)) console.error('[trs-api] accepts-transfers not set', e)
  }
}

const rowBySub = (ctx: AppContext, sub: string) =>
  one<Row>(ctx.db, 'SELECT * FROM craftinghosting_servers WHERE subdomain = ?', sub)

function subOfHost(ctx: AppContext, host: string): string | null {
  const zone = ctx.config.dns?.zone
  if (!zone) return null
  const h = host.trim().toLowerCase().replace(/\.$/, '')
  if (!h.endsWith(`.${zone}`)) return null
  const sub = h.slice(0, -zone.length - 1)
  return SUBDOMAIN.test(sub) ? sub : null
}

export type WakeState = 'running' | 'starting' | 'offline' | 'stopping' | 'cooldown' | 'unavailable'

function wakeState(p: PartnerServer, now: number): WakeState {
  const cd = p.session?.cooldownUntil ? Date.parse(p.session.cooldownUntil) : Number.NaN
  if (Number.isFinite(cd) && cd > now) return 'cooldown'
  if (liveFor(p)) return 'running'
  if (p.state === 'starting' || (p.state !== 'stopping' && p.session?.active)) return 'starting'
  if (p.state === 'stopping') return 'stopping'
  if (p.state === 'offline') return 'offline'
  return 'unavailable'
}

/** Für TRS Wake: Zustand und Ziel eines `<name>.trs-launcher.de`. */
export async function relayResolve(ctx: AppContext, host: string) {
  config(ctx)
  const sub = subOfHost(ctx, host)
  const row = sub ? rowBySub(ctx, sub) : undefined
  if (!sub || !row) throw notFound('unknown_host', 'Unknown host')
  const p = parseServer(await call(ctx, 'GET', path(row)))
  await syncDns(ctx, row, p)
  const owner = getUser(ctx, row.uuid)
  if (owner && liveFor(p) && (row.wl_key === null || !row.op_done || !row.setup)) {
    await syncAccess(ctx, owner, view(p, row.access, setupOf(row)))
  }
  const state = starting(row.uuid) ? 'starting' : wakeState(p, ctx.now())
  let maxPlayers = 20
  try {
    maxPlayers = (await craftingSettingsRaw(ctx, row)).maxPlayers
  } catch { /* vor dem ersten Start gibt es keine Einstellungen */ }
  return {
    name: sub,
    motd: (row.motd ?? p.name.replace(/ #[A-Z0-9]+$/, '')).slice(0, MOTD_LINE_MAX),
    state,
    target: state === 'running' && nodeOk(p) ? { host: p.host!, port: p.port! } : null,
    maxPlayers,
    cooldownUntil: state === 'cooldown' ? p.session?.cooldownUntil ?? null : null,
  }
}

const settingsCache = new Map<string, { at: number, maxPlayers: number }>()
async function craftingSettingsRaw(ctx: AppContext, row: Row) {
  const hit = settingsCache.get(row.server_id)
  if (hit && ctx.now() - hit.at < 60_000) return hit
  const v = { at: ctx.now(), maxPlayers: parseSettings(await call(ctx, 'GET', path(row, '/settings')), row).maxPlayers }
  settingsCache.set(row.server_id, v)
  return v
}

const wakes = new Map<string, number>()

/** Für TRS Wake: Server starten, wenn der Spieler darf (Nur Freunde: Name auf der Liste). */
export async function relayWake(ctx: AppContext, host: string, player: { name: string, uuid?: string }) {
  config(ctx)
  const sub = subOfHost(ctx, host)
  const row = sub ? rowBySub(ctx, sub) : undefined
  if (!sub || !row) throw notFound('unknown_host', 'Unknown host')
  const owner = getUser(ctx, row.uuid)
  if (!owner) throw notFound('unknown_host', 'Unknown host')
  if (maintenanceOf(ctx).active || !features(ctx).wakeOnJoin) throw new ApiError(409, 'unavailable', 'unavailable')
  if (row.blocked_reason !== null) throw new ApiError(409, 'unavailable', 'blocked')
  if (row.access === 'friends') {
    const names = new Set(whitelistEntries(ctx, owner).map((e) => e.name.toLowerCase()))
    if (!names.has(player.name.toLowerCase())) throw new ApiError(409, 'not_allowed', 'Not on the friends list')
  }
  const p = parseServer(await call(ctx, 'GET', path(row)))
  if (autoActive(row)) return { state: 'starting' as WakeState }
  const state = starting(row.uuid) ? 'starting' : wakeState(p, ctx.now())
  if (state === 'running' || state === 'starting') return { state }
  if (state === 'cooldown') {
    throw new ApiError(409, 'cooldown', 'cooldown', p.session?.cooldownUntil ? { cooldownUntil: p.session.cooldownUntil } : undefined)
  }
  if (state === 'unavailable') throw new ApiError(409, 'unavailable', 'unavailable')
  assertQuota(ctx, row)
  if (!p.eulaAccepted && row.eula_at === null) throw new ApiError(409, 'eula_required', 'eula required')
  const last = wakes.get(row.uuid) ?? 0
  if (ctx.now() - last < 30_000) return { state: 'starting' as WakeState }
  wakes.set(row.uuid, ctx.now())
  await syncAccess(ctx, owner, view(p, row.access, setupOf(row)))
  if (row.setup && !row.transfers) await ensureTransfers(ctx, row)
  await startOrQueue(ctx, row, false)
  run(ctx.db, 'UPDATE craftinghosting_servers SET rule_stop = NULL WHERE uuid = ?', row.uuid)
  return { state: 'starting' as WakeState }
}

/** Regelmäßig: DNS aller Server an ihren Zustand anpassen (Aufwecken braucht das, sobald einer einschläft). */
export async function sweepCraftingDns(ctx: AppContext): Promise<void> {
  if (!ctx.config.craftinghosting) return
  const wake = !!ctx.config.dns?.wake
  const rules = rulesOf(ctx).enabled
  if (!wake && !rules) return
  for (const r of all<Row>(ctx.db, "SELECT * FROM craftinghosting_servers WHERE auto_setup IN ('install', 'start', 'configure', 'stop')")) runAuto(ctx, r.uuid)
  const rows = all<Row>(ctx.db, rules ? 'SELECT * FROM craftinghosting_servers' : 'SELECT * FROM craftinghosting_servers WHERE subdomain IS NOT NULL')
  if (!rows.length) return
  const byId = new Map<string, PartnerServer>()
  for (let offset = 0; offset < 1000; offset += 100) {
    const page = z.object({ servers: z.array(serverSchema).max(100) }).loose()
      .safeParse(await call(ctx, 'GET', `/servers?limit=100&offset=${offset}`))
    if (!page.success) return
    for (const p of page.data.servers) byId.set(p.id, p)
    if (page.data.servers.length < 100) break
  }
  for (const row of rows) {
    const p = byId.get(row.server_id)
    if (!p) continue
    if (autoActive(row)) { runAuto(ctx, row.uuid); continue }
    if (rules) {
      try {
        await enforceRules(ctx, row, p)
      } catch (e) {
        if (!(e instanceof ApiError)) console.error('[trs-api] CraftingHosting rules failed', e)
      }
    }
    if (wake && row.subdomain) await syncDns(ctx, rowOf(ctx, row.uuid) ?? row, p)
  }
}

/** Fußzeile auf den aktuellen Text bringen: Plugin-Datei bzw. einzige MOTD-Zeile. Fehler stören den Start nicht. */
async function refreshFooter(ctx: AppContext, row: Row): Promise<void> {
  try {
    if (row.plugin === 'bukkit' || row.plugin === 'fabric' || row.plugin === 'forge' || row.plugin === 'neoforge') {
      const file = row.plugin === 'bukkit' ? '/plugins/TRSServerRules/hosting.yml' : '/config/trs-server-rules-hosting.yml'
      await call(ctx, 'PUT', path(row, `/files/content?path=${encodeURIComponent(file)}`), undefined,
        { type: 'text/yaml', data: `# TRS Launcher – CraftingHosting\nmotd-footer: "${MOTD_FOOTER}"\n` })
    } else if (row.motd) {
      await call(ctx, 'PATCH', path(row, '/settings'), { motd: motdFor(row, row.motd) })
    }
  } catch (e) {
    if (!(e instanceof ApiError)) console.error('[trs-api] MOTD footer not refreshed', e)
  }
}

// ---------------------------------------------------------------- Dateien, server.properties, Plugins/Mods

const MAX_TEXT = 1024 * 1024
const TEXT_EXT = new Set(['properties', 'yml', 'yaml', 'json', 'json5', 'toml', 'txt', 'cfg', 'conf', 'ini', 'md', 'log',
  'csv', 'mcmeta', 'sk', 'js', 'lang', 'xml', 'snbt', 'mcfunction', 'html', 'css', 'sh', 'env', 'list'])
/** Nie änderbar: Netz/Fernsteuerung, Transfers für TRS Wake, Whitelist folgt dem Zugang. */
const LOCKED_KEYS = /^(server-port|server-ip|query\.port|enable-query|enable-rcon|rcon\..+|management-server-.+|accepts-transfers|white-list|enforce-whitelist)$/

export const filePath = z.string().max(512).transform((v) => v.replace(/\/{2,}/g, '/').replace(/(.)\/$/, '$1'))
  .refine((v) => v.startsWith('/') && !v.includes('\\') && !/[\u0000-\u001f]/.test(v) && !v.split('/').some((x) => x === '..' || x === '.'), 'invalid path')
export const filesQuery = z.object({ dir: filePath.default('/') })
export const fileQuery = z.object({ path: filePath })
export const fileWriteBody = z.object({ content: z.string().max(MAX_TEXT) }).strict()
export const fileDeleteBody = z.object({ paths: z.array(filePath).min(1).max(50) }).strict()
export const folderBody = z.object({ path: filePath }).strict()
export const propertiesPatchBody = z.object({
  properties: z.record(z.string().regex(/^[a-z0-9.-]{1,64}$/), z.string().max(500).regex(/^[^\r\n]*$/)),
}).strict().refine((b) => Object.keys(b.properties).length > 0 && Object.keys(b.properties).length <= 200)
export const addonQuery = z.object({ file: z.string().regex(/^[\w.+\-() [\]]{1,128}\.jar$/) })

const extOf = (name: string) => (name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : '')
const isText = (name: string) => !name.includes('.') || TEXT_EXT.has(extOf(name)) || name === '.env'

/** Von TRS verwaltete Pfade (Plugin, Fußzeile, Whitelist bei „Nur Freunde“) und ihre Ordner. */
/** Server-Software und Laufzeit: nur lesen (kaputt = Server startet nicht mehr). */
const SYSTEM_DIRS = ['/libraries', '/versions', '/cache', '/.cache', '/bundler', '/.fabric', '/.paper-remapped',
  '/plugins/.paper-remapped', '/logs', '/crash-reports', '/.console_history']
const SYSTEM_FILES = /^\/([^/]+\.jar|eula\.txt|start\.sh|run\.sh|run\.bat|user_jvm_args\.txt|\.env|usercache\.json|version_history\.json)$/

/** Warum gesperrt: `system` = Server-Software, `trs` = von TRS verwaltet, `access` = folgt dem Zugang. */
export type LockReason = 'system' | 'trs' | 'access'

function lockOf(row: Row, path0: string, dir = false): LockReason | null {
  const p = path0.toLowerCase()
  if (SYSTEM_FILES.test(p) && !dir) return 'system'
  if (SYSTEM_DIRS.some((d) => p === d || p.startsWith(`${d}/`))) return 'system'
  if (dir) return ['/', '/plugins', '/mods', '/config', '/plugins/trsserverrules'].includes(p) ? 'trs' : null
  if (/^\/(plugins|mods)\/trs-server-rules-[^/]*\.jar$/.test(p)) return 'trs'
  if (p === '/plugins/trsserverrules/hosting.yml' || p === '/config/trs-server-rules-hosting.yml') return 'trs'
  if (row.access === 'friends' && p === '/whitelist.json') return 'access'
  return null
}

function protectedPath(row: Row, path0: string): boolean {
  const p = path0.toLowerCase()
  if (SYSTEM_FILES.test(p)) return true
  if (SYSTEM_DIRS.some((d) => p === d || p.startsWith(`${d}/`))) return true
  if (/^\/(plugins|mods)\/trs-server-rules-[^/]*\.jar$/.test(p)) return true
  if (p === '/plugins/trsserverrules/hosting.yml' || p === '/config/trs-server-rules-hosting.yml') return true
  if (row.access === 'friends' && p === '/whitelist.json') return true
  return false
}
const protectedDir = (p: string) => ['/', '/plugins', '/mods', '/config', '/plugins/trsserverrules'].includes(p.toLowerCase())
  || SYSTEM_DIRS.some((d) => p.toLowerCase() === d || p.toLowerCase().startsWith(`${d}/`))

const entrySchema = z.object({
  name: z.string().max(255),
  path: z.string().max(600).nullish(),
  type: z.string().max(20).nullish(),
  size: z.number().nullish(),
  modifiedAt: z.string().max(40).nullish(),
}).loose()

async function listDir(ctx: AppContext, row: Row, dir: string) {
  const r = z.object({ files: z.array(entrySchema).max(5000) }).loose()
    .safeParse(await call(ctx, 'GET', path(row, `/files?dir=${encodeURIComponent(dir)}`)))
  if (!r.success) throw upstream()
  return r.data.files.filter((f) => f.name && !f.name.includes('/')).map((f) => {
    const p = dir === '/' ? `/${f.name}` : `${dir}/${f.name}`
    const type = f.type === 'directory' ? 'directory' as const : 'file' as const
    return { name: f.name, path: p, type, size: f.size ?? 0, modifiedAt: f.modifiedAt ?? null }
  })
}

export async function craftingFiles(ctx: AppContext, user: UserRow, dir: string) {
  assertFeature(ctx, 'files')
  return onServer(ctx, user, async (row) => {
    const entries = (await listDir(ctx, row, dir)).map((e) => {
      const isDir = e.type === 'directory'
      const prot = isDir ? protectedDir(e.path) : protectedPath(row, e.path)
      const text = !isDir && isText(e.name) && e.size <= MAX_TEXT
      return {
        ...e,
        editable: text && !prot,
        /** Textdatei, die man ansehen darf (auch gesperrte). */
        viewable: text,
        protected: prot,
        lock: prot ? lockOf(row, e.path, isDir) ?? 'trs' : null,
      }
    }).sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'directory' ? -1 : 1))
    const dirLock = lockOf(row, dir, true)
    return {
      dir,
      entries,
      /** Darf man hier neue Ordner/Dateien anlegen? */
      writable: !(dirLock === 'system') && !protectedPath(row, `${dir === '/' ? '' : dir}/x`),
    }
  })
}

async function readText(ctx: AppContext, row: Row, file: string): Promise<string> {
  const r = z.object({ content: z.string() }).loose().safeParse(await call(ctx, 'GET', path(row, `/files/content?path=${encodeURIComponent(file)}`)))
  if (!r.success) throw upstream()
  if (r.data.content.length > MAX_TEXT) throw new ApiError(413, 'file_too_large', 'File too large')
  return r.data.content
}

export async function craftingReadFile(ctx: AppContext, user: UserRow, file: string) {
  assertFeature(ctx, 'files')
  return onServer(ctx, user, async (row) => {
    if (!isText(file.slice(file.lastIndexOf('/') + 1))) throw new ApiError(415, 'file_not_text', 'Not a text file')
    return { path: file, content: await readText(ctx, row, file) }
  })
}

// ---- server.properties

function parseProps(text: string): Map<string, string> {
  const out = new Map<string, string>()
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trimStart()
    if (!line || line.startsWith('#') || line.startsWith('!')) continue
    const m = /^((?:\\.|[^=:\s\\])+)\s*[=:]?\s*(.*)$/.exec(line)
    if (!m) continue
    out.set(unesc(m[1]!), unesc(m[2]!))
  }
  return out
}
function unesc(v: string): string {
  return v.replace(/\\u([0-9a-fA-F]{4})|\\(.)/g, (_, u: string | undefined, c: string | undefined) =>
    u ? String.fromCharCode(Number.parseInt(u, 16)) : c === 'n' ? '\n' : c === 't' ? '\t' : c ?? '')
}
function esc(v: string, key = false): string {
  let out = ''
  for (const ch of v) {
    const code = ch.codePointAt(0)!
    if (ch === '\\') out += '\\\\'
    else if (ch === ':' || ch === '=' || (key && ch === ' ')) out += `\\${ch}`
    else if (code > 0x7e || code < 0x20) out += ch.split('').map((c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`).join('')
    else out += ch
  }
  return out
}

/** Zeilen von server.properties setzen (Kommentare/Reihenfolge bleiben), fehlende Schlüssel anhängen. */
function setProps(text: string, values: Record<string, string>): string {
  const left = new Map(Object.entries(values))
  const lines = text.split(/\r?\n/).map((raw) => {
    const line = raw.trimStart()
    if (!line || line.startsWith('#') || line.startsWith('!')) return raw
    const m = /^((?:\\.|[^=:\s\\])+)/.exec(line)
    const key = m ? unesc(m[1]!) : null
    if (key === null || !left.has(key)) return raw
    const v = left.get(key)!
    left.delete(key)
    return `${esc(key, true)}=${esc(v)}`
  })
  while (lines.length && lines.at(-1) === '') lines.pop()
  for (const [k, v] of left) lines.push(`${esc(k, true)}=${esc(v)}`)
  return `${lines.join('\n')}\n`
}

const stripFooter = (motd: string) => cutFooter(motd)
  .replace(/§./g, '').slice(0, MOTD_LINE_MAX)

export async function craftingProperties(ctx: AppContext, user: UserRow) {
  assertFeature(ctx, 'properties')
  return onServer(ctx, user, async (row) => {
    const props = parseProps(await readText(ctx, row, '/server.properties'))
    const out: Record<string, string> = {}
    for (const [k, v] of props) out[k] = k === 'motd' ? (row.motd ?? stripFooter(v)) : v
    return { properties: out, locked: Object.keys(out).filter((k) => LOCKED_KEYS.test(k)) }
  })
}

async function applyMotd(ctx: AppContext, row: Row, line: string) {
  const clean = line.replace(/§./g, '').trim().slice(0, MOTD_LINE_MAX)
  run(ctx.db, 'UPDATE craftinghosting_servers SET motd = ? WHERE uuid = ?', clean, row.uuid)
  await call(ctx, 'PATCH', path(row, '/settings'), { motd: motdFor(row, clean) })
}

export async function craftingSetProperties(ctx: AppContext, user: UserRow, values: Record<string, string>) {
  assertFeature(ctx, 'properties')
  const locked = Object.keys(values).filter((k) => LOCKED_KEYS.test(k))
  if (locked.length) throw new ApiError(400, 'property_locked', 'Property locked', { keys: locked })
  await onServer(ctx, user, async (row) => {
    const { motd, ...rest } = values
    if (Object.keys(rest).length) {
      const text = await readText(ctx, row, '/server.properties')
      await call(ctx, 'PUT', path(row, '/files/content?path=%2Fserver.properties'), undefined,
        { type: 'text/plain', data: setProps(text, { ...rest, 'accepts-transfers': 'true' }) })
    }
    if (motd !== undefined) await applyMotd(ctx, row, motd)
  })
  return craftingProperties(ctx, user)
}

export async function craftingWriteFile(ctx: AppContext, user: UserRow, file: string, content: string) {
  assertFeature(ctx, 'files')
  return onServer(ctx, user, async (row) => {
    if (protectedPath(row, file)) throw forbidden('file_protected', 'Managed by TRS')
    if (!isText(file.slice(file.lastIndexOf('/') + 1))) throw new ApiError(415, 'file_not_text', 'Not a text file')
    if (file.toLowerCase() === '/server.properties') {
      // Gesperrte Schlüssel behalten ihren Wert, MOTD über die Regeln von §43.7.
      const old = parseProps(await readText(ctx, row, '/server.properties'))
      const wanted = parseProps(content)
      const keep: Record<string, string> = { 'accepts-transfers': 'true' }
      for (const [k, v] of old) if (LOCKED_KEYS.test(k) && k !== 'accepts-transfers') keep[k] = v
      if (old.has('motd')) keep.motd = old.get('motd')!
      await call(ctx, 'PUT', path(row, '/files/content?path=%2Fserver.properties'), undefined,
        { type: 'text/plain', data: setProps(content, keep) })
      const line = wanted.get('motd')
      if (line !== undefined && stripFooter(line) !== (row.motd ?? stripFooter(old.get('motd') ?? ''))) await applyMotd(ctx, row, stripFooter(line))
      return { path: file, size: Buffer.byteLength(content) }
    }
    await call(ctx, 'PUT', path(row, `/files/content?path=${encodeURIComponent(file)}`), undefined, { type: 'text/plain', data: content })
    return { path: file, size: Buffer.byteLength(content) }
  })
}

export async function craftingDeleteFiles(ctx: AppContext, user: UserRow, paths: string[]) {
  assertFeature(ctx, 'files')
  await onServer(ctx, user, async (row) => {
    if (paths.some((p) => protectedPath(row, p) || protectedDir(p) || protectedInside(p))) throw forbidden('file_protected', 'Managed by TRS')
    await call(ctx, 'POST', path(row, '/files/delete'), { paths })
  })
}
/** Ordner, in denen verwaltete Dateien liegen, dürfen nicht als Ganzes weg. */
const protectedInside = (p: string) => ['/plugins/trsserverrules', '/config'].some((d) => d.startsWith(`${p.toLowerCase()}/`) || d === p.toLowerCase())

export async function craftingCreateFolder(ctx: AppContext, user: UserRow, dir: string) {
  await onServer(ctx, user, async (row) => {
    if (protectedPath(row, dir) || protectedPath(row, `${dir}/x`)) throw forbidden('file_protected', 'Managed by the server')
    await call(ctx, 'POST', path(row, '/files/folder'), { path: dir })
  })
}

// ---- Plugins/Mods

function addonDir(serverType: string | null): { kind: 'plugins' | 'mods', dir: string } | null {
  if (serverType === 'paper' || serverType === 'purpur') return { kind: 'plugins', dir: '/plugins' }
  if (serverType === 'fabric' || serverType === 'forge' || serverType === 'neoforge' || serverType === 'quilt') return { kind: 'mods', dir: '/mods' }
  return null
}

export async function craftingAddons(ctx: AppContext, user: UserRow) {
  assertFeature(ctx, 'addons')
  return onServer(ctx, user, async (row) => {
    const s = await fresh(ctx, row)
    const a = addonDir(s.serverType)
    if (!a) return { kind: null, dir: null, items: [] }
    let entries: Awaited<ReturnType<typeof listDir>> = []
    try {
      entries = await listDir(ctx, row, a.dir)
    } catch (e) {
      if (!(e instanceof ApiError && (e.code === 'file_not_found' || e.code === 'no_server'))) throw e
    }
    const items = entries.filter((e) => e.type === 'file' && e.name.toLowerCase().endsWith('.jar'))
      .map((e) => ({ file: e.name, size: e.size, modifiedAt: e.modifiedAt, protected: protectedPath(row, e.path) }))
      .sort((x, y) => x.file.localeCompare(y.file))
    return { kind: a.kind, dir: a.dir, items }
  })
}

export async function craftingRemoveAddon(ctx: AppContext, user: UserRow, file: string) {
  assertFeature(ctx, 'addons')
  await onServer(ctx, user, async (row) => {
    const s = await fresh(ctx, row)
    const a = addonDir(s.serverType)
    if (!a) throw new ApiError(409, 'mods_unsupported', 'No plugins or mods on this server type')
    const p = `${a.dir}/${file}`
    if (protectedPath(row, p)) throw forbidden('file_protected', 'Managed by TRS')
    await call(ctx, 'POST', path(row, '/files/delete'), { paths: [p] })
  })
}

// ---------------------------------------------------------------- Welten

const DIMS = ['', '_nether', '_the_end']

async function setLevelName(ctx: AppContext, row: Row, name: string) {
  const text = await readText(ctx, row, '/server.properties')
  await call(ctx, 'PUT', path(row, '/files/content?path=%2Fserver.properties'), undefined,
    { type: 'text/plain', data: setProps(text, { 'level-name': name, 'accepts-transfers': 'true' }) })
}

function assertStopped(s: ServerView) {
  if (s.state === 'installing') throw conflict('server_installing', 'server installing')
  if (s.session.active || s.state === 'running' || s.state === 'starting' || s.state === 'stopping') {
    throw conflict('server_running', 'Stop the server first')
  }
}

/** Welten = Ordner mit level.dat; Paper legt Nether/End als `<name>_nether`/`<name>_the_end` daneben. */
export async function craftingWorlds(ctx: AppContext, user: UserRow) {
  assertFeature(ctx, 'worlds')
  return onServer(ctx, user, async (row) => {
    const props = parseProps(await readText(ctx, row, '/server.properties'))
    const active = props.get('level-name') || 'world'
    const root = await listDir(ctx, row, '/')
    const dirs = root.filter((e) => e.type === 'directory' && WORLD_NAME.test(e.name)).map((e) => e.name)
    const names = new Set<string>()
    for (const d of dirs.slice(0, 40)) {
      const base = d.replace(/_(nether|the_end)$/, '')
      if (names.has(base)) continue
      if (d !== base && dirs.includes(base)) continue
      const inner = await listDir(ctx, row, `/${d}`).catch(() => [])
      if (inner.some((f) => f.name === 'level.dat')) names.add(base)
    }
    if (dirs.includes(active)) names.add(active)
    const worlds = [...names].sort().map((name) => ({
      name,
      active: name === active,
      dimensions: DIMS.map((x) => `${name}${x}`).filter((d) => dirs.includes(d)),
    }))
    return { active, exists: dirs.includes(active), worlds }
  })
}

export async function craftingActivateWorld(ctx: AppContext, user: UserRow, name: string) {
  await onServer(ctx, user, async (row) => {
    assertStopped(await fresh(ctx, row))
    await setLevelName(ctx, row, name)
  })
  return craftingWorlds(ctx, user)
}

/** Welt löschen; die aktive Welt wird damit beim nächsten Start neu erzeugt (Seed aus den Einstellungen). */
export async function craftingDeleteWorld(ctx: AppContext, user: UserRow, name: string) {
  await onServer(ctx, user, async (row) => {
    assertStopped(await fresh(ctx, row))
    await call(ctx, 'POST', path(row, '/files/delete'), { paths: DIMS.map((x) => `/${name}${x}`) })
  })
  return craftingWorlds(ctx, user)
}

/** Alle Dateien einer Welt (rekursiv, alle Dimensionen) für den Download im Launcher. */
export async function craftingWorldFiles(ctx: AppContext, user: UserRow, name: string) {
  assertFeature(ctx, 'worldDownload')
  return onServer(ctx, user, async (row) => {
    const files: { path: string, size: number }[] = []
    const walk = async (dir: string, depth: number) => {
      if (depth > 8 || files.length >= 5000) return
      for (const e of await listDir(ctx, row, dir)) {
        if (files.length >= 5000) return
        if (e.type === 'directory') await walk(e.path, depth + 1)
        else files.push({ path: e.path, size: e.size })
      }
    }
    const root = await listDir(ctx, row, '/')
    for (const d of DIMS.map((x) => `${name}${x}`)) {
      if (root.some((e) => e.type === 'directory' && e.name === d)) await walk(`/${d}`, 0)
    }
    if (!files.length) throw notFound('file_not_found', 'World not found')
    return { name, files, truncated: files.length >= 5000 }
  })
}

const UPLOAD_OR_DOWNLOAD_HOST = UPLOAD_HOST

/** Kurzlebige Download-Links (10 min) für bis zu 100 Dateien. */
export async function craftingDownloadUrls(ctx: AppContext, user: UserRow, paths: string[]) {
  return onServer(ctx, user, async (row) => {
    const urls: { path: string, url: string }[] = []
    for (const raw of paths) {
      const p = filePath.parse(raw)
      const r = z.object({ url: z.string().max(2000) }).loose()
        .safeParse(await call(ctx, 'GET', path(row, `/files/download-url?path=${encodeURIComponent(p)}`)))
      if (!r.success || !URL.canParse(r.data.url)) throw upstream()
      const u = new URL(r.data.url)
      if (u.protocol !== 'https:' || !UPLOAD_OR_DOWNLOAD_HOST.test(u.hostname)) throw upstream()
      urls.push({ path: p, url: u.href })
    }
    return { urls, expiresAt: new Date(ctx.now() + 10 * 60 * 1000).toISOString() }
  })
}

// ---------------------------------------------------------------- Webhooks von CraftingHosting (§43.15)

const WEBHOOK_TYPES = new Set(['server.installed', 'server.install_failed', 'server.running', 'server.stopped',
  'server.cooldown_ended', 'server.deletion_soon', 'server.deleted', 'webhook.test'])
const seenDeliveries = new Map<string, number>()

/** Signatur `sha256=<HMAC(secret, "<ts>.<body>")>`, höchstens 5 Minuten alt. */
export function verifyWebhook(secret: string, raw: Buffer, ts: string, sig: string, nowMs: number): boolean {
  if (!/^\d{1,12}$/.test(ts) || Math.abs(nowMs / 1000 - Number(ts)) > 300) return false
  const want = Buffer.from(`sha256=${createHmac('sha256', secret).update(`${ts}.`).update(raw).digest('hex')}`)
  const got = Buffer.from(sig)
  return got.length === want.length && timingSafeEqual(got, want)
}

/** Geheimnis aus der Admin-Einrichtung, sonst aus der Env (`CRAFTINGHOSTING_WEBHOOK_SECRET`). */
export function webhookSecret(ctx: AppContext): string | null {
  return webhookSecrets(ctx)[0] ?? null
}

/** Alle hinterlegten Geheimnisse – wer zuletzt eingerichtet hat (Admin-Seite oder Partner-API), gewinnt beim Prüfen. */
export function webhookSecrets(ctx: AppContext): string[] {
  return [...new Set([adminGet(ctx, 'webhook_secret'), ctx.config.craftinghostingWebhookSecret].filter((x): x is string => !!x))]
}

const webhookEvent = z.object({
  id: z.string().max(100),
  type: z.string().max(60),
  data: z.object({ serverId: z.string().max(64).nullish() }).loose().nullish(),
}).loose()

/**
 * Eingang eines Webhooks: nur Anstoß, den Zustand holen wir selbst (Ereignisse können doppelt/vertauscht kommen).
 * Läuft nach der Antwort weiter, damit CraftingHosting nicht auf uns warten muss.
 */
export function craftingWebhook(ctx: AppContext, raw: Buffer): { ok: true } {
  const parsed = webhookEvent.safeParse(JSON.parse(raw.toString('utf8') || 'null'))
  if (!parsed.success || !WEBHOOK_TYPES.has(parsed.data.type)) return { ok: true }
  const ev = parsed.data
  const now = ctx.now()
  for (const [k, at] of seenDeliveries) if (now - at > 24 * 3600_000) seenDeliveries.delete(k)
  if (seenDeliveries.has(ev.id)) return { ok: true }
  seenDeliveries.set(ev.id, now)
  const serverId = ev.data?.serverId
  if (!serverId) return { ok: true }
  const row = one<Row>(ctx.db, 'SELECT * FROM craftinghosting_servers WHERE server_id = ?', serverId)
  if (!row) return { ok: true }
  void (async () => {
    if (ev.type === 'server.deleted') {
      forget(ctx, row.uuid)
      return
    }
    if (ev.type === 'server.install_failed' || ev.type === 'server.deletion_soon') {
      console.info(`[trs-api] CraftingHosting ${ev.type} for ${serverId}`)
    }
    const p = parseServer(await call(ctx, 'GET', path(row)))
    await syncDns(ctx, rowOf(ctx, row.uuid) ?? row, p)
    const owner = getUser(ctx, row.uuid)
    if (owner && ev.type === 'server.running') await syncAccess(ctx, owner, view(p, row.access, setupOf(row)))
  })().catch((e) => {
    if (!(e instanceof ApiError)) console.error('[trs-api] CraftingHosting webhook handling failed', e)
  })
  return { ok: true }
}

/** Team: Webhook beim Partner setzen (Geheimnis kommt nur einmal und bleibt bei uns), Status, Test. */
export async function adminWebhook(ctx: AppContext, action: 'status' | 'setup' | 'test' | 'remove', by: string) {
  config(ctx)
  const url = ctx.config.craftinghostingWebhookUrl
  if (action === 'setup') {
    const r = z.object({ secret: z.string().regex(/^whsec_[A-Za-z0-9_-]{8,200}$/) }).loose()
      .safeParse(await call(ctx, 'PUT', '/webhook', { url }))
    if (!r.success) throw upstream()
    adminSet(ctx, 'webhook_secret', r.data.secret, by)
  } else if (action === 'test') {
    await call(ctx, 'POST', '/webhook/test', {})
  } else if (action === 'remove') {
    await call(ctx, 'DELETE', '/webhook').catch((e) => {
      if (!(e instanceof ApiError && e.code === 'no_webhook')) throw e
    })
    adminSet(ctx, 'webhook_secret', null, by)
  }
  const st = z.object({
    url: z.string().max(500).nullable().catch(null),
    pending: z.number().catch(0),
    recent: z.array(z.object({ type: z.string().max(60).nullish(), status: z.union([z.number(), z.string()]).nullish(),
      at: z.string().max(40).nullish(), createdAt: z.string().max(40).nullish() }).loose()).max(50).catch([]),
  }).loose().catch({ url: null, pending: 0, recent: [] }).parse(await call(ctx, 'GET', '/webhook'))
  return {
    configured: !!webhookSecret(ctx) && st.url === url,
    url: st.url,
    expectedUrl: url,
    pending: st.pending,
    recent: st.recent.slice(0, 10),
  }
}

// ---------------------------------------------------------------- Laufzeit-Regeln (§43.17)
// 24-h-Kontingent über alle Sitzungen, danach 2 h Pause; 3 Tage aus = volles Kontingent; Mo–So (Berlin) höchstens
// 168 h − 8 h Laufzeit. Gezählt wird im 60-s-Durchgang (sweepCraftingDns), höchstens 120 s je Durchgang.

export interface Rules {
  enabled: boolean
  budgetHours: number
  cooldownHours: number
  idleResetDays: number
  weeklyOfflineHours: number
  warnMinutes: number
}
const DEFAULT_RULES: Rules = { enabled: true, budgetHours: 24, cooldownHours: 2, idleResetDays: 3, weeklyOfflineHours: 8, warnMinutes: 30 }
const MAX_TICK_S = 120

export function rulesOf(ctx: AppContext): Rules {
  const raw = adminGet(ctx, 'rules')
  if (!raw) return DEFAULT_RULES
  try {
    return { ...DEFAULT_RULES, ...adminConfigBody.shape.rules.unwrap().parse(JSON.parse(raw)) }
  } catch {
    return DEFAULT_RULES
  }
}

function publicRules(ctx: AppContext) {
  const r = rulesOf(ctx)
  return { budgetHours: r.budgetHours, cooldownHours: r.cooldownHours, idleResetDays: r.idleResetDays,
    weeklyOfflineHours: r.weeklyOfflineHours, warnMinutes: r.warnMinutes, week: 'mon-sun-europe-berlin' }
}

const berlinParts = new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Berlin', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23', weekday: 'short' })
function berlin(ms: number) {
  const o = Object.fromEntries(berlinParts.formatToParts(ms).map((x) => [x.type, x.value]))
  return { y: +o.year!, m: +o.month!, d: +o.day!, h: +o.hour!, min: +o.minute!, s: +o.second!, wd: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].indexOf(o.weekday!) }
}
/** Mitternacht (Berlin) des Tages y-m-d als Epoch-ms. */
function berlinMidnight(y: number, m: number, d: number): number {
  let t = Date.UTC(y, m - 1, d)
  for (let i = 0; i < 2; i++) {
    const b = berlin(t)
    t -= (Date.UTC(b.y, b.m - 1, b.d, b.h, b.min, b.s) - Date.UTC(y, m - 1, d))
  }
  return t
}
/** Montag 0 Uhr (Berlin) der Woche von `ms` und der nächsten. */
function weekOf(ms: number): { key: string, start: number, end: number } {
  const b = berlin(ms)
  const mon = new Date(Date.UTC(b.y, b.m - 1, b.d - b.wd))
  const next = new Date(Date.UTC(b.y, b.m - 1, b.d - b.wd + 7))
  return {
    key: mon.toISOString().slice(0, 10),
    start: berlinMidnight(mon.getUTCFullYear(), mon.getUTCMonth() + 1, mon.getUTCDate()),
    end: berlinMidnight(next.getUTCFullYear(), next.getUTCMonth() + 1, next.getUTCDate()),
  }
}

/** Stand nach Regeln ohne Laufzeit-Fortschritt (Pause vorbei, 3 Tage aus, neue Woche). */
function settled(ctx: AppContext, row: Row, now: number) {
  const r = rulesOf(ctx)
  const wk = weekOf(now)
  let used = row.run_used_s ?? 0
  let week = row.week_key === wk.key ? row.week_used_s ?? 0 : 0
  let cd = row.cooldown_until
  let warned = row.warned ?? 0
  if (cd !== null && cd <= now) {
    cd = null
    used = 0
    warned = 0
  }
  if (cd === null && row.run_acct_at === null && row.last_on_at !== null && used > 0 && now - row.last_on_at >= r.idleResetDays * 86_400_000) {
    used = 0
    warned = 0
  }
  if (row.week_key !== wk.key) week = 0
  return { r, wk, used, week, cd, warned }
}

function quotaOf(ctx: AppContext, row: Row): Quota | null {
  const now = ctx.now()
  const { r, wk, used, week, cd } = settled(ctx, row, now)
  if (!r.enabled) return null
  const budget = r.budgetHours * 3600
  const cap = (168 - r.weeklyOfflineHours) * 3600
  const idleAt = used > 0 && row.last_on_at !== null && row.run_acct_at === null ? row.last_on_at + r.idleResetDays * 86_400_000 : null
  return {
    usedSeconds: Math.round(used),
    budgetSeconds: budget,
    remainingSeconds: cd !== null ? 0 : Math.max(0, Math.round(Math.min(budget - used, cap - week))),
    weekUsedSeconds: Math.round(week),
    weekCapSeconds: cap,
    weekResetsAt: new Date(wk.end).toISOString(),
    idleResetAt: idleAt !== null && cd === null ? new Date(idleAt).toISOString() : null,
    cooldownUntil: cd !== null ? new Date(cd).toISOString() : null,
  }
}

/** Start/Aufwecken nur mit Restzeit; sonst `409 cooldown` mit Ende der Pause. */
function assertQuota(ctx: AppContext, row: Row) {
  const q = quotaOf(ctx, row)
  if (!q) return
  if (q.cooldownUntil) throw new ApiError(409, 'cooldown', 'cooldown', { cooldownUntil: q.cooldownUntil })
  if (q.remainingSeconds <= 0) {
    const r = rulesOf(ctx)
    const wk = weekOf(ctx.now())
    const until = q.weekUsedSeconds >= q.weekCapSeconds ? wk.end : ctx.now() + r.cooldownHours * 3_600_000
    run(ctx.db, 'UPDATE craftinghosting_servers SET cooldown_until = ? WHERE uuid = ?', until, row.uuid)
    throw new ApiError(409, 'cooldown', 'cooldown', { cooldownUntil: new Date(until).toISOString() })
  }
}

const isOn = (p: PartnerServer) => p.session?.active === true || p.state === 'running' || p.state === 'starting'

function warnText(minutes: number) {
  return `tellraw @a ["",{"text":"[TRS] ","color":"red"},{"text":"Der Server stoppt in ${minutes} Minuten (Laufzeit-Limit). / Server stops in ${minutes} minutes (runtime limit).","color":"yellow"}]`
}

/** Ein Durchgang für einen Server: zählen, warnen, bei 0 stoppen. */
export async function enforceRules(ctx: AppContext, row: Row, p: PartnerServer): Promise<void> {
  if (autoActive(row)) return
  const now = ctx.now()
  const st = settled(ctx, row, now)
  const { r, wk } = st
  let { used, week, cd, warned } = st
  const on = isOn(p)
  if (on && row.run_acct_at !== null) {
    const dt = Math.min(MAX_TICK_S, Math.max(0, (now - row.run_acct_at) / 1000))
    used += dt
    week += dt
  }
  const budget = r.budgetHours * 3600
  const cap = (168 - r.weeklyOfflineHours) * 3600
  const remaining = Math.min(budget - used, cap - week)
  let stop = false
  let ruleStop = row.rule_stop
  if (on && remaining <= 0) {
    stop = true
    cd = week >= cap ? wk.end : now + r.cooldownHours * 3_600_000
    ruleStop = 'session_limit'
  } else if (on && r.warnMinutes > 0 && remaining <= r.warnMinutes * 60 && !warned) {
    warned = 1
    void call(ctx, 'POST', path(row, '/command'), { command: warnText(Math.max(1, Math.ceil(remaining / 60))) }).catch(() => undefined)
  }
  run(ctx.db, `UPDATE craftinghosting_servers SET run_used_s = ?, run_acct_at = ?, last_on_at = ?, cooldown_until = ?, week_key = ?,
    week_used_s = ?, warned = ?, rule_stop = ? WHERE uuid = ?`,
  Math.round(used), on && !stop ? now : null, on ? now : row.last_on_at, cd, wk.key, Math.round(week), warned, ruleStop, row.uuid)
  if (stop) {
    stopPending(row.uuid)
    await call(ctx, 'POST', path(row, '/stop'), {})
  }
}

/** Team: Kontingent eines Servers zurücksetzen (Pause aufheben). */
export function adminCraftingResetQuota(ctx: AppContext, serverId: string): string {
  const row = one<Row>(ctx.db, 'SELECT * FROM craftinghosting_servers WHERE server_id = ?', serverId)
  if (!row) throw notFound('no_server', 'Unknown server')
  run(ctx.db, `UPDATE craftinghosting_servers SET run_used_s = 0, cooldown_until = NULL, week_used_s = 0, warned = 0, rule_stop = NULL
    WHERE uuid = ?`, row.uuid)
  return row.uuid
}

// ---------------------------------------------------------------- Anzeige-Texte (§43.18)

const LANGS = ['en', 'de', 'es', 'fr', 'nl', 'pl', 'pt-BR', 'tr'] as const
type Lang = (typeof LANGS)[number]
const TEXTS: Record<string, Record<Lang, string>> = {
  runtime: { 'en': 'Runtime', 'de': 'Laufzeit', 'es': 'Tiempo', 'fr': 'Temps', 'nl': 'Looptijd', 'pl': 'Czas działania', 'pt-BR': 'Tempo', 'tr': 'Çalışma süresi' },
  cooldown: { 'en': 'Mandatory break', 'de': 'Pflichtpause', 'es': 'Pausa obligatoria', 'fr': 'Pause obligatoire', 'nl': 'Verplichte pauze', 'pl': 'Obowiązkowa przerwa', 'pt-BR': 'Pausa obrigatória', 'tr': 'Zorunlu mola' },
  cooldownValue: { 'en': 'start again in {countdown:%s}', 'de': 'wieder startbar in {countdown:%s}', 'es': 'se puede iniciar en {countdown:%s}', 'fr': 'redémarrable dans {countdown:%s}', 'nl': 'weer te starten over {countdown:%s}', 'pl': 'ponowny start za {countdown:%s}', 'pt-BR': 'pode iniciar em {countdown:%s}', 'tr': '{countdown:%s} sonra yeniden başlatılabilir' },
  leftRunning: { 'en': '{countdown:%s} of %h h left', 'de': 'noch {countdown:%s} von %h h', 'es': 'quedan {countdown:%s} de %h h', 'fr': 'encore {countdown:%s} sur %h h', 'nl': 'nog {countdown:%s} van %h u', 'pl': 'zostało {countdown:%s} z %h h', 'pt-BR': 'restam {countdown:%s} de %h h', 'tr': '%h sa içinden {countdown:%s} kaldı' },
  leftIdle: { 'en': '{duration:%s} of %h h left', 'de': 'noch {duration:%s} von %h h', 'es': 'quedan {duration:%s} de %h h', 'fr': 'encore {duration:%s} sur %h h', 'nl': 'nog {duration:%s} van %h u', 'pl': 'zostało {duration:%s} z %h h', 'pt-BR': 'restam {duration:%s} de %h h', 'tr': '%h sa içinden {duration:%s} kaldı' },
  week: { 'en': 'This week %u / %c h', 'de': 'Diese Woche %u / %c h', 'es': 'Esta semana %u / %c h', 'fr': 'Cette semaine %u / %c h', 'nl': 'Deze week %u / %c u', 'pl': 'Ten tydzień %u / %c h', 'pt-BR': 'Esta semana %u / %c h', 'tr': 'Bu hafta %u / %c sa' },
  idleReset: { 'en': 'Full quota again {date:%d}', 'de': 'Volles Kontingent wieder {date:%d}', 'es': 'Cupo completo de nuevo {date:%d}', 'fr': 'Quota complet à nouveau {date:%d}', 'nl': 'Weer volledig tegoed {date:%d}', 'pl': 'Pełny limit znów {date:%d}', 'pt-BR': 'Cota completa de novo {date:%d}', 'tr': 'Tam kota yeniden {date:%d}' },
  warn: { 'en': 'Your server stops in {countdown:%s} – its runtime is almost used up.', 'de': 'Dein Server stoppt in {countdown:%s} – die Laufzeit ist fast aufgebraucht.', 'es': 'Tu servidor se detiene en {countdown:%s}: el tiempo casi se ha agotado.', 'fr': 'Ton serveur s’arrête dans {countdown:%s} – le temps est presque épuisé.', 'nl': 'Je server stopt over {countdown:%s} – de looptijd is bijna op.', 'pl': 'Twój serwer zatrzyma się za {countdown:%s} – czas działania prawie się skończył.', 'pt-BR': 'Seu servidor para em {countdown:%s} – o tempo está quase acabando.', 'tr': 'Sunucun {countdown:%s} içinde duracak – çalışma süresi neredeyse bitti.' },
  setupInstall: { 'en': 'Installing your server', 'de': 'Server wird installiert', 'es': 'Instalando tu servidor', 'fr': 'Installation du serveur', 'nl': 'Server wordt geïnstalleerd', 'pl': 'Instalowanie serwera', 'pt-BR': 'Instalando seu servidor', 'tr': 'Sunucu kuruluyor' },
  setupStart: { 'en': 'First start', 'de': 'Erster Start', 'es': 'Primer inicio', 'fr': 'Premier démarrage', 'nl': 'Eerste start', 'pl': 'Pierwsze uruchomienie', 'pt-BR': 'Primeira inicialização', 'tr': 'İlk başlatma' },
  setupConfigure: { 'en': 'Setting things up', 'de': 'Wird eingerichtet', 'es': 'Configurando', 'fr': 'Configuration', 'nl': 'Wordt ingesteld', 'pl': 'Konfigurowanie', 'pt-BR': 'Configurando', 'tr': 'Ayarlanıyor' },
  setupStop: { 'en': 'Saving and stopping', 'de': 'Wird gespeichert und gestoppt', 'es': 'Guardando y deteniendo', 'fr': 'Enregistrement et arrêt', 'nl': 'Opslaan en stoppen', 'pl': 'Zapisywanie i zatrzymywanie', 'pt-BR': 'Salvando e parando', 'tr': 'Kaydediliyor ve durduruluyor' },
  setupFailed: { 'en': 'Setup did not finish', 'de': 'Einrichtung nicht abgeschlossen', 'es': 'La configuración no terminó', 'fr': 'La configuration n’a pas abouti', 'nl': 'Instellen is niet gelukt', 'pl': 'Konfiguracja nie powiodła się', 'pt-BR': 'A configuração não terminou', 'tr': 'Kurulum tamamlanamadı' },
  hintInstall: { 'en': 'The server software is being downloaded.', 'de': 'Die Server-Software wird heruntergeladen.', 'es': 'Se está descargando el software del servidor.', 'fr': 'Le logiciel serveur est en cours de téléchargement.', 'nl': 'De serversoftware wordt gedownload.', 'pl': 'Pobieranie oprogramowania serwera.', 'pt-BR': 'O software do servidor está sendo baixado.', 'tr': 'Sunucu yazılımı indiriliyor.' },
  hintStart: { 'en': 'World and files are being created. This usually takes 1–3 minutes.', 'de': 'Welt und Dateien werden erzeugt. Das dauert meist 1–3 Minuten.', 'es': 'Se crean el mundo y los archivos. Suele tardar 1–3 minutos.', 'fr': 'Le monde et les fichiers sont créés. Cela prend en général 1 à 3 minutes.', 'nl': 'Wereld en bestanden worden aangemaakt. Dit duurt meestal 1–3 minuten.', 'pl': 'Tworzenie świata i plików. Zwykle trwa to 1–3 minuty.', 'pt-BR': 'O mundo e os arquivos estão sendo criados. Costuma levar 1–3 minutos.', 'tr': 'Dünya ve dosyalar oluşturuluyor. Genellikle 1–3 dakika sürer.' },
  hintConfigure: { 'en': 'Access, address and TRS Server Rules are being set.', 'de': 'Zugang, Adresse und TRS Server Rules werden gesetzt.', 'es': 'Se configuran el acceso, la dirección y TRS Server Rules.', 'fr': 'Accès, adresse et TRS Server Rules sont configurés.', 'nl': 'Toegang, adres en TRS Server Rules worden ingesteld.', 'pl': 'Ustawianie dostępu, adresu i TRS Server Rules.', 'pt-BR': 'Acesso, endereço e TRS Server Rules estão sendo definidos.', 'tr': 'Erişim, adres ve TRS Server Rules ayarlanıyor.' },
  hintStop: { 'en': 'Almost done – everything is being saved.', 'de': 'Gleich geschafft – alles wird gespeichert.', 'es': 'Casi listo: se está guardando todo.', 'fr': 'Presque fini – tout est enregistré.', 'nl': 'Bijna klaar – alles wordt opgeslagen.', 'pl': 'Prawie gotowe – wszystko jest zapisywane.', 'pt-BR': 'Quase pronto – tudo está sendo salvo.', 'tr': 'Neredeyse bitti – her şey kaydediliyor.' },
  hintFailed: { 'en': 'The server did not start in time. Your data is safe.', 'de': 'Der Server ist nicht rechtzeitig gestartet. Deine Daten sind sicher.', 'es': 'El servidor no se inició a tiempo. Tus datos están a salvo.', 'fr': 'Le serveur n’a pas démarré à temps. Tes données sont en sécurité.', 'nl': 'De server is niet op tijd gestart. Je gegevens zijn veilig.', 'pl': 'Serwer nie uruchomił się na czas. Twoje dane są bezpieczne.', 'pt-BR': 'O servidor não iniciou a tempo. Seus dados estão seguros.', 'tr': 'Sunucu zamanında başlamadı. Verilerin güvende.' },
  setupRetry: { 'en': 'Try again', 'de': 'Erneut versuchen', 'es': 'Reintentar', 'fr': 'Réessayer', 'nl': 'Opnieuw proberen', 'pl': 'Spróbuj ponownie', 'pt-BR': 'Tentar de novo', 'tr': 'Tekrar dene' },
  setupLocked: { 'en': 'Managing your server opens as soon as setup is done. You can close the launcher meanwhile.', 'de': 'Die Verwaltung öffnet sich, sobald alles eingerichtet ist. Du kannst den Launcher dabei schließen.', 'es': 'La gestión se abre en cuanto termine la configuración. Puedes cerrar el launcher mientras tanto.', 'fr': 'La gestion s’ouvre dès que tout est configuré. Tu peux fermer le launcher en attendant.', 'nl': 'Beheer opent zodra alles is ingesteld. Je kunt de launcher intussen sluiten.', 'pl': 'Zarządzanie otworzy się po zakończeniu konfiguracji. W tym czasie możesz zamknąć launcher.', 'pt-BR': 'O gerenciamento abre assim que tudo estiver configurado. Você pode fechar o launcher enquanto isso.', 'tr': 'Kurulum biter bitmez yönetim açılır. Bu sırada başlatıcıyı kapatabilirsin.' },
  tileRuns: { 'en': 'Your server runs while the launcher is open.', 'de': 'Dein Server läuft, solange der Launcher offen ist.', 'es': 'Tu servidor funciona mientras el launcher está abierto.', 'fr': 'Ton serveur tourne tant que le launcher est ouvert.', 'nl': 'Je server draait zolang de launcher open is.', 'pl': 'Twój serwer działa, dopóki launcher jest otwarty.', 'pt-BR': 'Seu servidor roda enquanto o launcher estiver aberto.', 'tr': 'Sunucun, başlatıcı açık olduğu sürece çalışır.' },
  tileQuota: { 'en': '%h h runtime in total, then a %p h break.', 'de': 'Insgesamt %h h Laufzeit, danach %p h Pause.', 'es': '%h h de tiempo en total, luego %p h de pausa.', 'fr': '%h h de fonctionnement au total, puis %p h de pause.', 'nl': 'In totaal %h u looptijd, daarna %p u pauze.', 'pl': 'Łącznie %h h działania, potem %p h przerwy.', 'pt-BR': '%h h de tempo no total, depois %p h de pausa.', 'tr': 'Toplam %h sa çalışma süresi, sonra %p sa mola.' },
  tileIdleReset: { 'en': 'Unused for %d days? Your full %h h are back.', 'de': '%d Tage nicht genutzt? Dann hast du wieder volle %h h.', 'es': '¿%d días sin usarlo? Vuelves a tener %h h completas.', 'fr': 'Pas utilisé pendant %d jours ? Tu récupères tes %h h.', 'nl': '%d dagen niet gebruikt? Dan heb je weer volle %h u.', 'pl': 'Nieużywany przez %d dni? Znów masz pełne %h h.', 'pt-BR': 'Sem uso por %d dias? Você tem as %h h completas de novo.', 'tr': '%d gün kullanılmadı mı? Tam %h sa yeniden senin.' },
  tileWeekly: { 'en': 'At least %o h offline per week (Mon–Sun).', 'de': 'Mindestens %o h pro Woche offline (Mo–So).', 'es': 'Al menos %o h apagado por semana (lun–dom).', 'fr': 'Au moins %o h hors ligne par semaine (lun–dim).', 'nl': 'Minstens %o u per week offline (ma–zo).', 'pl': 'Co najmniej %o h offline w tygodniu (pon–niedz).', 'pt-BR': 'Pelo menos %o h offline por semana (seg–dom).', 'tr': 'Haftada en az %o sa kapalı (Pzt–Paz).' },
  tileDelete: { 'en': 'Servers are deleted after %d days without a start.', 'de': 'Server werden nach %d Tagen ohne Start gelöscht.', 'es': 'Los servidores se eliminan tras %d días sin iniciarse.', 'fr': 'Les serveurs sont supprimés après %d jours sans démarrage.', 'nl': 'Servers worden na %d dagen zonder start verwijderd.', 'pl': 'Serwery są usuwane po %d dniach bez uruchomienia.', 'pt-BR': 'Servidores são apagados após %d dias sem iniciar.', 'tr': 'Sunucular %d gün başlatılmazsa silinir.' },
  modsFailed: { 'en': '%n mod files could not be put on your server: %l', 'de': '%n Mod-Dateien kamen nicht auf deinen Server: %l', 'es': 'No se pudieron subir %n archivos de mods a tu servidor: %l', 'fr': '%n fichiers de mods n’ont pas pu être mis sur ton serveur : %l', 'nl': '%n moddbestanden konden niet op je server worden gezet: %l', 'pl': 'Nie udało się wgrać %n plików modów na serwer: %l', 'pt-BR': '%n arquivos de mods não puderam ser enviados ao seu servidor: %l', 'tr': '%n mod dosyası sunucuna yüklenemedi: %l' },
  tileEula: { 'en': 'You accept the Minecraft EULA when creating the server.', 'de': 'Die Minecraft-EULA akzeptierst du beim Anlegen.', 'es': 'Aceptas el EULA de Minecraft al crear el servidor.', 'fr': 'Tu acceptes l’EULA de Minecraft à la création.', 'nl': 'Je accepteert de Minecraft-EULA bij het aanmaken.', 'pl': 'EULA Minecrafta akceptujesz przy tworzeniu.', 'pt-BR': 'Você aceita o EULA do Minecraft ao criar.', 'tr': 'Minecraft EULA’sını oluştururken kabul edersin.' },
}

/** Text je Sprache mit `%x`-Werten. */
function txt(key: string, vars: Record<string, string | number> = {}): Localized {
  const out: Localized = {}
  for (const lang of LANGS) {
    let t = TEXTS[key]![lang]
    for (const [k, v] of Object.entries(vars)) t = t.replaceAll(`%${k}`, String(v))
    out[lang] = t
  }
  return out
}

function withAlerts(d: Display, extra: Display['alerts']): Display {
  return extra.length ? { ...d, alerts: [...d.alerts, ...extra] } : d
}

/** Nach dem Hochladen: welche Mod-Dateien nicht auf den Server kamen (einmal als Hinweis). */
function modsAlerts(uuid: string): Display['alerts'] {
  const job = modJobs.get(uuid)
  if (!job?.done || !job.failed.length) return []
  const names = job.failed.slice(0, 8).join(', ') + (job.failed.length > 8 ? ' …' : '')
  return [{ id: `mods-failed-${job.at}`, text: txt('modsFailed', { n: job.failed.length, l: names }), level: 'warning' }]
}

function displayOf(ctx: AppContext, v: ServerView, q: Quota): Display {
  const r = rulesOf(ctx)
  const hours = Math.round(q.budgetSeconds / 3600)
  const lines: Display['lines'] = []
  const alerts: Display['alerts'] = []
  let runtime: Display['runtime']
  if (q.cooldownUntil) {
    const left = Math.max(0, Math.round((Date.parse(q.cooldownUntil) - ctx.now()) / 1000))
    const total = Math.max(left, r.cooldownHours * 3600)
    runtime = { label: txt('cooldown'), value: txt('cooldownValue', { s: left }), progress: total ? 1 - left / total : 1, tone: 'cooldown' }
  } else {
    const active = v.session.active
    const warn = active && r.warnMinutes > 0 && q.remainingSeconds <= r.warnMinutes * 60
    runtime = {
      label: txt('runtime'),
      value: txt(active ? 'leftRunning' : 'leftIdle', { s: q.remainingSeconds, h: hours }),
      progress: q.budgetSeconds ? Math.min(1, q.usedSeconds / q.budgetSeconds) : 0,
      tone: warn ? 'warn' : 'normal',
    }
    if (warn && q.remainingSeconds > 0) {
      alerts.push({ id: `quota-warn-${Math.floor((ctx.now() + q.remainingSeconds * 1000) / 600_000)}`, text: txt('warn', { s: q.remainingSeconds }), level: 'warning' })
    }
  }
  lines.push({ text: txt('week', { u: Math.floor(q.weekUsedSeconds / 3600), c: Math.round(q.weekCapSeconds / 3600) }), tone: q.weekCapSeconds - q.weekUsedSeconds < 6 * 3600 ? 'warn' : 'normal' })
  if (q.idleResetAt && !v.session.active) lines.push({ text: txt('idleReset', { d: q.idleResetAt }), tone: 'normal' })
  return { runtime, lines, alerts }
}

function tilesOf(ctx: AppContext, deleteDays: number) {
  const r = rulesOf(ctx)
  if (!r.enabled) return null
  return [
    { icon: 'launcher', text: txt('tileRuns') },
    { icon: 'clock', text: txt('tileQuota', { h: r.budgetHours, p: r.cooldownHours }) },
    { icon: 'clock', text: txt('tileIdleReset', { d: r.idleResetDays, h: r.budgetHours }) },
    { icon: 'calendar', text: txt('tileWeekly', { o: r.weeklyOfflineHours }) },
    { icon: 'calendar', text: txt('tileDelete', { d: deleteDays }) },
    { icon: 'doc', text: txt('tileEula') },
  ]
}

// ---------------------------------------------------------------- Automatische Ersteinrichtung (§43.19)
// Nach dem Anlegen (mit EULA): installieren → erster Start → einrichten (TRS Server Rules, Zugang) → stoppen.
// Der Stand steht in `auto_setup`, damit ein Neustart der API den Ablauf fortsetzt (sweepCraftingDns stößt ihn an).

const AUTO_PHASES = ['install', 'start', 'configure', 'stop'] as const
const AUTO_TICK_MS = 5_000
const AUTO_MAX_MS = 10 * 60_000
const AUTO_BEAT_MS = 40_000
const autoTimers = new Map<string, ReturnType<typeof setTimeout>>()
const autoBeats = new Map<string, number>()
const isAutoFailed = (row: Pick<Row, 'auto_setup'> | undefined) => !!row?.auto_setup?.startsWith('failed')
function autoActive(row: Pick<Row, 'auto_setup'> | undefined): boolean {
  return !!row && (AUTO_PHASES as readonly string[]).includes(row.auto_setup ?? '')
}

function setupDisplay(row: Row): NonNullable<Display['setup']> | null {
  const failed = isAutoFailed(row)
  if (!failed && !autoActive(row)) return null
  // Gescheitert: Schritt, in dem es hing (`failed:<phase>`), sonst der erste.
  const phase = failed ? (row.auto_setup!.split(':')[1] ?? 'install') : row.auto_setup!
  const index = Math.max(0, AUTO_PHASES.indexOf(phase as (typeof AUTO_PHASES)[number]))
  const keys = ['Install', 'Start', 'Configure', 'Stop']
  return {
    phase: failed ? 'failed' : row.auto_setup!,
    index,
    failed,
    title: txt(failed ? 'setupFailed' : `setup${keys[index]}`),
    hint: txt(failed ? 'hintFailed' : `hint${keys[index]}`),
    steps: keys.map((k) => txt(`setup${k}`)),
    retry: txt('setupRetry'),
    locked: txt('setupLocked'),
    startedAt: row.auto_at ? new Date(row.auto_at).toISOString() : null,
  }
}

function runAuto(ctx: AppContext, uuid: string) {
  if (autoTimers.has(uuid)) return
  const t = setTimeout(() => {
    autoTimers.delete(uuid)
    autoStep(ctx, uuid).then((again) => { if (again) runAuto(ctx, uuid) }).catch((e) => {
      if (!(e instanceof ApiError)) console.error('[trs-api] CraftingHosting auto setup failed', e)
      runAuto(ctx, uuid)
    })
  }, AUTO_TICK_MS)
  t.unref?.()
  autoTimers.set(uuid, t)
}

/** Ein Schritt der Einrichtung. Liefert, ob es weitergeht. Exportiert für Tests. */
export async function autoStep(ctx: AppContext, uuid: string): Promise<boolean> {
  const row = rowOf(ctx, uuid)
  if (!row || !autoActive(row)) return false
  const owner = getUser(ctx, uuid)
  if (!owner) return false
  const set = (phase: string) => run(ctx.db, 'UPDATE craftinghosting_servers SET auto_setup = ? WHERE uuid = ?', phase === 'failed' ? `failed:${row.auto_setup}` : phase, uuid)
  const start = async () => {
    try {
      await call(ctx, 'POST', path(row, '/start'), { acceptEula: true })
      return true
    } catch (e) {
      if (isNotReady(e) || (e instanceof ApiError && (e.code === 'server_installing' || e.code === 'craftinghosting_upstream'))) return false
      throw e
    }
  }
  if (ctx.now() - (row.auto_at ?? ctx.now()) > AUTO_MAX_MS) {
    set('failed')
    console.warn(`[trs-api] CraftingHosting auto setup timed out in phase ${row.auto_setup} (${row.server_id})`)
    await call(ctx, 'POST', path(row, '/stop'), {}).catch(() => undefined)
    return false
  }
  let p: PartnerServer
  try {
    p = parseServer(await call(ctx, 'GET', path(row)))
  } catch (e) {
    if (e instanceof ApiError && e.code === 'no_server') { forget(ctx, uuid); return false }
    return true
  }
  const running = p.state === 'running'
  if (running && ctx.now() - (autoBeats.get(uuid) ?? 0) > AUTO_BEAT_MS) {
    autoBeats.set(uuid, ctx.now())
    await call(ctx, 'POST', path(row, '/heartbeat')).catch(() => undefined)
  }
  try {
    if (row.auto_setup === 'install') {
      if (p.state === 'failed' || p.state === 'suspended') { set('failed'); return false }
      if (running) set('configure')
      else if (p.state === 'offline' && await start()) set('start')
    } else if (row.auto_setup === 'start') {
      if (running) set('configure')
      else if (p.state === 'offline' && !p.session?.active) await start()
    } else if (row.auto_setup === 'configure') {
      if (!running) { if (p.state === 'offline') set('start'); return true }
      await syncAccess(ctx, owner, view(p, row.access, setupOf(row)))
      if (rowOf(ctx, uuid)?.setup) {
        await call(ctx, 'POST', path(row, '/stop'), {})
        set('stop')
      }
    } else if (p.state === 'offline' && !p.session?.active) {
      set('done')
      autoBeats.delete(uuid)
      const now = rowOf(ctx, uuid)
      if (now) await syncDns(ctx, now, p).catch(() => undefined)
      return false
    }
  } catch (e) {
    if (e instanceof ApiError && ['cooldown', 'server_failed', 'server_suspended', 'eula_required'].includes(e.code)) { set('failed'); return false }
    if (!(e instanceof ApiError)) throw e
  }
  return true
}
