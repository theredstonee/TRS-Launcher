// Umhang-Werkstatt: reine Pixel-Funktionen für den Zuschneide-Dialog (ohne DOM,
// daher testbar). Ergebnis ist immer ein senkrechter Streifen aus Frames im
// Vanilla-Layout 64·s × 32·s (s = 1…16) – dasselbe Format wie die mitgelieferten
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

/** Wie der Vertrag mit API und TRS Client: bis 1024×512 je Frame, bis 32 Frames, bis 20 MiB. */
export const CAPE_MAX_SCALE = 16
export const CAPE_MAX_FRAMES = 32
export const CAPE_MAX_BYTES = 20 * 1024 * 1024
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
  /** Faktor der Quelle gegenüber 64×32 bzw. 22×17 (kann über 16 liegen – wird dann verkleinert). */
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

/** Aufteilung eines Sprite-Sheets: Spalten × Zeilen, davon `count` Zellen (zeilenweise). */
export interface SheetGrid {
  cols: number
  rows: number
  count: number
}

/**
 * Sprite-Sheet automatisch erkennen: Umhang-Texturen (64:32, 22:17) oder Motive
 * im Seitenverhältnis der Außenseite (10:16) – untereinander oder nebeneinander.
 * Raster (mehrere Spalten UND Zeilen) nur von Hand: sonst sähe z. B. ein 1280×1024-
 * Foto wie 5×8 Texturen aus. `frames` (z. B. aus dem TRS-Studio-JSON) muss passen. Sonst 1×1.
 */
export function guessSheet(width: number, height: number, frames?: number): SheetGrid {
  const single: SheetGrid = { cols: 1, rows: 1, count: 1 }
  if (width < 1 || height < 1) return single
  const fits = (g: SheetGrid) => g.count >= 2 && g.count <= 64 && (frames === undefined || g.count === frames)
  // 1. Senkrechter Streifen aus Umhang-Texturen (wie bisher).
  const strip = detectTexture(width, height, frames)
  if (strip && strip.frames > 1) return { cols: 1, rows: strip.frames, count: strip.frames }
  if (strip) return single
  // 2. Zellen mit bekanntem Seitenverhältnis in einer Zeile oder Spalte.
  //    Motive (10:16) nur untereinander – nebeneinander sähe jedes 5:4-Foto wie ein Sheet aus.
  for (const [cw, ch, maxCols] of [
    [64, 32, 64],
    [22, 17, 64],
    [10, 16, 1],
  ] as const) {
    for (let cols = 1; cols <= maxCols; cols++) {
      const cellW = width / cols
      const cellH = (cellW * ch) / cw
      if (!Number.isInteger(cellW) || !Number.isInteger(cellH) || cellH < 1) continue
      const rows = height / cellH
      if (!Number.isInteger(rows) || (cols > 1 && rows > 1)) continue
      const grid = { cols, rows, count: cols * rows }
      if (fits(grid)) return grid
    }
  }
  return single
}

/** Zelle `index` (zeilenweise) eines Sheets mit `cols` × `rows` gleich großen Zellen. */
export function sheetCell(src: Rgba, cols: number, rows: number, index: number): Rgba {
  const w = Math.max(1, Math.floor(src.width / cols))
  const h = Math.max(1, Math.floor(src.height / rows))
  const out = rgba(w, h)
  const cx = (index % cols) * w
  const cy = Math.floor(index / cols) * h
  for (let y = 0; y < h; y++) {
    const from = ((cy + y) * src.width + cx) * 4
    out.data.set(src.data.subarray(from, from + w * 4), y * w * 4)
  }
  return out
}

/**
 * Sieht ein Bild im Format 64:32 bzw. 22:17 wirklich wie eine Umhang-Textur aus – oder
 * nur zufällig so (z. B. ein quadratisches Logo = „zwei 2:1-Frames“)? Eine Umhang-Textur
 * lässt den Bereich neben Umhang und Elytra frei (durchsichtig oder einfarbig): rechts ab
 * x = 46 und unten unter Umhang (y ≥ 17) und Elytra (y ≥ 22). 22:17 („nur Umhang“) hat
 * keine freien Flächen – dort zählt nur, dass der Faktor ganzzahlig und ≤ 16 ist.
 */
export function plausibleTexture(img: Rgba, layout: Pick<TextureLayout, 'kind' | 'scale'>): boolean {
  if (layout.kind === 'cape-only') return Number.isInteger(layout.scale) && layout.scale <= CAPE_MAX_SCALE
  const k = img.width / 64
  const free: Crop[] = [
    { x: 46 * k, y: 0, w: 18 * k, h: 32 * k },
    { x: 0, y: 17 * k, w: 22 * k, h: 15 * k },
    { x: 22 * k, y: 22 * k, w: 24 * k, h: 10 * k },
  ]
  const d = img.data
  let total = 0
  let clear = 0
  const counts = new Map<number, number>()
  for (const r of free) {
    const step = Math.max(1, Math.floor(Math.min(r.w, r.h) / 24))
    for (let y = Math.floor(r.y); y < Math.min(img.height, r.y + r.h); y += step) {
      for (let x = Math.floor(r.x); x < Math.min(img.width, r.x + r.w); x += step) {
        const i = (y * img.width + x) * 4
        total++
        if (d[i + 3]! < 16) {
          clear++
          continue
        }
        // Grob quantisiert: leichtes Rauschen (JPEG) zählt als dieselbe Farbe.
        const key = ((d[i]! >> 4) << 8) | ((d[i + 1]! >> 4) << 4) | (d[i + 2]! >> 4)
        counts.set(key, (counts.get(key) ?? 0) + 1)
      }
    }
  }
  if (!total) return false
  const top = Math.max(0, ...counts.values())
  // Frei = fast ganz durchsichtig ODER fast ganz eine Farbe (eine Mischung wäre z. B. ein flaches Logo).
  return clear / total >= 0.9 || top / total >= 0.9
}

