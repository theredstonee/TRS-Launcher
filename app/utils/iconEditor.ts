import { z } from 'zod'

/*
 * Symbol-Editor: Datenmodell + reine Pixel-Operationen (ohne DOM, getestet in tests/icon-editor.test.ts).
 *
 * Ein Symbol = Hintergrund + ein Motiv (Pixel-Raster 16/32 oder ein kleines Bild). Gespeichert wird das
 * fertige 128-px-PNG und daneben diese Quelle als JSON (`icon-source.json`), damit es später weiter
 * bearbeitbar bleibt. Format: docs/icon-format.md.
 */

export type GridSize = 16 | 32
/** Farbe als 0xRRGGBBAA (vorzeichenlos); 0 = durchsichtig. */
export type Px = number
export interface PixelGrid {
  size: GridSize
  px: Px[]
}

export const GRID_SIZES: GridSize[] = [16, 32]
export const ICON_OUTPUT_SIZE = 128

// --- Farben ---------------------------------------------------------------------

const HEX = /^#([0-9a-f]{6}|[0-9a-f]{8})$/i

export function hexToPx(hex: string): Px {
  if (!HEX.test(hex)) return 0
  const body = hex.slice(1)
  const rgba = body.length === 6 ? `${body}ff` : body
  return Number.parseInt(rgba, 16) >>> 0
}

export function pxToHex(px: Px): string {
  const hex = (px >>> 0).toString(16).padStart(8, '0')
  return hex.endsWith('ff') ? `#${hex.slice(0, 6)}` : `#${hex}`
}

export function rgbaToPx(r: number, g: number, b: number, a: number): Px {
  return (((r & 255) << 24) | ((g & 255) << 16) | ((b & 255) << 8) | (a & 255)) >>> 0
}

export function pxToRgba(px: Px): [number, number, number, number] {
  return [(px >>> 24) & 255, (px >>> 16) & 255, (px >>> 8) & 255, px & 255]
}

/** TRS-Farben: Redstone, Deepslate, Gold, Akzente. */
export const TRS_PALETTE = [
  '#ff5a45', '#d42a1f', '#7a1410', '#ff9c8a', '#f2c230', '#ffe27a', '#e8862a',
  '#1b1420', '#2a2630', '#4a4652', '#8b8b95', '#c7c7cf', '#ffffff', '#8e4fd6',
] as const

/** Minecraft-ähnliche Grundfarben (Farbstoffe + Erze/Natur), eigene Werte. */
export const MC_PALETTE = [
  '#f9fffe', '#9d9d97', '#474f52', '#1d1d21', '#835432', '#b02e26', '#f9801d', '#fed83d',
  '#80c71f', '#5e7c16', '#169c9c', '#3ab3da', '#3c44aa', '#8932b8', '#c74ebd', '#f38baa',
  '#5d9c3a', '#7a5a3a', '#8f8f8f', '#45e0d0', '#41d16b', '#2747a8', '#ffd84a', '#ff2d1f',
] as const

// --- Raster -----------------------------------------------------------------------

export function emptyGrid(size: GridSize): PixelGrid {
  return { size, px: new Array<Px>(size * size).fill(0) }
}

export function cloneGrid(grid: PixelGrid): PixelGrid {
  return { size: grid.size, px: grid.px.slice() }
}

export function inside(grid: PixelGrid, x: number, y: number): boolean {
  return x >= 0 && y >= 0 && x < grid.size && y < grid.size
}

export function getPx(grid: PixelGrid, x: number, y: number): Px {
  return inside(grid, x, y) ? grid.px[y * grid.size + x]! : 0
}

export function sameGrid(a: PixelGrid, b: PixelGrid): boolean {
  return a.size === b.size && a.px.every((p, i) => p === b.px[i])
}

export function isEmptyGrid(grid: PixelGrid): boolean {
  return grid.px.every((p) => (p & 255) === 0)
}

export type MirrorMode = 'none' | 'x' | 'y' | 'xy'
export type Point = [number, number]

/** Punkte inkl. Spiegelungen (ohne Doppelte). */
export function mirrorPoints(points: Point[], size: number, mode: MirrorMode): Point[] {
  const out = new Map<number, Point>()
  const add = (x: number, y: number) => out.set(y * size + x, [x, y])
  for (const [x, y] of points) {
    add(x, y)
    if (mode === 'x' || mode === 'xy') add(size - 1 - x, y)
    if (mode === 'y' || mode === 'xy') add(x, size - 1 - y)
    if (mode === 'xy') add(size - 1 - x, size - 1 - y)
  }
  return [...out.values()]
}

