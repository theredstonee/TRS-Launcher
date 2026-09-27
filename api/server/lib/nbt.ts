import { gunzipSync, gzipSync, inflateSync } from 'node:zlib'

/**
 * Minecraft-NBT lesen und schreiben (Big Endian, Java Edition) – nur für den Schaltungs-Import/Export (§25).
 * Robust gegen böswillige Dateien: entpackte Größe begrenzt (gzip-Bombe), Tiefe, Zahl der Knoten, Listen- und
 * Array-Längen werden gegen die restlichen Bytes geprüft, bevor Speicher belegt wird. Compounds sind Objekte ohne
 * Prototyp (kein `__proto__`-Unfug), Arrays bleiben typisiert.
 */

export const TAG = { End: 0, Byte: 1, Short: 2, Int: 3, Long: 4, Float: 5, Double: 6, ByteArray: 7, String: 8, List: 9, Compound: 10, IntArray: 11, LongArray: 12 } as const

export type NbtValue = number | bigint | string | Uint8Array | Int32Array | BigInt64Array | NbtValue[] | NbtCompound
export interface NbtCompound {
  [key: string]: NbtValue
}

export class NbtError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'NbtError'
  }
}

export interface NbtLimits {
  /** Höchstens so viele Bytes nach dem Entpacken. */
  maxInflated: number
  /** Verschachtelung (Compound/List). */
  maxDepth: number
  /** Knoten insgesamt (jeder Wert, jedes Listen-Element). */
  maxNodes: number
}

export const DEFAULT_NBT_LIMITS: NbtLimits = { maxInflated: 8 * 1024 * 1024, maxDepth: 24, maxNodes: 400_000 }

/** gzip (1f 8b) bzw. zlib (78 xx) entpacken, sonst unverändert. Grenze gegen Zip-Bomben. */
export function inflateNbt(data: Uint8Array, max = DEFAULT_NBT_LIMITS.maxInflated): Buffer {
  const buf = Buffer.from(data.buffer, data.byteOffset, data.byteLength)
  try {
    if (buf.length >= 2 && buf[0] === 0x1f && buf[1] === 0x8b) return gunzipSync(buf, { maxOutputLength: max })
    if (buf.length >= 2 && buf[0] === 0x78 && (buf[0]! * 256 + buf[1]!) % 31 === 0) return inflateSync(buf, { maxOutputLength: max })
  } catch (err) {
    const code = (err as { code?: string }).code
    if (code === 'ERR_BUFFER_TOO_LARGE' || (err as Error) instanceof RangeError) throw new NbtError('file is too large when unpacked')
    throw new NbtError('file is not a valid gzip archive')
  }
  if (buf.length > max) throw new NbtError('file is too large')
  return buf
}

class Reader {
  off = 0
  nodes = 0
  constructor(
    private readonly b: Buffer,
    private readonly lim: NbtLimits,
  ) {}

  private need(n: number): void {
    if (n < 0 || this.off + n > this.b.length) throw new NbtError('unexpected end of data')
  }

  u8(): number {
    this.need(1)
    return this.b[this.off++]!
  }

  i16(): number {
    this.need(2)
    const v = this.b.readInt16BE(this.off)
    this.off += 2
    return v
  }

  i32(): number {
    this.need(4)
    const v = this.b.readInt32BE(this.off)
    this.off += 4
    return v
  }

  str(): string {
    this.need(2)
    const len = this.b.readUInt16BE(this.off)
    this.off += 2
    this.need(len)
    // Java benutzt „modifiziertes UTF-8“; für Block-IDs und Eigenschaften reicht normales UTF-8.
    const s = this.b.toString('utf8', this.off, this.off + len)
    this.off += len
    return s
  }

  private count(n = 1): void {
    this.nodes += n
    if (this.nodes > this.lim.maxNodes) throw new NbtError('file has too many entries')
  }

