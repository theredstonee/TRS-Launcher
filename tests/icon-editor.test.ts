import { describe, expect, it } from 'vitest'
import {
  buildPalette,
  createHistory,
  decodeGrid,
  defaultSource,
  emptyGrid,
  encodeGrid,
  flipGrid,
  floodFill,
  getPx,
  gridFromRgba,
  hexToPx,
  iconSourceSchema,
  isEmptyGrid,
  linePoints,
  matchesTexture,
  mirrorPoints,
  paint,
  parseIconSource,
  pxToHex,
  quantize,
  rectPoints,
  resizeGrid,
  sameGrid,
  textureLabel,
  type PixelGrid,
} from '../app/utils/iconEditor'
import { TRS_ICON_COLORS, TRS_ICON_IDS, TRS_ICONS, trsIconGrid } from '../app/utils/trsIcons'

const RED = hexToPx('#ff0000')
const BLUE = hexToPx('#0000ff')

function rgba(colors: [number, number, number, number][]): number[] {
  return colors.flat()
}

describe('Farben', () => {
  it('Hex ↔ Pixel', () => {
    expect(pxToHex(hexToPx('#12ab34'))).toBe('#12ab34')
    expect(pxToHex(hexToPx('#12ab3480'))).toBe('#12ab3480')
    expect(hexToPx('#ff0000')).toBe(0xff0000ff)
    expect(hexToPx('rot')).toBe(0)
    expect(hexToPx('#fff')).toBe(0)
  })
})

describe('Werkzeuge', () => {
  it('Stift mit Spiegelung', () => {
    expect(mirrorPoints([[1, 2]], 16, 'x')).toEqual([[1, 2], [14, 2]])
    expect(mirrorPoints([[1, 2]], 16, 'xy')).toHaveLength(4)
    // Mitte auf der Achse: kein doppelter Punkt.
    expect(mirrorPoints([[7, 7]], 15, 'xy')).toHaveLength(1)
    const g = paint(emptyGrid(16), [[0, 0]], RED, 'x')
    expect(getPx(g, 0, 0)).toBe(RED)
    expect(getPx(g, 15, 0)).toBe(RED)
    expect(getPx(g, 0, 15)).toBe(0)
    // Außerhalb wird ignoriert, das Original bleibt unverändert.
    const base = emptyGrid(16)
    paint(base, [[-1, 3], [99, 99]], RED)
    expect(isEmptyGrid(base)).toBe(true)
  })

  it('Linie und Rechteck', () => {
    expect(linePoints(0, 0, 3, 3)).toEqual([[0, 0], [1, 1], [2, 2], [3, 3]])
    expect(linePoints(0, 0, 4, 0)).toHaveLength(5)
    expect(linePoints(2, 5, 2, 5)).toEqual([[2, 5]])
    expect(rectPoints(0, 0, 3, 3, false)).toHaveLength(12)
    expect(rectPoints(3, 3, 0, 0, true)).toHaveLength(16)
  })

  it('Füllen nur der zusammenhängenden Fläche, auch gespiegelt', () => {
    // Senkrechte Wand in Spalte 4 trennt links von rechts.
    let g = paint(emptyGrid(16), linePoints(4, 0, 4, 15), BLUE)
    g = floodFill(g, 0, 0, RED)
    expect(getPx(g, 3, 15)).toBe(RED)
    expect(getPx(g, 4, 0)).toBe(BLUE)
    expect(getPx(g, 10, 10)).toBe(0)
    // Gleiche Farbe: nichts passiert.
    expect(sameGrid(floodFill(g, 0, 0, RED), g)).toBe(true)
    // Gespiegelt füllt auch die rechte Seite (Spiegelpunkt liegt rechts der Wand).
    const mirrored = floodFill(paint(emptyGrid(16), linePoints(4, 0, 4, 15), BLUE), 0, 0, RED, 'x')
    expect(getPx(mirrored, 15, 0)).toBe(RED)
  })

  it('Größe wechseln und spiegeln', () => {
    const g = paint(emptyGrid(16), [[0, 0]], RED)
    const big = resizeGrid(g, 32)
    expect([getPx(big, 0, 0), getPx(big, 1, 1), getPx(big, 2, 2)]).toEqual([RED, RED, 0])
    expect(sameGrid(resizeGrid(big, 16), g)).toBe(true)
    expect(getPx(flipGrid(g, 'x'), 15, 0)).toBe(RED)
    expect(getPx(flipGrid(g, 'y'), 0, 15)).toBe(RED)
  })
})

