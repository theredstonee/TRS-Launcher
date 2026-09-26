import { randomBytes } from 'node:crypto'
import dgram from 'node:dgram'
import { KeyedLimiter, normalizeIp } from './limits.ts'
import type { ErrorCode } from './protocol.ts'
import type { UdpBinding } from './rooms.ts'
import { bindingResponse, isBindingRequest, isStun } from './stun.ts'
import type { Deps } from './tcp.ts'
import { verifyToken } from './token.ts'

/** TRS-UDP (siehe PROTOCOL.md): "TRSU" + Typ. */
export const UDP_MAGIC = Buffer.from('TRSU', 'latin1')
export const U = {
  BIND: 0x01,
  DATA: 0x02,
  PING: 0x03,
  UNBIND: 0x04,
  BOUND: 0x81,
  DATA_FROM: 0x82,
  PONG: 0x83,
  ERROR: 0x8f,
} as const
export const MAX_UDP_PAYLOAD = 1200
const KEY_LEN = 8
const TOKEN_CHARS = /^[\x21-\x7e]+$/

export function udpPacket(type: number, ...parts: Buffer[]): Buffer {
  return Buffer.concat([UDP_MAGIC, Buffer.from([type]), ...parts])
}

export class UdpRelay {
  readonly deps: Deps
  readonly socket: dgram.Socket
  private readonly stunLimiter: KeyedLimiter
  private readonly bindLimiter: KeyedLimiter
  private readonly errorLimiter: KeyedLimiter
  private sweeper: ReturnType<typeof setInterval> | null = null
  bytes = 0

  constructor(deps: Deps) {
    this.deps = deps
    const c = deps.config
    this.stunLimiter = new KeyedLimiter(c.stunPerIpPerSec, 1000, deps.now)
    this.bindLimiter = new KeyedLimiter(c.udpBindsPerIpPerMin, 60_000, deps.now)
    this.errorLimiter = new KeyedLimiter(1, 1000, deps.now)
    this.socket = dgram.createSocket({ type: 'udp4' })
    this.socket.on('message', (msg, rinfo) => {
      try {
        this.onMessage(msg, rinfo.address, rinfo.port)
      } catch (err) {
        deps.log(`udp handler error: ${(err as Error).message}`)
      }
    })
    this.socket.on('error', (err) => deps.log(`udp socket error: ${err.message}`))
  }

  listen(): Promise<number> {
    const c = this.deps.config
    return new Promise((resolve, reject) => {
      this.socket.once('error', reject)
      this.socket.bind(c.udpPort, c.bindHost, () => {
        this.socket.off('error', reject)
        this.sweeper = setInterval(() => this.deps.registry.sweepUdp(this.deps.now(), c.udpBindingTtlMs), Math.min(5000, c.udpBindingTtlMs / 2))
        this.sweeper.unref()
        resolve(this.socket.address().port)
      })
    })
  }

  private send(buf: Buffer, address: string, port: number): void {
    this.socket.send(buf, port, address, () => {})
  }

  private error(code: ErrorCode, address: string, port: number): void {
    if (this.errorLimiter.take(`${address}:${port}`)) this.send(udpPacket(U.ERROR, Buffer.from(code, 'latin1')), address, port)
  }

  onMessage(msg: Buffer, address: string, port: number): void {
    const ip = normalizeIp(address)
    if (isStun(msg)) {
      if (!isBindingRequest(msg) || !this.stunLimiter.take(ip)) return
      const res = bindingResponse(msg, address, port)
      if (res) this.send(res, address, port)
      return
    }
    if (msg.length < 5 || !msg.subarray(0, 4).equals(UDP_MAGIC)) return
    const type = msg[4]!
    const body = msg.subarray(5)
    switch (type) {
      case U.BIND:
        return this.bind(body, ip, address, port)
      case U.DATA:
        return this.data(body, address, port)
      case U.PING: {
        const b = this.binding(body, address, port)
        if (b && body.length === KEY_LEN) this.send(udpPacket(U.PONG, body), address, port)
        return
      }
      case U.UNBIND: {
        const b = this.binding(body, address, port)
        if (b && body.length === KEY_LEN) this.deps.registry.removeBinding(b)
        return
      }
      default:
    }
  }

  /** Bindung zum Schlüssel – nur von genau der Adresse, die gebunden hat. Frischt sie auf. */
  private binding(body: Buffer, address: string, port: number): UdpBinding | null {
    if (body.length < KEY_LEN) return null
    const b = this.deps.registry.udpByKey.get(body.subarray(0, KEY_LEN).toString('hex'))
    if (!b || b.address !== address || b.port !== port) return null
    b.lastSeen = this.deps.now()
    return b
  }

  private bind(body: Buffer, ip: string, address: string, port: number): void {
    const { config, registry, now } = this.deps
    if (!this.bindLimiter.take(ip)) return this.error('rate_limited', address, port)
    const raw = body.toString('latin1')
    if (!TOKEN_CHARS.test(raw)) return this.error('bad_token', address, port)
    const r = verifyToken(raw, config.secrets, Math.floor(now() / 1000), config.clockSkewSec)
    if (!r.ok) return this.error(r.code, address, port)
    const t = r.token
    const room = t.role === 'host' ? registry.forHost(t.r, t.h, t.m, now()) : registry.get(t.r)
    if (t.role === 'host' && !room) return this.error('bad_token', address, port)
    if (t.role === 'guest') {
      if (!room || room.hostUuid !== t.h || (!room.control && !room.udpHost)) return this.error('host_offline', address, port)
      if (room.kicked.has(t.u)) return this.error('kicked', address, port)
      if (!room.canAdmit(t.u, config.maxRoomPlayers)) return this.error('room_full', address, port)
    }
    const key = randomBytes(KEY_LEN)
    registry.addBinding({ keyHex: key.toString('hex'), room: room!, uuid: t.u, role: t.role, address, port, lastSeen: now() })
    this.send(udpPacket(U.BOUND, key), address, port)
  }

  private data(body: Buffer, address: string, port: number): void {
    const b = this.binding(body, address, port)
    if (!b) return
    const room = b.room
    let target: UdpBinding | undefined
    let payload: Buffer
    if (b.role === 'guest') {
      payload = body.subarray(KEY_LEN)
      if (!room.udpHost) return this.error('host_offline', address, port)
      target = room.udpHost
    } else {
      if (body.length < KEY_LEN + 16) return
      target = room.udpGuests.get(body.subarray(KEY_LEN, KEY_LEN + 16).toString('hex'))
      payload = body.subarray(KEY_LEN + 16)
      if (!target) return
    }
    if (payload.length > MAX_UDP_PAYLOAD) return
    const out = udpPacket(U.DATA_FROM, Buffer.from(b.uuid, 'hex'), payload)
    // Über dem Budget → verwerfen (UDP darf verlieren, der Datenstrom darüber wiederholt).
    if (!room.bandwidth.take(this.deps.now(), out.length)) return
    room.bytes += out.length
    this.bytes += out.length
    this.send(out, target.address, target.port)
  }

  sweepLimiters(): void {
    this.stunLimiter.sweep()
    this.bindLimiter.sweep()
    this.errorLimiter.sweep()
  }

  close(): Promise<void> {
    if (this.sweeper) clearInterval(this.sweeper)
    return new Promise((resolve) => this.socket.close(() => resolve()))
  }
}
