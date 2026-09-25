// Umhang-Werkstatt: reine Pixel-Funktionen für den Zuschneide-Dialog (ohne DOM,
// daher testbar). Ergebnis ist immer ein senkrechter Streifen aus Frames im
// Vanilla-Layout 64·s × 32·s (s = 1…8) – dasselbe Format wie die mitgelieferten
// Umhänge. Der Kern und der Server prüfen den fertigen Streifen noch einmal.

export interface Rgba {
  width: number
  height: number
  data: Uint8ClampedArray
}

/** Ausschnitt im Quellbild (Pixel, dürfen gebrochen sein). */
export interface Crop {
  x: number
  y: number
  w: number
  h: number
}

export const CAPE_MAX_SCALE = 8
export const CAPE_MAX_FRAMES = 16
export const CAPE_MAX_BYTES = 5 * 1024 * 1024
export const FRAME_TIME_MIN = 50
export const FRAME_TIME_MAX = 1000
export const FRAME_TIME_DEFAULT = 100
/** Seitenverhältnis der Umhang-Außenseite (10 × 16 Pixel im Vanilla-Raster). */
export const FACE_ASPECT = 10 / 16

export function rgba(width: number, height: number): Rgba {
  return { width, height, data: new Uint8ClampedArray(width * height * 4) }
}

/** Bildtempo auf 10 ms runden und in den erlaubten Bereich bringen. */
export function clampFrameTime(ms: number): number {
  if (!Number.isFinite(ms)) return FRAME_TIME_DEFAULT
  return Math.min(FRAME_TIME_MAX, Math.max(FRAME_TIME_MIN, Math.round(ms / 10) * 10))
}

/** Bildtempo so, dass `frames` Bilder zusammen `durationMs` dauern (GIF, ausgedünnte Streifen). */
export function frameTimeFor(durationMs: number, frames: number): number {
  return clampFrameTime(frames > 0 ? durationMs / frames : FRAME_TIME_DEFAULT)
}

/** Gleichmäßig verteilte Frame-Nummern: höchstens `keep` aus `total` (wie der Kern bei GIFs). */
export function sampleFrames(total: number, keep = CAPE_MAX_FRAMES): number[] {
  if (total <= keep) return Array.from({ length: total }, (_, i) => i)
  return Array.from({ length: keep }, (_, i) => Math.floor((i * total) / keep))
}

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })
/** Dateinamen wie im Explorer sortieren („frame2“ vor „frame10“). */
export function naturalCompare(a: string, b: string): number {
  return collator.compare(a, b)
}

export interface TextureLayout {
  /** `full` = 64:32 je Frame, `cape-only` = reines Umhang-Format 22:17. */
  kind: 'full' | 'cape-only'
  frames: number
  frameHeight: number
  /** Faktor der Quelle gegenüber 64×32 bzw. 22×17 (kann über 8 liegen – wird dann verkleinert). */
  scale: number
}

/**
 * Ist das Bild schon eine Umhang-Textur (ggf. Streifen)? Mit `frames` (z. B. aus
 * dem TRS-Studio-JSON) wird nur geprüft, ohne wird die Frame-Zahl erkannt.
 */
export function detectTexture(width: number, height: number, frames?: number): TextureLayout | null {
  if (width < 22 || height < 17) return null
  const candidates: [TextureLayout['kind'], number, number][] = [
    ['full', 64, 32],
    ['cape-only', 22, 17],
  ]
  for (const [kind, bw, bh] of candidates) {
    const frameHeight = (width * bh) / bw
    if (!Number.isInteger(frameHeight) || frameHeight <= 0) continue
    const n = height / frameHeight
    if (!Number.isInteger(n) || n < 1 || n > 64) continue
    if (frames !== undefined && n !== frames) continue
    return { kind, frames: n, frameHeight, scale: width / bw }
  }
  return null
}

/** Frame `index` aus einem senkrechten Streifen (Frame-Höhe `frameHeight`). */
export function extractFrame(src: Rgba, index: number, frameHeight: number): Rgba {
  const out = rgba(src.width, frameHeight)
  const start = index * frameHeight * src.width * 4
  out.data.set(src.data.subarray(start, start + frameHeight * src.width * 4))
  return out
}

/** Sprite-Sheet: `frames` gleich hohe Bilder untereinander (Rest unten fällt weg). */
export function splitStrip(src: Rgba, frames: number): Rgba[] {
  const n = Math.max(1, Math.min(64, Math.floor(frames)))
  const h = Math.floor(src.height / n)
  if (h < 1) return [src]
  return Array.from({ length: n }, (_, i) => extractFrame(src, i, h))
}

