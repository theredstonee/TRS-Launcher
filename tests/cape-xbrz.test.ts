import { describe, expect, it } from 'vitest'
import { rgba, type Rgba } from '../app/utils/capeImage'
import { CAPE_REGIONS, capeRegionAt, isCapePixel, scaleRect } from '../app/utils/capeLayout'
import { binaryAlpha, cleanAlpha, fitTo, nearestScale, upscaleTexture, xbrzStep } from '../app/utils/capeUpscale'
import { colorDistance, packRgba, unpackRgba, xbrzScale } from '../app/utils/xbrz'

type C = [number, number, number, number]
const RED: C = [220, 30, 30, 255]
const BLUE: C = [30, 60, 220, 255]
const WHITE: C = [255, 255, 255, 255]
const BLACK: C = [0, 0, 0, 255]
const CLEAR: C = [0, 0, 0, 0]

/** Kleines Bild aus Zeilen von Zeichen (`.` = durchsichtig). */
function art(rows: string[], palette: Record<string, C>): Rgba {
  const img = rgba(rows[0]!.length, rows.length)
  rows.forEach((row, y) => [...row].forEach((ch, x) => img.data.set(palette[ch] ?? CLEAR, (y * img.width + x) * 4)))
  return img
}

function px(img: Rgba, x: number, y: number): number[] {
  const i = (y * img.width + x) * 4
  return [...img.data.subarray(i, i + 4)]
}

function filled(w: number, h: number, c: C): Rgba {
  const img = rgba(w, h)
  for (let i = 0; i < img.data.length; i += 4) img.data.set(c, i)
  return img
}

describe('xBRZ', () => {
  it('lässt einfarbige Flächen unverändert und vergrößert genau um den Faktor', () => {
    for (const k of [2, 3, 4] as const) {
      const out = xbrzScale(filled(5, 3, RED), k)
      expect([out.width, out.height]).toEqual([5 * k, 3 * k])
      for (let y = 0; y < out.height; y++) for (let x = 0; x < out.width; x++) expect(px(out, x, y)).toEqual(RED)
    }
  })

  it('glättet eine Treppenkante (neue Zwischenfarben nur an der Kante)', () => {
    const stairs = art(['BWWWW', 'BBWWW', 'BBBWW', 'BBBBW', 'BBBBB'], { B: BLACK, W: WHITE })
    const out = xbrzScale(stairs, 4)
    const colors = new Set<string>()
    for (let y = 0; y < out.height; y++) for (let x = 0; x < out.width; x++) colors.add(px(out, x, y).join(','))
    // Schwarz, Weiß und Grautöne dazwischen …
    expect(colors.size).toBeGreaterThan(2)
    for (const c of colors) {
      const [r, g, b, a] = c.split(',').map(Number)
      expect(a).toBe(255)
      expect(r).toBe(g)
      expect(g).toBe(b)
    }
    // … aber weit weg von der Kante bleibt alles rein.
    expect(px(out, 0, out.height - 1)).toEqual(BLACK)
    expect(px(out, out.width - 1, 0)).toEqual(WHITE)
    // Die Treppe wird zur Diagonale: in einem Block auf der Kante gibt es Mischfarben.
    const edge = [...Array(4).keys()].flatMap((dy) => [...Array(4).keys()].map((dx) => px(out, 4 + dx, 4 + dy).join(',')))
    expect(edge.some((c) => c !== BLACK.join(',') && c !== WHITE.join(','))).toBe(true)
  })

  it('ist deterministisch und packt Farben verlustfrei', () => {
    const img = art(['RB.', '.RB', 'B.R'], { R: RED, B: BLUE })
    expect(xbrzScale(img, 3).data).toEqual(xbrzScale(img, 3).data)
    expect(unpackRgba(packRgba(img), 3, 3).data).toEqual(img.data)
    // Durchsichtige Pixel werden einheitlich (sonst „verschiedene“ unsichtbare Farben).
    const ghost = rgba(1, 1)
    ghost.data.set([9, 9, 9, 0])
    expect(packRgba(ghost)[0]).toBe(0)
  })

  it('misst Farbabstände mit Alpha', () => {
    const p = (c: C) => packRgba(art(['x'], { x: c }))[0]!
    expect(colorDistance(p(RED), p(RED))).toBe(0)
    expect(colorDistance(p(BLACK), p(WHITE))).toBeGreaterThan(200)
    // Ganz durchsichtig gegen deckend = voller Alpha-Abstand.
    expect(colorDistance(0, p(WHITE))).toBe(255)
  })
})

