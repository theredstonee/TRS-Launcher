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
  return fillElytraFromFront(tex, s)
}

/**
 * Elytra immer aus der Außenseite des Umhangs erzeugen – rechnet exakt wie der TRS Client
 * (`ElytraArt.java`), damit Vorschau und Spiel gleich aussehen. Vanilla-Elytra: Flügel =
 * Quader `texOffs(22, 0)`, 10 × 20 × 2, beide Flügel nutzen denselben Bereich (das Modell
 * spiegelt den rechten selbst). Koordinaten in 64er-Einheiten, Pixel = ⌊(u · w + 32) / 64⌋:
 *
 * - Motiv F = Umhang-Außenseite (1, 1)–(11, 17). Löcher (Alpha < 128) bekommen die Farbe des
 *   nächsten deckenden Pixels (Breitensuche, 4er-Nachbarschaft), alles wird deckend; ohne
 *   deckendes Pixel grau (64, 64, 64).
 * - Außenseite O = F auf 10 × 20 gestreckt: Zeile y ← ⌊y · fh / oh⌋ (jede vierte Zeile doppelt).
 * - Bereich (22, 0)–(46, 22) leeren, dann:
 *   außen (36, 2) 10 × 20 = O (+z, vom Rücken weg; dort malt auch Mojang);
 *   innen (24, 2) 10 × 20 = O gespiegelt, ⌊c · 3/5⌋;
 *   Schulterkante (34, 2) 2 × 20 = O(0, y) · 4/5; Endkante (22, 2) 2 × 20 = O(letzte, y) · 4/5;
 *   oben (24, 0) 10 × 2 = O(gespiegelt, 0) · 4/5; unten (34, 0) 10 × 2 = O(gespiegelt, letzte) · 4/5.
 */
export function fillElytraFromFront(tex: Rgba, s: number): Rgba {
  const out: Rgba = { width: tex.width, height: tex.height, data: tex.data.slice() }
  const w = tex.width
  if (s < 1 || w < 64 || tex.height * 2 !== w) return out
  const px = (u: number) => Math.floor((u * w + 32) / 64)
  const d = out.data
  // Motiv holen (als ARGB-Zahlen wie im Client), Löcher füllen.
  const sx0 = px(1)
  const fw = px(11) - sx0
  const fh = px(17) - sx0
  const face = new Uint32Array(fw * fh)
  for (let y = 0; y < fh; y++) {
    for (let x = 0; x < fw; x++) {
      const i = ((sx0 + y) * w + sx0 + x) * 4
      face[y * fw + x] = ((d[i + 3]! << 24) | (d[i]! << 16) | (d[i + 1]! << 8) | d[i + 2]!) >>> 0
    }
  }
  if (!fillHoles(face, fw, fh)) face.fill(0xff404040)
  // Außenseite O.
  const ox0 = px(36)
  const oy0 = px(2)
  const ow = px(46) - ox0
  const oh = px(22) - oy0
  const outer = new Uint32Array(ow * oh)
  for (let y = 0; y < oh; y++) {
    const sy = Math.floor((y * fh) / oh)
    for (let x = 0; x < ow; x++) outer[y * ow + x] = face[sy * fw + Math.floor((x * fw) / ow)]!
  }
  const put = (x: number, y: number, c: number) => {
    const o = (y * w + x) * 4
    d[o] = (c >>> 16) & 255
    d[o + 1] = (c >>> 8) & 255
    d[o + 2] = c & 255
    d[o + 3] = c >>> 24
  }
  const shade = (c: number, num: number, den: number) =>
    (0xff000000 | ((Math.floor((((c >>> 16) & 255) * num) / den)) << 16) | ((Math.floor((((c >>> 8) & 255) * num) / den)) << 8) | Math.floor(((c & 255) * num) / den)) >>> 0
  const mirror = (x: number, n: number) => ow - 1 - Math.min(ow - 1, Math.floor((x * ow) / n))
  // Bereich leeren.
  for (let y = 0; y < oy0 + oh; y++) d.fill(0, (y * w + px(22)) * 4, (y * w + px(46)) * 4)
  for (let y = 0; y < oh; y++) for (let x = 0; x < ow; x++) put(ox0 + x, oy0 + y, outer[y * ow + x]!)
  // Innenseite: gespiegelt und dunkler.
  const ix0 = px(24)
  const iw = px(34) - ix0
  for (let y = 0; y < oh; y++) for (let x = 0; x < iw; x++) put(ix0 + x, oy0 + y, shade(outer[y * ow + mirror(x, iw)]!, 3, 5))
  // Kanten: Schulter = erste, Flügelende = letzte Spalte der Außenseite.
  const ex0 = px(34)
  const ew = px(36) - ex0
  const wx0 = px(22)
  const ww = px(24) - wx0
  for (let y = 0; y < oh; y++) {
    const shoulder = shade(outer[y * ow]!, 4, 5)
    const end = shade(outer[y * ow + ow - 1]!, 4, 5)
    for (let x = 0; x < ew; x++) put(ex0 + x, oy0 + y, shoulder)
    for (let x = 0; x < ww; x++) put(wx0 + x, oy0 + y, end)
  }
  // Oben/unten: oberste bzw. unterste Zeile der Außenseite, gespiegelt.
  const tx0 = px(24)
  const tw = px(34) - tx0
  const bx0 = px(34)
  const bw = px(44) - bx0
  for (let y = 0; y < oy0; y++) {
    for (let x = 0; x < tw; x++) put(tx0 + x, y, shade(outer[mirror(x, tw)]!, 4, 5))
    for (let x = 0; x < bw; x++) put(bx0 + x, y, shade(outer[(oh - 1) * ow + mirror(x, bw)]!, 4, 5))
  }
  return out
}

/** Löcher (Alpha < 128) mit der Farbe des nächsten deckenden Pixels füllen, alles deckend; false = nichts Deckendes. */
function fillHoles(face: Uint32Array, w: number, h: number): boolean {
  const n = w * h
  if (!n) return false
  const queue = new Int32Array(n)
  const done = new Uint8Array(n)
  let tail = 0
  for (let i = 0; i < n; i++) {
    if (face[i]! >>> 24 >= 128) {
      done[i] = 1
      queue[tail++] = i
    }
  }
  if (!tail) return false
  for (let head = 0; head < tail; head++) {
    const i = queue[head]!
    const c = face[i]!
    const x = i % w
    const visit = (j: number) => {
      if (done[j]) return
      done[j] = 1
      face[j] = c
      queue[tail++] = j
    }
    if (x > 0) visit(i - 1)
    if (x < w - 1) visit(i + 1)
    if (i >= w) visit(i - w)
    if (i + w < n) visit(i + w)
  }
  for (let i = 0; i < n; i++) face[i] = (face[i]! | 0xff000000) >>> 0
  return true
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
