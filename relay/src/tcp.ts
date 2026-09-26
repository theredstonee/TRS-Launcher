import { randomBytes } from 'node:crypto'
import net from 'node:net'
import type { RelayConfig } from './config.ts'
import { KeyedLimiter, normalizeIp } from './limits.ts'
import {
  FrameReader, PREAMBLE, T, bytesToUuid, errorFrame, frame, uuidToBytes,
  type ErrorCode, type Frame,
} from './protocol.ts'
import type { ControlHandle, GuestHandle, Room, RoomRegistry } from './rooms.ts'
import { verifyToken } from './token.ts'

export interface Deps {
  config: RelayConfig
  registry: RoomRegistry
  now: () => number
  log: (msg: string) => void
}

type Stage = 'preamble' | 'hello' | 'control' | 'pending' | 'raw' | 'closed'

const TOKEN_CHARS = /^[\x21-\x7e]+$/
/** Fehler, die als fehlgeschlagener Handshake zählen (Missbrauchsschutz je IP). */
const HANDSHAKE_FAILS: ReadonlySet<ErrorCode> = new Set<ErrorCode>(['bad_preamble', 'bad_frame', 'bad_token', 'expired', 'unknown_pair'])

/** Eine TCP-Verbindung zum Relay (Steuerung, Gast oder Host-Daten). */
class Conn implements GuestHandle {
  readonly socket: net.Socket
  readonly ip: string
  readonly relay: TcpRelay
  stage: Stage = 'preamble'
  role: 'control' | 'guest' | 'hostdata' | null = null
  room: Room | null = null
  uuid: string | null = null
  pairIdHex = ''
  pairId: Buffer | null = null
  pair: Pair | null = null
  private controlHandle: ControlHandle | null = null
  lastRx: number
  private pre: Buffer = Buffer.alloc(0)
  private readonly reader = new FrameReader()
  private pendingBuf: Buffer[] = []
  private pendingLen = 0
  private timer: ReturnType<typeof setTimeout> | null = null
  drainWait = false
  throttleTimer: ReturnType<typeof setTimeout> | null = null
  closed = false

  constructor(relay: TcpRelay, socket: net.Socket, ip: string) {
    this.relay = relay
    this.socket = socket
    this.ip = ip
    this.lastRx = relay.deps.now()
    socket.setNoDelay(true)
    socket.setKeepAlive(true, 30_000)
    socket.on('data', (c: Buffer) => this.onData(c))
    socket.on('error', () => {})
    socket.on('close', () => this.cleanup())
    this.timer = setTimeout(() => this.fail('idle_timeout'), relay.deps.config.handshakeTimeoutMs)
  }

  private onData(chunk: Buffer): void {
    if (this.closed) return
    this.lastRx = this.relay.deps.now()
    switch (this.stage) {
      case 'preamble': {
        this.pre = Buffer.concat([this.pre, chunk])
        const n = Math.min(this.pre.length, PREAMBLE.length)
        if (!this.pre.subarray(0, n).equals(PREAMBLE.subarray(0, n))) return this.fail('bad_preamble')
        if (this.pre.length < PREAMBLE.length) return
        const rest = this.pre.subarray(PREAMBLE.length)
        this.pre = Buffer.alloc(0)
        this.stage = 'hello'
        if (rest.length > 0) this.onData(rest)
        return
      }
      case 'hello': {
        this.reader.push(chunk)
        const f = this.reader.next()
        if (f === 'error') return this.fail('bad_frame')
        if (f === null) return
        this.handleHello(f)
        if (this.closed) return
        const rest = this.reader.rest()
        if (rest.length > 0) this.onData(rest)
        return
      }
      case 'control': {
        this.reader.push(chunk)
        for (;;) {
          const f = this.reader.next()
          if (f === 'error') return this.fail('bad_frame')
          if (f === null) return
          this.handleControl(f)
          if (this.closed) return
        }
      }
      case 'pending': {
        this.pendingLen += chunk.length
        if (this.pendingLen > this.relay.deps.config.guestBufferMax) return this.fail('buffer_overflow')
        this.pendingBuf.push(chunk)
        return
      }
      case 'raw':
        this.pair?.forward(this, chunk)
        return
      default:
    }
  }