/** Setzt Punkte auf eine Farbe (neues Raster). */
export function paint(grid: PixelGrid, points: Point[], color: Px, mirror: MirrorMode = 'none'): PixelGrid {
  const next = cloneGrid(grid)
  for (const [x, y] of mirrorPoints(points, grid.size, mirror)) {
    if (inside(grid, x, y)) next.px[y * grid.size + x] = color >>> 0
  }
  return next
}

/** Bresenham-Linie. */
export function linePoints(x0: number, y0: number, x1: number, y1: number): Point[] {
  const points: Point[] = []
  const dx = Math.abs(x1 - x0)
  const dy = -Math.abs(y1 - y0)
  const sx = x0 < x1 ? 1 : -1
  const sy = y0 < y1 ? 1 : -1
  let err = dx + dy
  let x = x0
  let y = y0
  for (let guard = 0; guard < 4096; guard++) {
    points.push([x, y])
    if (x === x1 && y === y1) break
    const e2 = 2 * err
    if (e2 >= dy) {
      err += dy
      x += sx
    }
    if (e2 <= dx) {
      err += dx
      y += sy
    }
  }
  return points
}

export function rectPoints(x0: number, y0: number, x1: number, y1: number, filled: boolean): Point[] {
  const [left, right] = x0 < x1 ? [x0, x1] : [x1, x0]
  const [top, bottom] = y0 < y1 ? [y0, y1] : [y1, y0]
  const points: Point[] = []
  for (let y = top; y <= bottom; y++) {
    for (let x = left; x <= right; x++) {
      if (filled || y === top || y === bottom || x === left || x === right) points.push([x, y])
    }
  }
  return points
}

/** Füllt die zusammenhängende Fläche gleicher Farbe (4er-Nachbarschaft), mit Spiegelung. */
export function floodFill(grid: PixelGrid, x: number, y: number, color: Px, mirror: MirrorMode = 'none'): PixelGrid {
  let next = cloneGrid(grid)
  for (const [sx, sy] of mirrorPoints([[x, y]], grid.size, mirror)) {
    if (!inside(next, sx, sy)) continue
    const target = next.px[sy * next.size + sx]!
    if (target === color >>> 0) continue
    const stack: Point[] = [[sx, sy]]
    const px = next.px
    while (stack.length) {
      const [cx, cy] = stack.pop()!
      if (!inside(next, cx, cy) || px[cy * next.size + cx] !== target) continue
      px[cy * next.size + cx] = color >>> 0
      stack.push([cx + 1, cy], [cx - 1, cy], [cx, cy + 1], [cx, cy - 1])
    }
    next = { size: next.size, px }
  }
  return next
}

/** Größe wechseln (nächster Nachbar). */
export function resizeGrid(grid: PixelGrid, size: GridSize): PixelGrid {
  if (grid.size === size) return cloneGrid(grid)
  const next = emptyGrid(size)
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      next.px[y * size + x] = getPx(grid, Math.floor((x * grid.size) / size), Math.floor((y * grid.size) / size))
    }
  }
  return next
}

/** Ganzes Bild spiegeln (Knopf „Spiegeln“). */
export function flipGrid(grid: PixelGrid, axis: 'x' | 'y'): PixelGrid {
  const next = emptyGrid(grid.size)
  for (let y = 0; y < grid.size; y++) {
    for (let x = 0; x < grid.size; x++) {
      const [sx, sy] = axis === 'x' ? [grid.size - 1 - x, y] : [x, grid.size - 1 - y]
      next.px[y * grid.size + x] = getPx(grid, sx, sy)
    }
  }
  return next
}

/** Aus RGBA-Daten (z. B. ImageData) ein Raster; fast durchsichtige Pixel werden ganz durchsichtig. */
export function gridFromRgba(data: ArrayLike<number>, size: GridSize, alphaCut = 32): PixelGrid {
  const grid = emptyGrid(size)
  for (let i = 0; i < size * size; i++) {
    const a = data[i * 4 + 3]!
    grid.px[i] = a < alphaCut ? 0 : rgbaToPx(data[i * 4]!, data[i * 4 + 1]!, data[i * 4 + 2]!, a)
  }
  return grid
}

// --- Verlauf (Rückgängig/Wiederholen) -------------------------------------------

export interface History<T> {
  readonly current: T
  readonly canUndo: boolean
  readonly canRedo: boolean
  push(state: T): void
  undo(): T
  redo(): T
  reset(state: T): void
}

