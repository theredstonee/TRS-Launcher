import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { decode as jpegDecode, encode as jpegEncode } from 'jpeg-js'
import { PNG } from 'pngjs'
import { ApiError, unsupportedMedia } from './errors'

/**
 * Chat-Bilder: PNG/JPEG/WebP rein, **immer neu kodiert** raus (PNG bei Transparenz, sonst JPEG) –
 * ohne Metadaten (EXIF/GPS, Kommentare, Farbprofile, Textchunks), auf feste Pixelgrenzen verkleinert,
 * plus Vorschaubild. Nur die dekodierten Pixel gelangen in die Ausgabe (Polyglot-/Exploit-Schutz).
 *
 * Grenzen: Datei ≤ 5 MiB, Eingabe ≤ 8192 px Kante und ≤ 24 MP (vor dem Dekodieren am Header geprüft),
 * Ausgabe ≤ 2048 px Kante, Vorschau ≤ 400 px. JPEG-Ausrichtung (EXIF-Orientation) wird angewendet.
 */

export const MAX_CHAT_IMAGE_BYTES = 5 * 1024 * 1024
export const MAX_INPUT_EDGE = 8192
export const MAX_INPUT_PIXELS = 24_000_000
export const MAX_OUTPUT_EDGE = 2048
export const THUMB_EDGE = 400
const JPEG_QUALITY = 85
const THUMB_QUALITY = 75

export type InputKind = 'png' | 'jpeg' | 'webp'
export type OutputMime = 'image/png' | 'image/jpeg'

export interface Rgba {
  width: number
  height: number
  data: Uint8Array
}

export interface EncodedImage {
  mime: OutputMime
  data: Buffer
  width: number
  height: number
}

export interface ProcessedImage {
  full: EncodedImage
  thumb: EncodedImage
}

const bad = (code: string, message: string) => new ApiError(400, code, message)

const MIME_OF: Record<InputKind, string> = { png: 'image/png', jpeg: 'image/jpeg', webp: 'image/webp' }

/** Art anhand der Magic Bytes. */
export function sniffImage(buf: Buffer): InputKind | null {
  if (buf.length >= 8 && buf.readUInt32BE(0) === 0x89504e47 && buf.readUInt32BE(4) === 0x0d0a1a0a) return 'png'
  if (buf.length >= 3 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return 'jpeg'
  if (buf.length >= 16 && buf.toString('latin1', 0, 4) === 'RIFF' && buf.toString('latin1', 8, 12) === 'WEBP') return 'webp'
  return null
}

/** Maße aus dem Header (ohne zu dekodieren). Wirft bei kaputten/unbekannten Dateien. */
export function imageSize(buf: Buffer, kind: InputKind): { width: number, height: number } {
  if (kind === 'png') {
    if (buf.length < 24 || buf.toString('latin1', 12, 16) !== 'IHDR') throw bad('invalid_image', 'Invalid PNG header')
    return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) }
  }
  if (kind === 'jpeg') {
    let off = 2
    while (off + 9 < buf.length) {
      if (buf[off] !== 0xff) throw bad('invalid_image', 'Invalid JPEG structure')
      const marker = buf[off + 1]!
      if (marker === 0xff) {
        off++
        continue
      }
      if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
        off += 2
        continue
      }
      const len = buf.readUInt16BE(off + 2)
      if (len < 2) throw bad('invalid_image', 'Invalid JPEG segment')
      // SOF0–SOF15 außer DHT (C4), JPG (C8), DAC (CC)
      if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
        return { height: buf.readUInt16BE(off + 5), width: buf.readUInt16BE(off + 7) }
      }
      off += 2 + len
    }
    throw bad('invalid_image', 'JPEG has no frame header')
  }
  // WebP: VP8 (verlustbehaftet), VP8L (verlustfrei) oder VP8X (erweitert).
  const chunk = buf.toString('latin1', 12, 16)
  if (chunk === 'VP8X') {
    if (buf.length < 30) throw bad('invalid_image', 'Invalid WebP header')
    const flags = buf[20]!
    if (flags & 0x02) throw bad('animated_image', 'Animated images are not allowed')
    return { width: 1 + buf.readUIntLE(24, 3), height: 1 + buf.readUIntLE(27, 3) }
  }
  if (chunk === 'VP8 ') {
    if (buf.length < 30 || buf[23] !== 0x9d || buf[24] !== 0x01 || buf[25] !== 0x2a) throw bad('invalid_image', 'Invalid WebP header')
    return { width: buf.readUInt16LE(26) & 0x3fff, height: buf.readUInt16LE(28) & 0x3fff }
  }
  if (chunk === 'VP8L') {
    if (buf.length < 25 || buf[20] !== 0x2f) throw bad('invalid_image', 'Invalid WebP header')
    const b = buf.readUInt32LE(21)
    return { width: (b & 0x3fff) + 1, height: ((b >> 14) & 0x3fff) + 1 }
  }
  throw bad('invalid_image', 'Unsupported WebP variant')
}

