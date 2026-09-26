import { lookup, resolveSrv } from 'node:dns/promises'
import { BlockList, isIP, Socket } from 'node:net'
import { sanitizeServerIcon } from './images'

/**
 * Status für Server-Einladungen (Icon, Spielerzahl, MOTD) – der **Server** fragt den
 * Minecraft-Server (Server List Ping), nicht die Empfänger. So sieht ein fremder Server nie
 * die IP-Adressen der Empfänger, und die Anzeige ist nicht vom Absender fälschbar.
 *
 * SSRF-Schutz:
 * - Adresse wie im Spiel: `host[:port]`, ohne Port zuerst SRV `_minecraft._tcp.<host>`.
 * - DNS wird genau einmal aufgelöst; verbunden wird mit der geprüften IP (kein DNS-Rebinding).
 * - Nur öffentliche Unicast-Adressen (keine privaten, Loopback-, Link-Local-, CGNAT-, Multicast-,
 *   Dokumentations- oder reservierten Netze, IPv4-in-IPv6 inklusive), nur Ports 1024–65535.
 * - Harte Grenzen: 3 s Zeitlimit, Antwort ≤ 256 KiB, höchstens 8 Abfragen gleichzeitig, Cache
 *   60 s (erreichbar) bzw. 30 s (nicht erreichbar), Aufrufer-Limit je Konto (Route).
 */

export interface ServerStatus {
  address: string
  /** `false`, wenn nicht erreichbar oder nicht abfragbar (siehe `reason`). */
  online: boolean
  reason: 'private_address' | 'unresolvable' | 'timeout' | 'refused' | 'invalid_response' | 'disabled' | 'busy' | null
  version: { name: string, protocol: number } | null
  players: { online: number, max: number } | null
  /** Beschreibung als reiner Text (Formatcodes entfernt), ≤ 256 Zeichen. */
  motd: string | null
  /** 64×64-PNG als Data-URL (serverseitig neu kodiert) oder `null`. */
  icon: string | null
  latencyMs: number | null
  checkedAt: string
}

export interface PingDeps {
  /** SRV-Auflösung; `null` = kein Eintrag. */
  resolveSrv: (name: string) => Promise<{ name: string, port: number } | null>
  /** Alle Adressen eines Hostnamens. */
  lookup: (host: string) => Promise<string[]>
  /** Öffnet eine TCP-Verbindung (für Tests austauschbar). */
  connect: (ip: string, port: number) => Socket
  /** Private Adressen erlauben (nur Tests). */
  allowPrivate?: boolean
}

