import { isIPv4 } from 'node:net'
import { crc32 } from 'node:zlib'

/** STUN (RFC 5389) – nur Binding Request → Binding Success Response ohne Authentifizierung. */
export const MAGIC_COOKIE = 0x2112a442
export const BINDING_REQUEST = 0x0001
export const BINDING_SUCCESS = 0x0101
export const ATTR_MAPPED_ADDRESS = 0x0001
export const ATTR_XOR_MAPPED_ADDRESS = 0x0020
export const ATTR_FINGERPRINT = 0x8028
const FINGERPRINT_XOR = 0x5354554e
export const MAX_STUN_REQUEST = 548

/** Sieht wie STUN aus (erste zwei Bits 0 + Magic Cookie)? */
export function isStun(msg: Buffer): boolean {
  return msg.length >= 20 && (msg[0]! & 0xc0) === 0 && msg.readUInt32BE(4) === MAGIC_COOKIE
}

/** Gültiger Binding Request? Attribute werden ignoriert, die Länge muss aber stimmen. */
export function isBindingRequest(msg: Buffer): boolean {
  if (!isStun(msg) || msg.length > MAX_STUN_REQUEST) return false
  const len = msg.readUInt16BE(2)
  return msg.readUInt16BE(0) === BINDING_REQUEST && len % 4 === 0 && len === msg.length - 20
}

function ipv4Bytes(address: string): Buffer | null {
  const a = address.startsWith('::ffff:') ? address.slice(7) : address
  if (!isIPv4(a)) return null
  return Buffer.from(a.split('.').map(Number))
}

/** Antwort mit XOR-MAPPED-ADDRESS, MAPPED-ADDRESS und FINGERPRINT (52 Bytes). `null` bei Nicht-IPv4. */
export function bindingResponse(request: Buffer, address: string, port: number): Buffer | null {
  const ip = ipv4Bytes(address)
  if (!ip) return null
  const out = Buffer.alloc(20 + 12 + 12 + 8)
  out.writeUInt16BE(BINDING_SUCCESS, 0)
  out.writeUInt16BE(out.length - 20, 2)
  out.writeUInt32BE(MAGIC_COOKIE, 4)
  request.copy(out, 8, 8, 20) // Transaktions-ID
  let o = 20
  // XOR-MAPPED-ADDRESS
  out.writeUInt16BE(ATTR_XOR_MAPPED_ADDRESS, o)
  out.writeUInt16BE(8, o + 2)
  out.writeUInt8(0, o + 4)
  out.writeUInt8(0x01, o + 5)
  out.writeUInt16BE(port ^ (MAGIC_COOKIE >>> 16), o + 6)
  out.writeUInt32BE((ip.readUInt32BE(0) ^ MAGIC_COOKIE) >>> 0, o + 8)
  o += 12
  // MAPPED-ADDRESS (für alte Clients)
  out.writeUInt16BE(ATTR_MAPPED_ADDRESS, o)
  out.writeUInt16BE(8, o + 2)
  out.writeUInt8(0, o + 4)
  out.writeUInt8(0x01, o + 5)
  out.writeUInt16BE(port, o + 6)
  ip.copy(out, o + 8)
  o += 12
  // FINGERPRINT: CRC-32 über alles davor (Länge enthält schon den FINGERPRINT) XOR 0x5354554e
  out.writeUInt16BE(ATTR_FINGERPRINT, o)
  out.writeUInt16BE(4, o + 2)
  out.writeUInt32BE((crc32(out.subarray(0, o)) ^ FINGERPRINT_XOR) >>> 0, o + 4)
  return out
}

export interface ParsedBinding {
  txid: Buffer
  xorMapped: { address: string, port: number } | null
  mapped: { address: string, port: number } | null
  fingerprintOk: boolean | null
}

/** Antwort zerlegen (für Tests und das Prüfskript). */
export function parseBindingResponse(msg: Buffer): ParsedBinding | null {
  if (!isStun(msg) || msg.readUInt16BE(0) !== BINDING_SUCCESS || msg.readUInt16BE(2) !== msg.length - 20) return null
  const res: ParsedBinding = { txid: msg.subarray(8, 20), xorMapped: null, mapped: null, fingerprintOk: null }
  let o = 20
  while (o + 4 <= msg.length) {
    const type = msg.readUInt16BE(o)
    const len = msg.readUInt16BE(o + 2)
    const v = msg.subarray(o + 4, o + 4 + len)
    if ((type === ATTR_XOR_MAPPED_ADDRESS || type === ATTR_MAPPED_ADDRESS) && len === 8 && v[1] === 0x01) {
      let port = v.readUInt16BE(2)
      let ip = v.readUInt32BE(4)
      if (type === ATTR_XOR_MAPPED_ADDRESS) {
        port ^= MAGIC_COOKIE >>> 16
        ip = (ip ^ MAGIC_COOKIE) >>> 0
      }
      const address = [ip >>> 24, (ip >>> 16) & 255, (ip >>> 8) & 255, ip & 255].join('.')
      if (type === ATTR_XOR_MAPPED_ADDRESS) res.xorMapped = { address, port }
      else res.mapped = { address, port }
    }
    if (type === ATTR_FINGERPRINT && len === 4) {
      res.fingerprintOk = ((crc32(msg.subarray(0, o)) ^ FINGERPRINT_XOR) >>> 0) === v.readUInt32BE(0)
    }
    o += 4 + len + ((4 - (len % 4)) % 4)
  }
  return res
}

/** Binding Request bauen (Tests, Prüfskript). */
export function bindingRequest(txid: Buffer): Buffer {
  const b = Buffer.alloc(20)
  b.writeUInt16BE(BINDING_REQUEST, 0)
  b.writeUInt16BE(0, 2)
  b.writeUInt32BE(MAGIC_COOKIE, 4)
  txid.copy(b, 8, 0, 12)
  return b
}