/**
 * Wie {@link guessSheet}, aber mit Blick ins Bild: Ein Raster aus Umhang-Texturen gilt nur,
 * wenn die erste Zelle wie eine aussieht ({@link plausibleTexture}) – sonst bleibt es ein
 * Bild (ein quadratisches Logo wird nicht in zwei „Frames“ zerschnitten). Mit `frames`
 * (TRS-Studio-JSON) wird dem Raster vertraut.
 */
export function guessSheetFor(img: Rgba, frames?: number): SheetGrid {
  const grid = guessSheet(img.width, img.height, frames)
  if (frames !== undefined || grid.count < 2) return grid
  const cell = sheetCell(img, grid.cols, grid.rows, 0)
  const layout = detectTexture(cell.width, cell.height, 1)
  if (layout && !plausibleTexture(cell, layout)) return { cols: 1, rows: 1, count: 1 }
  return grid
}

/** Sprite-Sheet in Frames zerlegen (Spalten/Zeilen 1–64, höchstens `count` Zellen, Rest fällt weg). */
export function splitSheet(src: Rgba, grid: SheetGrid): Rgba[] {
  const cols = Math.max(1, Math.min(64, Math.floor(grid.cols)))
  const rows = Math.max(1, Math.min(64, Math.floor(grid.rows)))
  if (src.width < cols || src.height < rows) return [src]
  const count = Math.max(1, Math.min(cols * rows, 64, Math.floor(grid.count)))
  if (cols === 1 && rows === 1) return [src]
  return Array.from({ length: count }, (_, i) => sheetCell(src, cols, rows, i))
}

/**
 * Ausschnitt skalieren (Pixel-Art). Verkleinern mittelt die Fläche (mit Alpha
 * gewichtet, damit durchsichtige Pixel keine Farbe abgeben), Vergrößern bleibt
 * pixelig. `outside`: was jenseits des Bildrands liegt – Randpixel (`clamp`) oder
 * durchsichtig (`transparent`, für „Einpassen“).
 */
export function resample(src: Rgba, crop: Crop, dw: number, dh: number, outside: 'clamp' | 'transparent' = 'clamp'): Rgba {
  const out = rgba(dw, dh)
  const rx = crop.w / dw
  const ry = crop.h / dh
  const nearest = rx <= 1 && ry <= 1
  const clear = outside === 'transparent'
  const px = (x: number, y: number) => {
    if (clear && (x < 0 || y < 0 || x >= src.width || y >= src.height)) return -1
    const cx = Math.min(src.width - 1, Math.max(0, x))
    const cy = Math.min(src.height - 1, Math.max(0, y))
    return (cy * src.width + cx) * 4
  }
  for (let dy = 0; dy < dh; dy++) {
    for (let dx = 0; dx < dw; dx++) {
      const o = (dy * dw + dx) * 4
      if (nearest) {
        const i = px(Math.floor(crop.x + (dx + 0.5) * rx), Math.floor(crop.y + (dy + 0.5) * ry))
        if (i < 0) continue
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
          area += w
          if (i < 0) continue
          const alpha = src.data[i + 3]! * w
          r += src.data[i]! * alpha
          g += src.data[i + 1]! * alpha
          b += src.data[i + 2]! * alpha
          a += alpha
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
 * Motivs. Der Elytra-Bereich bleibt durchsichtig (TRS-Umhänge werden nie als
 * Elytra genutzt).
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
 * Wie das Bild in den Rahmen der Außenseite kommt: `cover` = füllen (Ränder werden
 * abgeschnitten), `contain` = einpassen (ganzes Bild sichtbar, Rest ist Hintergrund).
 */
export type FitMode = 'cover' | 'contain'

/**
 * Ausschnitt mit Seitenverhältnis `aspect`. Zoom 1 = `cover`: größter Ausschnitt
 * im Bild, `contain`: kleinster Rahmen um das ganze Bild (ragt über das Bild hinaus).
 * `zoom` > 1 verkleinert ihn. Die Mitte (cx, cy) wird so verschoben, dass der
 * Ausschnitt im Bild bleibt bzw. – wo er größer ist – das Bild im Ausschnitt.
 */
export function cropFor(width: number, height: number, zoom: number, cx: number, cy: number, aspect = FACE_ASPECT, fit: FitMode = 'cover'): Crop {
  const z = Math.max(1, zoom)
  let w: number
  let h: number
  if (fit === 'contain') {
    w = Math.max(width, height * aspect)
    h = w / aspect
  } else {
    w = width
    h = width / aspect
    if (h > height) {
      h = height
      w = height * aspect
    }
  }
  w /= z
  h /= z
  const x = Math.min(Math.max(cx - w / 2, Math.min(0, width - w)), Math.max(0, width - w))
  const y = Math.min(Math.max(cy - h / 2, Math.min(0, height - h)), Math.max(0, height - h))
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

/** Fertiger Streifen (Frames in dieser Reihenfolge, höchstens 32). */
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
