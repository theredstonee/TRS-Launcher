import { createHash } from 'node:crypto'
import { describe, expect, it, vi } from 'vitest'
import {
  craftingCommand, craftingCreate, craftingDelete, craftingHeartbeat, craftingInfo, craftingInstallMods, craftingLogs,
  craftingServer, craftingSetSettings, craftingSettings, craftingStart, craftingStats, craftingStop, craftingUpdate,
  craftingAddons, craftingDeleteFiles, craftingFiles, craftingProperties, craftingReadFile, craftingRemoveAddon,
  craftingSetProperties, craftingWriteFile, craftingWorlds, craftingActivateWorld, craftingDeleteWorld, craftingWorldFiles,
  craftingDownloadUrls, worldNameQuery, modsBody, craftingModsDone, craftingModsUpload, craftingChangeVersion, craftingBackups, versionBody,
  adminCraftingConfig, adminSetCraftingConfig, adminCraftingBlock, relayWake as wakeForTest,
  craftingReinstall, craftingBackupAction, craftingWebhook, verifyWebhook, adminWebhook, webhookSecret, adminCraftingResetQuota, autoStep,
  adminCraftingDelete, adminCraftingServers, adminCraftingStop, craftingCheckSubdomain, relayResolve, relayWake, sweepCraftingDns, craftingRestartDone, craftingWorldImport, craftingWorldUpload, createServerBody, MOTD_FOOTER, settingsBody,
} from '../server/lib/craftinghosting'
import { RULES } from '../server/lib/ratelimit'
import { run } from '../server/lib/db'
import { getUser } from '../server/lib/users'
import { createWebSession } from '../server/lib/weblogin'
import infoRoute from '../server/routes/v1/craftinghosting/info.get'
import serverGet from '../server/routes/v1/craftinghosting/server/index.get'
import serverPatch from '../server/routes/v1/craftinghosting/server/index.patch'
import startRoute from '../server/routes/v1/craftinghosting/server/start.post'
import heartbeatRoute from '../server/routes/v1/craftinghosting/server/heartbeat.post'
import { befriend, codeAsync, players } from './chathelpers'
import { callRoute } from './circuithelpers'
import { login, makeEnv, type TestEnv } from './helpers'

const KEY = 'test-partner-key'

interface FakeServer {
  id: string
  host?: string
  port?: number
  name: string
  player: { id: string, externalId: string, name: string }
  state: string
  address: string
  serverType: string
  version: string
  eulaAccepted: boolean
  session: Record<string, unknown>
}

/** Minimaler CraftingHosting-Partner im Speicher. */
class FakePartner {
  servers = new Map<string, FakeServer>()
  commands: string[] = []
  calls: string[] = []
  files = new Map<string, string>()
  settings: Record<string, unknown> = { motd: 'A Minecraft Server', maxPlayers: 20, whitelist: false, pvp: null, levelType: 'minecraft\\:normal' }
  mods: string[] = []
  modCalls: unknown[] = []
  /** Über den Upload-Link hochgeladene Dateien (Name → Größe). */
  uploads = new Map<string, number>()
  uploadFail = 0
  uploadHost = 'upload.craftinghosting.free'
  /** So oft antwortet die Node noch mit 503 service_unavailable (Start/Logs). */
  notReady = 0
  startBodies: Record<string, unknown>[] = []
  backups: { id: string, name: string | null, bytes: number, state: string, createdAt: string, completedAt: null }[] = []
  restored: string[] = []
  webhookUrl: string | null = null
  zipFolder = 'world'
  used = 0
  max = 100
  fail: { status: number, code: string, fields?: Record<string, unknown> } | null = null
  heartbeatBody: 'server' | 'session' = 'session'
  private n = 0

  fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(input))
    const method = init?.method ?? 'GET'
    const path = url.pathname.replace('/api/v1/partner', '')
    this.calls.push(`${method} ${path}`)
    if (url.hostname === this.uploadHost && path === '/upload') {
      if (this.uploadFail > 0) {
        this.uploadFail--
        return this.err(500, 'upload_failed')
      }
      const form = await new Response(init!.body as FormData).formData()
      for (const f of form.getAll('files') as File[]) this.uploads.set(f.name, f.size)
      return this.json(200, { ok: true })
    }
    if ((init?.headers as Record<string, string>)?.Authorization !== `Bearer ${KEY}`) return this.err(401, 'invalid_api_key')
    if (this.fail) return this.err(this.fail.status, this.fail.code, this.fail.fields)
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    let body: any = {}
    try {
      if (typeof init?.body === 'string') body = JSON.parse(init.body)
    } catch {
      body = {}
    }
    if (method === 'GET' && path === '/me') {
      return this.json(200, {
        plan: { id: 'free-4gb', name: 'Free 4 GB', memoryMb: 4096, cpuPercent: 200, diskMb: 10240 },
        serverTypes: [{ id: 'paper', name: 'Paper', versions: ['latest', '1.21.11'] }],
        limits: { maxServers: this.max, usedServers: this.used, maxServersPerPlayer: 1, sessionMaxHours: 24, cooldownHours: 2,
          heartbeatTimeoutSeconds: 180, deleteAfterIdleDays: 30, serverNameMaxLength: 22 },
        eulaUrl: 'https://www.minecraft.net/eula',
      })
    }
    if (path === '/webhook' && method === 'PUT') {
      this.webhookUrl = body.url
      return this.json(200, { url: body.url, secret: 'whsec_testsecret123456' })
    }
    if (path === '/webhook' && method === 'GET') return this.json(200, { url: this.webhookUrl, events: [], pending: 0, recent: [] })
    if (path === '/webhook/test') return this.json(200, { ok: true })
    if (method === 'GET' && path === '/servers') {
      const p = url.searchParams.get('player')
      return this.json(200, { servers: [...this.servers.values()].filter((s) => p === null || s.player.externalId === p) })
    }
    if (method === 'POST' && path === '/servers') {
      if ([...this.servers.values()].some((s) => s.player.externalId === body.player.externalId)) return this.err(409, 'player_limit_reached')
      const id = `srv-${++this.n}`
      const s: FakeServer = {
        id, name: `${body.name} #P7KXGQXL`, player: { id: 'P7KXGQXL', ...body.player }, state: 'installing',
        address: 'node1.example.com:25568', host: 'node1.example.com', port: 25568, serverType: body.serverType, version: body.version, eulaAccepted: false,
        session: { active: false, startedAt: null, heartbeatTimeoutSeconds: 180, maxRunSeconds: 86400, remainingSeconds: 86400 },
        deleteAt: '2026-12-18T16:00:00.000Z',
      }
      this.servers.set(id, s)
      this.used++
      const res = this.json(201, { server: s })
      s.state = 'offline'
      return res
    }
    const m = /^\/servers\/([^/]+)(?:\/([\w/.-]+))?$/.exec(path)
    const s = m ? this.servers.get(decodeURIComponent(m[1]!)) : undefined
    if (!m || !s) return this.err(404, 'not_found')
    switch (`${method} ${m[2] ?? ''}`) {
      case 'GET ': return this.json(200, { server: s })
      case 'PATCH ':
        if (body.serverType || body.version) {
          if (s.state !== 'offline' || s.session.active) return this.err(409, 'server_running')
          if (body.serverType) s.serverType = body.serverType
          if (body.version) s.version = body.version
          ;(s as unknown as Record<string, unknown>).install = { status: 'installing', serverType: s.serverType, version: s.version, build: 'x', step: 1, steps: 1, error: null }
          return this.json(200, { server: { ...s, state: 'installing' } })
        }
        s.name = `${body.name} #P7KXGQXL`
        return this.json(200, { server: s })
      case 'POST reinstall': return this.json(200, { server: { ...s, state: 'installing' } })
      case 'GET backups': return this.json(200, { limit: 2, backups: this.backups })
      case 'POST backups': {
        if (this.backups.length >= 2) return this.err(409, 'backup_limit')
        const b = { id: `b${this.backups.length + 1}`, name: body.name ?? null, bytes: 5000, state: 'ready', createdAt: '2026-11-19T07:16:26+01:00', completedAt: null }
        this.backups.push(b)
        return this.json(201, { backup: b })
      }
      case 'GET settings': return this.json(200, { settings: this.settings })
      case 'PATCH settings': Object.assign(this.settings, body); return this.json(200, { settings: this.settings })
      case 'PUT files/content': {
        const b = init?.body
        this.files.set(url.searchParams.get('path')!, typeof b === 'string' ? b : `<${(b as Uint8Array).length} bytes>`)
        return this.json(200, { ok: true })
      }
      case 'POST files/folder': return this.json(200, { ok: true })
      case 'POST backups/b1/restore':
        if (s.session.active) return this.err(409, 'server_running')
        this.restored.push('b1')
        return this.json(200, { ok: true })
      case 'GET backups/b1/download-url': return this.json(200, { id: 'b1', url: `https://${this.uploadHost}/download/backup?token=x`, expiresInSeconds: 300 })
      case 'DELETE backups/b1': this.backups = this.backups.filter((b) => b.id !== 'b1'); return this.json(200, { ok: true })
      case 'GET files/download-url': return this.json(200, { path: url.searchParams.get('path'), url: `https://${this.uploadHost}/download/file?token=x`, expiresInSeconds: 600 })
      case 'POST files/upload-url': return this.json(200, { url: `https://${this.uploadHost}/upload?token=abc` })
      case 'POST files/delete':
        for (const f of body.paths) for (const k of [...this.files.keys()]) if (k === f || k.startsWith(`${f}/`)) this.files.delete(k)
        return this.json(200, { ok: true })
      case 'POST files/decompress':
        if (!this.files.has(body.path)) return this.err(404, 'file_not_found')
        this.files.set(`/${this.zipFolder}/level.dat`, 'x')
        return this.json(200, { ok: true })
      case 'POST mods':
        this.modCalls.push(body)
        if (s.serverType === 'vanilla') return this.err(409, 'mods_unsupported')
        if (body.project === 'missing') return this.err(404, 'mod_not_found')
        this.mods.push(body.project)
        return this.json(200, { ok: true })
      case 'GET logs': if (this.notReady > 0) return this.err(503, 'service_unavailable')
        return this.json(200, { lines: ['[10:00:00 INFO]: Done (3.2s)!', '\u001b[31mred\u001b[0m'] })
      case 'GET stats': return this.json(200, { state: 'running', cpuPercent: 12.5, cpuLimitPercent: 200, memoryBytes: 100,
        memoryLimitBytes: 4096, diskBytes: 5, diskLimitBytes: 10, networkRxBytes: 1, networkTxBytes: 2, uptimeSeconds: 60,
        players: { online: 2, max: 20 } })
      case 'GET files/content': {
        const f = url.searchParams.get('path')!
        if (f === '/server.properties' && !this.files.has(f)) this.files.set(f, 'motd=A Minecraft Server\naccepts-transfers=false\n')
        return this.files.has(f) ? this.json(200, { path: f, content: this.files.get(f) }) : this.err(404, 'file_not_found')
      }
      case 'GET files': {
        const dir = (url.searchParams.get('dir') ?? '/').replace(/\/$/, '') || ''
        const seen = new Map<string, { name: string, type: string, size: number }>()
        for (const [f, c] of this.files) {
          if (!f.startsWith(`${dir}/`)) continue
          const rest = f.slice(dir.length + 1)
          const [first, ...more] = rest.split('/')
          seen.set(first!, { name: first!, type: more.length ? 'directory' : 'file', size: more.length ? 4096 : c.length })
        }
        return this.json(200, { directory: dir || '/', files: [...seen.values()].map((e) => ({ ...e, path: `${dir}/${e.name}`, modifiedAt: '2026-11-18T18:00:00Z' })) })
      }
      case 'DELETE ': this.servers.delete(s.id); this.used--; return new Response(null, { status: 204 })
      case 'POST start':
        this.startBodies.push(body)
        if (this.notReady > 0) {
          this.notReady--
          return this.err(503, 'service_unavailable')
        }
        if (!s.eulaAccepted && !body.acceptEula) return this.err(409, 'eula_required')
        s.eulaAccepted = true
        s.state = 'running'
        s.session = { ...s.session, active: true, startedAt: '2026-11-18T16:00:00.000Z', lastStopReason: null }
        return this.json(200, { server: s })
      case 'POST heartbeat':
        if (!s.session.active) return this.err(409, 'not_running', { reason: 'host_timeout' })
        return this.heartbeatBody === 'server' ? this.json(200, { server: s }) : this.json(200, { session: s.session })
      case 'POST stop':
        if (body.whenEmpty) return this.json(200, { server: s })
        s.state = 'offline'
        s.session = { ...s.session, active: false, lastStopReason: 'host_stopped' }
        return this.json(200, { server: s })
      case 'POST command':
        if (!s.session.active) return this.err(409, 'not_running')
        this.commands.push(body.command)
        return this.json(200, { ok: true })
    }
    return this.err(404, 'not_found')
  }) as typeof fetch

  private json(status: number, v: unknown) {
    return new Response(JSON.stringify(v), { status, headers: { 'Content-Type': 'application/json' } })
  }

  private err(status: number, code: string, fields?: Record<string, unknown>) {
    return this.json(status, { error: { code, message: 'x', fields } })
  }
}

