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
const MAX_MODS = 50
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
function partnerError(status: number, body: unknown): ApiError {
  const parsed = errorSchema.safeParse(body)
  const code = parsed.success ? parsed.data.error.code : ''
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
      sessionMaxHours: l.sessionMaxHours,
      cooldownHours: l.cooldownHours,
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
}).strict()
export const blockBody = z.object({ reason: z.string().trim().min(1).max(300) }).strict()

export function adminCraftingConfig(ctx: AppContext) {
  return {
    notice: noticeOf(ctx),
    maintenance: maintenanceOf(ctx),
    features: features(ctx),
    overrides: adminFeatures(ctx),
    available: { ownAddress: !!ctx.config.dns, wakeOnJoin: !!ctx.config.dns?.wake },
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
      const s = withBlock(view(p, row.access, setupOf(row), domainOf(ctx, rowOf(ctx, user.uuid))), row)
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
  run(ctx.db, `INSERT INTO craftinghosting_servers (uuid, server_id, access, created_at) VALUES (?, ?, ?, ?)
    ON CONFLICT(uuid) DO UPDATE SET server_id = excluded.server_id, access = excluded.access, wl_key = NULL,
      op_done = 0, setup = 0, motd = NULL, plugin = NULL, subdomain = NULL, dns_record = NULL, dns_target = NULL,
      dns_a_record = NULL, transfers = 0`,
  user.uuid, s.id, body.access, ctx.now())
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
  return view(s, body.access, 'pending', domainOf(ctx, rowOf(ctx, user.uuid)))
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
  return withBlock(view(p, now.access, setupOf(now), domainOf(ctx, now)), now)
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
    await syncAccess(ctx, user, await fresh(ctx, row))
    if (row.setup && !row.transfers) await ensureTransfers(ctx, row)
    if (row.setup) await refreshFooter(ctx, row)
    return fresh(ctx, row, await call(ctx, 'POST', path(row, '/start'), acceptEula ? { acceptEula: true } : {}))
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

export async function craftingLogs(ctx: AppContext, user: UserRow, lines: number): Promise<string[]> {
  return onServer(ctx, user, async (row) => {
    const o = obj(await call(ctx, 'GET', path(row, `/logs?lines=${lines}`))) ?? {}
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

/** Reine Client-Mods (Modrinth server_side = unsupported) gar nicht erst schicken. `null` = Modrinth nicht erreichbar. */
async function modrinthProjects(ctx: AppContext, projects: string[]) {
  const f = ctx.modrinthFetch ?? fetch
  const url = `https://api.modrinth.com/v2/projects?ids=${encodeURIComponent(JSON.stringify(projects))}`
  try {
    const res = await f(url, {
      headers: { 'User-Agent': 'theredstonee/TRS-Launcher (trs-launcher.theredstonee.de)', 'Accept': 'application/json' },
      redirect: 'error',
      signal: AbortSignal.timeout(TIMEOUT_MS),
    })
    if (!res.ok) return null
    return z.array(modrinthProject).max(200).parse(await res.json())
  } catch {
    return null
  }
}

type SkipReason = 'client_only' | 'not_found' | 'unsupported'

export async function craftingInstallMods(ctx: AppContext, user: UserRow, projects: string[], versions: { project: string, version: string }[] = []) {
  assertFeature(ctx, 'addons')
  return onServer(ctx, user, async (row) => {
    const pinned = new Map(versions.map((v) => [v.project, v.version]))
    const wanted = [...new Set([...projects, ...pinned.keys()])].slice(0, MAX_MODS)
    const skipped: { project: string, reason: SkipReason }[] = []
    const known = await modrinthProjects(ctx, wanted)
    let send = wanted
    if (known) {
      const byKey = new Map<string, z.output<typeof modrinthProject>>()
      for (const p of known) {
        byKey.set(p.id, p)
        byKey.set(p.slug, p)
      }
      send = []
      for (const p of wanted) {
        const m = byKey.get(p)
        if (!m) skipped.push({ project: p, reason: 'not_found' })
        else if (m.server_side === 'unsupported') skipped.push({ project: p, reason: 'client_only' })
        else send.push(p)
      }
    }
    const installed: string[] = []
    for (const p of send) {
      try {
        await call(ctx, 'POST', path(row, '/mods'), pinned.has(p) ? { project: p, version: pinned.get(p) } : { project: p })
        installed.push(p)
      } catch (e) {
        if (!(e instanceof ApiError) || e.code === 'mods_unsupported' || e.code === 'no_server' || e.status >= 500) throw e
        skipped.push({ project: p, reason: e.code === 'mod_not_found' ? 'not_found' : 'unsupported' })
      }
    }
    if (installed.length) {
      const now = rowOf(ctx, row.uuid)
      const known = new Set(z.array(z.string()).catch([]).parse(JSON.parse(now?.mods ?? '[]')))
      for (const p of installed) known.add(p)
      run(ctx.db, 'UPDATE craftinghosting_servers SET mods = ? WHERE uuid = ?', JSON.stringify([...known].slice(0, 200)), row.uuid)
    }
    return { installed, skipped }
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
      const v = withBlock(view(p, row?.access ?? 'friends', setupOf(row), domainOf(ctx, row)), row)
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
  const state = restarts.has(row.uuid) ? 'starting' : wakeState(p, ctx.now())
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
  const state = restarts.has(row.uuid) ? 'starting' : wakeState(p, ctx.now())
  if (state === 'running' || state === 'starting') return { state }
  if (state === 'cooldown') {
    throw new ApiError(409, 'cooldown', 'cooldown', p.session?.cooldownUntil ? { cooldownUntil: p.session.cooldownUntil } : undefined)
  }
  if (state === 'unavailable') throw new ApiError(409, 'unavailable', 'unavailable')
  if (!p.eulaAccepted) throw new ApiError(409, 'eula_required', 'eula required')
  const last = wakes.get(row.uuid) ?? 0
  if (ctx.now() - last < 30_000) return { state: 'starting' as WakeState }
  wakes.set(row.uuid, ctx.now())
  await syncAccess(ctx, owner, view(p, row.access, setupOf(row)))
  if (row.setup && !row.transfers) await ensureTransfers(ctx, row)
  await call(ctx, 'POST', path(row, '/start'), {})
  return { state: 'starting' as WakeState }
}

/** Regelmäßig: DNS aller Server an ihren Zustand anpassen (Aufwecken braucht das, sobald einer einschläft). */
export async function sweepCraftingDns(ctx: AppContext): Promise<void> {
  if (!ctx.config.craftinghosting || !ctx.config.dns?.wake) return
  const rows = all<Row>(ctx.db, 'SELECT * FROM craftinghosting_servers WHERE subdomain IS NOT NULL')
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
    if (p) await syncDns(ctx, row, p)
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
