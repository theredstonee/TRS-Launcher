import { describe, expect, it } from 'vitest'
import { clampCrop, CROP_MAX_ZOOM, frameSize, initialCrop, minZoom, panBy, rotateCrop, sniffImage, zoomAt } from '../app/utils/logo-crop'

describe('logo crop geometry', () => {
  it('starts filled for square-ish images and fitted for wide wordmarks', () => {
    expect(initialCrop(500, 480)).toEqual({ rot: 0, zoom: 1, ox: 0, oy: 0 })
    expect(initialCrop(1000, 250).zoom).toBeCloseTo(0.25)
    expect(minZoom(1000, 250)).toBeCloseTo(0.25)
    expect(frameSize(1000, 250, 0, 1)).toBe(250)
    expect(frameSize(1000, 250, 0, 0.25)).toBe(1000)
  })

  it('keeps the frame inside a larger image and a smaller image inside the frame', () => {
    // Zoom 1: Rahmen 250 px im 1000×250-Bild → waagerecht ±375, senkrecht fest.
    expect(clampCrop({ rot: 0, zoom: 1, ox: 9999, oy: 50 }, 1000, 250)).toEqual({ rot: 0, zoom: 1, ox: 375, oy: 0 })
    // Eingepasst: Rahmen 1000 px, Bild 250 hoch → senkrecht ±375.
    expect(clampCrop({ rot: 0, zoom: 0.25, ox: 10, oy: -9999 }, 1000, 250)).toEqual({ rot: 0, zoom: 0.25, ox: 0, oy: -375 })
    expect(clampCrop({ rot: 0, zoom: 99, ox: 0, oy: 0 }, 400, 400).zoom).toBe(CROP_MAX_ZOOM)
    expect(clampCrop({ rot: 0, zoom: 0.01, ox: 0, oy: 0 }, 400, 400).zoom).toBe(1)
    expect(clampCrop({ rot: 0, zoom: Number.NaN, ox: Number.NaN, oy: 0 }, 400, 400)).toEqual({ rot: 0, zoom: 1, ox: 0, oy: 0 })
  })

  it('pans in screen pixels, zooms around the pointer and rotates the offset', () => {
    const s = { rot: 0 as const, zoom: 2, ox: 0, oy: 0 }
    // Rahmen 200 Bild-Pixel auf 100 Bildschirm-Pixel → Faktor 0,5.
    expect(panBy(s, 10, -4, 0.5, 400, 400)).toEqual({ rot: 0, zoom: 2, ox: 20, oy: -8 })
    // Zoom um die Mitte verschiebt nichts; um einen Punkt bleibt der Bildpunkt darunter stehen.
    expect(zoomAt(s, 4, 0, 0, 100, 400, 400)).toEqual({ rot: 0, zoom: 4, ox: 0, oy: 0 })
    const z = zoomAt(s, 4, 40, 0, 100, 400, 400)
    const under = (st: typeof s) => 40 / (100 / frameSize(400, 400, 0, st.zoom)) - st.ox
    expect(under(z)).toBeCloseTo(under(s))
    const r = rotateCrop({ rot: 0, zoom: 1, ox: 100, oy: 0 }, 800, 400)
    expect(r.rot).toBe(90)
    expect(r.oy).toBe(100)
    expect(r.ox).toBeCloseTo(0)
    expect(rotateCrop({ rot: 270, zoom: 1, ox: 0, oy: 0 }, 10, 10).rot).toBe(0)
  })

  it('recognises PNG, JPEG, GIF and WebP by their first bytes only', () => {
    const b = (...x: number[]) => new Uint8Array([...x, ...Array(12).fill(0)].slice(0, 12))
    expect(sniffImage(b(0x89, 0x50, 0x4e, 0x47))).toBe('png')
    expect(sniffImage(b(0xff, 0xd8, 0xff, 0xe0))).toBe('jpeg')
    expect(sniffImage(b(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))).toBe('gif')
    expect(sniffImage(b(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50))).toBe('webp')
    expect(sniffImage(b(0x3c, 0x73, 0x76, 0x67))).toBeNull()
    expect(sniffImage(new Uint8Array())).toBeNull()
  })
})