describe('Rückgängig / Wiederholen', () => {
  it('Verlauf mit Grenze', () => {
    const h = createHistory<PixelGrid>(emptyGrid(16), 3)
    expect(h.canUndo).toBe(false)
    const a = paint(h.current, [[0, 0]], RED)
    h.push(a)
    const b = paint(a, [[1, 1]], BLUE)
    h.push(b)
    expect(h.undo()).toBe(a)
    expect(h.canRedo).toBe(true)
    expect(h.redo()).toBe(b)
    expect(h.redo()).toBe(b)
    h.undo()
    h.push(paint(a, [[2, 2]], BLUE))
    expect(h.canRedo).toBe(false)
    // Grenze: höchstens 3 Schritte zurück.
    for (let i = 0; i < 10; i++) h.push(paint(h.current, [[i, 0]], RED))
    let steps = 0
    while (h.canUndo) {
      h.undo()
      steps++
    }
    expect(steps).toBe(3)
    h.reset(emptyGrid(32))
    expect(h.canUndo || h.canRedo).toBe(false)
  })
})

describe('Verpixeln', () => {
  it('Palette per Median-Cut und nächste Farbe', () => {
    const data = rgba([
      [250, 10, 10, 255], [240, 0, 0, 255], [10, 10, 250, 255], [0, 0, 240, 255],
    ])
    const palette = buildPalette(data, 2)
    expect(palette).toHaveLength(2)
    const grid = quantize(rgba([
      [255, 0, 0, 255], [0, 0, 255, 255], [0, 0, 0, 10], [200, 30, 30, 255],
    ]).concat(new Array(16 * 16 * 4 - 16).fill(0)), 16, palette)
    expect(pxToHex(grid.px[0]!)).toMatch(/^#f/)
    expect(grid.px[1]).not.toBe(grid.px[0])
    expect(grid.px[2]).toBe(0)
    expect(grid.px[3]).toBe(grid.px[0])
    expect(buildPalette([0, 0, 0, 0])).toEqual([])
  })

  it('Raster aus RGBA', () => {
    const data = new Array(16 * 16 * 4).fill(0)
    data.splice(0, 8, 255, 0, 0, 255, 0, 0, 255, 20)
    const g = gridFromRgba(data, 16)
    expect(g.px[0]).toBe(RED)
    expect(g.px[1]).toBe(0)
  })
})

describe('Quelle speichern/laden', () => {
  it('Raster-Kodierung hin und zurück', () => {
    let g = paint(emptyGrid(32), [[0, 0], [31, 31]], RED)
    g = paint(g, [[5, 5]], hexToPx('#00ff0080'))
    const enc = encodeGrid(g)
    expect(enc.palette).toEqual(['#ff0000', '#00ff0080'])
    expect(enc.data).toHaveLength(32 * 32 * 2)
    expect(sameGrid(decodeGrid(enc)!, g)).toBe(true)
    expect(decodeGrid({ ...enc, data: enc.data.slice(2) })).toBeNull()
    expect(decodeGrid({ ...enc, palette: [] })).toBeNull()
  })

  it('Schema und kaputte Daten', () => {
    const source = defaultSource()
    expect(iconSourceSchema.parse(source)).toEqual(source)
    expect(parseIconSource(JSON.stringify(source))).toEqual(source)
    expect(parseIconSource('{')).toBeNull()
    expect(parseIconSource(JSON.stringify({ ...source, v: 2 }))).toBeNull()
    expect(parseIconSource(JSON.stringify({ ...source, layer: { kind: 'image', png: 'javascript:alert(1)' } }))).toBeNull()
    expect(parseIconSource(JSON.stringify({ ...source, bg: { ...source.bg, color: 'red' } }))).toBeNull()
    expect(parseIconSource(null)).toBeNull()
  })

  it('Textur-Suche', () => {
    expect(textureLabel('item/diamond_sword')).toBe('diamond sword')
    expect(matchesTexture('item/diamond_sword', 'sword dia')).toBe(true)
    expect(matchesTexture('item/diamond_sword', 'diamond_sw')).toBe(true)
    expect(matchesTexture('block/stone', 'sword')).toBe(false)
    expect(matchesTexture('block/stone', '  ')).toBe(true)
  })
})

describe('TRS-Symbole', () => {
  it('sind 40 saubere 16×16-Zeichnungen', () => {
    expect(TRS_ICON_IDS.length).toBe(40)
    for (const id of TRS_ICON_IDS) {
      const art = TRS_ICONS[id]!
      expect(art, id).toHaveLength(16)
      art.forEach((line, y) => {
        expect(line.length, `${id} Zeile ${y}`).toBe(16)
        for (const ch of line) expect(ch === '.' || ch in TRS_ICON_COLORS, `${id}: ${ch}`).toBe(true)
      })
      const grid = trsIconGrid(id)!
      expect(isEmptyGrid(grid), id).toBe(false)
    }
    expect(trsIconGrid('fehlt')).toBeNull()
  })
})
