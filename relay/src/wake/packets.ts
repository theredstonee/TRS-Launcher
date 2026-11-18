import {
  McError, type Reader, dashUuid, nbtString, packet, writeLong, writeString, writeUuid, writeVarInt,
} from './mc.ts'

/** Protokollnummern, die wir beim Namen brauchen. */
export const P = {
  /** 1.19: Login Start mit optionalen Signaturdaten. */
  V1_19: 759,
  /** 1.19.1/1.19.2: zusätzlich optionale UUID. */
  V1_19_1: 760,
  /** 1.19.3: Signaturdaten weg, UUID optional. */
  V1_19_3: 761,
  /** 1.20.2: UUID Pflicht, Konfigurationsphase. */
  V1_20_2: 764,
  /** 1.20.3: Text-Komponenten als NBT (außer Login-Disconnect). */
  V1_20_3: 765,
  /** 1.20.5: Transfer. */
  V1_20_5: 766,
  /** 1.21.2: Login Success ohne „strict error handling“. */
  V1_21_2: 768,
} as const

/** Snapshots zählen ab 0x40000000 – deren Paket-IDs kennen wir nicht. */
const SNAPSHOT_BASE = 0x4000_0000

/** Paket-IDs je Protokollbereich für den Weg Login → Konfiguration → Transfer. */
export interface TransferProto {
  min: number
  max: number
  /** Login Success hat am Ende noch `strictErrorHandling` (nur 1.20.5–1.21.1). */
  strictErrorHandling: boolean
  /** serverbound, Login-Phase */
  loginAck: number
  /** clientbound, Konfigurationsphase */
  cfgDisconnect: number
  cfgKeepAlive: number
  cfgTransfer: number
}

/**
 * Stand: 1.20.5 (766) bis 26.1 (775). Seit 1.20.5 sind diese IDs gleich geblieben, neue Pakete
 * kamen nur hinten dazu (Server Links, Dialoge, Code of Conduct).
 */
export const TRANSFER_PROTOCOLS: readonly TransferProto[] = [
  { min: 766, max: 767, strictErrorHandling: true, loginAck: 0x03, cfgDisconnect: 0x02, cfgKeepAlive: 0x04, cfgTransfer: 0x0b },
  { min: 768, max: 775, strictErrorHandling: false, loginAck: 0x03, cfgDisconnect: 0x02, cfgKeepAlive: 0x04, cfgTransfer: 0x0b },
]

export const NEWEST_KNOWN_PROTOCOL = TRANSFER_PROTOCOLS[TRANSFER_PROTOCOLS.length - 1]!.max

/**
 * Tabelle für `protocol`, `null` = kein Transfer (zu alt oder Snapshot). Neuere Releases als die
 * Tabelle bekommen den letzten Eintrag (IDs bisher stabil).
 */
export function transferProto(protocol: number): TransferProto | null {
  if (protocol < P.V1_20_5 || protocol >= SNAPSHOT_BASE) return null
  for (const t of TRANSFER_PROTOCOLS) if (protocol >= t.min && protocol <= t.max) return t
  return protocol > NEWEST_KNOWN_PROTOCOL ? TRANSFER_PROTOCOLS[TRANSFER_PROTOCOLS.length - 1]! : null
}

// Handshake

export interface Handshake {
  protocol: number
  /** roh, wie gesendet */
  address: string
  port: number
  /** 1 status, 2 login, 3 transfer */
  intent: number
}

export function parseHandshake(r: Reader): Handshake {
  const protocol = r.varInt()
  const address = r.string(255)
  const port = r.u16()
  const intent = r.varInt()
  if (intent < 1 || intent > 3) throw new McError('bad intent')
  return { protocol, address, port, intent }
}

/** Adresse aus dem Handshake: alles ab `\0` (Forge/FML) weg, Punkt am Ende weg, klein. */
export function normalizeHost(address: string): string {
  const cut = address.indexOf('\0')
  let h = cut >= 0 ? address.slice(0, cut) : address
  if (h.endsWith('.')) h = h.slice(0, -1)
  return h.toLowerCase()
}

const LABEL = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/

/** Gültig ist nur `<name>.<domain>` mit genau einem Label vorn. Liefert den FQDN oder null. */
export function hostingHost(address: string, domain: string): string | null {
  const h = normalizeHost(address)
  const suffix = `.${domain.toLowerCase()}`
  if (!h.endsWith(suffix)) return null
  const name = h.slice(0, -suffix.length)
  return LABEL.test(name) ? h : null
}

// Login

export interface LoginStart {
  name: string
  /** 32 Hex ohne Bindestriche, nur wenn der Client sie schickt (ab 1.19.1 optional, ab 1.20.2 immer) */
  uuid: string | null
}

export function parseLoginStart(r: Reader, protocol: number): LoginStart {
  const name = r.string(16)
  if (!/^[\x21-\x7e]{1,16}$/.test(name)) throw new McError('bad name')
  let uuid: string | null = null
  if (protocol >= P.V1_20_2) {
    uuid = r.uuid()
  } else if (protocol >= P.V1_19_3) {
    if (r.bool()) uuid = r.uuid()
  } else if (protocol >= P.V1_19) {
    if (r.bool()) {
      r.long()
      r.byteArray(512)
      r.byteArray(4096)
    }
    if (protocol >= P.V1_19_1 && r.bool()) uuid = r.uuid()
  }
  return { name, uuid }
}

/** Login Disconnect (0x00): in allen Versionen JSON-Text. */
export function loginDisconnect(text: string): Buffer {
  return packet(0x00, writeString(JSON.stringify({ text })))
}

/** Login Success ab 1.20.5: UUID, Name, leere Properties (+ strict error handling bis 1.21.1). */
export function loginSuccess(t: TransferProto, uuid: string, name: string): Buffer {
  const parts = [writeUuid(uuid), writeString(name), writeVarInt(0)]
  if (t.strictErrorHandling) parts.push(Buffer.from([0]))
  return packet(0x02, ...parts)
}

/** Konfigurations-Disconnect: Text als NBT (ab 1.20.3), sonst JSON. */
export function configDisconnect(t: TransferProto, protocol: number, text: string): Buffer {
  const body = protocol >= P.V1_20_3 ? nbtString(text) : writeString(JSON.stringify({ text }))
  return packet(t.cfgDisconnect, body)
}

export function configKeepAlive(t: TransferProto, id: bigint): Buffer {
  return packet(t.cfgKeepAlive, writeLong(id))
}

export function transfer(t: TransferProto, host: string, port: number): Buffer {
  return packet(t.cfgTransfer, writeString(host), writeVarInt(port))
}

// Status

export function statusResponse(json: unknown): Buffer {
  return packet(0x00, writeString(JSON.stringify(json)))
}

export function pong(payload: bigint): Buffer {
  return packet(0x01, writeLong(payload))
}

export { dashUuid }