export function createHistory<T>(initial: T, limit = 100): History<T> {
  let past: T[] = []
  let future: T[] = []
  let current = initial
  return {
    get current() {
      return current
    },
    get canUndo() {
      return past.length > 0
    },
    get canRedo() {
      return future.length > 0
    },
    push(state: T) {
      past.push(current)
      if (past.length > limit) past = past.slice(past.length - limit)
      current = state
      future = []
    },
    undo() {
      const prev = past.pop()
      if (prev !== undefined) {
        future.push(current)
        current = prev
      }
      return current
    },
    redo() {
      const next = future.pop()
      if (next !== undefined) {
        past.push(current)
        current = next
      }
      return current
    },
    reset(state: T) {
      past = []
      future = []
      current = state
    },
  }
}

// --- Farben reduzieren (Verpixeln) ----------------------------------------------

function distance(a: [number, number, number], b: [number, number, number]): number {
  // Gewichtet wie das Auge (grün zählt mehr).
  const dr = a[0] - b[0]
  const dg = a[1] - b[1]
  const db = a[2] - b[2]
  return dr * dr * 0.3 + dg * dg * 0.59 + db * db * 0.11
}

/** Median-Cut: bis zu `max` typische Farben eines Bildes (deckende Pixel). */
export function buildPalette(data: ArrayLike<number>, max = 16): Px[] {
  const colors: [number, number, number][] = []
  for (let i = 0; i + 3 < data.length; i += 4) {
    if (data[i + 3]! >= 128) colors.push([data[i]!, data[i + 1]!, data[i + 2]!])
  }
  if (!colors.length) return []
  let boxes = [colors]
  while (boxes.length < max) {
    // Größte Box an ihrer längsten Achse teilen.
    let best = -1
    let bestRange = 0
    let bestAxis = 0
    boxes.forEach((box, i) => {
      if (box.length < 2) return
      for (let axis = 0; axis < 3; axis++) {
        let lo = 255
        let hi = 0
        for (const c of box) {
          lo = Math.min(lo, c[axis]!)
          hi = Math.max(hi, c[axis]!)
        }
        if (hi - lo > bestRange) {
          bestRange = hi - lo
          best = i
          bestAxis = axis
        }
      }
    })
    if (best < 0 || bestRange === 0) break
    const box = boxes[best]!.slice().sort((a, b) => a[bestAxis]! - b[bestAxis]!)
    const mid = box.length >> 1
    boxes = [...boxes.slice(0, best), box.slice(0, mid), box.slice(mid), ...boxes.slice(best + 1)]
  }
  const palette = boxes
    .filter((b) => b.length)
    .map((box) => {
      const sum = box.reduce((s, c) => [s[0]! + c[0], s[1]! + c[1], s[2]! + c[2]], [0, 0, 0])
      return rgbaToPx(Math.round(sum[0]! / box.length), Math.round(sum[1]! / box.length), Math.round(sum[2]! / box.length), 255)
    })
  return [...new Set(palette)]
}

/** Jedes Pixel auf die nächste Palettenfarbe; halb durchsichtige Pixel werden durchsichtig. */
export function quantize(data: ArrayLike<number>, size: GridSize, palette: Px[], alphaCut = 128): PixelGrid {
  const grid = emptyGrid(size)
  const colors = palette.map((p) => pxToRgba(p).slice(0, 3) as [number, number, number])
  for (let i = 0; i < size * size; i++) {
    if (data[i * 4 + 3]! < alphaCut || !colors.length) continue
    const c: [number, number, number] = [data[i * 4]!, data[i * 4 + 1]!, data[i * 4 + 2]!]
    grid.px[i] = palette[nearestIndex(c, colors)]! >>> 0
  }
  return grid
}

function nearestIndex(c: [number, number, number], colors: [number, number, number][]): number {
  let best = 0
  let bestDist = Infinity
  colors.forEach((p, j) => {
    const d = distance(c, p)
    if (d < bestDist) {
      bestDist = d
      best = j
    }
  })
  return best
}

// --- Gespeicherte Quelle ----------------------------------------------------------

const hexColor = z.string().regex(HEX)

export const ICON_FILLS = ['none', 'solid', 'gradient'] as const
export const ICON_PATTERNS = ['none', 'wire', 'deepslate', 'dots'] as const
export type IconFill = (typeof ICON_FILLS)[number]
export type IconPattern = (typeof ICON_PATTERNS)[number]

export const iconBackgroundSchema = z.object({
  fill: z.enum(ICON_FILLS),
  color: hexColor,
  color2: hexColor,
  angle: z.number().int().min(0).max(359),
  pattern: z.enum(ICON_PATTERNS),
  patternColor: hexColor,
  border: z.boolean(),
  borderColor: hexColor,
  glow: z.boolean(),
  glowColor: hexColor,
})
export type IconBackground = z.infer<typeof iconBackgroundSchema>