// ---------------------------------------------------------------- WebP (libwebp als Wasm, nur Dekoder)

type WasmLoader = () => Promise<Uint8Array>
let webpLoader: WasmLoader = async () => {
  const require = createRequire(import.meta.url)
  return readFile(require.resolve('@jsquash/webp/codec/dec/webp_dec.wasm'))
}
let webpReady: Promise<(buf: ArrayBuffer) => Promise<{ width: number, height: number, data: Uint8ClampedArray }>> | null = null

/** Im Server liefert ein Plugin die Wasm-Datei aus den Server-Assets (gebündelt). */
export function setWebpWasmLoader(loader: WasmLoader): void {
  webpLoader = loader
  webpReady = null
}

async function webpDecoder() {
  webpReady ??= (async () => {
    // Emscripten-Code erwartet die Browser-Klasse ImageData.
    const g = globalThis as { ImageData?: unknown }
    g.ImageData ??= class ImageData {
      constructor(
        readonly data: Uint8ClampedArray,
        readonly width: number,
        readonly height: number,
      ) {}
    }
    const mod = await import('@jsquash/webp/decode.js')
    const bytes = await webpLoader()
    await mod.init(await WebAssembly.compile(bytes as Uint8Array<ArrayBuffer>))
    return mod.default as unknown as (buf: ArrayBuffer) => Promise<{ width: number, height: number, data: Uint8ClampedArray }>
  })()
  try {
    return await webpReady
  } catch (err) {
    webpReady = null
    throw err
  }
}

// ---------------------------------------------------------------- Dekodieren

/** JPEG-EXIF-Ausrichtung (1–8), 1 wenn keine. Liest nur APP1 „Exif“ → IFD0 → Tag 0x0112. */
export function jpegOrientation(buf: Buffer): number {
  let off = 2
  while (off + 4 < buf.length && buf[off] === 0xff) {
    const marker = buf[off + 1]!
    if (marker === 0xda || marker === 0xd9) break
    const len = buf.readUInt16BE(off + 2)
    if (marker === 0xe1 && len >= 16 && buf.toString('latin1', off + 4, off + 10) === 'Exif\0\0') {
      const tiff = off + 10
      const end = Math.min(buf.length, off + 2 + len)
      const le = buf.toString('latin1', tiff, tiff + 2) === 'II'
      const u16 = (p: number) => (p + 2 <= end ? (le ? buf.readUInt16LE(p) : buf.readUInt16BE(p)) : 0)
      const u32 = (p: number) => (p + 4 <= end ? (le ? buf.readUInt32LE(p) : buf.readUInt32BE(p)) : 0)
      const ifd = tiff + u32(tiff + 4)
      const n = u16(ifd)
      for (let i = 0; i < n && i < 256; i++) {
        const e = ifd + 2 + i * 12
        if (e + 12 > end) break
        if (u16(e) === 0x0112) {
          const v = u16(e + 8)
          return v >= 1 && v <= 8 ? v : 1
        }
      }
      return 1
    }
    off += 2 + len
  }
  return 1
}

async function decodeRgba(buf: Buffer, kind: InputKind): Promise<Rgba> {
  try {
    if (kind === 'png') {
      const p = PNG.sync.read(buf)
      return { width: p.width, height: p.height, data: p.data }
    }
    if (kind === 'jpeg') {
      const j = jpegDecode(buf, {
        useTArray: true,
        formatAsRGBA: true,
        tolerantDecoding: true,
        maxResolutionInMP: MAX_INPUT_PIXELS / 1_000_000,
        maxMemoryUsageInMB: 400,
      })
      return { width: j.width, height: j.height, data: j.data }
    }
    const decode = await webpDecoder()
    const ab = buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength) as ArrayBuffer
    const w = await decode(ab)
    return { width: w.width, height: w.height, data: new Uint8Array(w.data.buffer, w.data.byteOffset, w.data.byteLength) }
  } catch (err) {
    if (err instanceof ApiError) throw err
    throw bad('invalid_image', 'The image could not be decoded')
  }
}