describe('Hochskalieren von Umhang-Texturen', () => {
  it('wählt die xBRZ-Stufe passend zum Faktor', () => {
    expect(xbrzStep(1)).toBeNull()
    expect(xbrzStep(1.5)).toBeNull()
    expect(xbrzStep(2)).toBe(2)
    expect(xbrzStep(3)).toBe(3)
    expect(xbrzStep(4)).toBe(4)
    expect(xbrzStep(6)).toBe(3)
    expect(xbrzStep(8)).toBe(4)
    expect(xbrzStep(16)).toBe(4)
    expect(xbrzStep(10)).toBe(2)
    expect(xbrzStep(5)).toBe(4)
    expect(xbrzStep(5.33)).toBe(4)
  })

  it('vergrößert pixelgenau in Blöcken', () => {
    const img = art(['RB'], { R: RED, B: BLUE })
    const out = nearestScale(img, 3)
    expect([out.width, out.height]).toEqual([6, 3])
    expect(px(out, 2, 2)).toEqual(RED)
    expect(px(out, 3, 0)).toEqual(BLUE)
    expect(fitTo(img, 2, 1)).toBe(img)
    expect(fitTo(img, 4, 2).width).toBe(4)
  })

  it('bringt eine 64×32-Textur exakt auf 1024×512 (pixelgenau und geglättet)', () => {
    const tex = filled(64, 32, RED)
    for (const smooth of [false, true]) {
      const out = upscaleTexture(tex, 1, 16, smooth)
      expect([out.width, out.height]).toEqual([1024, 512])
      expect(px(out, 500, 300)).toEqual(RED)
    }
    // Nicht ganzzahlig (3 → 16) und kleiner (8 → 4) geht auch.
    expect(upscaleTexture(filled(192, 96, RED), 3, 16, true).width).toBe(1024)
    expect(upscaleTexture(filled(512, 256, RED), 8, 4, false).width).toBe(256)
    // Gleicher Faktor: Kopie.
    const same = upscaleTexture(tex, 1, 1, true)
    expect(same).not.toBe(tex)
    expect(same.data).toEqual(tex.data)
  })

  it('glättet jeden Teil für sich – keine Farben über die Kanten der UV-Inseln', () => {
    // Außenseite rot, Innenseite blau, direkt nebeneinander (x 11 = rechte Kante, x 12 = innen).
    const tex = rgba(64, 32)
    const paint = (r: { x: number; y: number; w: number; h: number }, c: C) => {
      for (let y = r.y; y < r.y + r.h; y++) for (let x = r.x; x < r.x + r.w; x++) tex.data.set(c, (y * 64 + x) * 4)
    }
    const outer = CAPE_REGIONS.find((r) => r.id === 'capeOuter')!
    const inner = CAPE_REGIONS.find((r) => r.id === 'capeInner')!
    paint(outer, RED)
    paint(inner, BLUE)
    // Ein schräges Muster in der Außenseite, damit xBRZ wirklich etwas tut.
    for (let i = 0; i < 10; i++) tex.data.set(BLUE, ((1 + i) * 64 + 1 + i) * 4)
    const out = upscaleTexture(tex, 1, 4, true)
    const o = scaleRect(outer, 4)
    const n = scaleRect(inner, 4)
    // Rechter Rand der Außenseite: nie Blau aus der Innenseite (dort ist das Muster weit weg).
    for (let y = o.y + o.h - 8; y < o.y + o.h; y++) expect(px(out, o.x + o.w - 1, y)).toEqual(RED)
    // Linker Rand der Innenseite: rein blau.
    for (let y = n.y; y < n.y + n.h; y++) expect(px(out, n.x, y)).toEqual(BLUE)
  })

  it('hält Alpha sauber: harte Kanten bleiben hart, ohne dunklen Saum', () => {
    const shape = art(['....', '.RR.', '.RRR', '..RR'], { R: RED })
    expect(binaryAlpha(shape)).toBe(true)
    const tex = rgba(64, 32)
    // Form in die Außenseite (1, 1) setzen.
    for (let y = 0; y < 4; y++) for (let x = 0; x < 4; x++) tex.data.set(px(shape, x, y), ((1 + y) * 64 + 1 + x) * 4)
    const out = upscaleTexture(tex, 1, 4, true)
    for (let i = 0; i < out.data.length; i += 4) {
      const a = out.data[i + 3]!
      expect(a === 0 || a === 255).toBe(true)
      // Sichtbare Pixel haben genau die Farbe der Form (kein Mischen mit Schwarz).
      if (a === 255) expect([...out.data.subarray(i, i + 3)]).toEqual(RED.slice(0, 3))
    }
    // Weiche Quelle bleibt weich.
    const soft = art(['x'], { x: [10, 20, 30, 128] })
    expect(binaryAlpha(soft)).toBe(false)
    const cleaned = cleanAlpha(art(['x'], { x: [1, 2, 3, 0] }), art(['x'], { x: [7, 8, 9, 0] }), false)
    expect(px(cleaned, 0, 0)).toEqual([7, 8, 9, 0])
  })

  it('kennt die Teile des Umhangs', () => {
    expect(capeRegionAt(5, 5, 1)?.id).toBe('capeOuter')
    expect(capeRegionAt(15, 5, 1)?.id).toBe('capeInner')
    // Elytra-Bereich gehört nicht mehr zum Umhang.
    expect(capeRegionAt(30, 10, 1)).toBeNull()
    expect(capeRegionAt(80, 80, 8)?.id).toBe('capeOuter')
    expect(isCapePixel(60, 30, 1)).toBe(false)
    expect(capeRegionAt(0, 0, 1)).toBeNull()
  })
})
