import { CAPE_MAX_FRAMES, FRAME_TIME_DEFAULT, clampFrameTime, rgba, sampleFrames, type Rgba } from './capeImage'
import type { CapeStyle } from './capeHd'
import { capeRegionAt, scaleRect, type CapeRect } from './capeLayout'
import type { MirrorMode, Point } from './iconEditor'

/*
 * Umhang-Editor: Datenmodell (Frames mit Standzeit) und Pixel-Werkzeuge auf
 * RGBA-Bildern beliebiger Größe (64·s × 32·s). Rein, ohne DOM – getestet in
 * tests/cape-editor.test.ts. Bilder sind unveränderlich: jede Änderung erzeugt ein
 * neues Bild, der Verlauf (Rückgängig) hält nur Verweise.
 */

/** Ein Frame der Zeitleiste; `hold` = wie viele Takte er steht (beim Hochladen dupliziert). */
export interface CapeFrame {
  id: number
  image: Rgba
  hold: number
}

/** Bearbeitetes Dokument: alle Frames im selben Faktor `scale` (64·scale × 32·scale). */
export interface CapeDoc {
  scale: number
  frames: CapeFrame[]
  /** `hd` = „HD – wie Bild“ (Faktor 16, glatte Vorschau und glattes Skalieren); fehlt = Pixel-Art. */
  style?: CapeStyle
}

/** Ergebnis des Import-Schritts. */
export interface CapeImportResult {
  doc: CapeDoc
  frameTime: number
  name: string
  /** Wie viele Frames die Quelle hatte (mehr als 32 → gleichmäßig ausgewählt). */
  sourceFrames: number
}

let nextId = 1
export function frameId(): number {
  return nextId++
}

export function newFrame(image: Rgba, hold = 1): CapeFrame {
  return { id: frameId(), image, hold: Math.max(1, Math.floor(hold)) }
}

/** Takte der ganzen Zeitleiste (= Frames im fertigen Streifen). */
export function timelineLength(frames: readonly CapeFrame[]): number {
  return frames.reduce((sum, f) => sum + f.hold, 0)
}

/** Frames so oft wiederholt, wie sie stehen – das geht hoch. */
export function expandTimeline(frames: readonly CapeFrame[]): Rgba[] {
  return frames.flatMap((f) => Array.from({ length: f.hold }, () => f.image))
}

/** Index des Frames, der im Takt `tick` (0 …) zu sehen ist. */
export function frameAtTick(frames: readonly CapeFrame[], tick: number): number {
  const total = timelineLength(frames)
  if (!total) return 0
  let t = ((tick % total) + total) % total
  for (let i = 0; i < frames.length; i++) {
    t -= frames[i]!.hold
    if (t < 0) return i
  }
  return frames.length - 1
}

export function moveFrame(frames: readonly CapeFrame[], from: number, to: number): CapeFrame[] {
  const list = frames.slice()
  if (from < 0 || from >= list.length) return list
  const [item] = list.splice(from, 1)
  list.splice(Math.max(0, Math.min(list.length, to)), 0, item!)
  return list
}

/** Kopie direkt dahinter (nur, wenn noch Platz in den 32 Takten ist). */
export function duplicateFrame(frames: readonly CapeFrame[], index: number, max = CAPE_MAX_FRAMES): CapeFrame[] {
  const f = frames[index]
  if (!f || timelineLength(frames) + f.hold > max) return frames.slice()
  const list = frames.slice()
  list.splice(index + 1, 0, newFrame(f.image, f.hold))
  return list
}

/** Frame entfernen – der letzte bleibt immer. */
export function removeFrame(frames: readonly CapeFrame[], index: number): CapeFrame[] {
  if (frames.length <= 1) return frames.slice()
  return frames.filter((_, i) => i !== index)
}

/** Standzeit setzen (1 … so viel, wie in die 32 Takte passt). */
export function setHold(frames: readonly CapeFrame[], index: number, hold: number, max = CAPE_MAX_FRAMES): CapeFrame[] {
  const f = frames[index]
  if (!f) return frames.slice()
  const room = max - (timelineLength(frames) - f.hold)
  const next = Math.max(1, Math.min(room, Math.floor(hold)))
  return frames.map((x, i) => (i === index ? { ...x, hold: next } : x))
}