/**
 * Ausschnitt skalieren. Verkleinern mittelt die Fläche (mit Alpha gewichtet,
 * damit durchsichtige Pixel keine Farbe abgeben), Vergrößern bleibt pixelig.
 */
export function resample(src: Rgba, crop: Crop, dw: number, dh: number): Rgba {
  const out = rgba(dw, dh)
  const rx = crop.w / dw
  const ry = crop.h / dh
  const nearest = rx <= 1 && ry <= 1
  const px = (x: number, y: number) => {
    const cx = Math.min(src.width - 1, Math.max(0, x))
    const cy = Math.min(src.height - 1, Math.max(0, y))
    return (cy * src.width + cx) * 4
  }
  for (let dy = 0; dy < dh; dy++) {
    for (let dx = 0; dx < dw; dx++) {
      const o = (dy * dw + dx) * 4
      if (nearest) {
        const i = px(Math.floor(crop.x + (dx + 0.5) * rx), Math.floor(crop.y + (dy + 0.5) * ry))
        out.data[o] = src.data[i]!
        out.data[o + 1] = src.data[i + 1]!
        out.data[o + 2] = src.data[i + 2]!
        out.data[o + 3] = src.data[i + 3]!
        continue
      }
      const x0 = crop.x + dx * rx
      const x1 = x0 + rx
      const y0 = crop.y + dy * ry
      const y1 = y0 + ry
      let r = 0
      let g = 0
      let b = 0
      let a = 0
      let area = 0
      for (let y = Math.floor(y0); y < Math.ceil(y1); y++) {
        const wy = Math.min(y1, y + 1) - Math.max(y0, y)
        if (wy <= 0) continue
        for (let x = Math.floor(x0); x < Math.ceil(x1); x++) {
          const wx = Math.min(x1, x + 1) - Math.max(x0, x)
          if (wx <= 0) continue
          const w = wx * wy
          const i = px(x, y)
          const alpha = src.data[i + 3]! * w
          r += src.data[i]! * alpha
          g += src.data[i + 1]! * alpha
          b += src.data[i + 2]! * alpha
          a += alpha
          area += w
        }
      }
      if (a > 0) {
        out.data[o] = r / a
        out.data[o + 1] = g / a
        out.data[o + 2] = b / a
        out.data[o + 3] = a / area
      }
    }
  }
  return out
}

/** Rechteck aus `src` nach `dst` kopieren (Ziel wird an den Rändern abgeschnitten). */
export function blit(src: Rgba, sx: number, sy: number, w: number, h: number, dst: Rgba, dx: number, dy: number) {
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const tx = dx + x
      const ty = dy + y
      if (tx < 0 || ty < 0 || tx >= dst.width || ty >= dst.height) continue
      const i = ((sy + y) * src.width + sx + x) * 4
      const o = (ty * dst.width + tx) * 4
      dst.data[o] = src.data[i]!
      dst.data[o + 1] = src.data[i + 1]!
      dst.data[o + 2] = src.data[i + 2]!
      dst.data[o + 3] = src.data[i + 3]!
    }
  }
}

/** Waagerecht gespiegelt und abgedunkelt – für die Innenseite. */
function inner(face: Rgba, shade = 0.6): Rgba {
  const out = rgba(face.width, face.height)
  for (let y = 0; y < face.height; y++) {
    for (let x = 0; x < face.width; x++) {
      const i = (y * face.width + (face.width - 1 - x)) * 4
      const o = (y * face.width + x) * 4
      out.data[o] = face.data[i]! * shade
      out.data[o + 1] = face.data[i + 1]! * shade
      out.data[o + 2] = face.data[i + 2]! * shade
      out.data[o + 3] = face.data[i + 3]!
    }
  }
  return out
}

/**
 * Ganze Umhang-Textur (64·s × 32·s) aus einem Motiv für die Außenseite
 * (10·s × 16·s): Innenseite gespiegelt und dunkler, Kanten aus den Rändern des
 * Motivs, dazu passende Elytra-Flügel (gleiche Aufteilung wie Vanilla).
 */