// Getrennte Listen: eine gemeinsame Liste prüft IPv4 auch gegen IPv6-Regeln (::ffff:0:0/96 träfe dann alles).
const blocked4 = new BlockList()
const blocked6 = new BlockList()
for (const [net, prefix] of [
  ['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8], ['169.254.0.0', 16], ['172.16.0.0', 12],
  ['192.0.0.0', 24], ['192.0.2.0', 24], ['192.88.99.0', 24], ['192.168.0.0', 16], ['198.18.0.0', 15],
  ['198.51.100.0', 24], ['203.0.113.0', 24], ['224.0.0.0', 4], ['240.0.0.0', 4],
] as const) blocked4.addSubnet(net, prefix, 'ipv4')
for (const [net, prefix] of [
  ['::', 128], ['::1', 128], ['::ffff:0:0', 96], ['64:ff9b::', 96], ['64:ff9b:1::', 48], ['100::', 64], ['2001::', 23],
  ['2001:db8::', 32], ['2002::', 16], ['fc00::', 7], ['fe80::', 10], ['fec0::', 10], ['ff00::', 8],
] as const) blocked6.addSubnet(net, prefix, 'ipv6')

/** Öffentliche Unicast-Adresse? IPv4-in-IPv6 (::ffff:…) gilt immer als nicht erlaubt. */
export function isPublicIp(ip: string): boolean {
  const v = isIP(ip)
  if (v === 4) return !blocked4.check(ip, 'ipv4')
  if (v === 6) return !blocked6.check(ip, 'ipv6')
  return false
}

export const defaultPingDeps: PingDeps = {
  async resolveSrv(name) {
    try {
      const r = await resolveSrv(name)
      const best = r.sort((a, b) => a.priority - b.priority || b.weight - a.weight)[0]
      return best ? { name: best.name, port: best.port } : null
    } catch {
      return null
    }
  },
  async lookup(host) {
    const r = await lookup(host, { all: true, verbatim: true })
    return r.map((a) => a.address)
  },
  connect(ip, port) {
    return new Socket().connect({ host: ip, port })
  },
}

/** `host[:port]` zerlegen (Syntax ist vorher per Schema geprüft). */
export function splitAddress(address: string): { host: string, port: number | null } {
  const m = /^(.*?)(?::(\d{1,5}))?$/.exec(address.trim().toLowerCase())!
  return { host: m[1]!.replace(/\.$/, ''), port: m[2] ? Number(m[2]) : null }
}

function varint(n: number): Buffer {
  const out: number[] = []
  let v = n >>> 0
  do {
    let b = v & 0x7f
    v >>>= 7
    if (v !== 0) b |= 0x80
    out.push(b)
  } while (v !== 0)
  return Buffer.from(out)
}

function packet(id: number, payload: Buffer): Buffer {
  const body = Buffer.concat([varint(id), payload])
  return Buffer.concat([varint(body.length), body])
}

function readVarint(buf: Buffer, off: number): { value: number, next: number } | null {
  let value = 0
  for (let i = 0; i < 5; i++) {
    if (off + i >= buf.length) return null
    const b = buf[off + i]!
    value |= (b & 0x7f) << (7 * i)
    if ((b & 0x80) === 0) return { value: value >>> 0, next: off + i + 1 }
  }
  throw new Error('varint too long')
}

const MAX_RESPONSE = 256 * 1024

/** Chat-Komponente (String oder Objekt mit text/extra) → reiner Text ohne §-Codes. */
export function flattenMotd(desc: unknown, depth = 0): string {
  if (depth > 8) return ''
  if (typeof desc === 'string') return desc
  if (Array.isArray(desc)) return desc.slice(0, 64).map((d) => flattenMotd(d, depth + 1)).join('')
  if (desc && typeof desc === 'object') {
    const o = desc as { text?: unknown, extra?: unknown, translate?: unknown }
    return (typeof o.text === 'string' ? o.text : typeof o.translate === 'string' ? o.translate : '') + flattenMotd(o.extra ?? '', depth + 1)
  }
  return ''
}

const cleanText = (s: string, max: number) =>
  s.replace(/§./g, '').replace(/[\p{Cc}\p{Cf}]/gu, (c) => (c === '\n' ? '\n' : '')).trim().slice(0, max)

/** Ein Server List Ping an `ip:port`; `host` geht als Hostname in den Handshake. */
export function slp(deps: PingDeps, ip: string, port: number, host: string, timeoutMs: number): Promise<{ json: unknown, latencyMs: number }> {
  return new Promise((resolve, reject) => {
    const started = Date.now()
    const socket = deps.connect(ip, port)
    let buf = Buffer.alloc(0)
    let done = false
    const finish = (err: Error | null, value?: { json: unknown, latencyMs: number }) => {
      if (done) return
      done = true
      clearTimeout(timer)
      socket.destroy()
      if (err) reject(err)
      else resolve(value!)
    }
    const timer = setTimeout(() => finish(Object.assign(new Error('timeout'), { code: 'ETIMEDOUT' })), timeoutMs)
    socket.once('error', (err) => finish(err))
    // Verbindung zu, bevor eine vollständige Antwort da war: mit Daten = kaputte Antwort, sonst abgelehnt.
    socket.once('close', () => finish(Object.assign(new Error('closed'), { code: buf.length > 0 ? 'EINVALID' : 'ECONNRESET' })))
    socket.once('connect', () => {
      const hostBuf = Buffer.from(host.slice(0, 255), 'utf8')
      const portBuf = Buffer.alloc(2)
      portBuf.writeUInt16BE(port)
      // Handshake (Protokoll 767 = 1.21, der Server antwortet unabhängig davon), dann Status-Anfrage.
      socket.write(Buffer.concat([
        packet(0x00, Buffer.concat([varint(767), varint(hostBuf.length), hostBuf, portBuf, varint(1)])),
        packet(0x00, Buffer.alloc(0)),
      ]))
    })
    socket.on('data', (chunk: Buffer) => {
      buf = Buffer.concat([buf, chunk])
      if (buf.length > MAX_RESPONSE + 16) return finish(Object.assign(new Error('too large'), { code: 'EINVALID' }))
      try {
        const len = readVarint(buf, 0)
        if (!len) return
        if (len.value > MAX_RESPONSE) return finish(Object.assign(new Error('too large'), { code: 'EINVALID' }))
        if (buf.length < len.next + len.value) return
        const id = readVarint(buf, len.next)
        if (!id || id.value !== 0) return finish(Object.assign(new Error('bad packet'), { code: 'EINVALID' }))
        const strLen = readVarint(buf, id.next)
        if (!strLen || strLen.next + strLen.value > len.next + len.value) return finish(Object.assign(new Error('bad string'), { code: 'EINVALID' }))
        const json: unknown = JSON.parse(buf.toString('utf8', strLen.next, strLen.next + strLen.value))
        finish(null, { json, latencyMs: Date.now() - started })
      } catch {
        finish(Object.assign(new Error('bad response'), { code: 'EINVALID' }))
      }
    })
  })
}

interface CacheEntry {
  at: number
  ttl: number
  value?: ServerStatus
  pending?: Promise<ServerStatus>
}

export class ServerStatusService {
  private cache = new Map<string, CacheEntry>()
  private running = 0

  constructor(
    private readonly enabled: boolean,
    private readonly now: () => number = Date.now,
    private readonly deps: PingDeps = defaultPingDeps,
    private readonly opts: { timeoutMs?: number, maxConcurrent?: number, okTtlMs?: number, failTtlMs?: number, maxEntries?: number } = {},
  ) {}

  private result(address: string, partial: Partial<ServerStatus>): ServerStatus {
    return {
      address,
      online: false,
      reason: null,
      version: null,
      players: null,
      motd: null,
      icon: null,
      latencyMs: null,
      checkedAt: new Date(this.now()).toISOString(),
      ...partial,
    }
  }

  /** Status mit Cache; nie wirft es – Fehler landen in `reason`. */
  async status(rawAddress: string): Promise<ServerStatus> {
    const address = rawAddress.trim().toLowerCase()
    if (!this.enabled) return this.result(address, { reason: 'disabled' })
    const t = this.now()
    const hit = this.cache.get(address)
    if (hit?.value && t - hit.at < hit.ttl) return hit.value
    if (hit?.pending) return hit.pending
    if (this.running >= (this.opts.maxConcurrent ?? 8)) return this.result(address, { reason: 'busy' })
    if (this.cache.size >= (this.opts.maxEntries ?? 5000)) this.prune()
    const pending = this.fetch(address).then((value) => {
      this.cache.set(address, { at: this.now(), ttl: value.online ? (this.opts.okTtlMs ?? 60_000) : (this.opts.failTtlMs ?? 30_000), value })
      return value
    })
    this.cache.set(address, { at: t, ttl: 0, pending })
    return pending
  }

  private prune(): void {
    const t = this.now()
    for (const [k, e] of this.cache) if (!e.pending && t - e.at >= e.ttl) this.cache.delete(k)
    if (this.cache.size >= (this.opts.maxEntries ?? 5000)) this.cache.clear()
  }

  private async fetch(address: string): Promise<ServerStatus> {
    this.running++
    try {
      const { host, port: explicit } = splitAddress(address)
      let target = host
      let port = explicit ?? 25565
      if (explicit === null && isIP(host) === 0) {
        const srv = await this.deps.resolveSrv(`_minecraft._tcp.${host}`)
        if (srv) {
          target = srv.name.replace(/\.$/, '').toLowerCase()
          port = srv.port
        }
      }
      if (port < 1024 || port > 65535) return this.result(address, { reason: 'private_address' })
      let ips: string[]
      if (isIP(target) !== 0) ips = [target]
      else {
        try {
          ips = await this.deps.lookup(target)
        } catch {
          return this.result(address, { reason: 'unresolvable' })
        }
      }
      if (ips.length === 0) return this.result(address, { reason: 'unresolvable' })
      const allowed = this.deps.allowPrivate ? ips : ips.filter(isPublicIp)
      if (allowed.length === 0) return this.result(address, { reason: 'private_address' })
      let res: { json: unknown, latencyMs: number }
      try {
        res = await slp(this.deps, allowed[0]!, port, host, this.opts.timeoutMs ?? 3000)
      } catch (err) {
        const code = (err as { code?: string }).code
        return this.result(address, { reason: code === 'ETIMEDOUT' ? 'timeout' : code === 'EINVALID' ? 'invalid_response' : 'refused' })
      }
      return this.parse(address, res.json, res.latencyMs)
    } finally {
      this.running--
    }
  }

  private parse(address: string, json: unknown, latencyMs: number): ServerStatus {
    if (!json || typeof json !== 'object') return this.result(address, { reason: 'invalid_response' })
    const o = json as { version?: { name?: unknown, protocol?: unknown }, players?: { online?: unknown, max?: unknown }, description?: unknown, favicon?: unknown }
    const int = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? Math.max(0, Math.min(1_000_000_000, Math.trunc(v))) : 0)
    return this.result(address, {
      online: true,
      version: o.version && typeof o.version.name === 'string'
        ? { name: cleanText(o.version.name, 64), protocol: int(o.version.protocol) }
        : null,
      players: o.players ? { online: int(o.players.online), max: int(o.players.max) } : null,
      motd: cleanText(flattenMotd(o.description ?? ''), 256) || null,
      icon: sanitizeServerIcon(o.favicon),
      latencyMs,
    })
  }
}