  /** Länge eines Arrays/einer Liste prüfen: jedes Element braucht mindestens `size` Bytes. */
  private len(size: number): number {
    const n = this.i32()
    if (n < 0) throw new NbtError('negative length')
    if (size > 0) this.need(n * size)
    return n
  }

  value(type: number, depth: number): NbtValue {
    this.count()
    switch (type) {
      case TAG.Byte:
        this.need(1)
        return this.b.readInt8(this.off++)
      case TAG.Short:
        return this.i16()
      case TAG.Int:
        return this.i32()
      case TAG.Long: {
        this.need(8)
        const v = this.b.readBigInt64BE(this.off)
        this.off += 8
        return v
      }
      case TAG.Float: {
        this.need(4)
        const v = this.b.readFloatBE(this.off)
        this.off += 4
        return v
      }
      case TAG.Double: {
        this.need(8)
        const v = this.b.readDoubleBE(this.off)
        this.off += 8
        return v
      }
      case TAG.ByteArray: {
        const n = this.len(1)
        const v = new Uint8Array(this.b.subarray(this.off, this.off + n))
        this.off += n
        return v
      }
      case TAG.String:
        return this.str()
      case TAG.List: {
        if (depth >= this.lim.maxDepth) throw new NbtError('data is nested too deeply')
        const et = this.u8()
        if (et > TAG.LongArray) throw new NbtError(`unknown tag type ${et}`)
        const minSize = [0, 1, 2, 4, 8, 4, 8, 4, 2, 5, 1, 4, 4][et]!
        const n = this.len(minSize)
        if (et === TAG.End && n > 0) throw new NbtError('list of END tags')
        this.count(n)
        const out: NbtValue[] = []
        for (let i = 0; i < n; i++) out.push(this.value(et, depth + 1))
        return out
      }
      case TAG.Compound: {
        if (depth >= this.lim.maxDepth) throw new NbtError('data is nested too deeply')
        const out = Object.create(null) as NbtCompound
        for (;;) {
          const t = this.u8()
          if (t === TAG.End) return out
          if (t > TAG.LongArray) throw new NbtError(`unknown tag type ${t}`)
          const name = this.str()
          out[name] = this.value(t, depth + 1)
        }
      }
      case TAG.IntArray: {
        const n = this.len(4)
        const v = new Int32Array(n)
        for (let i = 0; i < n; i++) v[i] = this.b.readInt32BE(this.off + i * 4)
        this.off += n * 4
        return v
      }
      case TAG.LongArray: {
        const n = this.len(8)
        const v = new BigInt64Array(n)
        for (let i = 0; i < n; i++) v[i] = this.b.readBigInt64BE(this.off + i * 8)
        this.off += n * 8
        return v
      }
      default:
        throw new NbtError(`unknown tag type ${type}`)
    }
  }
}

/** Liest eine (ggf. gepackte) NBT-Datei: Wurzel muss ein Compound sein. */
export function readNbt(data: Uint8Array, limits: Partial<NbtLimits> = {}): { name: string, root: NbtCompound } {
  const lim = { ...DEFAULT_NBT_LIMITS, ...limits }
  const buf = inflateNbt(data, lim.maxInflated)
  const r = new Reader(buf, lim)
  const type = r.u8()
  if (type !== TAG.Compound) throw new NbtError('not an NBT file (root must be a compound)')
  const name = r.str()
  const root = r.value(TAG.Compound, 0) as NbtCompound
  return { name, root }
}

// ---------------------------------------------------------------- Zugriff

export const isCompound = (v: unknown): v is NbtCompound =>
  typeof v === 'object' && v !== null && !Array.isArray(v) && !ArrayBuffer.isView(v)

export function num(v: unknown): number | null {
  if (typeof v === 'number' && Number.isFinite(v)) return v
  if (typeof v === 'bigint' && v >= BigInt(Number.MIN_SAFE_INTEGER) && v <= BigInt(Number.MAX_SAFE_INTEGER)) return Number(v)
  return null
}

export function str(v: unknown): string | null {
  return typeof v === 'string' ? v : null
}

// ---------------------------------------------------------------- Schreiben