export function textureFromFace(face: Rgba, s: number): Rgba {
  const tex = rgba(64 * s, 32 * s)
  const w = 10 * s
  const h = 16 * s
  // Umhang: oben (1,0), unten (11,0), Seiten (0,1) und (11,1), außen (1,1), innen (12,1).
  blit(face, 0, 0, w, h, tex, s, s)
  blit(inner(face), 0, 0, w, h, tex, 12 * s, s)
  blit(face, 0, 0, s, h, tex, 0, s)
  blit(face, w - s, 0, s, h, tex, 11 * s, s)
  blit(face, 0, 0, w, s, tex, s, 0)
  blit(face, 0, h - s, w, s, tex, 11 * s, 0)
  // Elytra (22,0): oben (24,0) 10×2, unten (34,0), Seiten (22,2)/(34,2) 2×20, außen (24,2), innen (36,2).
  const wing = resample(face, { x: 0, y: 0, w: face.width, h: face.height }, w, 20 * s)
  blit(wing, 0, 0, w, 20 * s, tex, 24 * s, 2 * s)
  blit(inner(wing), 0, 0, w, 20 * s, tex, 36 * s, 2 * s)
  blit(wing, 0, 0, 2 * s, 20 * s, tex, 22 * s, 2 * s)
  blit(wing, w - 2 * s, 0, 2 * s, 20 * s, tex, 34 * s, 2 * s)
  blit(wing, 0, 0, w, 2 * s, tex, 24 * s, 0)
  blit(wing, 0, 20 * s - 2 * s, w, 2 * s, tex, 34 * s, 0)
  return tex
}

/** Vorhandene Umhang-Textur (ein Frame) auf Faktor `s` bringen; 22:17 landet oben links. */
export function textureFromTexture(frame: Rgba, kind: TextureLayout['kind'], s: number): Rgba {
  const whole = { x: 0, y: 0, w: frame.width, h: frame.height }
  if (kind === 'full') return resample(frame, whole, 64 * s, 32 * s)
  const tex = rgba(64 * s, 32 * s)
  blit(resample(frame, whole, 22 * s, 17 * s), 0, 0, 22 * s, 17 * s, tex, 0, 0)
  return tex
}

/**
 * Größter Ausschnitt mit Seitenverhältnis `aspect` bei Zoom 1; `zoom` > 1
 * verkleinert ihn. Die Mitte (cx, cy) wird so verschoben, dass er im Bild bleibt.
 */
export function cropFor(width: number, height: number, zoom: number, cx: number, cy: number, aspect = FACE_ASPECT): Crop {
  const z = Math.max(1, zoom)
  let w = width
  let h = width / aspect
  if (h > height) {
    h = height
    w = height * aspect
  }
  w /= z
  h /= z
  const x = Math.min(Math.max(cx - w / 2, 0), width - w)
  const y = Math.min(Math.max(cy - h / 2, 0), height - h)
  return { x, y, w, h }
}

/** Frames untereinander zu einem Streifen. */
export function stack(frames: Rgba[]): Rgba {
  const first = frames[0]
  if (!first) return rgba(0, 0)
  const out = rgba(first.width, first.height * frames.length)
  frames.forEach((f, i) => out.data.set(f.data, i * first.width * first.height * 4))
  return out
}

export interface StripInput {
  frames: Rgba[]
  /** `texture` = Quelle ist schon eine Umhang-Textur, `crop` = Motiv für die Außenseite. */
  mode: 'texture' | 'crop'
  kind?: TextureLayout['kind']
  crop?: Crop
  scale: number
}

/** Fertiger Streifen für den Upload (Frames in dieser Reihenfolge, höchstens 16). */
export function buildStrip(input: StripInput): Rgba {
  const s = Math.max(1, Math.min(CAPE_MAX_SCALE, Math.round(input.scale)))
  const frames = input.frames.slice(0, CAPE_MAX_FRAMES).map((frame) => {
    if (input.mode === 'texture') return textureFromTexture(frame, input.kind ?? 'full', s)
    const crop = input.crop ?? { x: 0, y: 0, w: frame.width, h: frame.height }
    return textureFromFace(resample(frame, crop, 10 * s, 16 * s), s)
  })
  return stack(frames)
}

/** Sichtbare Pixel im Umhang-Bereich (22·s × 17·s) irgendeines Frames? Sonst lehnt der Server ab. */
export function capeVisible(strip: Rgba, s: number, frames: number): boolean {
  const frameHeight = strip.height / frames
  for (let f = 0; f < frames; f++) {
    for (let y = 0; y < 17 * s; y++) {
      const row = (f * frameHeight + y) * strip.width
      for (let x = 0; x < 22 * s; x++) {
        if (strip.data[(row + x) * 4 + 3]! > 0) return true
      }
    }
  }
  return false
}

/** Größe einer Base64-Data-URL in Bytes (ohne sie zu dekodieren). */
export function dataUrlBytes(url: string): number {
  const comma = url.indexOf(',')
  const b64 = comma < 0 ? url : url.slice(comma + 1)
  const pad = b64.endsWith('==') ? 2 : b64.endsWith('=') ? 1 : 0
  return Math.floor((b64.length * 3) / 4) - pad
}