const JAR = new Uint8Array([0x50, 0x4b, 3, 4, 1, 2, 3])
const sha = (b: Uint8Array) => createHash('sha256').update(b).digest('hex')

/** Release-Kanal von TRS Server Rules; ohne `version` = nicht erreichbar. */
function pluginChannel(version?: string) {
  const files = [
    { platform: 'bukkit', file: `trs-server-rules-bukkit-${version}.jar`, sha256: sha(JAR), size: JAR.length },
    { platform: 'fabric', mc: '1.21.11', file: `trs-server-rules-fabric-1.21.11-${version}.jar`, sha256: sha(JAR), size: JAR.length },
  ]
  return (async (input: string | URL | Request) => {
    const u = String(input)
    if (!version) return new Response('no', { status: 404 })
    if (u.endsWith('/server-plugin.json')) return new Response(JSON.stringify({ version, files }), { status: 200 })
    if (u.endsWith('.jar')) return new Response(JAR, { status: 200 })
    return new Response('no', { status: 404 })
  }) as typeof fetch
}

const ZONE = 'a'.repeat(32)

/** Cloudflare-DNS im Speicher (nur das, was die API braucht). */
class FakeDns {
  records = new Map<string, { name: string, type: string, data: Record<string, unknown> }>()
  foreign = new Set<string>(['lobby.trs-launcher.de'])
  private n = 0
  fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    const url = new URL(String(input))
    const method = init?.method ?? 'GET'
    const ok = (result: unknown) => new Response(JSON.stringify({ success: true, result }), { status: 200 })
    if ((init?.headers as Record<string, string>)?.Authorization !== 'Bearer cf-token') return new Response('{}', { status: 403 })
    if (url.pathname === '/client/v4/zones') return ok(url.searchParams.get('name') === 'trs-launcher.de' ? [{ id: ZONE }] : [])
    const m = /^\/client\/v4\/zones\/([0-9a-f]{32})\/dns_records(?:\/([0-9a-f]{32}))?$/.exec(url.pathname)
    if (!m || m[1] !== ZONE) return new Response('{}', { status: 404 })
    const id = m[2]
    if (method === 'GET') {
      const name = url.searchParams.get('name')!
      const hits = [...this.records.entries()].filter(([, r]) => r.name === name).map(([rid]) => ({ id: rid }))
      if (this.foreign.has(name)) hits.push({ id: 'f'.repeat(32) })
      return ok(hits)
    }
    if (method === 'DELETE' && id) {
      this.records.delete(id)
      return ok({ id })
    }
    const body = JSON.parse(String(init?.body))
    const rid = id ?? (++this.n).toString(16).padStart(32, '0')
    this.records.set(rid, { name: body.name, type: body.type, data: body.data })
    return ok({ id: rid })
  }) as typeof fetch
}

function setup(plugin?: string): { env: TestEnv, partner: FakePartner } {
  const env = makeEnv({ env: { CRAFTINGHOSTING_API_KEY: KEY, CRAFTINGHOSTING_API: 'https://partner.example.test/api/v1/partner/' } })
  const partner = new FakePartner()
  env.ctx.craftinghostingFetch = partner.fetch
  env.ctx.serverRulesFetch = pluginChannel(plugin)
  return { env, partner }
}

const body = (o: Partial<{ name: string, serverType: string, version: string, access: string }> = {}) =>
  createServerBody.parse({ name: 'Meine Welt', serverType: 'paper', version: 'latest', ...o })