  private token(f: Frame, role: 'host' | 'guest') {
    const raw = f.payload.toString('latin1')
    if (!TOKEN_CHARS.test(raw)) return { ok: false, code: 'bad_token' } as const
    const { config, now } = this.relay.deps
    const r = verifyToken(raw, config.secrets, Math.floor(now() / 1000), config.clockSkewSec)
    if (r.ok && r.token.role !== role) return { ok: false, code: 'bad_token' } as const
    return r
  }

  private handleHello(f: Frame): void {
    this.clearTimer()
    const { config, registry, now } = this.relay.deps
    if (f.type === T.HOST_HELLO) {
      const r = this.token(f, 'host')
      if (!r.ok) return this.fail(r.code)
      const t = r.token
      const existing = registry.get(t.r)
      if (existing && existing.hostUuid !== t.h) return this.fail('bad_token')
      const old = existing?.control
      if (existing && old) {
        // Neuer Host-Hello ersetzt die alte Steuerverbindung samt ihrer Paare.
        existing.control = null
        old.fail('replaced')
        this.relay.closeGuestsOf(existing, 'host_offline')
      }
      // Erst danach holen/anlegen: das Aufräumen kann den leeren Raum entfernt haben.
      const room = registry.forHost(t.r, t.h, t.m, now())
      if (!room) return this.fail('bad_token')
      room.kicked.clear()
      this.controlHandle = { send: (b) => this.send(b), fail: (c) => this.fail(c) }
      room.control = this.controlHandle
      this.room = room
      this.uuid = t.u
      this.role = 'control'
      this.stage = 'control'
      this.send(frame(T.WELCOME, JSON.stringify({
        room: room.id,
        maxGuests: Math.min(t.m, config.maxRoomPlayers) - 1,
        pairTimeoutMs: config.pairTimeoutMs,
        idleTimeoutMs: config.controlIdleMs,
      })))
      return
    }
    if (f.type === T.GUEST_HELLO) {
      const r = this.token(f, 'guest')
      if (!r.ok) return this.fail(r.code)
      const t = r.token
      const room = registry.get(t.r)
      if (!room || !room.control || room.hostUuid !== t.h) return this.fail('host_offline')
      if (room.kicked.has(t.u)) return this.fail('kicked')
      if (!room.canAdmit(t.u, config.maxRoomPlayers)) return this.fail('room_full')
      if (room.connsOf(t.u) >= config.maxConnsPerGuest) return this.fail('too_many_connections')
      this.pairId = randomBytes(16)
      this.pairIdHex = this.pairId.toString('hex')
      this.room = room
      this.uuid = t.u
      this.role = 'guest'
      this.stage = 'pending'
      room.addGuestConn(t.u, this)
      this.relay.pending.set(this.pairIdHex, this)
      this.timer = setTimeout(() => this.fail('host_timeout'), config.pairTimeoutMs)
      room.control.send(frame(T.GUEST_OPEN, Buffer.concat([this.pairId, uuidToBytes(t.u)])))
      return
    }
    if (f.type === T.PAIR) {
      if (f.payload.length !== 16) return this.fail('bad_frame')
      const guest = this.relay.pending.get(f.payload.toString('hex'))
      if (!guest || guest.closed) return this.fail('unknown_pair')
      this.relay.pending.delete(guest.pairIdHex)
      guest.clearTimer()
      this.role = 'hostdata'
      this.room = guest.room
      this.uuid = guest.uuid
      this.pairIdHex = guest.pairIdHex
      const pair = new Pair(this.relay, guest, this)
      guest.pair = pair
      this.pair = pair
      const welcome = frame(T.WELCOME, JSON.stringify({ room: guest.room!.id }))
      guest.socket.write(welcome)
      this.socket.write(welcome)
      guest.stage = 'raw'
      this.stage = 'raw'
      const buffered = guest.pendingBuf
      guest.pendingBuf = []
      guest.pendingLen = 0
      if (buffered.length > 0) pair.forward(guest, Buffer.concat(buffered))
      return
    }
    this.fail('bad_frame')
  }

  private handleControl(f: Frame): void {
    const room = this.room!
    switch (f.type) {
      case T.PING:
        if (f.payload.length > 8) return this.fail('bad_frame')
        this.send(frame(T.PONG, f.payload))
        return
      case T.PONG:
        if (f.payload.length > 8) return this.fail('bad_frame')
        return
      case T.CLOSE_GUEST: {
        if (f.payload.length !== 16) return this.fail('bad_frame')
        const hex = f.payload.toString('hex')
        const g = room.allGuestConns().find((x) => x.pairIdHex === hex)
        g?.kick('kicked')
        return
      }
      case T.KICK: {
        if (f.payload.length !== 16) return this.fail('bad_frame')
        const uuid = bytesToUuid(f.payload)
        room.kicked.add(uuid)
        for (const g of [...(room.guestConns.get(uuid) ?? [])]) g.kick('kicked')
        this.relay.deps.registry.kickUdp(room, uuid)
        return
      }
      default:
        this.fail('bad_frame')
    }
  }

