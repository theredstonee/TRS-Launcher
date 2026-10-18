import { FACE_ASPECT, blit, cropFor, resample, rgba, type Crop, type FitMode, type Rgba, type TextureLayout } from './capeImage'
import { CAPE_REGIONS, scaleRect } from './capeLayout'
import { blurredBackdrop, composite, smoothResample, solid } from './capeResample'
import { binaryAlpha } from './capeUpscale'

// Umhang-Werkstatt: zwei Stile.
// - `pixel` („Pixel-Art“): wie bisher – Bearbeitung im kleinen Raster, beim Hochladen
//   pixelgenau (oder mit xBRZ) hochskaliert, Vorschau mit scharfen Pixeln.
// - `hd` („HD – wie Bild“): nutzt das Format voll aus – immer 1024×512 je Frame
//   (Faktor 16), glatt skaliert (Lanczos), volle Farben und Alpha, Vorschau glatt.
// Dazu die Entscheidung „sieht das nach Pixel-Art aus?“ – dieselbe Regel nimmt der
// TRS Client („Automatisch“: blockgleiche Umhänge scharf, echte HD-Umhänge weich).

export type CapeStyle = 'pixel' | 'hd'

/** Faktor eines HD-Umhangs: das Format ganz ausgereizt (1024×512 je Frame). */
export const HD_SCALE = 16

/** Hintergrund beim Einpassen: Farbe (auch durchsichtig) oder das Bild unscharf dahinter. */
export type Backdrop = { kind: 'color'; color: readonly [number, number, number, number] } | { kind: 'blur' }

export interface FaceOptions {
  style: CapeStyle
  fit: FitMode
  backdrop: Backdrop
}

/** Zwei Pixel gleich? Ganz durchsichtige zählen unabhängig von ihrer Farbe als gleich. */
function samePixel(d: Uint8ClampedArray, i: number, j: number): boolean {
  const a = d[i + 3]!
  if (a !== d[j + 3]!) return false
  if (a === 0) return true
  return d[i] === d[j] && d[i + 1] === d[j + 1] && d[i + 2] === d[j + 2]
}

/** Besteht das Bild aus einfarbigen `b`×`b`-Blöcken (pixelgenau hochskalierte Pixel-Art)? */
export function isBlockUniform(img: Rgba, b: number): boolean {
  if (b < 1 || img.width % b !== 0 || img.height % b !== 0) return false
  if (b === 1) return true
  const d = img.data
  const w = img.width
  for (let y = 0; y < img.height; y++) {
    const by = y - (y % b)
    for (let x = 0; x < w; x++) {
      const bx = x - (x % b)
      if (x === bx && y === by) continue
      if (!samePixel(d, (y * w + x) * 4, (by * w + bx) * 4)) return false
    }
  }
  return true
}

function gcd(a: number, b: number): number {
  while (b) [a, b] = [b, a % b]
  return a
}

/** Größte Blockkante (≤ `max`), in der das Bild einfarbig ist – 1 = keine Blöcke. */
export function pixelBlock(img: Rgba, max = 64): number {
  const g = gcd(img.width, img.height)
  for (let b = Math.min(max, g); b >= 2; b--) {
    if (g % b === 0 && isBlockUniform(img, b)) return b
  }
  return 1
}

/** Verschiedene Farben (durchsichtig zählt als eine), höchstens bis `limit` gezählt; große Bilder nur Stichproben. */
export function countColors(img: Rgba, limit = 257): number {
  const seen = new Set<number>()
  const d = img.data
  const pixels = img.width * img.height
  const step = Math.max(1, Math.floor(pixels / 400_000))
  for (let p = 0; p < pixels; p += step) {
    const i = p * 4
    const key = d[i + 3] === 0 ? -1 : ((d[i]! << 24) | (d[i + 1]! << 16) | (d[i + 2]! << 8) | d[i + 3]!) >>> 0
    seen.add(key)
    if (seen.size >= limit) break
  }
  return seen.size
}

/**
 * Vorschlag für den Stil: Pixel-Art bei hochskalierten Blöcken, kleinen Quellen
 * (Rückseite unter 96 Pixel hoch) oder wenigen Farben mit hartem Alpha – sonst HD
 * (Fotos, Logos mit weichen Kanten, große Zeichnungen). `faceHeight` = wie viele
 * Quellpixel die Rückseite des Umhangs hoch ist.
 */
export function suggestStyle(img: Rgba, faceHeight: number): CapeStyle {
  if (pixelBlock(img) >= 2) return 'pixel'
  if (faceHeight < 96) return 'pixel'
  if (binaryAlpha(img) && countColors(img, 65) <= 64) return 'pixel'
  return 'hd'
}

/**
 * Wie der TRS Client („Automatisch“): eine fertige Umhang-Textur ist „glatt“, wenn
 * sie größer als 64×32 ist und NICHT aus einfarbigen k×k-Blöcken besteht (k = Breite / 64).
 */
export function capeLooksSmooth(img: Rgba): boolean {
  const k = img.width / 64
  if (!Number.isInteger(k) || k < 2) return false
  return !isBlockUniform(img, k)
}

