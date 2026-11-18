import net from 'node:net'
import { KeyedLimiter, normalizeIp } from '../limits.ts'
import { WakeApi, type Resolved } from './api.ts'
import type { WakeConfig } from './config.ts'
import { PacketReader, type Packet, legacyPingResponse } from './mc.ts'
import {
  type LoginStart, type TransferProto, configDisconnect, configKeepAlive, dashUuid, hostingHost, loginDisconnect,
  loginSuccess, parseHandshake, parseLoginStart, pong, statusResponse, transfer, transferProto,
} from './packets.ts'
import { MSG, cooldownMessage, statusJson, wakeErrorMessage } from './texts.ts'

export interface WakeDeps {
  config: WakeConfig
  api: WakeApi
  now: () => number
  log: (msg: string) => void
}

type Stage = 'handshake' | 'status' | 'ping' | 'login' | 'busy' | 'ack' | 'config' | 'done' | 'closed'

/** Eine Spieler-Verbindung: Server-Liste oder Login → (ab 1.20.5) warten → Transfer. */
class WakeConn {
  readonly socket: net.Socket
  readonly ip: string
  readonly l: WakeListener
  stage: Stage = 'handshake'
  protocol = 0
  /** FQDN, null = gehört nicht zu uns */
  host: string | null = null
  private readonly reader: PacketReader
  private seen = false
  private timer: ReturnType<typeof setTimeout> | null = null
  private keepAlive: ReturnType<typeof setInterval> | null = null
  private pollTimer: ReturnType<typeof setTimeout> | null = null
  private giveUpTimer: ReturnType<typeof setTimeout> | null = null
  private proto: TransferProto | null = null
  private target: { host: string, port: number } | null = null
  lastRx: number
  closed = false

  constructor(l: WakeListener, socket: net.Socket, ip: string) {
    this.l = l
    this.socket = socket
    this.ip = ip
    this.reader = new PacketReader(l.deps.config.maxPacket)
    this.lastRx = l.deps.now()
    socket.setNoDelay(true)
    socket.on('data', (c: Buffer) => this.onData(c))
    socket.on('error', () => {})
    socket.on('close', () => this.cleanup())
    this.arm(l.deps.config.handshakeTimeoutMs)
  }

  private arm(ms: number): void {
    this.clearTimer()
    this.timer = setTimeout(() => this.destroy(), ms)
  }

  private clearTimer(): void {
    if (this.timer) clearTimeout(this.timer)
    this.timer = null
  }

  private onData(chunk: Buffer): void {
    if (this.closed) return
    this.lastRx = this.l.deps.now()
    if (!this.seen) {
      this.seen = true
      // Alter Ping (bis 1.6) beginnt mit 0xFE, ein echter Handshake nie.
      if (chunk[0] === 0xfe) {
        this.stage = 'done'
        this.end(legacyPingResponse('TRS Wake', 0, 0))
        return
      }
    }
    if (this.stage === 'done') return
    this.reader.push(chunk)
    if (this.reader.buffered > this.l.deps.config.maxPacket * 2) return this.destroy()
    this.pump()
  }

  private pump(): void {
    while (!this.closed && this.stage !== 'busy' && this.stage !== 'done') {
      const p = this.reader.next()
      if (p === 'error') return this.destroy()
      if (p === null) return
      try {
        this.handle(p)
      } catch {
        return this.destroy()
      }
    }
  }

  private handle(p: Packet): void {
    const { config } = this.l.deps
    switch (this.stage) {
      case 'handshake': {
        if (p.id !== 0x00) return this.destroy()
        const hs = parseHandshake(p.data)
        this.protocol = hs.protocol
        this.host = hostingHost(hs.address, config.hostingDomain)
        this.stage = hs.intent === 1 ? 'status' : 'login'
        return
      }
      case 'status':
        if (p.id !== 0x00) return this.destroy()
        this.stage = 'busy'
        void this.status().catch(() => this.destroy())
        return
      case 'ping':
        if (p.id !== 0x01) return this.destroy()
        this.stage = 'done'
        this.end(pong(p.data.long()))
        return
      case 'login': {
        if (p.id !== 0x00) return this.destroy()
        const ls = parseLoginStart(p.data, this.protocol)
        this.clearTimer()
        this.stage = 'busy'
        void this.login(ls).catch(() => this.destroy())
        return
      }
      case 'ack':
        if (p.id === this.proto!.loginAck) this.configPhase()
        return
      default:
        // Konfiguration: Client-Infos, Brand, Keep-Alive-Antworten – egal.
    }
  }