// ---------------------------------------------------------------- Verkleinern, drehen

/**
 * Flächenmittelung (Box-Filter) mit vormultipliziertem Alpha; nur verkleinern. Arbeitet
 * zeilenweise (Speicher ∝ Zielbreite), damit auch 24-MP-Bilder den Server nicht belasten.
 */
export function downscale(src: Rgba, maxEdge: number): Rgba {
  const { width: sw, height: sh } = src
  const f = Math.min(1, maxEdge / Math.max(sw, sh))
  if (f >= 1) return src
  const dw = Math.max(1, Math.round(sw * f))
  const dh = Math.max(1, Math.round(sh * f))
  const sx = sw / dw
  const sy = sh / dh

  // Gewichte je Zielspalte: [Quellspalte, Gewicht]…
  const colStart = new Int32Array(dw)
  const colCount = new Int32Array(dw)
  const weights: number[] = []
  const cols: number[] = []
  for (let dx = 0; dx < dw; dx++) {
    const a = dx * sx
    const b = a + sx
    colStart[dx] = cols.length
    for (let x = Math.floor(a); x < Math.min(sw, Math.ceil(b)); x++) {
      const w = Math.min(b, x + 1) - Math.max(a, x)
      if (w > 0) {
        cols.push(x)
        weights.push(w)
      }
    }
    colCount[dx] = cols.length - colStart[dx]!
  }

  const out = new Uint8Array(dw * dh * 4)
  const row = new Float64Array(dw * 4)
  const acc = new Float64Array(dw * 4)
  const data = src.data
  let dy = 0
  let accW = 0
  const flush = () => {
    const o = dy * dw * 4
    for (let i = 0; i < dw; i++) {
      const a = acc[i * 4 + 3]!
      const alpha = a / accW
      out[o + i * 4 + 3] = Math.round(alpha)
      if (a > 0) {
        out[o + i * 4] = Math.min(255, Math.round(acc[i * 4]! / a))
        out[o + i * 4 + 1] = Math.min(255, Math.round(acc[i * 4 + 1]! / a))
        out[o + i * 4 + 2] = Math.min(255, Math.round(acc[i * 4 + 2]! / a))
      }
    }
    acc.fill(0)
    accW = 0
    dy++
  }
  for (let y = 0; y < sh && dy < dh; y++) {
    // Quellzeile waagerecht verkleinern (vormultipliziert).
    const base = y * sw * 4
    for (let dx = 0; dx < dw; dx++) {
      let r = 0, g = 0, b = 0, al = 0
      const s = colStart[dx]!
      for (let k = s; k < s + colCount[dx]!; k++) {
        const p = base + cols[k]! * 4
        const w = weights[k]!
        const aw = data[p + 3]! * w
        r += data[p]! * aw
        g += data[p + 1]! * aw
        b += data[p + 2]! * aw
        al += aw
      }
      row[dx * 4] = r / sx
      row[dx * 4 + 1] = g / sx
      row[dx * 4 + 2] = b / sx
      row[dx * 4 + 3] = al / sx
    }
    // Anteil dieser Quellzeile an Zielzeile dy (und ggf. dy+1).
    let top = y
    const bottom = y + 1
    while (top < bottom && dy < dh) {
      const rowEnd = (dy + 1) * sy
      const part = Math.min(bottom, rowEnd) - top
      if (part > 0) {
        for (let i = 0; i < acc.length; i++) acc[i]! += row[i]! * part
        accW += part
      }
      top += part
      if (rowEnd <= bottom + 1e-9) flush()
      else break
    }
  }
  while (dy < dh) flush()
  return { width: dw, height: dh, data: out }
}