  send(buf: Buffer): void {
    if (!this.closed) this.socket.write(buf)
  }

  kick(code: ErrorCode): void {
    if (this.pair) this.pair.close()
    else this.fail(code)
  }

  clearTimer(): void {
    if (this.timer) clearTimeout(this.timer)
    this.timer = null
  }

  /** Mit Fehler beenden. Im Rohmodus ohne Frame (die Bytes gehören dem Spiel). */
  fail(code: ErrorCode): void {
    if (this.closed) return
    if (HANDSHAKE_FAILS.has(code) && this.role === null) this.relay.failLimiter.take(this.ip)
    if (this.stage === 'raw') {
      this.pair?.close()
      return
    }
    this.socket.end(errorFrame(code))
    const t = setTimeout(() => this.socket.destroy(), 1000)
    t.unref()
    this.cleanup()
  }

  maybeResume(): void {
    if (!this.closed && !this.drainWait && !this.throttleTimer) this.socket.resume()
  }

  /** Einmaliges Aufräumen (Fehler, Schließen oder Socket zu). */
  cleanup(): void {
    if (this.closed) return
    this.closed = true
    this.stage = 'closed'
    this.clearTimer()
    if (this.throttleTimer) clearTimeout(this.throttleTimer)
    this.throttleTimer = null
    this.relay.forget(this)
    const room = this.room
    if (!room) return
    const { registry } = this.relay.deps
    if (this.role === 'control') {
      if (this.controlHandle && room.control === this.controlHandle) {
        room.control = null
        room.kicked.clear()
        this.relay.closeGuestsOf(room, 'host_offline')
      }
    } else if (this.role === 'guest' && !this.pair) {
      this.relay.pending.delete(this.pairIdHex)
      room.removeGuestConn(this.uuid!, this)
      room.control?.send(frame(T.GUEST_CLOSED, this.pairId!))
    }
    this.pair?.close()
    registry.release(room)
  }
}

/** Gekoppeltes Paar: Gast ⇄ Host-Datenverbindung, rohe Bytes mit Bandbreitenbremse. */
class Pair {
  readonly relay: TcpRelay
  readonly guest: Conn
  readonly host: Conn
  readonly room: Room
  lastActivity: number
  closed = false

  constructor(relay: TcpRelay, guest: Conn, host: Conn) {
    this.relay = relay
    this.guest = guest
    this.host = host
    this.room = guest.room!
    this.lastActivity = relay.deps.now()
    relay.pairs.add(this)
  }

  forward(from: Conn, chunk: Buffer): void {
    if (this.closed) return
    const to = from === this.guest ? this.host : this.guest
    const now = this.relay.deps.now()
    this.lastActivity = now
    this.room.bytes += chunk.length
    this.relay.bytes += chunk.length
    if (!to.socket.write(chunk) && !from.drainWait) {
      from.drainWait = true
      from.socket.pause()
      to.socket.once('drain', () => {
        from.drainWait = false
        from.maybeResume()
      })
    }
    const wait = this.room.bandwidth.consume(now, chunk.length)
    if (wait > 0 && !from.throttleTimer) {
      from.socket.pause()
      from.throttleTimer = setTimeout(() => {
        from.throttleTimer = null
        from.maybeResume()
      }, wait)
    }
  }

  close(): void {
    if (this.closed) return
    this.closed = true
    this.relay.pairs.delete(this)
    this.room.removeGuestConn(this.guest.uuid!, this.guest)
    for (const c of [this.guest, this.host]) {
      c.socket.end()
      const t = setTimeout(() => c.socket.destroy(), 2000)
      t.unref()
    }
    this.room.control?.send(frame(T.GUEST_CLOSED, this.guest.pairId!))
    this.guest.cleanup()
    this.host.cleanup()
    this.relay.deps.registry.release(this.room)
  }
}

