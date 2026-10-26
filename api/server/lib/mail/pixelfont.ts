import { PNG } from 'pngjs'

/**
 * Eigene Bitmap-Schrift für die Pixel-Überschriften der Mails (Stil „Silkscreen“: nur Großbuchstaben, 5 Pixel hohe
 * Versalien, kantig). Selbst gezeichnet – keine fremde Schrift, keine Lizenzfragen.
 *
 * Raster je Zeichen: 8 Zeilen. Zeile 0–1 = Akzente (Ä, É, Ñ …), Zeile 2–6 = Buchstabe, Zeile 7 = Unterlänge
 * (Komma, Ç, „ …). `#` = gesetzt. Breite je Zeichen = Länge der Zeilen.
 */

interface Glyph {
  body: string[]
  top?: string[]
  below?: string
}

const G = (body: string, extra: { top?: string, below?: string } = {}): Glyph => ({
  body: body.split(' '),
  ...(extra.top ? { top: extra.top.split(' ') } : {}),
  ...(extra.below ? { below: extra.below } : {}),
})

const BASE: Record<string, Glyph> = {
  A: G('.##. #..# #### #..# #..#'),
  B: G('###. #..# ###. #..# ###.'),
  C: G('.### #... #... #... .###'),
  D: G('###. #..# #..# #..# ###.'),
  E: G('#### #... ###. #... ####'),
  F: G('#### #... ###. #... #...'),
  G: G('.### #... #.## #..# .###'),
  H: G('#..# #..# #### #..# #..#'),
  I: G('### .#. .#. .#. ###'),
  J: G('...# ...# ...# #..# .##.'),
  K: G('#..# #.#. ##.. #.#. #..#'),
  L: G('#... #... #... #... ####'),
  M: G('#...# ##.## #.#.# #...# #...#'),
  N: G('#..# ##.# #.## #..# #..#'),
  O: G('.##. #..# #..# #..# .##.'),
  P: G('###. #..# ###. #... #...'),
  Q: G('.##. #..# #..# #.#. .#.#'),
  R: G('###. #..# ###. #.#. #..#'),
  S: G('.### #... .##. ...# ###.'),
  T: G('##### ..#.. ..#.. ..#.. ..#..'),
  U: G('#..# #..# #..# #..# .##.'),
  V: G('#...# #...# .#.#. .#.#. ..#..'),
  W: G('#...# #...# #.#.# ##.## #...#'),
  X: G('#...# .#.#. ..#.. .#.#. #...#'),
  Y: G('#...# .#.#. ..#.. ..#.. ..#..'),
  Z: G('#### ...# .##. #... ####'),
  '0': G('.##. #.## #..# ##.# .##.'),
  '1': G('.#. ##. .#. .#. ###'),
  '2': G('###. ...# .##. #... ####'),
  '3': G('###. ...# .##. ...# ###.'),
  '4': G('#..# #..# #### ...# ...#'),
  '5': G('#### #... ###. ...# ###.'),
  '6': G('.##. #... ###. #..# .##.'),
  '7': G('#### ...# ..#. .#.. .#..'),
  '8': G('.##. #..# .##. #..# .##.'),
  '9': G('.##. #..# .### ...# .##.'),
  ' ': G('... ... ... ... ...'),
  '.': G('. . . . #'),
  ',': G('. . . . #', { below: '#' }),
  '!': G('# # # . #'),
  '?': G('###. ...# .##. .... .#..'),
  ':': G('. # . # .'),
  ';': G('. # . # #', { below: '#' }),
  '-': G('... ... ### ... ...'),
  '–': G('.... .... #### .... ....'),
  '—': G('..... ..... ##### ..... .....'),
  '+': G('... .#. ### .#. ...'),
  '=': G('... ### ... ### ...'),
  "'": G('# # . . .'),
  '"': G('#.# #.# ... ... ...'),
  '(': G('.# #. #. #. .#'),
  ')': G('#. .# .# .# #.'),
  '[': G('## #. #. #. ##'),
  ']': G('## .# .# .# ##'),
  '/': G('..# ..# .#. #.. #..'),
  '&': G('.#.. #.#. .#.. #.#. .#.#'),
  '#': G('.#.# #### .#.# #### .#.#'),
  '%': G('#..# ...# ..#. .#.. #..#'),
  '*': G('... #.# .#. #.# ...'),
  '@': G('.##. #..# #.## #... .###'),
  '€': G('.### #... ###. #... .###'),
  '<': G('..# .#. #.. .#. ..#'),
  '>': G('#.. .#. ..# .#. #..'),
  '_': G('.... .... .... .... ####'),
  '·': G('. . # . .'),
  '×': G('... #.# .#. #.# ...'),
  '→': G('..#.. ...#. ##### ...#. ..#..'),
  '♥': G('.#.#. ##### ##### .###. ..#..'),
  '¡': G('# . # # #'),
  '¿': G('..#. .... .##. #... .###'),
  'ẞ': G('###. #..# #.#. #..# #.#.'),
}