/** Wendet die EXIF-Ausrichtung an (2–8: spiegeln/drehen). */
export function orient(img: Rgba, o: number): Rgba {
  if (o <= 1 || o > 8) return img
  const { width: w, height: h, data } = img
  const swap = o >= 5
  const W = swap ? h : w
  const H = swap ? w : h
  const out = new Uint8Array(W * H * 4)
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let nx: number, ny: number
      switch (o) {
        case 2: nx = w - 1 - x; ny = y; break
        case 3: nx = w - 1 - x; ny = h - 1 - y; break
        case 4: nx = x; ny = h - 1 - y; break
        case 5: nx = y; ny = x; break
        case 6: nx = h - 1 - y; ny = x; break
        case 7: nx = h - 1 - y; ny = w - 1 - x; break
        default: nx = y; ny = w - 1 - x; break // 8
      }
      const s = (y * w + x) * 4
      const d = (ny * W + nx) * 4
      out[d] = data[s]!
      out[d + 1] = data[s + 1]!
      out[d + 2] = data[s + 2]!
      out[d + 3] = data[s + 3]!
    }
  }
  return { width: W, height: H, data: out }
}

function hasAlpha(img: Rgba): boolean {
  const d = img.data
  for (let i = 3; i < d.length; i += 4) if (d[i] !== 255) return true
  return false
}

function encode(img: Rgba, alpha: boolean, quality: number): EncodedImage {
  if (alpha) {
    const p = new PNG({ width: img.width, height: img.height })
    p.data = Buffer.from(img.data.buffer, img.data.byteOffset, img.data.byteLength)
    return { mime: 'image/png', data: PNG.sync.write(p, { colorType: 6, deflateLevel: 6 }), width: img.width, height: img.height }
  }
  const j = jpegEncode({ width: img.width, height: img.height, data: img.data }, quality)
  return { mime: 'image/jpeg', data: j.data, width: img.width, height: img.height }
}

/**
 * Prüft (Content-Type ↔ Magic Bytes, Größe, Pixel) und kodiert neu. `contentType` ist der
 * Medientyp der Anfrage (`image/png`, `image/jpeg`, `image/webp`).
 */
export async function processChatImage(buf: Buffer, contentType: string): Promise<ProcessedImage> {
  if (buf.length === 0) throw bad('invalid_image', 'Request body is empty')
  if (buf.length > MAX_CHAT_IMAGE_BYTES) throw new ApiError(413, 'payload_too_large', 'Image is larger than 5 MiB')
  const kind = sniffImage(buf)
  if (!kind) throw unsupportedMedia('Only PNG, JPEG and WebP images are allowed')
  if (MIME_OF[kind] !== contentType) throw unsupportedMedia('Content-Type does not match the image data')
  const { width, height } = imageSize(buf, kind)
  if (width < 1 || height < 1) throw bad('invalid_image', 'Image has no pixels')
  if (width > MAX_INPUT_EDGE || height > MAX_INPUT_EDGE || width * height > MAX_INPUT_PIXELS) {
    throw bad('image_too_large', `Images can be at most ${MAX_INPUT_EDGE} px per side and ${MAX_INPUT_PIXELS / 1_000_000} megapixels`)
  }
  let img = await decodeRgba(buf, kind)
  if (img.width !== width || img.height !== height || img.data.length < width * height * 4) {
    throw bad('invalid_image', 'Image header does not match its data')
  }
  img = downscale(img, MAX_OUTPUT_EDGE)
  if (kind === 'jpeg') img = orient(img, jpegOrientation(buf))
  const alpha = hasAlpha(img)
  const full = encode(img, alpha, JPEG_QUALITY)
  const thumb = encode(downscale(img, THUMB_EDGE), alpha, THUMB_QUALITY)
  return { full, thumb }
}

/** 64×64-Server-Icon (Data-URL aus dem Status-Ping) prüfen und neu kodieren; `null` wenn ungültig. */
export function sanitizeServerIcon(dataUrl: unknown): string | null {
  if (typeof dataUrl !== 'string' || dataUrl.length > 200_000) return null
  const m = /^data:image\/png;base64,([A-Za-z0-9+/=\s]+)$/.exec(dataUrl)
  if (!m) return null
  try {
    const buf = Buffer.from(m[1]!.replace(/\s+/g, ''), 'base64')
    if (sniffImage(buf) !== 'png') return null
    const { width, height } = imageSize(buf, 'png')
    if (width !== 64 || height !== 64) return null
    const p = PNG.sync.read(buf)
    const out = new PNG({ width: 64, height: 64 })
    out.data = Buffer.from(p.data)
    return `data:image/png;base64,${PNG.sync.write(out, { colorType: 6 }).toString('base64')}`
  } catch {
    return null
  }
}
