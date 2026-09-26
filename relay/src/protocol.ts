/** TCP-Protokoll (siehe PROTOCOL.md). */

export const PREAMBLE = Buffer.from([0x54, 0x52, 0x53, 0x52, 0x01]) // "TRSR" + Version 1
export const MAX_FRAME_PAYLOAD = 1024
export const FRAME_HEADER = 3

export const T = {
  HOST_HELLO: 0x01,
  GUEST_HELLO: 0x02,
  PAIR: 0x03,
  GUEST_OPEN: 0x20,
  GUEST_CLOSED: 0x21,
  CLOSE_GUEST: 0x22,
  KICK: 0x23,
  PING: 0x30,
  PONG: 0x31,
  WELCOME: 0x81,
  ERROR: 0x8f,
} as const

export type ErrorCode =
  | 'bad_preamble' | 'bad_frame' | 'bad_token' | 'expired' | 'host_offline' | 'host_timeout'
  | 'unknown_pair' | 'room_full' | 'too_many_connections' | 'rate_limited' | 'replaced' | 'kicked'
  | 'server_full' | 'shutting_down' | 'idle_timeout' | 'buffer_overflow'

export function frame(type: number, payload: Buffer | string = Buffer.alloc(0)): Buffer {
  const p = typeof payload === 'string' ? Buffer.from(payload, 'utf8') : payload
  if (p.length > 0xffff) throw new RangeError('frame payload too large')
  const h = Buffer.allocUnsafe(FRAME_HEADER)
  h.writeUInt8(type, 0)
  h.writeUInt16BE(p.length, 1)
  return Buffer.concat([h, p])
}

export function errorFrame(code: ErrorCode): Buffer {
  return frame(T.ERROR, code)
}

export interface Frame {
  type: number
  payload: Buffer
}

/**
 * Zerlegt einen Bytestrom in Frames. `push` liefert fertige Frames; zu große Frames → `error`.
 * `rest()` gibt übrige, noch nicht als Frame gelesene Bytes zurück (Wechsel in den Rohmodus).
 */
export class FrameReader {
  private buf: Buffer = Buffer.alloc(0)
  private readonly max: number

  constructor(max = MAX_FRAME_PAYLOAD) {
    this.max = max
  }

  push(chunk: Buffer): void {
    this.buf = this.buf.length === 0 ? chunk : Buffer.concat([this.buf, chunk])
  }

  /** Nächstes Frame, `null` wenn unvollständig, `'error'` wenn zu groß. */
  next(): Frame | null | 'error' {
    if (this.buf.length < FRAME_HEADER) return null
    const len = this.buf.readUInt16BE(1)
    if (len > this.max) return 'error'
    if (this.buf.length < FRAME_HEADER + len) return null
    const f = { type: this.buf[0]!, payload: Buffer.from(this.buf.subarray(FRAME_HEADER, FRAME_HEADER + len)) }
    this.buf = this.buf.subarray(FRAME_HEADER + len)
    return f
  }

  rest(): Buffer {
    const r = this.buf
    this.buf = Buffer.alloc(0)
    return r
  }

  get pending(): number {
    return this.buf.length
  }
}

export function uuidToBytes(uuid: string): Buffer {
  return Buffer.from(uuid, 'hex')
}

export function bytesToUuid(b: Buffer): string {
  return b.toString('hex')
}