/** Rand des Bildes: Anteil durchsichtiger Pixel, einfarbig?, mittlere Farbe der deckenden. */
export function borderInfo(img: Rgba): { transparent: number; uniform: boolean; color: [number, number, number] } {
  const d = img.data
  const w = img.width
  const h = img.height
  const idx: number[] = []
  const step = Math.max(1, Math.floor((w + h) / 400))
  for (let x = 0; x < w; x += step) idx.push(x, (h - 1) * w + x)
  for (let y = 0; y < h; y += step) idx.push(y * w, y * w + w - 1)
  let clear = 0
  let n = 0
  const sum = [0, 0, 0]
  for (const p of idx) {
    const i = p * 4
    if (d[i + 3]! < 16) {
      clear++
      continue
    }
    n++
    for (let c = 0; c < 3; c++) sum[c]! += d[i + c]!
  }
  const color = sum.map((v) => (n ? Math.round(v / n) : 0)) as [number, number, number]
  let dev = 0
  for (const p of idx) {
    const i = p * 4
    if (d[i + 3]! < 16) continue
    dev += Math.abs(d[i]! - color[0]) + Math.abs(d[i + 1]! - color[1]) + Math.abs(d[i + 2]! - color[2])
  }
  const total = Math.max(1, idx.length)
  return { transparent: clear / total, uniform: n > 0 && dev / n < 24, color }
}

/** Dunkles Neutral als Hintergrund hinter durchsichtigen Logos (im Spiel wird Durchsichtiges sonst schwarz). */
export const DEFAULT_BACKDROP_COLOR: readonly [number, number, number, number] = [32, 33, 39, 255]

/**
 * Vorschlag „Füllen“ oder „Einpassen“: Bilder mit durchsichtigem oder einfarbigem Rand
 * (Logos, Sticker) werden eingepasst – nichts wird abgeschnitten, der Rand geht nahtlos
 * in den Hintergrund über. Fotos füllen den Rahmen wie bisher.
 */
export function suggestFit(img: Rgba): { fit: FitMode; backdrop: Backdrop } {
  const aspect = img.width / img.height
  const border = borderInfo(img)
  const nearFace = Math.abs(aspect / FACE_ASPECT - 1) < 0.04
  if (!nearFace && border.transparent > 0.5) return { fit: 'contain', backdrop: { kind: 'color', color: DEFAULT_BACKDROP_COLOR } }
  if (!nearFace && border.uniform && border.transparent < 0.05) return { fit: 'contain', backdrop: { kind: 'color', color: [...border.color, 255] } }
  return { fit: 'cover', backdrop: { kind: 'blur' } }
}

/** Außenseite (10·s × 16·s) aus dem Ausschnitt – im gewählten Stil, eingepasst mit Hintergrund. */
export function renderFace(src: Rgba, crop: Crop, s: number, options: FaceOptions): Rgba {
  const dw = 10 * s
  const dh = 16 * s
  const contain = options.fit === 'contain'
  const outside = contain ? 'transparent' : 'clamp'
  const face = options.style === 'hd' ? smoothResample(src, crop, dw, dh, { outside }) : resample(src, crop, dw, dh, outside)
  if (!contain) return face
  const back =
    options.backdrop.kind === 'blur'
      ? blurredBackdrop(src, cropFor(src.width, src.height, 1, src.width / 2, src.height / 2), dw, dh)
      : solid(dw, dh, options.backdrop.color)
  return composite(face, back)
}

/** Teilfläche der Quelle (Faktor `from`, gebrochen erlaubt) glatt auf Faktor `to` – Teil für Teil, nichts läuft über UV-Kanten. */
function regionsSmooth(src: Rgba, from: number, to: number, regions = CAPE_REGIONS): Rgba {
  const out = rgba(64 * to, 32 * to)
  for (const region of regions) {
    const a = scaleRect(region, from)
    const b = scaleRect(region, to)
    const part = smoothResample(src, a, b.w, b.h)
    blit(part, 0, 0, b.w, b.h, out, b.x, b.y)
  }
  return out
}

/**
 * Fertige Umhang-Textur (ein Frame, beliebiger Faktor) glatt auf Faktor `s` bringen
 * (HD-Stil). 22:17 („nur Umhang“) liegt oben links wie im Vanilla-Raster.
 */
export function textureFromTextureHd(frame: Rgba, kind: TextureLayout['kind'], s: number): Rgba {
  const from = frame.width / (kind === 'full' ? 64 : 22)
  if (kind === 'full' && from === s) return { width: frame.width, height: frame.height, data: frame.data.slice() }
  return regionsSmooth(frame, from, s)
}

/** HD-Textur von Faktor `from` auf `to` (z. B. kleiner, damit das PNG unter 20 MB bleibt). */
export function rescaleTextureHd(tex: Rgba, from: number, to: number): Rgba {
  if (from === to) return { width: tex.width, height: tex.height, data: tex.data.slice() }
  return regionsSmooth(tex, from, to)
}

/** Quelle für schnelle Vorschauen: höchstens `maxSide` Pixel an der längeren Kante (glatt verkleinert). */
export function shrinkSource(src: Rgba, maxSide: number): { image: Rgba; factor: number } {
  const longest = Math.max(src.width, src.height)
  if (longest <= maxSide) return { image: src, factor: 1 }
  const factor = maxSide / longest
  const w = Math.max(1, Math.round(src.width * factor))
  const h = Math.max(1, Math.round(src.height * factor))
  return { image: smoothResample(src, { x: 0, y: 0, w: src.width, h: src.height }, w, h), factor: w / src.width }
}

/** `#rrggbb` → Farbe (deckend). */
export function hexColor(hex: string): [number, number, number, number] {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim())
  if (!m) return [...DEFAULT_BACKDROP_COLOR]
  const n = Number.parseInt(m[1]!, 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255, 255]
}

/** Farbe → `#rrggbb` (für den Farbwähler). */
export function colorHex(color: readonly [number, number, number, number]): string {
  return `#${color
    .slice(0, 3)
    .map((v) => v.toString(16).padStart(2, '0'))
    .join('')}`
}