/** Pixel-Raster kompakt: Palette + je Pixel zwei Hex-Ziffern (`00` = durchsichtig, `01` = palette[0] …). */
export const encodedPixelsSchema = z.object({
  kind: z.literal('pixels'),
  size: z.union([z.literal(16), z.literal(32)]),
  palette: z.array(hexColor).max(255),
  data: z.string().regex(/^[0-9a-f]*$/),
})
export type EncodedPixels = z.infer<typeof encodedPixelsSchema>

export const MAX_IMAGE_LAYER_CHARS = 200_000
export const iconLayerSchema = z.discriminatedUnion('kind', [
  encodedPixelsSchema,
  z.object({ kind: z.literal('image'), png: z.string().startsWith('data:image/png;base64,').max(MAX_IMAGE_LAYER_CHARS) }),
  z.object({ kind: z.literal('none') }),
])
export type IconLayer = z.infer<typeof iconLayerSchema>

export const ICON_ORIGINS = ['pixel', 'mc', 'trs', 'upload'] as const
export type IconOrigin = (typeof ICON_ORIGINS)[number]

export const iconSourceSchema = z.object({
  v: z.literal(1),
  bg: iconBackgroundSchema,
  layer: iconLayerSchema,
  /** Anteil des Motivs an der Kachel (0.4–1). */
  scale: z.number().min(0.4).max(1),
  origin: z.object({ kind: z.enum(ICON_ORIGINS), ref: z.string().max(80).nullable() }),
})
export type IconSource = z.infer<typeof iconSourceSchema>

export function encodeGrid(grid: PixelGrid): EncodedPixels {
  const palette: Px[] = []
  const index = new Map<Px, number>()
  let data = ''
  for (const p of grid.px) {
    if ((p & 255) === 0) {
      data += '00'
      continue
    }
    let i = index.get(p)
    if (i === undefined) {
      if (palette.length >= 255) {
        // Mehr als 255 Farben: nächste vorhandene nehmen (kommt beim Zeichnen praktisch nie vor).
        const colors = palette.map((q) => pxToRgba(q).slice(0, 3) as [number, number, number])
        i = nearestIndex(pxToRgba(p).slice(0, 3) as [number, number, number], colors) + 1
      } else {
        palette.push(p)
        i = palette.length
        index.set(p, i)
      }
    }
    data += i.toString(16).padStart(2, '0')
  }
  return { kind: 'pixels', size: grid.size, palette: palette.map(pxToHex), data }
}

export function decodeGrid(layer: EncodedPixels): PixelGrid | null {
  if (layer.data.length !== layer.size * layer.size * 2) return null
  const palette = layer.palette.map(hexToPx)
  const grid = emptyGrid(layer.size)
  for (let i = 0; i < grid.px.length; i++) {
    const n = Number.parseInt(layer.data.slice(i * 2, i * 2 + 2), 16)
    if (n > palette.length) return null
    grid.px[i] = n === 0 ? 0 : palette[n - 1]!
  }
  return grid
}

export function defaultBackground(): IconBackground {
  return {
    fill: 'gradient',
    color: '#3a1d24',
    color2: '#1b1420',
    angle: 135,
    pattern: 'wire',
    patternColor: '#ff3b2f',
    border: false,
    borderColor: '#ff5a45',
    glow: true,
    glowColor: '#ff5a45',
  }
}

export function defaultSource(): IconSource {
  return {
    v: 1,
    bg: defaultBackground(),
    layer: encodeGrid(emptyGrid(16)),
    scale: 0.75,
    origin: { kind: 'pixel', ref: null },
  }
}

/** Gespeicherten Text lesen; kaputt oder veraltet = `null` (dann startet der Editor frisch). */
export function parseIconSource(text: string | null | undefined): IconSource | null {
  if (!text) return null
  try {
    const parsed = iconSourceSchema.safeParse(JSON.parse(text))
    if (!parsed.success) return null
    if (parsed.data.layer.kind === 'pixels' && !decodeGrid(parsed.data.layer)) return null
    return parsed.data
  } catch {
    return null
  }
}

/** Ergebnis des Editors: fertiges PNG (Base64 ohne Präfix) + Quelle. */
export interface IconResult {
  png: string
  source: IconSource
}

/** Lesbarer Name einer Minecraft-Textur: `item/diamond_sword` → „diamond sword“. */
export function textureLabel(key: string): string {
  return key.slice(key.indexOf('/') + 1).replace(/_/g, ' ')
}

/** Suche in Texturen: alle Wörter müssen vorkommen (Leerzeichen oder Unterstrich egal). */
export function matchesTexture(key: string, query: string): boolean {
  const name = textureLabel(key).toLowerCase()
  return query
    .toLowerCase()
    .split(/[\s_]+/)
    .filter(Boolean)
    .every((word) => name.includes(word))
}
