import { z } from 'zod'
import type { AppContext } from './context'
import { createHash } from 'node:crypto'
import { all, one, run } from './db'
import { MODRINTH_ID } from './modrinth'
import { ApiError, badRequest, conflict, notFound, unavailable } from './errors'
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
export const MOTD_FOOTER = '§7Free server: §ctrs-launcher.de §8× §bcraftinghosting.free'
const MOTD_LINE_MAX = 60
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
  projects: z.array(z.string().trim().refine((v) => MODRINTH_ID.test(v) || MOD_SLUG.test(v))).min(1).max(100),
}).strict()
export const importBody = z.object({ file: z.literal(WORLD_ZIP) }).strict()
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
  'mods_unsupported'])

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
  if (status === 404) return code === 'file_not_found' || code === 'mod_not_found' ? notFound(code, code.replaceAll('_', ' ')) : noServer()
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
  }
}

/** Eigener Server oder null. Fehlt die Zeile, beim Partner nachsehen (z. B. nach DB-Wiederherstellung). */
export async function craftingServer(ctx: AppContext, user: UserRow): Promise<ServerView | null> {
  config(ctx)
  const row = rowOf(ctx, user.uuid)
  if (row) {
    try {
      const p = parseServer(await call(ctx, 'GET', `/servers/${encodeURIComponent(row.server_id)}`))
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
      const s = view(p, row.access, setupOf(row), domainOf(ctx, rowOf(ctx, user.uuid)))
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
  return view(direct.success ? direct.data.server : parseServer(await call(ctx, 'GET', path(row))), now.access, setupOf(now), domainOf(ctx, now))
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
  assertNotSanctioned(ctx, user.uuid, 'hosting_ban')
  return onServer(ctx, user, async (row) => {
    await syncAccess(ctx, user, await fresh(ctx, row))
    if (row.setup && !row.transfers) await ensureTransfers(ctx, row)
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
  const shown = out.motd.endsWith(` ${MOTD_FOOTER}`) ? out.motd.slice(0, -MOTD_FOOTER.length - 1) : out.motd
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

export async function craftingInstallMods(ctx: AppContext, user: UserRow, projects: string[]) {
  return onServer(ctx, user, async (row) => {
    const wanted = [...new Set(projects)].slice(0, MAX_MODS)
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
        await call(ctx, 'POST', path(row, '/mods'), { project: p })
        installed.push(p)
      } catch (e) {
        if (!(e instanceof ApiError) || e.code === 'mods_unsupported' || e.code === 'no_server' || e.status >= 500) throw e
        skipped.push({ project: p, reason: e.code === 'mod_not_found' ? 'not_found' : 'unsupported' })
      }
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

export async function craftingWorldImport(ctx: AppContext, user: UserRow): Promise<ServerView> {
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
    await call(ctx, 'POST', path(row, '/files/delete'), { paths: ['/world', '/world_nether', '/world_the_end'] })
    await call(ctx, 'POST', path(row, '/files/decompress'), { path: `/${WORLD_ZIP}` })
    await call(ctx, 'POST', path(row, '/files/delete'), { paths: [`/${WORLD_ZIP}`] }).catch(() => undefined)
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
    : s.serverType === 'fabric' || s.serverType === 'forge' ? s.serverType : null
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
      const v = view(p, row?.access ?? 'friends', setupOf(row), domainOf(ctx, row))
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