/** Getypte Werte zum Schreiben (Export als Strukturdatei; Tests bauen damit Litematica-/Sponge-Dateien). */
export type NbtOut =
  | { t: 'byte', v: number }
  | { t: 'short', v: number }
  | { t: 'int', v: number }
  | { t: 'long', v: bigint }
  | { t: 'string', v: string }
  | { t: 'bytes', v: Uint8Array }
  | { t: 'ints', v: number[] }
  | { t: 'longs', v: bigint[] }
  | { t: 'list', of: ListOf, v: NbtOut[] }
  | { t: 'compound', v: [string, NbtOut][] }
type ListOf = 'byte' | 'short' | 'int' | 'long' | 'string' | 'list' | 'compound' | 'end'

const TYPE_OF: Record<NbtOut['t'] | 'end', number> = {
  byte: TAG.Byte, short: TAG.Short, int: TAG.Int, long: TAG.Long, string: TAG.String, bytes: TAG.ByteArray,
  ints: TAG.IntArray, longs: TAG.LongArray, list: TAG.List, compound: TAG.Compound, end: TAG.End,
}

class Writer {
  private parts: Buffer[] = []
  u8(v: number) {
    this.parts.push(Buffer.from([v & 0xff]))
  }

  i32(v: number) {
    const b = Buffer.alloc(4)
    b.writeInt32BE(v)
    this.parts.push(b)
  }

  str(s: string) {
    const bytes = Buffer.from(s, 'utf8')
    if (bytes.length > 0xffff) throw new NbtError('string too long')
    const len = Buffer.alloc(2)
    len.writeUInt16BE(bytes.length)
    this.parts.push(len, bytes)
  }

  value(v: NbtOut) {
    switch (v.t) {
      case 'byte':
        this.u8(v.v)
        break
      case 'short': {
        const b = Buffer.alloc(2)
        b.writeInt16BE(v.v)
        this.parts.push(b)
        break
      }
      case 'int':
        this.i32(v.v)
        break
      case 'long': {
        const b = Buffer.alloc(8)
        b.writeBigInt64BE(BigInt.asIntN(64, v.v))
        this.parts.push(b)
        break
      }
      case 'bytes':
        this.i32(v.v.length)
        this.parts.push(Buffer.from(v.v))
        break
      case 'ints':
        this.i32(v.v.length)
        for (const x of v.v) this.i32(x)
        break
      case 'longs': {
        this.i32(v.v.length)
        const b = Buffer.alloc(v.v.length * 8)
        v.v.forEach((x, i) => b.writeBigInt64BE(BigInt.asIntN(64, x), i * 8))
        this.parts.push(b)
        break
      }
      case 'string':
        this.str(v.v)
        break
      case 'list':
        this.u8(v.v.length ? TYPE_OF[v.of] : TAG.End)
        this.i32(v.v.length)
        for (const x of v.v) this.value(x)
        break
      case 'compound':
        for (const [k, x] of v.v) {
          this.u8(TYPE_OF[x.t])
          this.str(k)
          this.value(x)
        }
        this.u8(TAG.End)
        break
    }
  }

  done(): Buffer {
    return Buffer.concat(this.parts)
  }
}

/** Schreibt eine gzip-gepackte NBT-Datei mit leerem Wurzelnamen (wie Strukturblöcke). */
export function writeNbt(root: Extract<NbtOut, { t: 'compound' }>, name = '', gzip = true): Buffer {
  const w = new Writer()
  w.u8(TAG.Compound)
  w.str(name)
  w.value(root)
  return gzip ? gzipSync(w.done()) : w.done()
}

export const nInt = (v: number): NbtOut => ({ t: 'int', v })
export const nStr = (v: string): NbtOut => ({ t: 'string', v })
export const nList = (of: ListOf, v: NbtOut[]): NbtOut => ({ t: 'list', of, v })
export const nCompound = (entries: [string, NbtOut][]): Extract<NbtOut, { t: 'compound' }> => ({ t: 'compound', v: entries })