export function replaceImage(frames: readonly CapeFrame[], index: number, image: Rgba): CapeFrame[] {
  return frames.map((x, i) => (i === index ? { ...x, image } : x))
}

/**
 * Unterschiedliche Frame-Dauern (GIF, APNG) als Standzeiten: Takt = kürzeste Dauer
 * (50–1000 ms), jeder Frame steht ≈ Dauer / Takt. Passt das nicht in `max` Takte,
 * stehen alle gleich lang (Takt = mittlere Dauer).
 */
export function holdsFromDelays(delays: readonly number[], max = CAPE_MAX_FRAMES): { frameTime: number; holds: number[] } {
  const valid = delays.map((d) => (Number.isFinite(d) && d > 0 ? d : FRAME_TIME_DEFAULT))
  if (!valid.length) return { frameTime: FRAME_TIME_DEFAULT, holds: [] }
  const total = valid.reduce((a, b) => a + b, 0)
  const even = { frameTime: clampFrameTime(total / valid.length), holds: valid.map(() => 1) }
  const tick = clampFrameTime(Math.min(...valid))
  const holds = valid.map((d) => Math.max(1, Math.round(d / tick)))
  if (holds.every((h) => h === 1) || holds.reduce((a, b) => a + b, 0) > max) return even
  return { frameTime: tick, holds }
}

/**
 * Zeitleiste auf höchstens `max` Takte kürzen: erst Standzeiten verkürzen,
 * dann gleichmäßig Frames weglassen.
 */
export function reduceTimeline(frames: readonly CapeFrame[], max: number): CapeFrame[] {
  let list = frames.map((f) => ({ ...f }))
  while (timelineLength(list) > max && list.some((f) => f.hold > 1)) {
    const longest = list.reduce((best, f, i) => (f.hold > list[best]!.hold ? i : best), 0)
    list[longest]!.hold--
  }
  if (list.length > max) list = sampleFrames(list.length, max).map((i) => list[i]!)
  return list
}

// --- Pixel ----------------------------------------------------------------------------

export type Color = [number, number, number, number]

/** Werkzeuge der Zeichenfläche. */
export type CapeTool = 'pencil' | 'eraser' | 'picker' | 'fill' | 'line' | 'move'

export function cloneRgba(img: Rgba): Rgba {
  return { width: img.width, height: img.height, data: img.data.slice() }
}

export function getPixel(img: Rgba, x: number, y: number): Color {
  if (x < 0 || y < 0 || x >= img.width || y >= img.height) return [0, 0, 0, 0]
  const i = (y * img.width + x) * 4
  return [img.data[i]!, img.data[i + 1]!, img.data[i + 2]!, img.data[i + 3]!]
}

export function setPixel(img: Rgba, x: number, y: number, c: Color) {
  if (x < 0 || y < 0 || x >= img.width || y >= img.height) return
  img.data.set(c, (y * img.width + x) * 4)
}

export function hexToColor(hex: string): Color {
  const m = /^#([0-9a-f]{6})([0-9a-f]{2})?$/i.exec(hex)
  if (!m) return [0, 0, 0, 255]
  const n = Number.parseInt(m[1]!, 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255, m[2] ? Number.parseInt(m[2], 16) : 255]
}

export function colorToHex(c: Color): string {
  return `#${[c[0], c[1], c[2]].map((v) => v.toString(16).padStart(2, '0')).join('')}`
}

/** Teil des Umhangs um (x, y) in Pixeln der Textur (Faktor `s`); außerhalb aller Teile `null`. */
export function partRect(x: number, y: number, s: number): CapeRect | null {
  const region = capeRegionAt(x, y, s)
  return region ? scaleRect(region, s) : null
}

/** Spiegelpunkte innerhalb des Teils, in dem der Punkt liegt (sonst innerhalb der ganzen Textur). */
export function mirrorInPart(points: Point[], img: Rgba, s: number, mode: MirrorMode): Point[] {
  if (mode === 'none') return points
  const out = new Map<number, Point>()
  for (const [x, y] of points) {
    const r = partRect(x, y, s) ?? { x: 0, y: 0, w: img.width, h: img.height }
    const mx = r.x + r.w - 1 - (x - r.x)
    const my = r.y + r.h - 1 - (y - r.y)
    const add = (px: number, py: number) => out.set(py * img.width + px, [px, py])
    add(x, y)
    if (mode === 'x' || mode === 'xy') add(mx, y)
    if (mode === 'y' || mode === 'xy') add(x, my)
    if (mode === 'xy') add(mx, my)
  }
  return [...out.values()]
}

