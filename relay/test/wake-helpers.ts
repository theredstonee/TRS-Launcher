import { createHash, createHmac, timingSafeEqual } from 'node:crypto'
import http from 'node:http'
import net from 'node:net'
import { testWakeConfig, type WakeConfig } from '../src/wake/config.ts'
import { startWake, type WakeListener } from '../src/wake/listener.ts'
import { PacketReader, type Packet, packet, writeString, writeUuid, writeVarInt } from '../src/wake/mc.ts'

export const WAKE_SECRET = 'test-wake-secret-0123456789abcdef-0123456789'
export const DOMAIN = 'trs-launcher.de'
export const HOSTNAME = `abc.${DOMAIN}`
export const UUID = '0123456789abcdef0123456789abcdef'

export interface HostEntry {
  status?: number
  body?: unknown
}

/** Nachgebaute TRS API: prüft die Signatur wie `api/server/lib/relay-auth.ts`. */
export class FakeApi {
  readonly server: http.Server
  resolve = new Map<string, HostEntry>()
  wake: (body: Record<string, unknown>) => HostEntry = () => ({ status: 202, body: { state: 'starting' } })
  readonly wakes: Record<string, unknown>[] = []
  resolves = 0
  badSigs = 0
  delayMs = 0
  port = 0

  private readonly secret: string

  constructor(secret = WAKE_SECRET) {
    this.secret = secret
    this.server = http.createServer((req, res) => {
      const chunks: Buffer[] = []
      req.on('data', (c: Buffer) => chunks.push(c))
      req.on('end', () => void this.handle(req, res, Buffer.concat(chunks)))
    })
  }

  async start(): Promise<string> {
    await new Promise<void>((r) => this.server.listen(0, '127.0.0.1', () => r()))
    this.port = (this.server.address() as net.AddressInfo).port
    return `http://127.0.0.1:${this.port}`
  }

  close(): Promise<void> {
    this.server.closeAllConnections()
    return new Promise((r) => this.server.close(() => r()))
  }

  setHost(host: string, body: unknown, status = 200): void {
    this.resolve.set(host, { status, body })
  }

  private verify(req: http.IncomingMessage, body: Buffer): boolean {
    const time = String(req.headers['x-relay-time'] ?? '')
    const sig = String(req.headers['x-relay-sig'] ?? '')
    if (!/^\d{1,12}$/.test(time) || Math.abs(Date.now() / 1000 - Number(time)) > 60 || !/^[0-9a-f]{64}$/.test(sig)) return false
    const msg = `${time}\n${req.method}\n${req.url}\n${createHash('sha256').update(body).digest('hex')}`
    const want = createHmac('sha256', this.secret).update(msg).digest()
    return timingSafeEqual(want, Buffer.from(sig, 'hex'))
  }

  private async handle(req: http.IncomingMessage, res: http.ServerResponse, body: Buffer): Promise<void> {
    const send = (e: HostEntry) => {
      res.writeHead(e.status ?? 200, { 'content-type': 'application/json' })
      res.end(e.body === undefined ? '' : JSON.stringify(e.body))
    }
    if (!this.verify(req, body)) {
      this.badSigs++
      return send({ status: 403, body: { error: { code: 'relay_auth' } } })
    }
    if (this.delayMs) await new Promise((r) => setTimeout(r, this.delayMs))
    const url = new URL(req.url ?? '/', 'http://x')
    if (req.method === 'GET' && url.pathname === '/v1/internal/hosting/resolve') {
      this.resolves++
      const e = this.resolve.get(url.searchParams.get('host') ?? '')
      return send(e ?? { status: 404, body: { error: { code: 'unknown_host' } } })
    }
    if (req.method === 'POST' && url.pathname === '/v1/internal/hosting/wake') {
      const b = JSON.parse(body.toString('utf8')) as Record<string, unknown>
      this.wakes.push(b)
      return send(this.wake(b))
    }
    send({ status: 404, body: { error: { code: 'not_found' } } })
  }
}

