import { bandwidthBucket, type TokenBucket } from './limits.ts'
import type { ErrorCode } from './protocol.ts'

/** Steuerverbindung des Hosts (TCP). */
export interface ControlHandle {
  send: (buf: Buffer) => void
  fail: (code: ErrorCode) => void
}

/** Eine TCP-Verbindung eines Gasts (wartend oder gekoppelt). */
export interface GuestHandle {
  readonly pairIdHex: string
  /** Wartend: ERROR `code` und schließen; gekoppelt: Paar schließen (roh, ohne Frame). */
  kick: (code: ErrorCode) => void
}

export interface UdpBinding {
  keyHex: string
  room: Room
  uuid: string
  role: 'host' | 'guest'
  address: string
  port: number
  lastSeen: number
}

export class Room {
  readonly id: string
  readonly hostUuid: string
  maxPlayers: number
  control: ControlHandle | null = null
  /** Vom Host gekickte UUIDs – gilt, solange diese Steuerverbindung lebt. */
  readonly kicked = new Set<string>()
  readonly guestConns = new Map<string, Set<GuestHandle>>()
  udpHost: UdpBinding | null = null
  readonly udpGuests = new Map<string, UdpBinding>()
  readonly bandwidth: TokenBucket
  bytes = 0

  constructor(id: string, hostUuid: string, maxPlayers: number, bandwidthMbit: number, now: number) {
    this.id = id
    this.hostUuid = hostUuid
    this.maxPlayers = maxPlayers
    this.bandwidth = bandwidthBucket(bandwidthMbit, now)
  }

  hasGuest(uuid: string): boolean {
    return this.guestConns.has(uuid) || this.udpGuests.has(uuid)
  }

  guestCount(): number {
    const all = new Set<string>(this.guestConns.keys())
    for (const u of this.udpGuests.keys()) all.add(u)
    return all.size
  }

  /** Gäste = Spieler ohne Host: höchstens min(m, Obergrenze) − 1 verschiedene UUIDs. */
  canAdmit(uuid: string, maxRoomPlayers: number): boolean {
    if (this.hasGuest(uuid)) return true
    return this.guestCount() < Math.min(this.maxPlayers, maxRoomPlayers) - 1
  }

  connsOf(uuid: string): number {
    return this.guestConns.get(uuid)?.size ?? 0
  }

  addGuestConn(uuid: string, g: GuestHandle): void {
    let s = this.guestConns.get(uuid)
    if (!s) {
      s = new Set()
      this.guestConns.set(uuid, s)
    }
    s.add(g)
  }

  removeGuestConn(uuid: string, g: GuestHandle): void {
    const s = this.guestConns.get(uuid)
    if (!s) return
    s.delete(g)
    if (s.size === 0) this.guestConns.delete(uuid)
  }

  allGuestConns(): GuestHandle[] {
    return [...this.guestConns.values()].flatMap((s) => [...s])
  }

  empty(): boolean {
    return !this.control && this.guestConns.size === 0 && !this.udpHost && this.udpGuests.size === 0
  }
}

/** Alle Räume (nur im RAM – die API ist die Quelle der Wahrheit). */
export class RoomRegistry {
  readonly rooms = new Map<string, Room>()
  readonly udpByKey = new Map<string, UdpBinding>()
  private readonly bandwidthMbit: number

  constructor(bandwidthMbit: number) {
    this.bandwidthMbit = bandwidthMbit
  }

  get(id: string): Room | undefined {
    return this.rooms.get(id)
  }

  /** Raum für einen Host-Token; `null`, wenn die Raum-ID schon einem anderen Host gehört. */
  forHost(id: string, hostUuid: string, maxPlayers: number, now: number): Room | null {
    let r = this.rooms.get(id)
    if (r && r.hostUuid !== hostUuid) return null
    if (!r) {
      r = new Room(id, hostUuid, maxPlayers, this.bandwidthMbit, now)
      this.rooms.set(id, r)
    }
    r.maxPlayers = maxPlayers
    return r
  }

  /** Leeren Raum entfernen. */
  release(room: Room): void {
    if (room.empty() && this.rooms.get(room.id) === room) this.rooms.delete(room.id)
  }

  addBinding(b: UdpBinding): void {
    const room = b.room
    if (b.role === 'host') {
      if (room.udpHost) this.udpByKey.delete(room.udpHost.keyHex)
      room.udpHost = b
    } else {
      const old = room.udpGuests.get(b.uuid)
      if (old) this.udpByKey.delete(old.keyHex)
      room.udpGuests.set(b.uuid, b)
    }
    this.udpByKey.set(b.keyHex, b)
  }

  removeBinding(b: UdpBinding): void {
    if (this.udpByKey.get(b.keyHex) === b) this.udpByKey.delete(b.keyHex)
    const room = b.room
    if (b.role === 'host') {
      if (room.udpHost === b) room.udpHost = null
    } else if (room.udpGuests.get(b.uuid) === b) {
      room.udpGuests.delete(b.uuid)
    }
    this.release(room)
  }

  /** Gekickter Gast: UDP-Bindung des Raums entfernen. */
  kickUdp(room: Room, uuid: string): void {
    const b = room.udpGuests.get(uuid)
    if (b) this.removeBinding(b)
  }

  /** Abgelaufene UDP-Bindungen entfernen. */
  sweepUdp(now: number, ttlMs: number): void {
    for (const b of [...this.udpByKey.values()]) if (now - b.lastSeen > ttlMs) this.removeBinding(b)
  }
}
