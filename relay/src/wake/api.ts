import { createHash, createHmac } from 'node:crypto'

export type HostState = 'running' | 'starting' | 'offline' | 'stopping' | 'cooldown' | 'unavailable'
const STATES: ReadonlySet<string> = new Set(['running', 'starting', 'offline', 'stopping', 'cooldown', 'unavailable'])

export interface HostInfo {
  name: string
  state: HostState
  target: { host: string, port: number } | null
  maxPlayers: number
  cooldownUntil: string | null
}

export type Resolved =
  | { ok: true, info: HostInfo }
  | { ok: false, code: 'unknown_host' | 'error' }

export type WakeResult =
  | { ok: true, state: string }
  | { ok: false, code: string, cooldownUntil?: string }

export interface WakeApiOptions {
  baseUrl: string
  /** erster Schlüssel signiert */
  secret: string
  /** Cache für resolve (Treffer und unknown_host) */
  cacheMs: number
  timeoutMs: number
  now: () => number
  log: (msg: string) => void
}

const MAX_BODY = 64 * 1024
const MAX_CACHE = 5000
/** Fehler kurz merken, damit eine hängende API nicht von jedem Ping getroffen wird. */
const ERROR_CACHE_MS = 2000

/** Signatur laut WAKE.md: HMAC(secret, time \n METHOD \n path?query \n sha256hex(body)). */
export function signRequest(secret: string, time: number, method: string, pathWithQuery: string, body: string): string {
  const bodyHash = createHash('sha256').update(body).digest('hex')
  return createHmac('sha256', secret).update(`${time}\n${method}\n${pathWithQuery}\n${bodyHash}`).digest('hex')
}

function parseInfo(v: unknown): HostInfo | null {
  if (!v || typeof v !== 'object') return null
  const o = v as Record<string, unknown>
  if (typeof o.name !== 'string' || typeof o.state !== 'string' || !STATES.has(o.state)) return null
  let target: HostInfo['target'] = null
  if (o.target && typeof o.target === 'object') {
    const t = o.target as Record<string, unknown>
    if (typeof t.host !== 'string' || t.host.length === 0 || t.host.length > 255) return null
    if (!Number.isInteger(t.port) || (t.port as number) < 1 || (t.port as number) > 65535) return null
    target = { host: t.host, port: t.port as number }
  }
  const maxPlayers = Number.isInteger(o.maxPlayers) ? Math.max(0, Math.min(100_000, o.maxPlayers as number)) : 0
  const cooldownUntil = typeof o.cooldownUntil === 'string' ? o.cooldownUntil : null
  return { name: o.name, state: o.state as HostState, target, maxPlayers, cooldownUntil }
}

/** Client für `/v1/internal/hosting/*` mit resolve-Cache und einer Anfrage je Host gleichzeitig. */
export class WakeApi {
  readonly opts: WakeApiOptions
  private readonly cache = new Map<string, { at: number, value: Resolved }>()
  private readonly inflight = new Map<string, Promise<Resolved>>()
  calls = 0

  constructor(opts: WakeApiOptions) {
    this.opts = opts
  }

  private async request(method: 'GET' | 'POST', path: string, body: string): Promise<{ status: number, json: unknown }> {
    const url = new URL(path, this.opts.baseUrl)
    const time = Math.floor(this.opts.now() / 1000)
    const headers: Record<string, string> = {
      'x-relay-time': String(time),
      'x-relay-sig': signRequest(this.opts.secret, time, method, url.pathname + url.search, body),
      accept: 'application/json',
      'user-agent': 'trs-wake',
    }
    if (method === 'POST') headers['content-type'] = 'application/json'
    this.calls++
    const res = await fetch(url, {
      method,
      headers,
      body: method === 'POST' ? body : undefined,
      redirect: 'error',
      signal: AbortSignal.timeout(this.opts.timeoutMs),
    })
    const text = await res.text()
    if (text.length > MAX_BODY) throw new Error('response too large')
    let json: unknown = null
    try {
      json = text ? JSON.parse(text) : null
    } catch { /* kein JSON */ }
    return { status: res.status, json }
  }

  /** Zustand eines Hosts. `maxAgeMs` < Cache-Zeit erzwingt frischere Daten (Warteschleife). */
  resolve(host: string, maxAgeMs = this.opts.cacheMs): Promise<Resolved> {
    const hit = this.cache.get(host)
    const now = this.opts.now()
    if (hit) {
      const ttl = hit.value.ok || hit.value.code === 'unknown_host' ? maxAgeMs : Math.min(maxAgeMs, ERROR_CACHE_MS)
      if (now - hit.at < ttl) return Promise.resolve(hit.value)
    }
    const running = this.inflight.get(host)
    if (running) return running
    const p = this.fetchResolve(host).then((value) => {
      if (this.cache.size >= MAX_CACHE) this.sweep()
      this.cache.set(host, { at: this.opts.now(), value })
      return value
    }).finally(() => this.inflight.delete(host))
    this.inflight.set(host, p)
    return p
  }

  private async fetchResolve(host: string): Promise<Resolved> {
    try {
      const r = await this.request('GET', `/v1/internal/hosting/resolve?host=${encodeURIComponent(host)}`, '')
      if (r.status === 404) return { ok: false, code: 'unknown_host' }
      const info = r.status === 200 ? parseInfo(r.json) : null
      if (info) return { ok: true, info }
      const code = (r.json as { error?: { code?: unknown } } | null)?.error?.code
      this.opts.log(`resolve failed status=${r.status} code=${typeof code === 'string' ? code : '-'} path=/v1/internal/hosting/resolve?host=${encodeURIComponent(host)} t=${Math.floor(this.opts.now() / 1000)}`)
    } catch (err) {
      this.opts.log(`resolve failed: ${(err as Error).name}`)
    }
    return { ok: false, code: 'error' }
  }

  /** Server starten lassen. Fehlercodes kommen aus der API (`not_allowed`, `cooldown`, …). */
  async wake(host: string, player: { name: string, uuid: string | null }): Promise<WakeResult> {
    const p: { name: string, uuid?: string } = { name: player.name }
    if (player.uuid) p.uuid = player.uuid
    try {
      const r = await this.request('POST', '/v1/internal/hosting/wake', JSON.stringify({ host, player: p }))
      // Nach einem Weckruf ist der gemerkte Zustand veraltet.
      this.cache.delete(host)
      const body = (r.json && typeof r.json === 'object' ? r.json : {}) as Record<string, unknown>
      if (r.status === 202 || r.status === 200) return { ok: true, state: typeof body.state === 'string' ? body.state : 'starting' }
      if (r.status === 429) return { ok: false, code: 'rate_limited' }
      const err = (body.error && typeof body.error === 'object' ? body.error : {}) as Record<string, unknown>
      const code = typeof err.code === 'string' ? err.code : `http_${r.status}`
      const details = (err.details && typeof err.details === 'object' ? err.details : {}) as Record<string, unknown>
      if (r.status !== 409 && r.status !== 404) this.opts.log(`wake failed status=${r.status}`)
      return typeof details.cooldownUntil === 'string'
        ? { ok: false, code, cooldownUntil: details.cooldownUntil }
        : { ok: false, code }
    } catch (err) {
      this.opts.log(`wake failed: ${(err as Error).name}`)
      return { ok: false, code: 'error' }
    }
  }

  sweep(): void {
    const t = this.opts.now()
    for (const [k, v] of this.cache) if (t - v.at >= this.opts.cacheMs) this.cache.delete(k)
    if (this.cache.size >= MAX_CACHE) this.cache.clear()
  }
}