  private async status(): Promise<void> {
    this.clearTimer()
    let r: Resolved | null = null
    if (this.host) r = await this.l.api.resolve(this.host)
    if (this.closed) return
    this.socket.write(statusResponse(statusJson(this.protocol, r)))
    this.stage = 'ping'
    this.arm(this.l.deps.config.handshakeTimeoutMs)
    this.pump()
  }

  private async login(ls: LoginStart): Promise<void> {
    const { config } = this.l.deps
    const host = this.host
    if (!host) return this.kick(MSG.unknown)
    const t = transferProto(this.protocol)
    const r = await this.l.api.resolve(host)
    if (this.closed) return
    if (!r.ok) return this.kick(r.code === 'unknown_host' ? MSG.unknown : MSG.unavailable)
    const canWait = () => t !== null && ls.uuid !== null && this.l.waiting.size < config.maxWaiting
    if (r.info.state === 'running' && r.info.target) {
      if (canWait()) return this.loginSuccess(t!, ls, r.info.target)
      return this.kick(MSG.running)
    }
    const player = { name: ls.name, uuid: ls.uuid ? dashUuid(ls.uuid) : null }
    let w = await this.l.api.wake(host, player)
    if (this.closed) return
    if (!w.ok && w.code === 'rate_limited') {
      // Freunde, die gleichzeitig beitreten: läuft der Start schon, einfach mitwarten.
      const again = await this.l.api.resolve(host, 0)
      if (this.closed) return
      if (again.ok && (again.info.state === 'starting' || again.info.state === 'running')) w = { ok: true, state: again.info.state }
    }
    if (!w.ok) return this.kick(wakeErrorMessage(w.code, w.cooldownUntil))
    this.l.wakes++
    if (canWait()) return this.loginSuccess(t!, ls, null)
    this.kick(w.state === 'running' ? MSG.running : MSG.starting)
  }

  private loginSuccess(t: TransferProto, ls: LoginStart, target: { host: string, port: number } | null): void {
    this.proto = t
    this.target = target
    this.l.waiting.add(this)
    this.socket.write(loginSuccess(t, ls.uuid!, ls.name))
    this.stage = 'ack'
    this.arm(this.l.deps.config.ackTimeoutMs)
    this.pump()
  }

  private configPhase(): void {
    const { config, now } = this.l.deps
    this.clearTimer()
    this.stage = 'config'
    if (this.target) return this.transfer(this.target)
    this.keepAlive = setInterval(() => {
      if (now() - this.lastRx > config.configIdleMs) return this.destroy()
      this.socket.write(configKeepAlive(this.proto!, BigInt(now())))
    }, config.keepAliveMs)
    this.giveUpTimer = setTimeout(() => this.configKick(MSG.giveUp), config.giveUpMs)
    this.schedulePoll()
  }

  private schedulePoll(): void {
    this.pollTimer = setTimeout(() => void this.poll(), this.l.deps.config.pollMs)
  }

  private async poll(): Promise<void> {
    this.pollTimer = null
    if (this.stage !== 'config') return
    const { pollMs } = this.l.deps.config
    const r = await this.l.api.resolve(this.host!, Math.max(0, pollMs - 100))
    if (this.stage !== 'config') return
    if (r.ok) {
      const { state, target, cooldownUntil } = r.info
      if (state === 'running' && target) return this.transfer(target)
      if (state === 'cooldown') return this.configKick(cooldownMessage(cooldownUntil))
      if (state === 'unavailable') return this.configKick(MSG.unavailable)
    } else if (r.code === 'unknown_host') {
      return this.configKick(MSG.unknown)
    }
    this.schedulePoll()
  }

  private transfer(target: { host: string, port: number }): void {
    this.stopWaiting()
    this.stage = 'done'
    this.l.transfers++
    this.socket.write(transfer(this.proto!, target.host, target.port))
    // Der Client trennt nach dem Transfer selbst; zu früh schließen würde ihn „rauswerfen“.
    this.arm(this.l.deps.config.transferLingerMs)
  }

  configKick(text: string): void {
    if (this.closed) return
    this.stopWaiting()
    this.stage = 'done'
    this.end(configDisconnect(this.proto!, this.protocol, text))
  }

  private kick(text: string): void {
    this.stage = 'done'
    this.end(loginDisconnect(text))
  }

  private stopWaiting(): void {
    if (this.keepAlive) clearInterval(this.keepAlive)
    if (this.pollTimer) clearTimeout(this.pollTimer)
    if (this.giveUpTimer) clearTimeout(this.giveUpTimer)
    this.keepAlive = this.pollTimer = this.giveUpTimer = null
    this.l.waiting.delete(this)
  }