export function hostInfo(state: string, over: Record<string, unknown> = {}) {
  return { name: 'abc', state, target: null, maxPlayers: 20, cooldownUntil: null, ...over }
}

const listeners: WakeListener[] = []
const apis: FakeApi[] = []

/** Fake-API + Listener mit kurzen Zeiten. */
export async function wakeSetup(over: Partial<WakeConfig> = {}): Promise<{ api: FakeApi, listener: WakeListener, port: number }> {
  const api = new FakeApi()
  apis.push(api)
  const apiUrl = await api.start()
  const config = testWakeConfig({
    secret: WAKE_SECRET,
    apiUrl,
    keepAliveMs: 100,
    pollMs: 50,
    giveUpMs: 2000,
    resolveCacheMs: 10_000,
    ...over,
  })
  const { listener, port } = await startWake(config, { log: () => {} })
  listeners.push(listener)
  return { api, listener, port }
}

export async function closeWake(): Promise<void> {
  await Promise.all(listeners.splice(0).map((l) => l.close(200)))
  await Promise.all(apis.splice(0).map((a) => a.close()))
}

export function handshake(protocol: number, address: string, intent: number, port = 25565): Buffer {
  const p = Buffer.alloc(2)
  p.writeUInt16BE(port)
  return packet(0x00, writeVarInt(protocol), writeString(address), p, writeVarInt(intent))
}

export function loginStart(protocol: number, name: string, uuid: string | null = UUID): Buffer {
  if (protocol >= 764) return packet(0x00, writeString(name), writeUuid(uuid!))
  return packet(0x00, writeString(name))
}

/** Minecraft-Testclient mit Paketleser. */
export class McClient {
  readonly socket: net.Socket
  readonly reader = new PacketReader(1 << 20)
  raw: Buffer = Buffer.alloc(0)
  private waiters: (() => void)[] = []
  ended = false
  readonly closedP: Promise<void>

  private constructor(socket: net.Socket) {
    this.socket = socket
    socket.on('data', (c: Buffer) => {
      this.raw = Buffer.concat([this.raw, c])
      this.reader.push(c)
      this.wake()
    })
    socket.on('error', () => {})
    this.closedP = new Promise((resolve) => socket.on('close', () => {
      this.ended = true
      this.wake()
      resolve()
    }))
  }

  private wake(): void {
    for (const w of this.waiters.splice(0)) w()
  }

  static connect(port: number): Promise<McClient> {
    return new Promise((resolve, reject) => {
      const s = net.connect(port, '127.0.0.1', () => resolve(new McClient(s)))
      s.once('error', reject)
    })
  }

  write(...b: Buffer[]): void {
    this.socket.write(Buffer.concat(b))
  }

  async packet(ms = 2000): Promise<Packet> {
    const deadline = Date.now() + ms
    for (;;) {
      const p = this.reader.next()
      if (p === 'error') throw new Error('bad packet')
      if (p) return p
      if (this.ended) throw new Error('connection closed')
      const left = deadline - Date.now()
      if (left <= 0) throw new Error('timeout')
      await new Promise<void>((resolve) => {
        const t = setTimeout(resolve, left)
        this.waiters.push(() => {
          clearTimeout(t)
          resolve()
        })
      })
    }
  }

  /** Nächstes Paket mit dieser ID (z. B. Keep-Alives überspringen). */
  async packetId(id: number, ms = 2000): Promise<Packet> {
    const deadline = Date.now() + ms
    for (;;) {
      const p = await this.packet(Math.max(1, deadline - Date.now()))
      if (p.id === id) return p
    }
  }

  async closed(ms = 2000): Promise<void> {
    await Promise.race([this.closedP, new Promise((_, rej) => setTimeout(() => rej(new Error('not closed')), ms))])
  }

  destroy(): void {
    this.socket.destroy()
  }
}