// Akzente über dem Grundbuchstaben (eine Zeile, darunter eine leere).
const ACCENTS: Record<string, [string, string]> = {
  Ä: ['A', 'umlaut'], Ö: ['O', 'umlaut'], Ü: ['U', 'umlaut'], Ë: ['E', 'umlaut'], Ï: ['I', 'umlaut'],
  Á: ['A', 'acute'], É: ['E', 'acute'], Í: ['I', 'acute'], Ó: ['O', 'acute'], Ú: ['U', 'acute'],
  À: ['A', 'grave'], È: ['E', 'grave'], Ì: ['I', 'grave'], Ò: ['O', 'grave'], Ù: ['U', 'grave'],
  Â: ['A', 'circ'], Ê: ['E', 'circ'], Î: ['I', 'circ'], Ô: ['O', 'circ'], Û: ['U', 'circ'],
  Ñ: ['N', 'tilde'],
}

function accentRows(kind: string, width: number): string[] {
  const row = (cols: number[]) => Array.from({ length: width }, (_, i) => (cols.includes(i) ? '#' : '.')).join('')
  const mid = Math.floor((width - 1) / 2)
  switch (kind) {
    case 'umlaut': return [row([0, width - 1]), row([])]
    case 'acute': return [row([Math.min(width - 1, mid + 1)]), row([])]
    case 'grave': return [row([Math.max(0, mid)]), row([])]
    case 'circ': return [row([mid, mid + 1].filter((c) => c < width)), row([])]
    case 'tilde': return [row([1, 3].filter((c) => c < width)), row([0, 2].filter((c) => c < width))]
    default: return [row([]), row([])]
  }
}

function glyphOf(ch: string): Glyph | null {
  const direct = BASE[ch]
  if (direct) return direct
  const acc = ACCENTS[ch]
  if (acc) {
    const base = BASE[acc[0]]!
    return { body: base.body, top: accentRows(acc[1], base.body[0]!.length) }
  }
  if (ch === 'Ç') return { body: BASE.C!.body, below: '.#..' }
  // Typografische Anführungszeichen wie die geraden.
  if (ch === '’' || ch === '‘' || ch === '´' || ch === '`') return BASE["'"]!
  if (ch === '“' || ch === '”' || ch === '«' || ch === '»') return BASE['"']!
  if (ch === '„') return { body: ['...', '...', '...', '...', '#.#'], below: '#.#' }
  if (ch === '…') return G('..... ..... ..... ..... #.#.#')
  return null
}

/** Text so aufbereiten, wie die Schrift ihn zeichnen kann (Großbuchstaben, ß → ẞ, unbekannte Zeichen weg). */
export function pixelText(text: string): string {
  return [...text.normalize('NFC').replace(/ß/g, 'ẞ').toLocaleUpperCase('de')]
    .map((c) => (c === '\t' || c === '\n' ? ' ' : c))
    .filter((c) => glyphOf(c) !== null)
    .join('')
    .replace(/ {2,}/g, ' ')
    .trim()
}

/** Ein Lauf Text mit Farbe (Akzent-Teile in `*…*`). */
export interface Run {
  text: string
  accent: boolean
}

/** `Version 0.31 – *Der Coup*` → Läufe (Sternchen markieren den farbigen Akzentteil). */
export function parseRuns(input: string): Run[] {
  const out: Run[] = []
  const parts = input.split('*')
  parts.forEach((p, i) => {
    if (p) out.push({ text: p, accent: i % 2 === 1 })
  })
  return out
}

interface Cell {
  ch: string
  accent: boolean
  width: number
}

const ROWS = 8
const GAP = 1

/** Breite eines Wortes in Raster-Spalten. */
function cellsWidth(cells: Cell[]): number {
  return cells.reduce((w, c, i) => w + c.width + (i > 0 ? GAP : 0), 0)
}

/** Zerlegt in Zeilen (Wortumbruch, sehr lange Wörter hart), höchstens `maxCols` Spalten je Zeile. */
export function layout(runs: Run[], maxCols: number): Cell[][] {
  const cells: Cell[] = []
  for (const r of runs) {
    for (const ch of [...r.text.normalize('NFC').replace(/ß/g, 'ẞ').toLocaleUpperCase('de')]) {
      const c = ch === '\n' || ch === '\t' ? ' ' : ch
      const g = glyphOf(c)
      if (!g) continue
      cells.push({ ch: c, accent: r.accent, width: g.body[0]!.length })
    }
  }
  // Wörter (Leerzeichen trennen) – Akzentwechsel mitten im Wort bleiben ein Wort.
  const words: Cell[][] = []
  let cur: Cell[] = []
  for (const c of cells) {
    if (c.ch === ' ') {
      if (cur.length) words.push(cur)
      cur = []
    } else cur.push(c)
  }
  if (cur.length) words.push(cur)
  const space = BASE[' ']!.body[0]!.length
  const lines: Cell[][] = []
  let line: Cell[] = []
  for (let w of words) {
    // Zu lange Wörter hart teilen.
    while (cellsWidth(w) > maxCols && w.length > 1) {
      let n = w.length - 1
      while (n > 1 && cellsWidth(w.slice(0, n)) > maxCols) n--
      if (line.length) lines.push(line)
      lines.push(w.slice(0, n))
      line = []
      w = w.slice(n)
    }
    const next = line.length ? cellsWidth(line) + GAP + space + GAP + cellsWidth(w) : cellsWidth(w)
    if (line.length && next > maxCols) {
      lines.push(line)
      line = [...w]
    } else {
      if (line.length) line.push({ ch: ' ', accent: false, width: space })
      line.push(...w)
    }
  }
  if (line.length) lines.push(line)
  return lines
}