  end(buf: Buffer): void {
    if (this.closed) return
    this.socket.end(buf)
    this.arm(1000)
  }

  destroy(): void {
    this.socket.destroy()
    this.cleanup()
  }

  cleanup(): void {
    if (this.closed) return
    this.closed = true
    this.stage = 'closed'
    this.clearTimer()
    this.stopWaiting()
    this.l.forget(this)
  }
}

/** TCP-Listener für TRS Wake (Minecraft-Protokoll, Port 25565). */
export class WakeListener {
  readonly deps: WakeDeps
  readonly server: net.Server
  readonly api: WakeApi
  readonly conns = new Set<WakeConn>()
  readonly waiting = new Set<WakeConn>()
  private readonly perIp = new Map<string, number>()
  private readonly newConnLimiter: KeyedLimiter
  private timers: ReturnType<typeof setInterval>[] = []
  private shuttingDown = false
  rejected = 0
  wakes = 0
  transfers = 0

  constructor(deps: WakeDeps) {
    this.deps = deps
    this.api = deps.api
    this.newConnLimiter = new KeyedLimiter(deps.config.newConnsPerIpPerMin, 60_000, deps.now)
    this.server = net.createServer({ noDelay: true }, (s) => this.accept(s))
    this.server.on('error', (err) => deps.log(`wake server error: ${err.message}`))
  }

  listen(): Promise<number> {
    const c = this.deps.config
    return new Promise((resolve, reject) => {
      this.server.once('error', reject)
      this.server.listen(c.port, c.bindHost, () => {
        this.server.off('error', reject)
        this.timers = [
          setInterval(() => {
            this.newConnLimiter.sweep()
            this.api.sweep()
          }, 60_000),
          setInterval(() => {
            const s = this.stats()
            this.deps.log(`stats conns=${s.conns} waiting=${s.waiting} wakes=${s.wakes} transfers=${s.transfers} rejected=${s.rejected}`)
          }, 10 * 60_000),
        ]
        for (const t of this.timers) t.unref()
        resolve((this.server.address() as net.AddressInfo).port)
      })
    })
  }

  private accept(socket: net.Socket): void {
    const c = this.deps.config
    const ip = normalizeIp(socket.remoteAddress)
    socket.on('error', () => {})
    const ok = !this.shuttingDown
      && this.conns.size < c.maxConnections
      && (this.perIp.get(ip) ?? 0) < c.maxConnsPerIp
      && this.newConnLimiter.take(ip)
    if (!ok) {
      this.rejected++
      socket.destroy()
      return
    }
    this.perIp.set(ip, (this.perIp.get(ip) ?? 0) + 1)
    this.conns.add(new WakeConn(this, socket, ip))
  }

  forget(c: WakeConn): void {
    if (!this.conns.delete(c)) return
    const n = (this.perIp.get(c.ip) ?? 1) - 1
    if (n <= 0) this.perIp.delete(c.ip)
    else this.perIp.set(c.ip, n)
  }

  stats() {
    return { conns: this.conns.size, waiting: this.waiting.size, wakes: this.wakes, transfers: this.transfers, rejected: this.rejected }
  }

  /** Wartende bekommen eine Meldung, alles andere wird geschlossen. */
  async close(graceMs = 2000): Promise<void> {
    this.shuttingDown = true
    for (const t of this.timers) clearInterval(t)
    const closed = new Promise<void>((resolve) => this.server.close(() => resolve()))
    for (const c of [...this.conns]) {
      if (c.stage === 'config') c.configKick(MSG.shutdown)
      else c.destroy()
    }
    const deadline = Date.now() + graceMs
    while (this.conns.size > 0 && Date.now() < deadline) await new Promise((r) => setTimeout(r, 25))
    for (const c of [...this.conns]) c.destroy()
    await closed
  }
}

export interface WakeOptions {
  now?: () => number
  log?: (msg: string) => void
}

export async function startWake(config: WakeConfig, opts: WakeOptions = {}): Promise<{ listener: WakeListener, port: number }> {
  const now = opts.now ?? Date.now
  const log = opts.log ?? ((m: string) => console.info(`[trs-wake] ${m}`))
  const api = new WakeApi({
    baseUrl: config.apiUrl,
    secret: config.secret,
    cacheMs: config.resolveCacheMs,
    timeoutMs: config.apiTimeoutMs,
    now,
    log,
  })
  const listener = new WakeListener({ config, api, now, log })
  const port = await listener.listen()
  return { listener, port }
}