describe('craftinghosting', () => {
  it('is off without a key', async () => {
    const env = makeEnv()
    const [u] = await players(env, 'Steve')
    expect(env.ctx.config.craftinghosting).toBeNull()
    expect(await codeAsync(() => craftingInfo(env.ctx))).toBe('craftinghosting_unavailable')
    expect(await codeAsync(() => craftingServer(env.ctx, u!))).toBe('craftinghosting_unavailable')
  })

  it('reports plan, types and free places', async () => {
    const { env, partner } = setup()
    const info = await craftingInfo(env.ctx)
    expect(info.plan.memoryMb).toBe(4096)
    expect(info.serverTypes[0]!.versions).toContain('1.21.11')
    expect(info.limits.maxServersPerPlayer).toBe(1)
    expect(info.full).toBe(false)
    await craftingInfo(env.ctx)
    expect(partner.calls.filter((c) => c === 'GET /me')).toHaveLength(1)
  })

  it('validates the create body', () => {
    expect(() => body({ name: 'ab' })).toThrow()
    expect(() => body({ name: 'x'.repeat(23) })).toThrow()
    expect(() => body({ name: 'Welt<script>' })).toThrow()
    expect(body().access).toBe('friends')
  })

  it('creates one server per account and strips the player suffix', async () => {
    const { env, partner } = setup()
    const [u] = await players(env, 'Steve')
    const s = await craftingCreate(env.ctx, u!, body())
    expect(s.name).toBe('Meine Welt')
    expect(s.playerId).toBe('P7KXGQXL')
    expect(s.state).toBe('installing')
    expect(s.access).toBe('friends')
    expect([...partner.servers.values()][0]!.player.externalId).toBe(u!.uuid)
    expect(await codeAsync(() => craftingCreate(env.ctx, u!, body()))).toBe('server_exists')
    expect((await craftingServer(env.ctx, u!))?.id).toBe(s.id)
  })

  it('finds an existing partner server without a local row', async () => {
    const { env } = setup()
    const [u] = await players(env, 'Steve')
    const s = await craftingCreate(env.ctx, u!, body())
    env.ctx.db.prepare('DELETE FROM craftinghosting_servers').run()
    expect((await craftingServer(env.ctx, u!))?.id).toBe(s.id)
  })

  it('needs the EULA on first start; writes the whitelist before start and ops the owner', async () => {
    const { env, partner } = setup()
    const [u, f1, f2] = await players(env, 'Steve', 'Alex', 'Notch')
    befriend(env, u!, f1!)
    await craftingCreate(env.ctx, u!, body())
    expect(await codeAsync(() => craftingStart(env.ctx, u!, false))).toBe('eula_required')
    partner.commands = []
    const s = await craftingStart(env.ctx, u!, true)
    expect(s.state).toBe('running')
    expect(s.deleteAt).toBe('2026-12-18T16:00:00.000Z')
    expect(partner.settings.whitelist).toBe(true)
    const wl = JSON.parse(partner.files.get('/whitelist.json')!)
    expect(wl.map((e: { name: string }) => e.name)).toEqual(['Alex', 'Steve'])
    expect(wl[0].uuid).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/)
    expect(partner.commands).toEqual([])

    await craftingHeartbeat(env.ctx, u!)
    expect(partner.commands).toEqual(['op Steve'])
    await craftingRestartDone(u!.uuid)

    partner.commands = []
    befriend(env, u!, f2!)
    await craftingHeartbeat(env.ctx, u!)
    expect(partner.commands).toEqual(['whitelist reload', 'whitelist on'])
    expect(JSON.parse(partner.files.get('/whitelist.json')!)).toHaveLength(3)

    partner.commands = []
    await craftingHeartbeat(env.ctx, u!)
    expect(partner.commands).toEqual([])
  })

  it('open access turns the whitelist off; rename drops the suffix', async () => {
    const { env, partner } = setup()
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body({ access: 'open' }))
    await craftingStart(env.ctx, u!, true)
    expect(partner.settings.whitelist).toBe(false)
    expect(partner.files.has('/whitelist.json')).toBe(false)
    partner.commands = []
    const s = await craftingUpdate(env.ctx, u!, { access: 'friends', name: 'Neue Welt' })
    expect(s.access).toBe('friends')
    expect(s.name).toBe('Neue Welt')
    expect(partner.settings.whitelist).toBe(true)
    expect(partner.commands).toEqual(['whitelist reload', 'whitelist on', 'op Steve'])
  })

  it('heartbeat works with either response shape and reports not_running', async () => {
    const { env, partner } = setup()
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body())
    await craftingStart(env.ctx, u!, true)
    expect((await craftingHeartbeat(env.ctx, u!)).session.active).toBe(true)
    await craftingRestartDone(u!.uuid)
    partner.heartbeatBody = 'server'
    expect((await craftingHeartbeat(env.ctx, u!)).state).toBe('running')
    expect((await craftingStop(env.ctx, u!, true)).state).toBe('running')
    const stopped = await craftingStop(env.ctx, u!, false)
    expect(stopped.session.lastStopReason).toBe('host_stopped')
    const err = await craftingHeartbeat(env.ctx, u!).catch((e) => e)
    expect(err.code).toBe('not_running')
    expect(err.details).toEqual({ reason: 'host_timeout' })
  })

  it('maps partner errors', async () => {
    const { env, partner } = setup()
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body())
    partner.fail = { status: 409, code: 'cooldown', fields: { cooldownUntil: '2026-11-18T17:59:37.077Z' } }
    const e = await craftingStart(env.ctx, u!, true).catch((x) => x)
    expect([e.status, e.code, e.details]).toEqual([409, 'cooldown', { cooldownUntil: '2026-11-18T17:59:37.077Z' }])
    partner.fail = { status: 503, code: 'maintenance' }
    expect(await codeAsync(() => craftingStart(env.ctx, u!, true))).toBe('craftinghosting_maintenance')
    partner.fail = { status: 429, code: 'rate_limited' }
    expect(await codeAsync(() => craftingStart(env.ctx, u!, true))).toBe('craftinghosting_upstream')
    partner.fail = { status: 409, code: 'partner_limit_reached' }
    expect(await codeAsync(() => craftingStart(env.ctx, u!, true))).toBe('craftinghosting_full')
    partner.fail = null
    env.ctx.config.craftinghosting = { url: 'https://partner.example.test/api/v1/partner', key: 'wrong' }
    expect(await codeAsync(() => craftingStart(env.ctx, u!, true))).toBe('craftinghosting_unavailable')
  })

  it('commands strip a leading slash; a vanished server is forgotten', async () => {
    const { env, partner } = setup()
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body())
    await craftingStart(env.ctx, u!, true)
    partner.commands = []
    await craftingCommand(env.ctx, u!, '/time set day')
    expect(partner.commands).toEqual(['time set day'])
    partner.servers.clear()
    expect(await codeAsync(() => craftingStop(env.ctx, u!, false))).toBe('no_server')
    expect(await craftingServer(env.ctx, u!)).toBeNull()
  })

  it('deletes the server', async () => {
    const { env, partner } = setup()
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body())
    await craftingDelete(env.ctx, u!)
    expect(partner.servers.size).toBe(0)
    expect(await craftingServer(env.ctx, u!)).toBeNull()
    expect(await codeAsync(() => craftingStart(env.ctx, u!, true))).toBe('no_server')
  })

  it('settings, logs and stats', async () => {
    const { env } = setup()
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body())
    expect(() => settingsBody.parse({ whitelist: true })).toThrow()
    expect(() => settingsBody.parse({ maxPlayers: 101 })).toThrow()
    expect(() => settingsBody.parse({})).toThrow()
    const set = await craftingSetSettings(env.ctx, u!, settingsBody.parse({ motd: 'Hallo', maxPlayers: 8 }))
    expect([set.motd, set.maxPlayers]).toEqual(['Hallo', 8])
    const got = await craftingSettings(env.ctx, u!)
    expect([got.motd, got.levelType, got.pvp]).toEqual(['Hallo', 'normal', true])
    expect(await craftingLogs(env.ctx, u!, 50)).toEqual(['[10:00:00 INFO]: Done (3.2s)!', 'red'])
    expect(await craftingStats(env.ctx, u!)).toEqual({
      cpuPercent: 12.5, memory: { usedBytes: 100, limitBytes: 4096 }, disk: { usedBytes: 5, limitBytes: 10 },
      network: { rxBytes: 1, txBytes: 2 }, uptimeSeconds: 60, players: { online: 2, max: 20 },
    })
  })

  it('installs server mods and skips client-only ones', async () => {
    const { env, partner } = setup()
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body({ serverType: 'fabric', version: '1.21.1' }))
    const jar = (n: string) => new TextEncoder().encode(`jar-${n}`)
    const hash = (n: string) => createHash('sha512').update(jar(n)).digest('hex')
    const version = (id: string, project: string, file: string, deps: { project_id: string, dependency_type: string }[] = []) => ({
      id, project_id: project, version_type: 'release', dependencies: deps,
      files: [{ url: `https://cdn.modrinth.com/data/${project}/versions/${id}/${file}`, filename: file, primary: true, size: jar(file).length, hashes: { sha512: hash(file) } }],
    })
    const projects = [
      { id: 'AANobbMI', slug: 'sodium', server_side: 'unsupported' },
      { id: 'P7dR8mSH', slug: 'fabric-api', server_side: 'required' },
      { id: 'uXXizFIs', slug: 'ferrite-core', server_side: 'optional' },
      { id: 'missingX', slug: 'missing', server_side: 'optional' },
      { id: 'lhGA9TYQ', slug: 'architectury-api', server_side: 'required' },
      { id: 'clientDp', slug: 'client-dep', server_side: 'unsupported' },
    ]
    const versions: Record<string, unknown[]> = {
      P7dR8mSH: [{ ...version('fapiV001', 'P7dR8mSH', 'fabric-api-0.116.jar'), version_type: 'beta' }, version('fapiV002', 'P7dR8mSH', 'fabric-api-0.115.jar')],
      uXXizFIs: [version('ferrV001', 'uXXizFIs', 'ferritecore-7.0.3.jar', [
        { project_id: 'lhGA9TYQ', dependency_type: 'required' },
        { project_id: 'clientDp', dependency_type: 'required' },
        { project_id: 'optDep01', dependency_type: 'optional' },
      ])],
      lhGA9TYQ: [version('archV001', 'lhGA9TYQ', 'architectury-13.jar')],
      missingX: [],
    }
    const seen: string[] = []
    env.ctx.modrinthFetch = (async (input: string | URL | Request) => {
      const url = new URL(String(input))
      seen.push(url.pathname)
      if (url.hostname === 'cdn.modrinth.com') return new Response(jar(url.pathname.split('/').pop()!), { status: 200 })
      if (url.pathname === '/v2/projects') {
        const ids = JSON.parse(url.searchParams.get('ids')!) as string[]
        return new Response(JSON.stringify(projects.filter((p) => ids.includes(p.id) || ids.includes(p.slug))), { status: 200 })
      }
      const m = /^\/v2\/project\/([^/]+)\/version$/.exec(url.pathname)
      if (m) {
        expect(url.searchParams.get('loaders')).toBe('["fabric"]')
        expect(url.searchParams.get('game_versions')).toBe('["1.21.1"]')
        return new Response(JSON.stringify(versions[decodeURIComponent(m[1]!)] ?? []), { status: 200 })
      }
      return new Response('[]', { status: 404 })
    }) as typeof fetch
    partner.files.set('/mods/fabric-api-0.115.jar', 'x')
    partner.uploadFail = 1
    const r = await craftingInstallMods(env.ctx, u!, ['sodium', 'fabric-api', 'ferrite-core', 'missing', 'gone'])
    expect(r.installed).toEqual(['fabric-api', 'ferrite-core'])
    expect(r.skipped).toEqual([
      { project: 'sodium', reason: 'client_only' },
      { project: 'gone', reason: 'not_found' },
      { project: 'missing', reason: 'unsupported' },
    ])
    // Start wartet auf das Hochladen.
    const started = await craftingStart(env.ctx, u!, true)
    await craftingModsDone(u!.uuid)
    expect(['starting', 'running']).toContain(started.state)
    // Release vor Beta, Abhängigkeit mit, Client-only- und optionale Abhängigkeit nicht, vorhandene Datei nicht nochmal.
    expect([...partner.uploads.keys()].sort()).toEqual(['architectury-13.jar', 'ferritecore-7.0.3.jar'])
    expect(partner.modCalls).toEqual([])
    expect(seen.some((p) => p.includes('optDep01'))).toBe(false)
  })

  it('reports mod files that could not be uploaded', async () => {
    const { env, partner } = setup()
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body({ serverType: 'fabric', version: '1.21.1' }))
    env.ctx.modrinthFetch = (async (input: string | URL | Request) => {
      const url = new URL(String(input))
      if (url.pathname === '/v2/projects') return new Response(JSON.stringify([{ id: 'uXXizFIs', slug: 'ferrite-core', server_side: 'optional' }]), { status: 200 })
      if (url.hostname === 'cdn.modrinth.com') return new Response('tampered', { status: 200 })
      return new Response(JSON.stringify([{ id: 'ferrV001', project_id: 'uXXizFIs', version_type: 'release', dependencies: [],
        files: [{ url: 'https://cdn.modrinth.com/data/x/ferritecore.jar', filename: 'ferritecore.jar', primary: true, size: 4, hashes: { sha512: 'a'.repeat(128) } }] }]), { status: 200 })
    }) as typeof fetch
    expect((await craftingInstallMods(env.ctx, u!, ['ferrite-core'])).installed).toEqual(['ferrite-core'])
    await craftingModsDone(u!.uuid)
    expect(partner.uploads.size).toBe(0)
    const s = await craftingServer(env.ctx, u!)
    const alert = s!.display!.alerts.find((a) => a.id.startsWith('mods-failed-'))
    expect(alert?.text.de).toBe('1 Mod-Dateien kamen nicht auf deinen Server: ferritecore.jar')
  })

  it('upload link for mod files from the instance; replace keeps TRS Server Rules', async () => {
    const { env, partner } = setup()
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body({ serverType: 'fabric', version: '1.21.1' }))
    for (const f of ['/mods/old-a.jar', '/mods/old-b.jar', '/mods/trs-server-rules-fabric-1.21.1-1.1.0.jar', '/mods/readme.txt', '/config/x.toml']) partner.files.set(f, 'x')
    const keep = await craftingModsUpload(env.ctx, u!, false)
    expect(keep).toMatchObject({ url: 'https://upload.craftinghosting.free/upload?token=abc', directory: '/mods', removed: 0 })
    expect(partner.files.has('/mods/old-a.jar')).toBe(true)
    const r = await craftingModsUpload(env.ctx, u!, true)
    expect(r.removed).toBe(2)
    expect([...partner.files.keys()].filter((f) => f.startsWith('/mods/')).sort())
      .toEqual(['/mods/readme.txt', '/mods/trs-server-rules-fabric-1.21.1-1.1.0.jar'])
    await craftingStart(env.ctx, u!, true)
    expect(await codeAsync(() => craftingModsUpload(env.ctx, u!, true))).toBe('server_running')
    partner.uploadHost = 'evil.example.com'
    await craftingStop(env.ctx, u!, false)
    expect(await codeAsync(() => craftingModsUpload(env.ctx, u!, false))).toBe('craftinghosting_upstream')
  })

  it('mods need a mod or plugin server and Modrinth', async () => {
    const { env } = setup()
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body({ serverType: 'vanilla' }))
    env.ctx.modrinthFetch = (async () => new Response('[]', { status: 200 })) as typeof fetch
    expect(await codeAsync(() => craftingInstallMods(env.ctx, u!, ['lithium']))).toBe('mods_unsupported')
  })

  it('world upload only to the partner host; import needs a stopped server', async () => {
    const { env, partner } = setup()
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body())
    expect((await craftingWorldUpload(env.ctx, u!)).url).toBe('https://upload.craftinghosting.free/upload?token=abc')
    partner.uploadHost = 'evil.example.com'
    expect(await codeAsync(() => craftingWorldUpload(env.ctx, u!))).toBe('craftinghosting_upstream')
    expect(await codeAsync(() => craftingWorldImport(env.ctx, u!))).toBe('file_not_found')
    partner.files.set('/trs-world.zip', 'zip')
    await craftingStart(env.ctx, u!, true)
    expect(await codeAsync(() => craftingWorldImport(env.ctx, u!))).toBe('server_running')
    await craftingStop(env.ctx, u!, false)
    await craftingWorldImport(env.ctx, u!)
    expect(partner.files.has('/trs-world.zip')).toBe(false)
    expect(partner.files.has('/world/level.dat')).toBe(true)
  })

  it('first setup installs TRS Server Rules with the MOTD footer and restarts once', async () => {
    const { env, partner } = setup('1.1.0')
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body({ name: 'Meine Welt', serverType: 'paper' }))
    await craftingStart(env.ctx, u!, true)
    partner.calls = []
    const s = await craftingHeartbeat(env.ctx, u!)
    expect(s.setup).toBe('pending')
    expect(['restarting', 'done']).toContain((await craftingServer(env.ctx, u!))?.setup)
    await craftingRestartDone(u!.uuid)
    expect(partner.files.get('/plugins/trs-server-rules-bukkit-1.1.0.jar')).toBe('<7 bytes>')
    expect(partner.files.get('/plugins/TRSServerRules/hosting.yml')).toContain(`motd-footer: "${MOTD_FOOTER}"`)
    expect(partner.settings.motd).toBe('Meine Welt')
    expect(partner.calls.filter((c) => c.endsWith('/stop') || c.endsWith('/start'))).toHaveLength(2)
    const after = await craftingServer(env.ctx, u!)
    expect([after?.setup, after?.state]).toEqual(['done', 'running'])
    partner.calls = []
    await craftingHeartbeat(env.ctx, u!)
    expect(partner.calls.some((c) => c.includes('/files/content'))).toBe(false)

    await craftingSetSettings(env.ctx, u!, settingsBody.parse({ motd: 'Nur Freunde' }))
    expect(partner.settings.motd).toBe('Nur Freunde')
    expect((await craftingSettings(env.ctx, u!)).motd).toBe('Nur Freunde')
  })

  it('without a fitting plugin the footer goes onto the single MOTD line', async () => {
    const { env, partner } = setup('1.0.0')
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body({ name: 'Meine Welt', serverType: 'vanilla' }))
    await craftingStart(env.ctx, u!, true)
    await craftingHeartbeat(env.ctx, u!)
    await craftingRestartDone(u!.uuid)
    expect([...partner.files.keys()].some((f) => f.endsWith('.jar'))).toBe(false)
    expect(partner.settings.motd).toBe(`Meine Welt ${MOTD_FOOTER}`)
    expect((partner.settings.motd as string).length).toBeLessThanOrEqual(120)
    expect((await craftingSettings(env.ctx, u!)).motd).toBe('Meine Welt')
    await craftingSetSettings(env.ctx, u!, settingsBody.parse({ motd: 'x'.repeat(58) }))
    expect(partner.settings.motd).toBe(`${'x'.repeat(58)} ${MOTD_FOOTER}`)
    expect((partner.settings.motd as string).length).toBeLessThanOrEqual(120)
    expect(() => settingsBody.parse({ motd: 'x'.repeat(59) })).toThrow()
    expect(() => settingsBody.parse({ motd: '§cRot' })).toThrow()
  })

  it('heartbeats during the setup restart do not end the session in the launcher', async () => {
    const { env } = setup()
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body())
    await craftingServer(env.ctx, u!)
    await craftingStart(env.ctx, u!, true)
    await craftingHeartbeat(env.ctx, u!)
    const during = await craftingHeartbeat(env.ctx, u!)
    expect(during.setup).toBe('restarting')
    await craftingRestartDone(u!.uuid)
  })

  it('team overview lists all servers with owner, stop and delete', async () => {
    const { env, partner } = setup()
    const [u, v] = await players(env, 'Steve', 'Alex')
    const s1 = await craftingCreate(env.ctx, u!, body({ name: 'Welt A' }))
    await craftingCreate(env.ctx, v!, body({ name: 'Welt B', access: 'open' }))
    await craftingStart(env.ctx, u!, true)
    const list = await adminCraftingServers(env.ctx)
    expect(list.usage).toEqual({ used: 2, max: 100 })
    expect(list.servers.map((s) => [s.name, s.owner?.name, s.access])).toEqual([['Welt A', 'Steve', 'friends'], ['Welt B', 'Alex', 'open']])
    expect(await adminCraftingStop(env.ctx, s1.id)).toBe(u!.uuid)
    expect(partner.servers.get(s1.id)!.state).toBe('offline')
    expect(await adminCraftingDelete(env.ctx, s1.id)).toBe(u!.uuid)
    expect(partner.servers.has(s1.id)).toBe(false)
    expect(await craftingServer(env.ctx, u!)).toBeNull()
    expect((await adminCraftingServers(env.ctx)).servers).toHaveLength(1)
  })

  it('own address: check, SRV record on create, change and delete', async () => {
    const env = makeEnv({ env: { CRAFTINGHOSTING_API_KEY: KEY, CRAFTINGHOSTING_API: 'https://partner.example.test/api/v1/partner/',
      CLOUDFLARE_DNS_TOKEN: 'cf-token' } })
    const partner = new FakePartner()
    const dns = new FakeDns()
    env.ctx.craftinghostingFetch = partner.fetch
    env.ctx.serverRulesFetch = pluginChannel()
    env.ctx.cloudflareFetch = dns.fetch
    const [u, v] = await players(env, 'Steve', 'Alex')

    expect(await craftingCheckSubdomain(env.ctx, u!.uuid, 'Meine-Welt')).toEqual({ name: 'meine-welt', available: true, reason: null })
    expect((await craftingCheckSubdomain(env.ctx, u!.uuid, 'ab')).reason).toBe('invalid')
    expect((await craftingCheckSubdomain(env.ctx, u!.uuid, '-abc')).reason).toBe('invalid')
    expect((await craftingCheckSubdomain(env.ctx, u!.uuid, 'www')).reason).toBe('reserved')
    expect((await craftingCheckSubdomain(env.ctx, u!.uuid, 'lobby')).reason).toBe('taken')

    const s = await craftingCreate(env.ctx, u!, body({ name: 'Meine Welt', subdomain: 'meinewelt' }))
    expect(s.domain).toBe('meinewelt.trs-launcher.de')
    expect((await craftingServer(env.ctx, u!))?.address).toBe('node1.example.com:25568')
    const rec = [...dns.records.values()][0]!
    expect(rec).toEqual({ name: '_minecraft._tcp.meinewelt.trs-launcher.de', type: 'SRV',
      data: { priority: 0, weight: 5, port: 25568, target: 'node1.example.com' } })
    expect((await craftingCheckSubdomain(env.ctx, v!.uuid, 'meinewelt')).reason).toBe('taken')
    expect((await craftingCheckSubdomain(env.ctx, u!.uuid, 'meinewelt')).available).toBe(true)
    expect(await codeAsync(() => craftingCreate(env.ctx, v!, body({ subdomain: 'meinewelt' })))).toBe('subdomain_taken')

    // Ohne Wunschname: aus dem Servernamen abgeleitet
    const t2 = await craftingCreate(env.ctx, v!, body({ name: 'Meine Welt' }))
    expect(t2.domain).toBe('meine-welt.trs-launcher.de')

    // Node-Wechsel beim Partner zieht den Eintrag nach
    const ps = partner.servers.get(s.id)!
    ps.port = 25570
    await craftingServer(env.ctx, u!)
    expect([...dns.records.values()].find((r) => r.name.includes('meinewelt'))!.data.port).toBe(25570)

    // Adresse ändern
    const changed = await craftingUpdate(env.ctx, u!, { subdomain: 'neuewelt' })
    expect(changed.domain).toBe('neuewelt.trs-launcher.de')
    expect([...dns.records.values()].map((r) => r.name).sort()).toEqual([
      '_minecraft._tcp.meine-welt.trs-launcher.de', '_minecraft._tcp.neuewelt.trs-launcher.de'])
    expect(await codeAsync(() => craftingUpdate(env.ctx, u!, { subdomain: 'api' }))).toBe('subdomain_reserved')

    // Altbestand ohne Adresse bekommt beim Abruf eine
    env.ctx.db.prepare("UPDATE craftinghosting_servers SET subdomain = NULL, dns_record = NULL, dns_target = NULL WHERE uuid = ?").run(v!.uuid)
    dns.records.forEach((r, k) => { if (r.name.includes('meine-welt')) dns.records.delete(k) })
    expect((await craftingServer(env.ctx, v!))?.domain).toBe('meine-welt.trs-launcher.de')

    // Löschen entfernt den Eintrag
    await craftingDelete(env.ctx, u!)
    expect([...dns.records.values()].map((r) => r.name)).toEqual(['_minecraft._tcp.meine-welt.trs-launcher.de'])
  })

  it('without a DNS token the address check is unavailable but creating works', async () => {
    const { env } = setup()
    const [u] = await players(env, 'Steve')
    expect(await codeAsync(() => craftingCheckSubdomain(env.ctx, u!.uuid, 'meinewelt'))).toBe('dns_unavailable')
    const s = await craftingCreate(env.ctx, u!, body({ subdomain: 'meinewelt' }))
    expect(s.domain).toBeNull()
  })
})