export interface TitleStyle {
  /** Bildschirm-Pixel je Raster-Pixel (1×); das PNG hat das Doppelte (@2x). */
  pixel: number
  /** Größte Breite in Bildschirm-Pixeln (1×). */
  maxWidth: number
  color: string
  accent: string
  /** Schlagschatten (ein Raster-Pixel nach rechts unten), `null` = keiner. */
  shadow: string | null
  /** Zeilenabstand in Raster-Zeilen. */
  lineGap: number
}

export interface RenderedTitle {
  png: Buffer
  /** Anzeigegröße (1×). */
  width: number
  height: number
}

const hex = (h: string): [number, number, number] => [1, 3, 5].map((i) => Number.parseInt(h.slice(i, i + 2), 16)) as [number, number, number]

/** Rendert eine Überschrift als PNG (@2x, transparent). Leerer Text → 1×1 transparent. */
export function renderTitle(text: string, style: TitleStyle): RenderedTitle {
  const scale = style.pixel * 2
  const maxCols = Math.max(8, Math.floor(style.maxWidth / style.pixel) - 1)
  const lines = layout(parseRuns(text), maxCols)
  if (lines.length === 0) {
    const p = new PNG({ width: 2, height: 2 })
    return { png: PNG.sync.write(p), width: 1, height: 1 }
  }
  const lineH = ROWS + style.lineGap
  const cols = Math.max(...lines.map(cellsWidth)) + 1
  const rows = lines.length * lineH - style.lineGap + 1
  // Raster: 0 = leer, 1 = Schrift, 2 = Akzent, 3 = Schatten
  const grid = new Uint8Array(cols * rows)
  lines.forEach((line, li) => {
    let x = 0
    for (const c of line) {
      const g = glyphOf(c.ch)
      if (g) {
        const y0 = li * lineH
        const paint = (row: string, y: number) => {
          for (let i = 0; i < row.length; i++) if (row[i] === '#') grid[y * cols + x + i] = c.accent ? 2 : 1
        }
        g.top?.forEach((row, i) => paint(row, y0 + i))
        g.body.forEach((row, i) => paint(row, y0 + 2 + i))
        if (g.below) paint(g.below, y0 + 7)
      }
      x += c.width + GAP
    }
  })
  if (style.shadow) {
    for (let y = rows - 2; y >= 0; y--) {
      for (let x = cols - 2; x >= 0; x--) {
        const v = grid[y * cols + x]
        if ((v === 1 || v === 2) && grid[(y + 1) * cols + x + 1] === 0) grid[(y + 1) * cols + x + 1] = 3
      }
    }
  }
  // Leere Zeilen oben/unten abschneiden (Akzent- und Unterlängen-Raum nur, wenn gebraucht).
  const used = (y: number) => grid.subarray(y * cols, (y + 1) * cols).some((v) => v !== 0)
  let top = 0
  while (top < rows - 1 && !used(top)) top++
  let bottom = rows - 1
  while (bottom > top && !used(bottom)) bottom--
  const h = bottom - top + 1
  const png = new PNG({ width: cols * scale, height: h * scale })
  const colors: Record<number, [number, number, number, number]> = {
    1: [...hex(style.color), 255],
    2: [...hex(style.accent), 255],
    3: style.shadow ? [...hex(style.shadow), 255] : [0, 0, 0, 0],
  }
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < cols; x++) {
      const v = grid[(y + top) * cols + x]!
      if (v === 0) continue
      const rgba = colors[v]!
      for (let dy = 0; dy < scale; dy++) {
        for (let dx = 0; dx < scale; dx++) {
          const o = (((y * scale + dy) * cols * scale) + x * scale + dx) * 4
          png.data[o] = rgba[0]
          png.data[o + 1] = rgba[1]
          png.data[o + 2] = rgba[2]
          png.data[o + 3] = rgba[3]
        }
      }
    }
  }
  return { png: PNG.sync.write(png, { colorType: 6 }), width: cols * style.pixel, height: h * style.pixel }
}
