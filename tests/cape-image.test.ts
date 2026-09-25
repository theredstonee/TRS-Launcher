import { describe, expect, it } from 'vitest'
import {
  buildStrip,
  capeVisible,
  clampFrameTime,
  cropFor,
  dataUrlBytes,
  detectTexture,
  frameTimeFor,
  naturalCompare,
  resample,
  rgba,
  sampleFrames,
  splitStrip,
  stack,
  textureFromFace,
  textureFromTexture,
  type Rgba,
} from '../app/utils/capeImage'

function filled(width: number, height: number, color: [number, number, number, number]): Rgba {
  const img = rgba(width, height)
  for (let i = 0; i < img.data.length; i += 4) img.data.set(color, i)
  return img
}

function pixel(img: Rgba, x: number, y: number): number[] {
  const i = (y * img.width + x) * 4
  return [...img.data.subarray(i, i + 4)]
}

describe('Umhang-Werkstatt', () => {
  it('erkennt Umhang-Texturen und Streifen', () => {
    expect(detectTexture(64, 32)).toEqual({ kind: 'full', frames: 1, frameHeight: 32, scale: 1 })
    expect(detectTexture(512, 1024)).toEqual({ kind: 'full', frames: 4, frameHeight: 256, scale: 8 })
    expect(detectTexture(1024, 512)?.scale).toBe(16)
    expect(detectTexture(44, 34 * 3)).toEqual({ kind: 'cape-only', frames: 3, frameHeight: 34, scale: 2 })
    // Mit Frame-Zahl aus dem TRS-Studio-JSON wird nur geprüft.
    expect(detectTexture(512, 1024, 4)?.frames).toBe(4)
    expect(detectTexture(512, 1024, 3)).toBeNull()
    expect(detectTexture(300, 400)).toBeNull()
    expect(detectTexture(10, 5)).toBeNull()
  })

  it('verteilt Frames gleichmäßig und rechnet das Bildtempo um', () => {
    expect(sampleFrames(4)).toEqual([0, 1, 2, 3])
    expect(sampleFrames(32)).toEqual(Array.from({ length: 16 }, (_, i) => i * 2))
    expect(frameTimeFor(40 * 50, 16)).toBe(130)
    expect(clampFrameTime(3)).toBe(50)
    expect(clampFrameTime(99_999)).toBe(1000)
    expect(clampFrameTime(Number.NaN)).toBe(100)
  })

  it('sortiert Dateinamen natürlich', () => {
    expect(['frame10', 'frame2', 'Frame1'].sort(naturalCompare)).toEqual(['Frame1', 'frame2', 'frame10'])
  })

  it('schneidet Sprite-Sheets in gleich hohe Frames', () => {
    const sheet = rgba(4, 12)
    sheet.data.set([9, 9, 9, 255], (8 * 4) * 4)
    const frames = splitStrip(sheet, 3)
    expect(frames).toHaveLength(3)
    expect(frames.every((f) => f.width === 4 && f.height === 4)).toBe(true)
    expect(pixel(frames[2]!, 0, 0)).toEqual([9, 9, 9, 255])
    expect(stack(frames).height).toBe(12)
  })

  it('mittelt beim Verkleinern und bleibt beim Vergrößern pixelig', () => {
    const src = rgba(2, 1)
    src.data.set([255, 0, 0, 255, 0, 0, 255, 255])
    expect(pixel(resample(src, { x: 0, y: 0, w: 2, h: 1 }, 1, 1), 0, 0)).toEqual([128, 0, 128, 255])
    const up = resample(src, { x: 0, y: 0, w: 2, h: 1 }, 4, 1)
    expect(pixel(up, 1, 0)).toEqual([255, 0, 0, 255])
    expect(pixel(up, 2, 0)).toEqual([0, 0, 255, 255])
    // Durchsichtige Pixel geben keine Farbe ab.
    const half = rgba(2, 1)
    half.data.set([255, 255, 255, 255, 0, 0, 0, 0])
    expect(pixel(resample(half, { x: 0, y: 0, w: 2, h: 1 }, 1, 1), 0, 0)).toEqual([255, 255, 255, 128])
  })

  it('hält den Ausschnitt im Bild und im Seitenverhältnis 10:16', () => {
    const wide = cropFor(1000, 400, 1, 0, 0)
    expect(wide).toEqual({ x: 0, y: 0, w: 250, h: 400 })
    const zoomed = cropFor(1000, 400, 2, 990, 390)
    expect(zoomed.w).toBe(125)
    expect(zoomed.x + zoomed.w).toBe(1000)
    expect(zoomed.y + zoomed.h).toBe(400)
    const tall = cropFor(100, 1000, 1, 50, 500)
    expect(tall.w).toBe(100)
    expect(tall.h).toBe(160)
  })

  it('baut aus einem Motiv eine ganze Umhang-Textur', () => {
    const s = 2
    const face = filled(10 * s, 16 * s, [200, 100, 50, 255])
    const tex = textureFromFace(face, s)
    expect(tex.width).toBe(128)
    expect(tex.height).toBe(64)
    expect(pixel(tex, 1 * s, 1 * s)).toEqual([200, 100, 50, 255])
    // Innenseite dunkler, Elytra-Außenseite gefüllt, Rest durchsichtig.
    expect(pixel(tex, 12 * s, 1 * s)).toEqual([120, 60, 30, 255])
    expect(pixel(tex, 24 * s, 2 * s)).toEqual([200, 100, 50, 255])
    expect(pixel(tex, 60 * s, 30 * s)[3]).toBe(0)
    expect(capeVisible(tex, s, 1)).toBe(true)
  })

  it('bringt vorhandene Texturen auf den gewählten Faktor', () => {
    const big = filled(1024, 512, [1, 2, 3, 255])
    const tex = textureFromTexture(big, 'full', 8)
    expect([tex.width, tex.height]).toEqual([512, 256])
    const capeOnly = textureFromTexture(filled(22, 17, [5, 5, 5, 255]), 'cape-only', 1)
    expect([capeOnly.width, capeOnly.height]).toEqual([64, 32])
    expect(pixel(capeOnly, 21, 16)).toEqual([5, 5, 5, 255])
    expect(pixel(capeOnly, 22, 0)[3]).toBe(0)
  })

  it('baut den Upload-Streifen mit höchstens 16 Frames', () => {
    const frames = Array.from({ length: 20 }, () => filled(100, 160, [9, 9, 9, 255]))
    const strip = buildStrip({ frames, mode: 'crop', scale: 1 })
    expect([strip.width, strip.height]).toEqual([64, 32 * 16])
    const hd = buildStrip({ frames: frames.slice(0, 2), mode: 'crop', scale: 12 })
    expect([hd.width, hd.height]).toEqual([512, 512])
    expect(capeVisible(rgba(64, 32), 1, 1)).toBe(false)
  })

  it('schätzt die Dateigröße einer Data-URL', () => {
    expect(dataUrlBytes('data:image/png;base64,QUJD')).toBe(3)
    expect(dataUrlBytes('data:image/png;base64,QUI=')).toBe(2)
  })
})