describe('craftinghosting routes: website session', () => {
  const json = { 'content-type': 'application/json' }

  async function owner() {
    const { env, partner } = setup()
    const auth = await login(env, 'Steve')
    await craftingCreate(env.ctx, getUser(env.ctx, auth.user.uuid)!, body())
    const web = createWebSession(env.ctx, auth.user.uuid)
    return { env, partner, bearer: { authorization: `Bearer ${auth.token}` }, cookie: { cookie: `trs_session=${web.token}` }, csrf: web.csrf, uuid: auth.user.uuid }
  }

  it('reads the own server with the cookie, without CSRF', async () => {
    const { env, cookie } = await owner()
    const res = await callRoute(env, serverGet, { url: '/v1/craftinghosting/server', headers: cookie })
    expect(res.status).toBe(200)
    expect(res.body).toMatchObject({ server: { name: 'Meine Welt' } })
    expect((await callRoute(env, infoRoute, { url: '/v1/craftinghosting/info', headers: cookie })).status).toBe(200)
  })

  it('needs the CSRF token for cookie POST/PATCH', async () => {
    const { env, partner, cookie, csrf } = await owner()
    const url = '/v1/craftinghosting/server/start'
    const start = JSON.stringify({ acceptEula: true })
    expect(await callRoute(env, startRoute, { method: 'POST', url, headers: { ...cookie, ...json }, body: start }))
      .toMatchObject({ status: 403, error: { code: 'csrf_failed' } })
    expect(await callRoute(env, startRoute, { method: 'POST', url, headers: { ...cookie, ...json, 'x-csrf-token': 'nope' }, body: start }))
      .toMatchObject({ status: 403, error: { code: 'csrf_failed' } })
    expect(partner.calls).not.toContain('POST /servers/srv-1/start')

    const ok = await callRoute(env, startRoute, { method: 'POST', url, headers: { ...cookie, ...json, 'x-csrf-token': csrf }, body: start })
    expect(ok.status).toBe(200)
    expect(ok.body).toMatchObject({ server: { state: 'running' } })
    const hb = await callRoute(env, heartbeatRoute, { method: 'POST', url: '/v1/craftinghosting/server/heartbeat', headers: { ...cookie, 'x-csrf-token': csrf } })
    expect(hb.status).toBe(200)

    const patch = JSON.stringify({ access: 'open' })
    expect(await callRoute(env, serverPatch, { method: 'PATCH', url: '/v1/craftinghosting/server', headers: { ...cookie, ...json }, body: patch }))
      .toMatchObject({ status: 403, error: { code: 'csrf_failed' } })
    const patched = await callRoute(env, serverPatch, { method: 'PATCH', url: '/v1/craftinghosting/server', headers: { ...cookie, ...json, 'x-csrf-token': csrf }, body: patch })
    expect(patched.body).toMatchObject({ server: { access: 'open' } })
  })

  it('bearer still works; a bearer header is never mixed with the cookie', async () => {
    const { env, bearer, cookie, csrf } = await owner()
    expect((await callRoute(env, serverGet, { url: '/v1/craftinghosting/server', headers: bearer })).status).toBe(200)
    const start = await callRoute(env, startRoute, { method: 'POST', url: '/v1/craftinghosting/server/start', headers: { ...bearer, ...json }, body: '{"acceptEula":true}' })
    expect(start.status).toBe(200)
    const mixed = await callRoute(env, serverGet, { url: '/v1/craftinghosting/server', headers: { ...cookie, 'x-csrf-token': csrf, authorization: 'Bearer invalid' } })
    expect(mixed.status).toBe(401)
    expect((await callRoute(env, serverGet, { url: '/v1/craftinghosting/server' })).status).toBe(401)
    expect((await callRoute(env, serverGet, { url: '/v1/craftinghosting/server', headers: { cookie: 'trs_session=nope' } })).status).toBe(401)
  })

  it('cookie and bearer share the per-account limit', async () => {
    const { env, bearer, cookie, uuid } = await owner()
    for (let i = 0; i < RULES.craftinghostingUser.limit; i++) env.ctx.limiter.take(`craftinghosting:${uuid}`, RULES.craftinghostingUser)
    expect((await callRoute(env, serverGet, { url: '/v1/craftinghosting/server', headers: cookie })).status).toBe(429)
    expect((await callRoute(env, serverGet, { url: '/v1/craftinghosting/server', headers: bearer })).status).toBe(429)
  })

  it('TRS Wake: SRV points to the relay while asleep, to the node while running; wake rules', async () => {
    const env = makeEnv({ env: { CRAFTINGHOSTING_API_KEY: KEY, CRAFTINGHOSTING_API: 'https://partner.example.test/api/v1/partner/',
      CLOUDFLARE_DNS_TOKEN: 'cf-token', WAKE_IP: '116.203.12.169' } })
    const partner = new FakePartner()
    const dns = new FakeDns()
    env.ctx.craftinghostingFetch = partner.fetch
    env.ctx.serverRulesFetch = pluginChannel()
    env.ctx.cloudflareFetch = dns.fetch
    const [u, friend, stranger] = await players(env, 'Steve', 'Alex', 'Notch')
    befriend(env, u!, friend!)
    const srv = () => [...dns.records.values()].find((r) => r.type === 'SRV')!
    const a = () => [...dns.records.values()].find((r) => r.type === 'A')

    const s = await craftingCreate(env.ctx, u!, body({ subdomain: 'welt' }))
    expect(s.domain).toBe('welt.trs-launcher.de')
    expect(a()?.name).toBe('welt.trs-launcher.de')
    expect(srv().data).toEqual({ priority: 0, weight: 5, port: 25565, target: 'welt.trs-launcher.de' })

    // Unbekannt / falsche Domain
    expect(await codeAsync(() => relayResolve(env.ctx, 'nope.trs-launcher.de'))).toBe('unknown_host')
    expect(await codeAsync(() => relayResolve(env.ctx, 'welt.example.com'))).toBe('unknown_host')

    // Vor der EULA darf niemand aufwecken
    expect((await relayResolve(env.ctx, 'Welt.trs-launcher.de.')).state).toBe('offline')
    expect(await codeAsync(() => relayWake(env.ctx, 'welt.trs-launcher.de', { name: 'Steve' }))).toBe('eula_required')
    await craftingStart(env.ctx, u!, true)
    await craftingHeartbeat(env.ctx, u!)
    await craftingRestartDone(u!.uuid)
    await craftingStop(env.ctx, u!, false)
    await sweepCraftingDns(env.ctx)
    expect(srv().data.target).toBe('welt.trs-launcher.de')

    // Nur Freunde: Fremde nicht, Freunde ja
    expect(await codeAsync(() => relayWake(env.ctx, 'welt.trs-launcher.de', { name: 'Notch' }))).toBe('not_allowed')
    expect((await relayWake(env.ctx, 'welt.trs-launcher.de', { name: 'alex' })).state).toBe('starting')
    expect(partner.files.get('/server.properties')).toContain('accepts-transfers=true')
    const r = await relayResolve(env.ctx, 'welt.trs-launcher.de')
    expect(r.state).toBe('running')
    expect(r.target).toEqual({ host: 'node1.example.com', port: 25568 })
    expect(srv().data).toEqual({ priority: 0, weight: 5, port: 25568, target: 'node1.example.com' })

    // Schläft wieder ein → Sweep schaltet zurück
    await craftingStop(env.ctx, u!, false)
    await sweepCraftingDns(env.ctx)
    expect(srv().data.target).toBe('welt.trs-launcher.de')

    // Pflichtpause
    partner.servers.get(s.id)!.session = { ...partner.servers.get(s.id)!.session, cooldownUntil: new Date(Date.now() + 3600_000).toISOString() }
    expect((await relayResolve(env.ctx, 'welt.trs-launcher.de')).state).toBe('cooldown')
    expect(await codeAsync(() => relayWake(env.ctx, 'welt.trs-launcher.de', { name: 'Alex' }))).toBe('cooldown')
    void stranger

    // Löschen räumt SRV und A
    await craftingDelete(env.ctx, u!)
    expect(dns.records.size).toBe(0)
  })

  it('files, server.properties and plugins', async () => {
    const { env, partner } = setup('1.1.0')
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body({ name: 'Meine Welt', serverType: 'paper' }))
    await craftingStart(env.ctx, u!, true)
    await craftingHeartbeat(env.ctx, u!)
    await craftingRestartDone(u!.uuid)
    partner.files.set('/server.properties', '#Minecraft\nmotd=Meine Welt\nserver-port=25011\ndifficulty=easy\naccepts-transfers=true\nwhite-list=true\n')
    partner.files.set('/plugins/Chunky-1.4.jar', 'jar')
    partner.files.set('/plugins/Chunky/config.yml', 'a: 1\n')

    // Liste mit Schutz
    const root = await craftingFiles(env.ctx, u!, '/')
    expect(root.entries.find((e) => e.name === 'plugins')).toMatchObject({ type: 'directory', protected: true })
    expect(root.entries.find((e) => e.name === 'server.properties')).toMatchObject({ editable: true, protected: false })
    const plugins = await craftingFiles(env.ctx, u!, '/plugins')
    expect(plugins.entries.find((e) => e.name === 'trs-server-rules-bukkit-1.1.0.jar')?.protected).toBe(true)
    expect(plugins.entries.find((e) => e.name === 'trs-server-rules-bukkit-1.1.0.jar')?.lock).toBe('trs')
    expect(plugins.entries.find((e) => e.name === 'Chunky-1.4.jar')?.editable).toBe(false)

    // Lesen/Schreiben/Schutz
    expect((await craftingReadFile(env.ctx, u!, '/plugins/Chunky/config.yml')).content).toBe('a: 1\n')
    expect(await codeAsync(() => craftingReadFile(env.ctx, u!, '/plugins/Chunky-1.4.jar'))).toBe('file_not_text')
    await craftingWriteFile(env.ctx, u!, '/plugins/Chunky/config.yml', 'a: 2\n')
    expect(partner.files.get('/plugins/Chunky/config.yml')).toBe('a: 2\n')
    expect(await codeAsync(() => craftingWriteFile(env.ctx, u!, '/plugins/TRSServerRules/hosting.yml', 'x'))).toBe('file_protected')
    expect(await codeAsync(() => craftingDeleteFiles(env.ctx, u!, ['/plugins']))).toBe('file_protected')
    expect(await codeAsync(() => craftingDeleteFiles(env.ctx, u!, ['/whitelist.json']))).toBe('file_protected')

    // server.properties von Hand: gesperrte Schlüssel bleiben, MOTD über die Regel
    await craftingWriteFile(env.ctx, u!, '/server.properties', 'motd=Neu\nserver-port=1\ndifficulty=hard\naccepts-transfers=false\n')
    const sp = partner.files.get('/server.properties')!
    expect(sp).toContain('server-port=25011')
    expect(sp).toContain('difficulty=hard')
    expect(sp).toContain('accepts-transfers=true')
    expect(partner.settings.motd).toBe('Neu')

    // Alle Einstellungen
    const props = await craftingProperties(env.ctx, u!)
    expect(props.properties.motd).toBe('Neu')
    expect(props.locked).toEqual(expect.arrayContaining(['server-port', 'accepts-transfers']))
    expect(await codeAsync(() => craftingSetProperties(env.ctx, u!, { 'server-port': '2' }))).toBe('property_locked')
    const after = await craftingSetProperties(env.ctx, u!, { 'max-players': '8', 'level-name': 'welt:2', motd: 'Hallo' })
    expect(after.properties['max-players']).toBe('8')
    expect(partner.files.get('/server.properties')).toContain('level-name=welt\\:2')
    expect(after.properties['level-name']).toBe('welt:2')
    expect(partner.settings.motd).toBe('Hallo')

    // Plugins
    const addons = await craftingAddons(env.ctx, u!)
    expect(addons.kind).toBe('plugins')
    expect(addons.items.map((i) => [i.file, i.protected])).toEqual([['Chunky-1.4.jar', false], ['trs-server-rules-bukkit-1.1.0.jar', true]])
    await craftingRemoveAddon(env.ctx, u!, 'Chunky-1.4.jar')
    expect(partner.files.has('/plugins/Chunky-1.4.jar')).toBe(false)
    expect(await codeAsync(() => craftingRemoveAddon(env.ctx, u!, 'trs-server-rules-bukkit-1.1.0.jar'))).toBe('file_protected')
  })

  it('worlds: list, activate, delete, download links, import under a name', async () => {
    const { env, partner } = setup()
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body({ serverType: 'paper' }))
    await craftingStart(env.ctx, u!, true)
    await craftingHeartbeat(env.ctx, u!)
    await craftingRestartDone(u!.uuid)
    await craftingStop(env.ctx, u!, false)
    partner.files.set('/server.properties', 'level-name=world\naccepts-transfers=true\n')
    for (const f of ['/world/level.dat', '/world/region/r.0.0.mca', '/world_nether/DIM-1/region/r.0.0.mca', '/world_nether/level.dat',
      '/world_the_end/level.dat', '/lobby/level.dat', '/lobby/region/r.0.0.mca', '/plugins/x.jar']) partner.files.set(f, 'data')

    const w = await craftingWorlds(env.ctx, u!)
    expect(w.active).toBe('world')
    expect(w.worlds.map((x) => [x.name, x.active, x.dimensions])).toEqual([
      ['lobby', false, ['lobby']],
      ['world', true, ['world', 'world_nether', 'world_the_end']],
    ])

    const files = await craftingWorldFiles(env.ctx, u!, 'world')
    expect(files.files.map((f) => f.path).sort()).toEqual([
      '/world/level.dat', '/world/region/r.0.0.mca', '/world_nether/DIM-1/region/r.0.0.mca', '/world_nether/level.dat', '/world_the_end/level.dat'])
    const urls = await craftingDownloadUrls(env.ctx, u!, ['/world/level.dat'])
    expect(urls.urls[0]!.url).toMatch(/^https:\/\/upload\.craftinghosting\.free\//)
    expect(await codeAsync(() => craftingDownloadUrls(env.ctx, u!, ['/../etc/passwd']))).not.toBe('ok')

    await craftingActivateWorld(env.ctx, u!, 'lobby')
    expect(partner.files.get('/server.properties')).toContain('level-name=lobby')
    await craftingDeleteWorld(env.ctx, u!, 'world')
    expect([...partner.files.keys()].some((f) => f.startsWith('/world'))).toBe(false)

    partner.zipFolder = 'insel'
    partner.files.set('/trs-world.zip', 'zip')
    await craftingWorldImport(env.ctx, u!, 'insel', true)
    expect(partner.files.has('/insel/level.dat')).toBe(true)
    expect(partner.files.get('/server.properties')).toContain('level-name=insel')

    await craftingStart(env.ctx, u!, false)
    expect(await codeAsync(() => craftingDeleteWorld(env.ctx, u!, 'lobby'))).toBe('server_running')
  })

  it('server software is read-only; worlds cannot be named like system folders; pinned versions', async () => {
    const { env, partner } = setup()
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body({ serverType: 'paper' }))
    await craftingStart(env.ctx, u!, true)
    await craftingHeartbeat(env.ctx, u!)
    await craftingRestartDone(u!.uuid)
    for (const f of ['/server.jar', '/eula.txt', '/libraries/a/b.jar', '/versions/1.21.11/paper.jar', '/logs/latest.log',
      '/bukkit.yml', '/permissions.yml', '/config/paper-global.yml']) partner.files.set(f, 'x')
    for (const f of ['/eula.txt', '/libraries/a/b.txt', '/logs/latest.log', '/versions/x.json']) {
      expect(await codeAsync(() => craftingWriteFile(env.ctx, u!, f, 'y'))).toBe('file_protected')
    }
    for (const f of ['/server.jar', '/eula.txt', '/libraries/a/b.jar', '/logs/latest.log']) {
      expect(await codeAsync(() => craftingDeleteFiles(env.ctx, u!, [f]))).toBe('file_protected')
    }
    expect(await codeAsync(() => craftingDeleteFiles(env.ctx, u!, ['/libraries']))).toBe('file_protected')
    await craftingWriteFile(env.ctx, u!, '/bukkit.yml', 'ok')
    await craftingWriteFile(env.ctx, u!, '/permissions.yml', 'ok')
    await craftingWriteFile(env.ctx, u!, '/config/paper-global.yml', 'ok')
    const root = await craftingFiles(env.ctx, u!, '/')
    expect(root.entries.find((e) => e.name === 'eula.txt')).toMatchObject({ protected: true, editable: false, viewable: true, lock: 'system' })
    expect(root.entries.find((e) => e.name === 'bukkit.yml')).toMatchObject({ protected: false, editable: true, lock: null })
    expect(root.entries.find((e) => e.name === 'libraries')).toMatchObject({ protected: true, lock: 'system' })
    expect(root.writable).toBe(true)
    expect((await craftingFiles(env.ctx, u!, '/libraries')).writable).toBe(false)
    await craftingStop(env.ctx, u!, false)
    expect(() => worldNameQuery.parse({ name: 'libraries' })).toThrow()
    expect(() => worldNameQuery.parse({ name: 'Plugins' })).toThrow()
    expect(worldNameQuery.parse({ name: 'lobby' }).name).toBe('lobby')

    const parsed = modsBody.parse({ versions: [{ project: 'chunky', version: 'AbCdEf12' }] })
    const jar = new TextEncoder().encode('chunky')
    const asked: string[] = []
    env.ctx.modrinthFetch = (async (input: string | URL | Request) => {
      const url = new URL(String(input))
      asked.push(url.pathname)
      if (url.pathname === '/v2/projects') return new Response(JSON.stringify([{ id: 'fALzjamp', slug: 'chunky', server_side: 'required' }]), { status: 200 })
      if (url.pathname === '/v2/tag/game_version') return new Response(JSON.stringify([{ version: '26.1-snapshot-1', version_type: 'snapshot' }, { version: '1.21.11', version_type: 'release' }]), { status: 200 })
      if (url.pathname === '/v2/version/AbCdEf12') {
        return new Response(JSON.stringify({ id: 'AbCdEf12', project_id: 'fALzjamp', version_type: 'release', dependencies: [],
          files: [{ url: 'https://cdn.modrinth.com/data/fALzjamp/versions/AbCdEf12/Chunky-1.4.jar', filename: 'Chunky-1.4.jar', primary: true, size: jar.length,
            hashes: { sha512: createHash('sha512').update(jar).digest('hex') } }] }), { status: 200 })
      }
      if (url.hostname === 'cdn.modrinth.com') return new Response(jar, { status: 200 })
      return new Response('[]', { status: 404 })
    }) as typeof fetch
    expect((await craftingInstallMods(env.ctx, u!, parsed.projects, parsed.versions)).installed).toEqual(['chunky'])
    await craftingModsDone(u!.uuid)
    expect([...partner.uploads.keys()]).toEqual(['Chunky-1.4.jar'])
    expect(asked).not.toContain('/v2/project/fALzjamp/version')
  })

  it('feature switches come from the API; switched-off functions answer feature_disabled', async () => {
    const { env } = setup()
    const [u, staff] = await players(env, 'Steve', 'Mod')
    const info = await craftingInfo(env.ctx)
    expect(info.features).toMatchObject({ files: true, worlds: true, versionChange: true, backups: true, ownAddress: false })
    expect(info.notice).toBeNull()
    await craftingCreate(env.ctx, u!, body())
    adminSetCraftingConfig(env.ctx, staff!.uuid, { features: { versionChange: false, backups: false } })
    expect(await codeAsync(() => craftingChangeVersion(env.ctx, u!, versionBody.parse({ serverType: 'paper', version: '1.21.4' })))).toBe('feature_disabled')
    expect(await codeAsync(() => craftingBackups(env.ctx, u!))).toBe('feature_disabled')
    const on = makeEnv({ env: { CRAFTINGHOSTING_API_KEY: KEY, CRAFTINGHOSTING_FEATURES: 'versionChange, backups, bad-name!', CRAFTINGHOSTING_NOTICE: 'Wartung heute 22 Uhr' } })
    expect([...on.ctx.config.craftinghostingFeatures].sort()).toEqual(['backups', 'versionChange'])
    expect(on.ctx.config.craftinghostingNotice).toBe('Wartung heute 22 Uhr')
  })

  it('team: markdown notice, maintenance, feature switches, blocking a server', async () => {
    const { env, partner } = setup()
    const [u, staff] = await players(env, 'Steve', 'Mod')
    const s = await craftingCreate(env.ctx, u!, body())

    adminSetCraftingConfig(env.ctx, staff!.uuid, { notice: { markdown: '**Neu:** Welten-Download', level: 'success' } })
    expect((await craftingInfo(env.ctx)).notice).toMatchObject({ markdown: '**Neu:** Welten-Download', level: 'success' })
    adminSetCraftingConfig(env.ctx, staff!.uuid, { notice: null })
    expect((await craftingInfo(env.ctx)).notice).toBeNull()

    adminSetCraftingConfig(env.ctx, staff!.uuid, { features: { files: false, versionChange: true, ownAddress: true } })
    const info = await craftingInfo(env.ctx)
    expect(info.features).toMatchObject({ files: false, versionChange: true, ownAddress: false })
    expect(await codeAsync(() => craftingFiles(env.ctx, u!, '/'))).toBe('feature_disabled')
    adminSetCraftingConfig(env.ctx, staff!.uuid, { features: { files: null, versionChange: null } })
    expect(adminCraftingConfig(env.ctx).overrides).toEqual({ ownAddress: true })

    adminSetCraftingConfig(env.ctx, staff!.uuid, { maintenance: { active: true, message: 'Bis 22 Uhr' } })
    expect((await craftingInfo(env.ctx)).maintenance).toEqual({ active: true, message: 'Bis 22 Uhr' })
    const e = await craftingStart(env.ctx, u!, true).catch((x) => x)
    expect([e.status, e.code, e.details]).toEqual([503, 'hosting_maintenance', { message: 'Bis 22 Uhr' }])
    adminSetCraftingConfig(env.ctx, staff!.uuid, { maintenance: { active: false, message: null } })

    await craftingStart(env.ctx, u!, true)
    expect(await adminCraftingBlock(env.ctx, s.id, staff!.uuid, 'Werbung für fremden Server')).toBe(u!.uuid)
    expect(partner.servers.get(s.id)!.state).toBe('offline')
    expect((await craftingServer(env.ctx, u!))?.blocked?.reason).toBe('Werbung für fremden Server')
    const b = await craftingStart(env.ctx, u!, true).catch((x) => x)
    expect([b.code, b.details]).toEqual(['server_blocked', { reason: 'Werbung für fremden Server' }])
    await adminCraftingBlock(env.ctx, s.id, staff!.uuid, null)
    expect((await craftingServer(env.ctx, u!))?.blocked).toBeNull()
    await craftingStart(env.ctx, u!, true)
    void wakeForTest
  })

  it('version change, reinstall, backups through the partner', async () => {
    const { env, partner } = setup()
    const [u] = await players(env, 'Steve')
    const s = await craftingCreate(env.ctx, u!, body({ serverType: 'paper' }))
    await craftingStart(env.ctx, u!, true)
    expect(await codeAsync(() => craftingChangeVersion(env.ctx, u!, versionBody.parse({ serverType: 'fabric', version: '1.21.11' })))).toBe('server_running')
    await craftingStop(env.ctx, u!, false)
    const v = await craftingChangeVersion(env.ctx, u!, versionBody.parse({ serverType: 'fabric', version: '1.21.11' }))
    expect([v.serverType, v.version, v.state, v.install?.status]).toEqual(['fabric', '1.21.11', 'installing', 'installing'])
    expect((await craftingInfo(env.ctx)).features).toMatchObject({ versionChange: true, backups: true })
    partner.servers.get(s.id)!.state = 'offline'
    expect((await craftingReinstall(env.ctx, u!)).state).toBe('installing')

    expect(await craftingBackupAction(env.ctx, u!, 'create', undefined, 'Vor dem Update')).toEqual({ ok: true, id: 'b1' })
    const list = await craftingBackups(env.ctx, u!)
    expect(list).toEqual({ max: 2, backups: [{ id: 'b1', name: 'Vor dem Update', size: 5000, createdAt: '2026-11-19T06:16:26.000Z', state: 'ready' }] })
    await craftingBackupAction(env.ctx, u!, 'restore', 'b1')
    expect(partner.restored).toEqual(['b1'])
    expect((await craftingBackupAction(env.ctx, u!, 'download', 'b1') as { url: string }).url).toMatch(/^https:\/\/upload\.craftinghosting\.free\//)
    await craftingBackupAction(env.ctx, u!, 'delete', 'b1')
    expect(partner.backups).toEqual([])
  })

  it('webhooks: setup stores the secret, signature check, events re-sync DNS', async () => {
    const env = makeEnv({ env: { CRAFTINGHOSTING_API_KEY: KEY, CRAFTINGHOSTING_API: 'https://partner.example.test/api/v1/partner/',
      CLOUDFLARE_DNS_TOKEN: 'cf-token', WAKE_IP: '116.203.12.169' } })
    const partner = new FakePartner()
    const dns = new FakeDns()
    env.ctx.craftinghostingFetch = partner.fetch
    env.ctx.serverRulesFetch = pluginChannel()
    env.ctx.cloudflareFetch = dns.fetch
    const [u, staff] = await players(env, 'Steve', 'Mod')
    const s = await craftingCreate(env.ctx, u!, body({ subdomain: 'welt' }))
    const st = await adminWebhook(env.ctx, 'setup', staff!.uuid)
    expect(st.configured).toBe(true)
    expect(partner.webhookUrl).toBe('https://api.trs-launcher.de/v1/internal/craftinghosting/webhook')
    const viaEnv = makeEnv({ env: { CRAFTINGHOSTING_API_KEY: KEY, CRAFTINGHOSTING_WEBHOOK_SECRET: 'whsec_fromenv12345' } })
    expect(webhookSecret(viaEnv.ctx)).toBe('whsec_fromenv12345')

    const raw = Buffer.from(JSON.stringify({ id: 'd1', type: 'server.running', data: { serverId: s.id } }))
    const ts = String(Math.floor(Date.now() / 1000))
    const { createHmac } = await import('node:crypto')
    const sig = `sha256=${createHmac('sha256', 'whsec_testsecret123456').update(`${ts}.`).update(raw).digest('hex')}`
    expect(verifyWebhook('whsec_testsecret123456', raw, ts, sig, Date.now())).toBe(true)
    expect(verifyWebhook('whsec_other', raw, ts, sig, Date.now())).toBe(false)
    expect(verifyWebhook('whsec_testsecret123456', raw, String(Number(ts) - 600), sig, Date.now())).toBe(false)

    const srv = () => [...dns.records.values()].find((r) => r.type === 'SRV')!
    expect(srv().data.target).toBe('welt.trs-launcher.de')
    partner.servers.get(s.id)!.state = 'running'
    partner.servers.get(s.id)!.session = { ...partner.servers.get(s.id)!.session, active: true }
    craftingWebhook(env.ctx, raw)
    await new Promise((r) => setTimeout(r, 50))
    expect(srv().data.target).toBe('node1.example.com')
    craftingWebhook(env.ctx, raw)
    craftingWebhook(env.ctx, Buffer.from(JSON.stringify({ id: 'd2', type: 'server.deleted', data: { serverId: s.id } })))
    await new Promise((r) => setTimeout(r, 50))
    expect(await craftingServer(env.ctx, u!)).not.toBeNull()
  })

  it('slow node: start answers starting and retries in the background; console shows a hint', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      const { env, partner } = setup()
      const [u] = await players(env, 'Steve')
      await craftingCreate(env.ctx, u!, body())
      partner.notReady = 2
      const v = await craftingStart(env.ctx, u!, true)
      expect(v.state).toBe('starting')
      expect(await craftingLogs(env.ctx, u!, 50, 'de')).toEqual(['Server startet …'])
      expect((await craftingServer(env.ctx, u!))!.state).toBe('starting')
      await vi.advanceTimersByTimeAsync(10_000)
      await vi.advanceTimersByTimeAsync(10_000)
      expect((await craftingServer(env.ctx, u!))!.state).toBe('running')
      expect(partner.startBodies.length).toBe(3)

      // Gibt nach 3 Minuten auf
      const [v2] = await players(env, 'Alex')
      await craftingCreate(env.ctx, v2!, body())
      partner.notReady = 1000
      expect((await craftingStart(env.ctx, v2!, true)).state).toBe('starting')
      env.clock.advance(4 * 60_000)
      await vi.advanceTimersByTimeAsync(10_000)
      expect((await craftingServer(env.ctx, v2!))!.state).toBe('offline')
    } finally {
      vi.useRealTimers()
    }
  })

  it('automatic first setup after create: install, first start, configure, stop; locked meanwhile, retry after failure', async () => {
    const { env, partner } = setup()
    const [u] = await players(env, 'Steve')
    const s = await craftingCreate(env.ctx, u!, createServerBody.parse({ ...body(), acceptEula: true }))
    expect(s.eulaAccepted).toBe(true)
    expect(s.display!.setup).toMatchObject({ phase: 'install', index: 0, failed: false })
    expect(s.display!.setup!.steps.map((x) => x.de)).toEqual(['Server wird installiert', 'Erster Start', 'Wird eingerichtet', 'Wird gespeichert und gestoppt'])
    const phase = async () => (await craftingServer(env.ctx, u!))!.display?.setup?.phase ?? null
    partner.servers.get(s.id)!.state = 'installing'
    expect(await autoStep(env.ctx, u!.uuid)).toBe(true)
    expect(await phase()).toBe('install')
    partner.servers.get(s.id)!.state = 'offline'
    await autoStep(env.ctx, u!.uuid)
    expect(partner.startBodies.at(-1)).toEqual({ acceptEula: true })
    // Start durch den Nutzer währenddessen ändert nichts
    const n = partner.startBodies.length
    await craftingStart(env.ctx, u!, false)
    expect(partner.startBodies.length).toBe(n)
    for (let i = 0; i < 6 && (await phase()) !== null; i++) await autoStep(env.ctx, u!.uuid)
    const done = (await craftingServer(env.ctx, u!))!
    expect(done.display?.setup ?? null).toBeNull()
    expect(done.state).toBe('offline')
    expect(done.setup).toBe('done')
    expect(done.quota?.usedSeconds ?? 0).toBe(0)

    // Zeitüberschreitung → gescheitert, „Start“ versucht es neu
    const [v] = await players(env, 'Alex')
    const s2 = await craftingCreate(env.ctx, v!, createServerBody.parse({ ...body(), acceptEula: true }))
    partner.servers.get(s2.id)!.state = 'installing'
    env.clock.advance(11 * 60_000)
    expect(await autoStep(env.ctx, v!.uuid)).toBe(false)
    expect((await craftingServer(env.ctx, v!))!.display!.setup).toMatchObject({ phase: 'failed', failed: true, index: 0 })
    // Scheitert es später, zeigt die Anzeige den Schritt, in dem es hing
    run(env.ctx.db, "UPDATE craftinghosting_servers SET auto_setup = 'failed:start' WHERE uuid = ?", v!.uuid)
    expect((await craftingServer(env.ctx, v!))!.display!.setup).toMatchObject({ failed: true, index: 1 })
    expect((await craftingStart(env.ctx, v!, false)).display!.setup).toMatchObject({ phase: 'install', failed: false })
  })

  it('runtime rules: quota over sessions, warning, stop + cooldown, idle reset, weekly cap', async () => {
    const { env, partner } = setup()
    const [u, staff] = await players(env, 'Steve', 'Mod')
    adminSetCraftingConfig(env.ctx, staff!.uuid, { rules: { enabled: true, budgetHours: 1, cooldownHours: 2, idleResetDays: 3, weeklyOfflineHours: 8, warnMinutes: 30 } })
    expect((await craftingInfo(env.ctx)).rules).toMatchObject({ budgetHours: 1, idleResetDays: 3 })
    const s = await craftingCreate(env.ctx, u!, body())
    // Ersteinrichtung (Plugin + Neustart) hier nicht nötig
    run(env.ctx.db, 'UPDATE craftinghosting_servers SET setup = 1, transfers = 1 WHERE server_id = ?', s.id)
    await craftingStart(env.ctx, u!, true)
    const tick = async (n: number) => {
      for (let i = 0; i < n; i++) {
        env.clock.advance(60_000)
        await sweepCraftingDns(env.ctx)
      }
    }
    await sweepCraftingDns(env.ctx)
    await tick(20)
    let v = (await craftingServer(env.ctx, u!))!
    expect(v.quota).toMatchObject({ usedSeconds: 1200, budgetSeconds: 3600, remainingSeconds: 2400 })
    expect(v.display!.runtime).toMatchObject({ tone: 'normal', label: { de: 'Laufzeit', en: 'Runtime' } })
    expect(v.display!.runtime!.value.de).toBe('noch {countdown:2400} von 1 h')
    expect(v.display!.lines[0]!.text.en).toBe('This week 0 / 160 h')
    expect((await craftingInfo(env.ctx)).tiles!.map((x) => x.text.de)).toContain('Insgesamt 1 h Laufzeit, danach 2 h Pause.')

    // Pause zwischendurch: zählt nicht, Kontingent bleibt
    await craftingStop(env.ctx, u!, false)
    await tick(30)
    expect((await craftingServer(env.ctx, u!))!.quota!.usedSeconds).toBe(1200)
    await craftingStart(env.ctx, u!, false)
    await sweepCraftingDns(env.ctx)
    partner.commands = []
    await tick(11)
    expect(partner.commands.some((c) => c.startsWith('tellraw @a') && c.includes('30'))).toBe(true)
    const warned = (await craftingServer(env.ctx, u!))!.display!
    expect(warned.runtime!.tone).toBe('warn')
    expect(warned.alerts[0]!.text.de).toMatch(/^Dein Server stoppt in \{countdown:\d+\}/)
    await tick(30)
    v = (await craftingServer(env.ctx, u!))!
    expect(v.state).toBe('offline')
    expect(v.session.lastStopReason).toBe('session_limit')
    expect(v.quota!.cooldownUntil).not.toBeNull()
    expect(await codeAsync(() => craftingStart(env.ctx, u!, false))).toBe('cooldown')

    // Nach der Pause: volles Kontingent
    env.clock.advance(2 * 3_600_000)
    await sweepCraftingDns(env.ctx)
    v = (await craftingServer(env.ctx, u!))!
    expect(v.quota).toMatchObject({ usedSeconds: 0, remainingSeconds: 3600, cooldownUntil: null })

    // 3 Tage aus setzt angebrochenes Kontingent zurück
    await craftingStart(env.ctx, u!, false)
    await sweepCraftingDns(env.ctx)
    await tick(10)
    await craftingStop(env.ctx, u!, false)
    await sweepCraftingDns(env.ctx)
    expect((await craftingServer(env.ctx, u!))!.quota!.idleResetAt).not.toBeNull()
    env.clock.advance(3 * 86_400_000)
    expect((await craftingServer(env.ctx, u!))!.quota!.usedSeconds).toBe(0)

    // Team setzt zurück
    run(env.ctx.db, 'UPDATE craftinghosting_servers SET cooldown_until = ? WHERE server_id = ?', env.clock.t + 3_600_000, s.id)
    expect(await codeAsync(() => craftingStart(env.ctx, u!, false))).toBe('cooldown')
    adminCraftingResetQuota(env.ctx, s.id)
    expect((await craftingStart(env.ctx, u!, false)).state).toBe('running')

    // Wochen-Limit: 160 h genutzt -> Pause bis Montag 0 Uhr Berlin
    run(env.ctx.db, 'UPDATE craftinghosting_servers SET week_used_s = ?, run_used_s = 0 WHERE server_id = ?', 160 * 3600 - 30, s.id)
    await sweepCraftingDns(env.ctx)
    await tick(1)
    v = (await craftingServer(env.ctx, u!))!
    expect(v.state).toBe('offline')
    const until = new Date(v.quota!.cooldownUntil!)
    expect(new Intl.DateTimeFormat('en-GB', { timeZone: 'Europe/Berlin', weekday: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(until)).toBe('Mon 00:00')
  })
})