export class TcpRelay {
  readonly deps: Deps
  readonly server: net.Server
  readonly conns = new Set<Conn>()
  readonly pending = new Map<string, Conn>()
  readonly pairs = new Set<Pair>()
  readonly failLimiter: KeyedLimiter
  private readonly newConnLimiter: KeyedLimiter
  private readonly perIp = new Map<string, number>()
  private sweeper: ReturnType<typeof setInterval> | null = null
  private shuttingDown = false
  bytes = 0
  rejected = 0

  constructor(deps: Deps) {
    this.deps = deps
    const c = deps.config
    this.failLimiter = new KeyedLimiter(c.handshakeFailsPerIp, 10 * 60_000, deps.now)
    this.newConnLimiter = new KeyedLimiter(c.newConnsPerIpPerMin, 60_000, deps.now)
    this.server = net.createServer({ noDelay: true, keepAlive: true }, (s) => this.accept(s))
    this.server.on('error', (err) => deps.log(`tcp server error: ${err.message}`))
  }

  listen(): Promise<number> {
    const c = this.deps.config
    return new Promise((resolve, reject) => {
      this.server.once('error', reject)
      this.server.listen(c.tcpPort, c.bindHost, () => {
        this.server.off('error', reject)
        const every = Math.max(50, Math.min(1000, Math.floor(Math.min(c.controlIdleMs, c.pipeIdleMs) / 4)))
        this.sweeper = setInterval(() => this.sweep(), every)
        this.sweeper.unref()
        resolve((this.server.address() as net.AddressInfo).port)
      })
    })
  }

  private reject(socket: net.Socket, code: ErrorCode): void {
    this.rejected++
    socket.on('error', () => {})
    socket.end(errorFrame(code))
    const t = setTimeout(() => socket.destroy(), 1000)
    t.unref()
  }

  private accept(socket: net.Socket): void {
    const c = this.deps.config
    const ip = normalizeIp(socket.remoteAddress)
    if (this.shuttingDown) return this.reject(socket, 'shutting_down')
    if (!this.failLimiter.has(ip)) return this.reject(socket, 'rate_limited')
    if (this.conns.size >= c.maxConnections) return this.reject(socket, 'server_full')
    if ((this.perIp.get(ip) ?? 0) >= c.maxConnsPerIp) return this.reject(socket, 'too_many_connections')
    if (!this.newConnLimiter.take(ip)) return this.reject(socket, 'rate_limited')
    this.perIp.set(ip, (this.perIp.get(ip) ?? 0) + 1)
    this.conns.add(new Conn(this, socket, ip))
  }

  forget(c: Conn): void {
    if (!this.conns.delete(c)) return
    const n = (this.perIp.get(c.ip) ?? 1) - 1
    if (n <= 0) this.perIp.delete(c.ip)
    else this.perIp.set(c.ip, n)
  }

  /** Alle Gast-Verbindungen eines Raums schließen (Host weg/ersetzt). */
  closeGuestsOf(room: Room, code: ErrorCode): void {
    for (const g of room.allGuestConns()) g.kick(code)
  }

  private sweep(): void {
    const { config, now } = this.deps
    const t = now()
    for (const c of [...this.conns]) {
      if (c.role === 'control' && t - c.lastRx > config.controlIdleMs) c.fail('idle_timeout')
    }
    for (const p of [...this.pairs]) if (t - p.lastActivity > config.pipeIdleMs) p.close()
  }

  sweepLimiters(): void {
    this.failLimiter.sweep()
    this.newConnLimiter.sweep()
  }

  get connectionCount(): number {
    return this.conns.size
  }

  /** Geordnet herunterfahren: keine neuen Verbindungen, Steuerungen bekommen `shutting_down`. */
  async close(): Promise<void> {
    this.shuttingDown = true
    if (this.sweeper) clearInterval(this.sweeper)
    const closed = new Promise<void>((resolve) => this.server.close(() => resolve()))
    // Erst die Hosts informieren, dann Paare und übrige Verbindungen schließen.
    for (const c of [...this.conns]) if (c.role === 'control') c.fail('shutting_down')
    for (const p of [...this.pairs]) p.close()
    for (const c of [...this.conns]) c.fail('shutting_down')
    const deadline = Date.now() + this.deps.config.shutdownGraceMs
    while (this.conns.size > 0 && Date.now() < deadline) await new Promise((r) => setTimeout(r, 25))
    for (const c of [...this.conns]) {
      c.socket.destroy()
      c.cleanup()
    }
    await closed
  }
}