/** Punkte setzen (in place – für den Strich auf einer Arbeitskopie). */
export function paintInPlace(img: Rgba, points: Point[], color: Color) {
  for (const [x, y] of points) setPixel(img, x, y, color)
}

/** Füllen: zusammenhängende gleiche Farbe (4er-Nachbarn), nie über den Teil des Umhangs hinaus. */
export function fillRgba(img: Rgba, x: number, y: number, color: Color, s: number): Rgba {
  const out = cloneRgba(img)
  if (x < 0 || y < 0 || x >= img.width || y >= img.height) return out
  const r = partRect(x, y, s) ?? { x: 0, y: 0, w: img.width, h: img.height }
  const d = out.data
  const at = (px: number, py: number) => (py * img.width + px) * 4
  const t = at(x, y)
  const target = [d[t]!, d[t + 1]!, d[t + 2]!, d[t + 3]!]
  const same = (i: number) =>
    target[3] === 0 ? d[i + 3] === 0 : d[i] === target[0] && d[i + 1] === target[1] && d[i + 2] === target[2] && d[i + 3] === target[3]
  if (target[3] !== 0 && target.every((v, i) => v === color[i])) return out
  if (target[3] === 0 && color[3] === 0) return out
  const seen = new Uint8Array(img.width * img.height)
  const stack: number[] = [x, y]
  while (stack.length) {
    const py = stack.pop()!
    const px = stack.pop()!
    if (px < r.x || py < r.y || px >= r.x + r.w || py >= r.y + r.h) continue
    const k = py * img.width + px
    if (seen[k] || !same(k * 4)) continue
    seen[k] = 1
    d.set(color, k * 4)
    stack.push(px + 1, py, px - 1, py, px, py + 1, px, py - 1)
  }
  return out
}

/** Inhalt eines Rechtecks um (dx, dy) verschieben; frei werdende Pixel werden durchsichtig. */
export function shiftRect(img: Rgba, r: CapeRect, dx: number, dy: number): Rgba {
  const out = cloneRgba(img)
  for (let y = r.y; y < r.y + r.h; y++) {
    for (let x = r.x; x < r.x + r.w; x++) {
      const sx = x - dx
      const sy = y - dy
      const inside = sx >= r.x && sy >= r.y && sx < r.x + r.w && sy < r.y + r.h
      setPixel(out, x, y, inside ? getPixel(img, sx, sy) : [0, 0, 0, 0])
    }
  }
  return out
}

/** Ganzen Teil waagerecht/senkrecht spiegeln. */
export function flipRect(img: Rgba, r: CapeRect, axis: 'x' | 'y'): Rgba {
  const out = cloneRgba(img)
  for (let y = 0; y < r.h; y++) {
    for (let x = 0; x < r.w; x++) {
      const sx = axis === 'x' ? r.w - 1 - x : x
      const sy = axis === 'y' ? r.h - 1 - y : y
      setPixel(out, r.x + x, r.y + y, getPixel(img, r.x + sx, r.y + sy))
    }
  }
  return out
}

/** Außenseite auf die Innenseite übernehmen (gespiegelt, wie beim Zuschneiden). */
export function copyOuterToInner(img: Rgba, s: number, shade = 0.6): Rgba {
  const out = cloneRgba(img)
  const w = 10 * s
  const h = 16 * s
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const c = getPixel(img, s + (w - 1 - x), s + y)
      setPixel(out, 12 * s + x, s + y, [c[0] * shade, c[1] * shade, c[2] * shade, c[3]].map(Math.round) as Color)
    }
  }
  return out
}

export function emptyTexture(s: number): Rgba {
  return rgba(64 * s, 32 * s)
}

/** Sind zwei Bilder gleich (für „nichts geändert“)? */
export function sameImage(a: Rgba, b: Rgba): boolean {
  if (a === b) return true
  if (a.width !== b.width || a.height !== b.height) return false
  for (let i = 0; i < a.data.length; i++) if (a.data[i] !== b.data[i]) return false
  return true
}
