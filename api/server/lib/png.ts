import { crc32, inflateSync } from 'node:zlib'
import { PNG } from 'pngjs'
import { ApiError } from './errors'

/** Größte Upload-Datei (Streifen mit bis zu 16 Frames in 512×256). */
export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024
/** Größter Faktor gegenüber 64×32 für Uploads (→ 512×256 je Frame). */
export const MAX_SCALE = 8
/** Höchstens so viele Frames hat ein hochgeladener Streifen. */
export const MAX_UPLOAD_FRAMES = 16
/** Höchstens so viele Pixel hat ein Upload (512 × 256 × 16 Frames) – geprüft am IHDR, vor dem Entpacken. */
export const MAX_UPLOAD_PIXELS = 512 * 4096
/** Größter Faktor für mitgelieferte Designs (HD-Pixel-Art, → 512×256 beim Umhang). */
export const BUILTIN_MAX_SCALE = 8

const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])

/** Erlaubte Chunks. Nebenchunks werden gelesen, aber beim Neukodieren verworfen. */
const ALLOWED = new Set([
  'IHDR', 'PLTE', 'IDAT', 'IEND',
  'tRNS', 'gAMA', 'cHRM', 'sRGB', 'iCCP', 'sBIT', 'pHYs', 'bKGD', 'tIME', 'tEXt', 'zTXt', 'iTXt', 'hIST', 'sPLT', 'eXIf',
])
const ANIMATION = new Set(['acTL', 'fcTL', 'fdAT'])

/** Gültige Kombinationen aus Farbtyp und Bittiefe (PNG-Spezifikation, Tabelle 11.1). */
const DEPTHS: Record<number, number[]> = {
  0: [1, 2, 4, 8, 16],
  2: [8, 16],
  3: [1, 2, 4, 8],
  4: [8, 16],
  6: [8, 16],
}
const CHANNELS: Record<number, number> = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }

export interface PngHeader {
  width: number
  height: number
  bitDepth: number
  colorType: number
  interlace: number
}

const bad = (code: string, message: string) => new ApiError(400, code, message)

/**
 * Prüft die Struktur Byte für Byte: Signatur, CRCs, Chunk-Whitelist, keine
 * Animation, nichts nach IEND (Polyglot-Schutz). Liefert Header + IDAT-Daten.
 */
export function inspectPng(buf: Buffer): { header: PngHeader, idat: Buffer } {
  if (buf.length < SIGNATURE.length + 25 + 12 || !buf.subarray(0, 8).equals(SIGNATURE)) {
    throw bad('invalid_png', 'File is not a PNG image')
  }
  let off = 8
  let header: PngHeader | null = null
  const idat: Buffer[] = []
  let idatDone = false
  let sawEnd = false
  let chunks = 0
  while (off < buf.length) {
    if (++chunks > 1000) throw bad('invalid_png', 'PNG has too many chunks')
    if (off + 12 > buf.length) throw bad('invalid_png', 'PNG is truncated')
    const len = buf.readUInt32BE(off)
    const type = buf.toString('latin1', off + 4, off + 8)
    if (!/^[A-Za-z]{4}$/.test(type)) throw bad('invalid_png', 'PNG contains an invalid chunk')
    if (len > 0x7fffffff || off + 12 + len > buf.length) throw bad('invalid_png', 'PNG is truncated')
    const data = buf.subarray(off + 8, off + 8 + len)
    const crc = buf.readUInt32BE(off + 8 + len)
    if ((crc32(buf.subarray(off + 4, off + 8 + len)) >>> 0) !== crc) throw bad('invalid_png', 'PNG checksum mismatch')
    off += 12 + len

    if (chunks === 1 && type !== 'IHDR') throw bad('invalid_png', 'PNG must start with IHDR')
    if (ANIMATION.has(type)) throw bad('animated_png', 'Animated PNGs are not allowed')
    if (!ALLOWED.has(type)) throw bad('invalid_png', `PNG chunk ${type} is not allowed`)

    switch (type) {
      case 'IHDR': {
        if (header || len !== 13) throw bad('invalid_png', 'Invalid PNG header')
        header = {
          width: data.readUInt32BE(0),
          height: data.readUInt32BE(4),
          bitDepth: data[8]!,
          colorType: data[9]!,
          interlace: data[12]!,
        }
        if (data[10] !== 0 || data[11] !== 0 || header.interlace > 1) throw bad('invalid_png', 'Invalid PNG header')
        if (!DEPTHS[header.colorType]?.includes(header.bitDepth)) throw bad('invalid_png', 'Invalid PNG color type')
        if (header.width === 0 || header.height === 0) throw bad('invalid_png', 'Invalid PNG size')
        break
      }
      case 'IDAT':
        if (idatDone) throw bad('invalid_png', 'PNG image data is not contiguous')
        idat.push(data)
        break
      case 'IEND':
        sawEnd = true
        break
      default:
        if (idat.length > 0) idatDone = true
    }
    if (type !== 'IDAT' && idat.length > 0) idatDone = true
    if (sawEnd) break
  }
  if (!header || idat.length === 0 || !sawEnd) throw bad('invalid_png', 'PNG is incomplete')
  if (off !== buf.length) throw bad('invalid_png', 'Data after the end of the PNG is not allowed')
  return { header, idat: Buffer.concat(idat) }
}

