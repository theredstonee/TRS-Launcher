/** Minecraft-Java-Protokoll: nur das Nötigste für TRS Wake (unkomprimiert, ohne Verschlüsselung). */

export class McError extends Error {}

export function varIntSize(value: number): number {
  let v = value >>> 0
  let n = 1
  while (v >= 0x80) {
    v >>>= 7
    n++
  }
  return n
}

export function writeVarInt(value: number): Buffer {
  const out: number[] = []
  let v = value >>> 0
  while (v >= 0x80) {
    out.push((v & 0x7f) | 0x80)
    v >>>= 7
  }
  out.push(v)
  return Buffer.from(out)
}

export function writeString(s: string): Buffer {
  const b = Buffer.from(s, 'utf8')
  return Buffer.concat([writeVarInt(b.length), b])
}

export function writeLong(v: bigint): Buffer {
  const b = Buffer.alloc(8)
  b.writeBigInt64BE(v)
  return b
}

/** UUID (32 Hex, Bindestriche egal) → 16 Bytes. */
export function writeUuid(uuid: string): Buffer {
  const hex = uuid.replace(/-/g, '')
  if (!/^[0-9a-fA-F]{32}$/.test(hex)) throw new McError('bad uuid')
  return Buffer.from(hex, 'hex')
}

export function dashUuid(hex: string): string {
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** Paket = VarInt(Länge) + VarInt(ID) + Daten. */
export function packet(id: number, ...parts: Buffer[]): Buffer {
  const body = Buffer.concat([writeVarInt(id), ...parts])
  return Buffer.concat([writeVarInt(body.length), body])
}

/** Leser über einen Paket-Inhalt. Wirft McError bei kaputten Daten. */
export class Reader {
  private pos = 0
  readonly buf: Buffer

  constructor(buf: Buffer) {
    this.buf = buf
  }

  get left(): number {
    return this.buf.length - this.pos
  }

  private need(n: number): void {
    if (this.left < n) throw new McError('short packet')
  }

  varInt(): number {
    let value = 0
    for (let i = 0; i < 5; i++) {
      this.need(1)
      const b = this.buf[this.pos++]!
      value |= (b & 0x7f) << (7 * i)
      if ((b & 0x80) === 0) return value
    }
    throw new McError('varint too long')
  }

  /** `maxChars` wie im Spiel: Grenze in Zeichen, Bytes höchstens ×3 (+ Puffer für Surrogate). */
  string(maxChars: number): string {
    const len = this.varInt()
    if (len < 0 || len > maxChars * 4) throw new McError('string too long')
    this.need(len)
    const s = this.buf.toString('utf8', this.pos, this.pos + len)
    this.pos += len
    if (s.length > maxChars) throw new McError('string too long')
    return s
  }

  u8(): number {
    this.need(1)
    return this.buf[this.pos++]!
  }

  bool(): boolean {
    const b = this.u8()
    if (b > 1) throw new McError('bad boolean')
    return b === 1
  }

  u16(): number {
    this.need(2)
    const v = this.buf.readUInt16BE(this.pos)
    this.pos += 2
    return v
  }

  long(): bigint {
    this.need(8)
    const v = this.buf.readBigInt64BE(this.pos)
    this.pos += 8
    return v
  }

  bytes(n: number): Buffer {
    this.need(n)
    const b = this.buf.subarray(this.pos, this.pos + n)
    this.pos += n
    return b
  }

  uuid(): string {
    return this.bytes(16).toString('hex')
  }

  /** VarInt-Länge + Bytes (z. B. Schlüssel/Signatur in 1.19). */
  byteArray(max: number): Buffer {
    const n = this.varInt()
    if (n < 0 || n > max) throw new McError('array too long')
    return this.bytes(n)
  }
}

export interface Packet {
  id: number
  data: Reader
}

/** Zerlegt einen Byte-Strom in Pakete. `'error'` = kaputt oder zu groß, Verbindung schließen. */
export class PacketReader {
  private buf: Buffer = Buffer.alloc(0)
  readonly maxSize: number

  constructor(maxSize: number) {
    this.maxSize = maxSize
  }

  push(chunk: Buffer): void {
    this.buf = this.buf.length === 0 ? chunk : Buffer.concat([this.buf, chunk])
  }

  get buffered(): number {
    return this.buf.length
  }

  next(): Packet | null | 'error' {
    let len = 0
    let i = 0
    for (;;) {
      if (i >= this.buf.length) return null
      if (i >= 3) return 'error'
      const b = this.buf[i]!
      len |= (b & 0x7f) << (7 * i)
      i++
      if ((b & 0x80) === 0) break
    }
    if (len < 1 || len > this.maxSize) return 'error'
    if (this.buf.length < i + len) return null
    const body = this.buf.subarray(i, i + len)
    this.buf = this.buf.subarray(i + len)
    try {
      const data = new Reader(body)
      return { id: data.varInt(), data }
    } catch {
      return 'error'
    }
  }
}

/** Network NBT (ab 1.20.2, Wurzel ohne Namen): einzelnes TAG_String. */
export function nbtString(s: string): Buffer {
  const body = modifiedUtf8(s)
  if (body.length > 0xffff) throw new McError('nbt string too long')
  const head = Buffer.alloc(3)
  head[0] = 0x08
  head.writeUInt16BE(body.length, 1)
  return Buffer.concat([head, body])
}

/** Javas „modified UTF-8“: \0 als C0 80, Surrogate einzeln mit je 3 Bytes. */
export function modifiedUtf8(s: string): Buffer {
  const out: number[] = []
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    if (c >= 0x01 && c <= 0x7f) out.push(c)
    else if (c <= 0x7ff) out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f))
    else out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f))
  }
  return Buffer.from(out)
}

/**
 * Antwort auf den alten Server-Listen-Ping (0xFE, bis 1.6): Kick-Paket 0xFF mit UTF-16BE-Text
 * im Format ab 1.4 (`§1\0protocol\0version\0motd\0online\0max`).
 */
export function legacyPingResponse(motd: string, online: number, max: number): Buffer {
  const text = ['§1', '127', 'TRS Wake', motd, String(online), String(max)].join('\0')
  const body = Buffer.from(text, 'utf16le').swap16()
  const head = Buffer.alloc(3)
  head[0] = 0xff
  head.writeUInt16BE(text.length, 1)
  return Buffer.concat([head, body])
}
