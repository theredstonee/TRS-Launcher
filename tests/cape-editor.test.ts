import { describe, expect, it } from 'vitest'
import { CAPE_MAX_BYTES, rgba, type Rgba } from '../app/utils/capeImage'
import {
  colorToHex,
  copyOuterToInner,
  duplicateFrame,
  expandTimeline,
  fillRgba,
  flipRect,
  frameAtTick,
  getPixel,
  hexToColor,
  holdsFromDelays,
  mirrorInPart,
  moveFrame,
  newFrame,
  reduceTimeline,
  removeFrame,
  sameImage,
  setHold,
  shiftRect,
  timelineLength,
  type CapeFrame,
} from '../app/utils/capeEditor'
import { base64Bytes, buildUploadStrip, defaultOutputScale, isExactScale, suggestReductions, StaleExport } from '../app/utils/capeExport'

function frames(holds: number[]): CapeFrame[] {
  return holds.map((h) => newFrame(rgba(64, 32), h))
}

function filled(w: number, h: number, c: number[]): Rgba {
  const img = rgba(w, h)
  for (let i = 0; i < img.data.length; i += 4) img.data.set(c, i)
  return img
}

describe('Zeitleiste', () => {
  it('klappt Standzeiten zu wiederholten Frames aus', () => {
    const list = frames([1, 3, 2])
    expect(timelineLength(list)).toBe(6)
    const out = expandTimeline(list)
    expect(out).toHaveLength(6)
    expect(out[1]).toBe(list[1]!.image)
    expect(out[3]).toBe(list[1]!.image)
    expect(out[4]).toBe(list[2]!.image)
    expect([0, 1, 3, 4, 5, 6].map((t) => frameAtTick(list, t))).toEqual([0, 1, 1, 2, 2, 0])
  })

  it('sortiert um, dupliziert und löscht – nie über 32 Takte, nie den letzten Frame', () => {
    const list = frames([1, 1, 1])
    const moved = moveFrame(list, 0, 2)
    expect(moved.map((f) => f.id)).toEqual([list[1]!.id, list[2]!.id, list[0]!.id])
    const dup = duplicateFrame(list, 1)
    expect(dup).toHaveLength(4)
    expect(dup[2]!.image).toBe(list[1]!.image)
    expect(dup[2]!.id).not.toBe(list[1]!.id)
    const full = frames([30, 2])
    expect(duplicateFrame(full, 1)).toHaveLength(2)
    expect(removeFrame(list, 0)).toHaveLength(2)
    expect(removeFrame(frames([1]), 0)).toHaveLength(1)
  })

  it('begrenzt die Standzeit auf den freien Platz', () => {
    const list = frames([10, 10])
    expect(setHold(list, 0, 50)[0]!.hold).toBe(22)
    expect(setHold(list, 0, 0)[0]!.hold).toBe(1)
    expect(timelineLength(setHold(list, 1, 99))).toBe(32)
  })

  it('macht aus GIF-Dauern Takt + Standzeiten', () => {
    expect(holdsFromDelays([100, 100, 300])).toEqual({ frameTime: 100, holds: [1, 1, 3] })
    expect(holdsFromDelays([80, 80, 80])).toEqual({ frameTime: 80, holds: [1, 1, 1] })
    // Passt nicht in 32 Takte → alle gleich lang (mittlere Dauer).
    const long = holdsFromDelays([50, ...Array(31).fill(1000)])
    expect(long.holds.every((h) => h === 1)).toBe(true)
    expect(long.frameTime).toBe(970)
    expect(holdsFromDelays([])).toEqual({ frameTime: 100, holds: [] })
    // Unsinnige Dauern zählen als 100 ms.
    expect(holdsFromDelays([0, Number.NaN]).frameTime).toBe(100)
  })

  it('kürzt zuerst Standzeiten, dann gleichmäßig Frames', () => {
    const list = frames([4, 4, 4])
    const shorter = reduceTimeline(list, 6)
    expect(shorter.map((f) => f.hold)).toEqual([2, 2, 2])
    const fewer = reduceTimeline(frames(Array(32).fill(1)), 16)
    expect(fewer).toHaveLength(16)
    expect(timelineLength(fewer)).toBe(16)
    expect(list[0]!.hold).toBe(4)
  })
})