/** Rohdatengröße nach dem Entpacken (inkl. Filter-Byte je Zeile, bei Interlacing je Durchgang). */
export function rawSize(h: PngHeader): number {
  const bpp = CHANNELS[h.colorType]! * h.bitDepth
  const row = (w: number) => Math.ceil((w * bpp) / 8) + 1
  if (h.interlace === 0) return row(h.width) * h.height
  const passes: [number, number, number, number][] = [
    [0, 0, 8, 8], [4, 0, 8, 8], [0, 4, 4, 8], [2, 0, 4, 4], [0, 2, 2, 4], [1, 0, 2, 2], [0, 1, 1, 2],
  ]
  let total = 0
  for (const [x0, y0, dx, dy] of passes) {
    const pw = Math.ceil((h.width - x0) / dx)
    const ph = Math.ceil((h.height - y0) / dy)
    if (pw > 0 && ph > 0) total += row(pw) * ph
  }
  return total
}

/** Dekodiert zu RGBA8 – mit Schutz gegen Dekompressionsbomben. */
export function decodeRgba(buf: Buffer, maxPixels: number): { width: number, height: number, data: Buffer } {
  const { header, idat } = inspectPng(buf)
  if (header.width * header.height > maxPixels) throw bad('invalid_dimensions', 'Image is too large')
  const expected = rawSize(header)
  let inflated: Buffer
  try {
    inflated = inflateSync(idat, { maxOutputLength: expected + 1 })
  } catch {
    throw bad('invalid_png', 'PNG image data is corrupt')
  }
  if (inflated.length !== expected) throw bad('invalid_png', 'PNG image data has the wrong size')
  try {
    const png = PNG.sync.read(buf)
    return { width: png.width, height: png.height, data: png.data }
  } catch {
    throw bad('invalid_png', 'PNG could not be decoded')
  }
}

export function encodeRgba(width: number, height: number, data: Buffer): Buffer {
  const png = new PNG({ width, height, colorType: 6, inputColorType: 6, inputHasAlpha: true, bitDepth: 8 })
  data.copy(png.data)
  return PNG.sync.write(png, { colorType: 6, inputColorType: 6, inputHasAlpha: true, bitDepth: 8, deflateLevel: 9 })
}

export interface CapeLayout {
  /** Maße des Ergebnisses (immer 64k×32k). */
  width: number
  height: number
  scale: number
  source: 'full' | 'cape-only'
}

/** Erlaubte Maße: 64k×32k oder das reine Umhang-Format 22k×17k (k = 1…maxScale). */
export function capeLayout(width: number, height: number, maxScale = MAX_SCALE): CapeLayout | null {
  for (let k = 1; k <= maxScale; k++) {
    if (width === 64 * k && height === 32 * k) return { width, height, scale: k, source: 'full' }
    if (width === 22 * k && height === 17 * k) return { width: 64 * k, height: 32 * k, scale: k, source: 'cape-only' }
  }
  return null
}

export interface UploadLayout extends CapeLayout {
  frames: number
  /** Maße eines Frames in der hochgeladenen Datei (64k×32k oder 22k×17k). */
  frameWidth: number
  frameHeight: number
}

/**
 * Maße eines Uploads: Breite 64k (Frame-Höhe 32k) oder 22k (Frame-Höhe 17k), k = 1…MAX_SCALE,
 * Höhe = Frame-Höhe × Frames (1…MAX_UPLOAD_FRAMES). Die Breite entscheidet – 64k und 22k
 * treffen sich für k ≤ 8 nie.
 */
export function uploadLayout(width: number, height: number): UploadLayout | null {
  let k: number
  let source: CapeLayout['source']
  if (width % 64 === 0 && width / 64 >= 1 && width / 64 <= MAX_SCALE) {
    k = width / 64
    source = 'full'
  } else if (width % 22 === 0 && width / 22 >= 1 && width / 22 <= MAX_SCALE) {
    k = width / 22
    source = 'cape-only'
  } else return null
  const frameHeight = source === 'full' ? 32 * k : 17 * k
  const frames = height / frameHeight
  if (!Number.isInteger(frames) || frames < 1 || frames > MAX_UPLOAD_FRAMES) return null
  return { width: 64 * k, height: 32 * k, scale: k, source, frames, frameWidth: width, frameHeight }
}

