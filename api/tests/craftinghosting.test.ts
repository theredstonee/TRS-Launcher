import { createHash } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import {
  craftingCommand, craftingCreate, craftingDelete, craftingHeartbeat, craftingInfo, craftingInstallMods, craftingLogs,
  craftingServer, craftingSetSettings, craftingSettings, craftingStart, craftingStats, craftingStop, craftingUpdate,
  adminCraftingDelete, adminCraftingServers, adminCraftingStop, craftingCheckSubdomain, craftingRestartDone, craftingWorldImport, craftingWorldUpload, createServerBody, MOTD_FOOTER, settingsBody,
} from '../server/lib/craftinghosting'
import { befriend, codeAsync, players } from './chathelpers'
import { makeEnv, type TestEnv } from './helpers'

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
  uploadHost = 'upload.craftinghosting.free'
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
    const m = /^\/servers\/([^/]+)(?:\/([\w/-]+))?$/.exec(path)
    const s = m ? this.servers.get(decodeURIComponent(m[1]!)) : undefined
    if (!m || !s) return this.err(404, 'not_found')
    switch (`${method} ${m[2] ?? ''}`) {
      case 'GET ': return this.json(200, { server: s })
      case 'PATCH ': s.name = `${body.name} #P7KXGQXL`; return this.json(200, { server: s })
      case 'GET settings': return this.json(200, { settings: this.settings })
      case 'PATCH settings': Object.assign(this.settings, body); return this.json(200, { settings: this.settings })
      case 'PUT files/content': {
        const b = init?.body
        this.files.set(url.searchParams.get('path')!, typeof b === 'string' ? b : `<${(b as Uint8Array).length} bytes>`)
        return this.json(200, { ok: true })
      }
      case 'POST files/folder': return this.json(200, { ok: true })
      case 'POST files/upload-url': return this.json(200, { url: `https://${this.uploadHost}/upload?token=abc` })
      case 'POST files/delete': for (const f of body.paths) this.files.delete(f); return this.json(200, { ok: true })
      case 'POST files/decompress':
        if (!this.files.has(body.path)) return this.err(404, 'file_not_found')
        this.files.set('/world/level.dat', 'x')
        return this.json(200, { ok: true })
      case 'POST mods':
        if (s.serverType === 'vanilla') return this.err(409, 'mods_unsupported')
        if (body.project === 'missing') return this.err(404, 'mod_not_found')
        this.mods.push(body.project)
        return this.json(200, { ok: true })
      case 'GET logs': return this.json(200, { lines: ['[10:00:00 INFO]: Done (3.2s)!', '\u001b[31mred\u001b[0m'] })
      case 'GET stats': return this.json(200, { state: 'running', cpuPercent: 12.5, cpuLimitPercent: 200, memoryBytes: 100,
        memoryLimitBytes: 4096, diskBytes: 5, diskLimitBytes: 10, networkRxBytes: 1, networkTxBytes: 2, uptimeSeconds: 60,
        players: { online: 2, max: 20 } })
      case 'GET files': return this.json(200, { directory: '/', files: [...this.files.keys()].map((f) => ({ name: f.slice(1), path: f, type: 'file' })) })
      case 'DELETE ': this.servers.delete(s.id); this.used--; return new Response(null, { status: 204 })
      case 'POST start':
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
    await craftingCreate(env.ctx, u!, body({ serverType: 'paper' }))
    env.ctx.modrinthFetch = (async () => new Response(JSON.stringify([
      { id: 'AANobbMI', slug: 'sodium', server_side: 'unsupported' },
      { id: 'P7dR8mSH', slug: 'fabric-api', server_side: 'required' },
      { id: 'missingX', slug: 'missing', server_side: 'optional' },
    ]), { status: 200 })) as typeof fetch
    const r = await craftingInstallMods(env.ctx, u!, ['sodium', 'fabric-api', 'missing', 'gone'])
    expect(r.installed).toEqual(['fabric-api'])
    expect(r.skipped).toEqual([
      { project: 'sodium', reason: 'client_only' },
      { project: 'gone', reason: 'not_found' },
      { project: 'missing', reason: 'not_found' },
    ])
    expect(partner.mods).toEqual(['fabric-api'])
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
    await craftingSetSettings(env.ctx, u!, settingsBody.parse({ motd: 'x'.repeat(60) }))
    expect(partner.settings.motd).toBe(`${'x'.repeat(60)} ${MOTD_FOOTER}`)
    expect((partner.settings.motd as string).length).toBeLessThanOrEqual(120)
    expect(() => settingsBody.parse({ motd: 'x'.repeat(61) })).toThrow()
    expect(() => settingsBody.parse({ motd: '§cRot' })).toThrow()
  })

  it('heartbeats during the setup restart do not end the session in the launcher', async () => {
    const { env } = setup()
    const [u] = await players(env, 'Steve')
    await craftingCreate(env.ctx, u!, body())
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