describe('Pixel-Werkzeuge', () => {
  it('füllt nur innerhalb des Teils (keine Lecks in Nachbar-Inseln)', () => {
    // Leere Textur: Außenseite (1,1,10,16) füllen – Innenseite (12,1) und Kante (11,1) bleiben leer.
    const out = fillRgba(rgba(64, 32), 5, 5, [255, 0, 0, 255], 1)
    expect(getPixel(out, 1, 1)).toEqual([255, 0, 0, 255])
    expect(getPixel(out, 10, 16)).toEqual([255, 0, 0, 255])
    expect(getPixel(out, 11, 5)[3]).toBe(0)
    expect(getPixel(out, 12, 5)[3]).toBe(0)
    expect(getPixel(out, 0, 0)[3]).toBe(0)
    // Bei Faktor 2 dieselbe Fläche, doppelt so groß.
    const hd = fillRgba(rgba(128, 64), 10, 10, [0, 0, 255, 255], 2)
    expect(getPixel(hd, 21, 33)).toEqual([0, 0, 255, 255])
    expect(getPixel(hd, 22, 10)[3]).toBe(0)
  })

  it('spiegelt innerhalb des Teils', () => {
    const img = rgba(64, 32)
    // Außenseite 1…10: x = 1 ↔ 10, y = 1 ↔ 16.
    expect(mirrorInPart([[1, 1]], img, 1, 'x')).toEqual([
      [1, 1],
      [10, 1],
    ])
    expect(mirrorInPart([[2, 3]], img, 1, 'xy')).toHaveLength(4)
    expect(mirrorInPart([[2, 3]], img, 1, 'none')).toEqual([[2, 3]])
    // Innenseite (12…21).
    expect(mirrorInPart([[12, 1]], img, 1, 'x')).toContainEqual([21, 1])
  })

  it('verschiebt und spiegelt Teile, übernimmt außen nach innen', () => {
    const img = rgba(64, 32)
    img.data.set([9, 9, 9, 255], (1 * 64 + 1) * 4)
    const rect = { x: 1, y: 1, w: 10, h: 16 }
    const moved = shiftRect(img, rect, 2, 1)
    expect(getPixel(moved, 3, 2)).toEqual([9, 9, 9, 255])
    expect(getPixel(moved, 1, 1)[3]).toBe(0)
    expect(getPixel(shiftRect(img, rect, -5, 0), 1, 1)[3]).toBe(0)
    const flipped = flipRect(img, rect, 'x')
    expect(getPixel(flipped, 10, 1)).toEqual([9, 9, 9, 255])
    const inner = copyOuterToInner(img, 1)
    expect(getPixel(inner, 21, 1)).toEqual([5, 5, 5, 255])
    expect(sameImage(img, img)).toBe(true)
    expect(sameImage(img, moved)).toBe(false)
  })

  it('rechnet Farben um', () => {
    expect(hexToColor('#ff8000')).toEqual([255, 128, 0, 255])
    expect(hexToColor('#ff800080')).toEqual([255, 128, 0, 128])
    expect(hexToColor('nope')).toEqual([0, 0, 0, 255])
    expect(colorToHex([255, 128, 0, 255])).toBe('#ff8000')
  })
})

describe('Hochladen', () => {
  it('wählt pixelgenaue Standard-Auflösungen', () => {
    expect(defaultOutputScale(1)).toBe(16)
    expect(defaultOutputScale(4)).toBe(16)
    expect(defaultOutputScale(3)).toBe(15)
    expect(defaultOutputScale(16)).toBe(16)
    expect(defaultOutputScale(9)).toBe(9)
    expect(isExactScale(2, 8)).toBe(true)
    expect(isExactScale(3, 8)).toBe(false)
    expect(isExactScale(4, 2)).toBe(false)
  })

  it('baut den Streifen aus der ausgeklappten Zeitleiste (gleiche Bilder nur einmal)', async () => {
    const a = filled(64, 32, [200, 0, 0, 255])
    const b = filled(64, 32, [0, 0, 200, 255])
    const seen: number[] = []
    const strip = await buildUploadStrip([a, a, b], { base: 1, scale: 2, smooth: false, onProgress: (done) => seen.push(done) })
    expect([strip.width, strip.height]).toEqual([128, 64 * 3])
    expect([...strip.data.subarray(0, 4)]).toEqual([200, 0, 0, 255])
    expect([...strip.data.subarray(128 * 64 * 2 * 4, 128 * 64 * 2 * 4 + 4)]).toEqual([0, 0, 200, 255])
    expect(seen).toEqual([1, 2])
    await expect(buildUploadStrip([a], { base: 1, scale: 2, smooth: false, stale: () => true })).rejects.toBeInstanceOf(StaleExport)
  })

  it('schlägt Verkleinerungen vor, wenn das PNG zu groß wäre', () => {
    expect(suggestReductions(CAPE_MAX_BYTES, 16, 32, 1)).toEqual([])
    const tips = suggestReductions(CAPE_MAX_BYTES * 2, 16, 32, 1)
    const scale = tips.find((r) => r.kind === 'scale')!
    const fewer = tips.find((r) => r.kind === 'frames')!
    expect(scale.value).toBeLessThan(16)
    expect(scale.bytes).toBeLessThanOrEqual(CAPE_MAX_BYTES)
    expect(fewer.value).toBeLessThanOrEqual(16)
    expect(fewer.bytes).toBeLessThanOrEqual(CAPE_MAX_BYTES)
    // Bei Faktor 4 nur pixelgenaue kleinere Faktoren (Vielfache von 4) oder darunter.
    const exact = suggestReductions(CAPE_MAX_BYTES * 1.5, 16, 1, 4)
    expect(exact).toHaveLength(1)
    expect(exact[0]!.value % 4 === 0 || exact[0]!.value < 4).toBe(true)
  })

  it('zählt Bytes hinter Base64', () => {
    expect(base64Bytes('QUJD')).toBe(3)
    expect(base64Bytes('QUI=')).toBe(2)
    expect(base64Bytes('QQ==')).toBe(1)
  })
})