export interface CleanCape {
  png: Buffer
  /** Maße EINES Frames (immer 64k×32k). */
  width: number
  height: number
  frames: number
  scale: number
  source: CapeLayout['source']
}

const DIMENSION_RULE = 'Cape must be 64k x 32k pixels per frame (k = 1-8, e.g. 64x32 up to 512x256) or the cape-only format '
  + `22k x 17k (22x17 up to 176x136); animated capes stack up to ${MAX_UPLOAD_FRAMES} frames vertically`

/**
 * Umhang-Upload säubern: prüfen, dekodieren, jeden Frame auf 64k×32k bringen (Streifen bleibt
 * senkrecht), unsichtbare Pixel nullen und NEU kodieren (nur IHDR/IDAT/IEND, keine Metadaten).
 * `frames` (optional) muss zur erkannten Frame-Zahl passen.
 */
export function sanitizeCapeUpload(buf: Buffer, opts: { frames?: number } = {}): CleanCape {
  if (buf.length > MAX_UPLOAD_BYTES) throw new ApiError(413, 'payload_too_large', 'Cape PNG must be at most 5 MB')
  const { header } = inspectPng(buf)
  // Maße zuerst am IHDR prüfen – vor jedem Entpacken (Dekompressionsbomben).
  if (header.width * header.height > MAX_UPLOAD_PIXELS) throw bad('invalid_dimensions', DIMENSION_RULE)
  const layout = uploadLayout(header.width, header.height)
  if (!layout) throw bad('invalid_dimensions', DIMENSION_RULE)
  if (opts.frames !== undefined && opts.frames !== layout.frames) {
    throw bad('invalid_dimensions', `The image contains ${layout.frames} frame(s), but frames=${opts.frames} was given`)
  }
  const img = decodeRgba(buf, MAX_UPLOAD_PIXELS)
  const outW = layout.width
  const outH = layout.height
  const out = Buffer.alloc(outW * outH * layout.frames * 4)
  // Jeder Frame oben links in seine 64k×32k-Fläche (beim reinen Umhang-Format bleibt der Rest leer).
  for (let f = 0; f < layout.frames; f++) {
    for (let y = 0; y < layout.frameHeight; y++) {
      const src = (f * layout.frameHeight + y) * img.width * 4
      img.data.copy(out, (f * outH + y) * outW * 4, src, src + img.width * 4)
    }
  }
  // Vollständig transparente Pixel tragen keine Farbe weiter (keine versteckten Daten).
  let visible = 0
  const capeW = 22 * layout.scale
  const capeH = 17 * layout.scale
  for (let i = 0; i < out.length; i += 4) {
    if (out[i + 3] === 0) {
      out[i] = 0
      out[i + 1] = 0
      out[i + 2] = 0
    } else {
      const p = i / 4
      if (p % outW < capeW && Math.floor(p / outW) % outH < capeH) visible++
    }
  }
  if (visible === 0) throw bad('empty_cape', 'The cape area of the image is fully transparent')
  return {
    png: encodeRgba(outW, outH * layout.frames, out),
    width: outW,
    height: outH,
    frames: layout.frames,
    scale: layout.scale,
    source: layout.source,
  }
}

/** Größte Skin-Datei (vor dem Neukodieren). */
export const MAX_SKIN_BYTES = 128 * 1024

/**
 * Skin für den Sync säubern: 64×64 oder das alte 64×32, prüfen, dekodieren und NEU kodieren
 * (nur IHDR/IDAT/IEND, keine Metadaten). Pixel bleiben unverändert – Minecraft zeichnet die
 * Grundebene deckend, auch „unsichtbare“ Farben gehören also zum Skin.
 */
export function sanitizeSkinUpload(buf: Buffer): { png: Buffer, width: number, height: number } {
  if (buf.length > MAX_SKIN_BYTES) throw new ApiError(413, 'payload_too_large', 'Skin PNG must be at most 128 KB')
  const { header } = inspectPng(buf)
  if (header.width !== 64 || (header.height !== 64 && header.height !== 32)) {
    throw bad('invalid_dimensions', 'Skin must be 64x64 or 64x32')
  }
  const img = decodeRgba(buf, 64 * 64)
  return { png: encodeRgba(img.width, img.height, img.data), width: img.width, height: img.height }
}
