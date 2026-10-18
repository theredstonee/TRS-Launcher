import { describe, expect, it } from 'vitest'
import {
  DEFAULT_BACKDROP_COLOR,
  HD_SCALE,
  capeLooksSmooth,
  colorHex,
  countColors,
  hexColor,
  isBlockUniform,
  pixelBlock,
  renderFace,
  rescaleTextureHd,
  shrinkSource,
  suggestFit,
  suggestStyle,
  textureFromTextureHd,
} from '../app/utils/capeHd'
import { buildUploadStrip, suggestReductions } from '../app/utils/capeExport'
import {
  CAPE_MAX_BYTES,
  cropFor,
  detectTexture,
  guessSheet,
  guessSheetFor,
  plausibleTexture,
  resample,
  rgba,
  splitSheet,
  textureFromFace,
  type Rgba,
} from '../app/utils/capeImage'
import { composite, smoothResample } from '../app/utils/capeResample'
import { trsCapeSourceSchema } from '../app/utils/trs'
import { nearestScale } from '../app/utils/capeUpscale'

function filled(width: number, height: number, color: readonly number[]): Rgba {
  const img = rgba(width, height)
  for (let i = 0; i < img.data.length; i += 4) img.data.set(color, i)
  return img
}

function pixel(img: Rgba, x: number, y: number): number[] {
  const i = (y * img.width + x) * 4
  return [...img.data.subarray(i, i + 4)]
}

/** Weicher Verlauf mit etwas Rauschen – wie ein Foto (viele Farben, keine Blöcke). */
function photo(width: number, height: number, seed = 7): Rgba {
  const img = rgba(width, height)
  let s = seed
  const rnd = () => ((s = (Math.imul(s, 1103515245) + 12345) & 0x7fffffff) / 0x7fffffff) * 12
  const d = img.data
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4
      d[i] = (x * 255) / width + rnd()
      d[i + 1] = (y * 255) / height + rnd()
      d[i + 2] = 128 + rnd()
      d[i + 3] = 255
    }
  }
  return img
}

/** Schachbrett aus 1-Pixel-Feldern (schwarz/weiß). */
function checker(width: number, height: number): Rgba {
  const img = rgba(width, height)
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const v = (x + y) % 2 ? 255 : 0
      img.data.set([v, v, v, 255], (y * width + x) * 4)
    }
  }
  return img
}

/** Zufällige Pixel-Art: wenige Farben, hartes Alpha. */
function pixelArt(width: number, height: number): Rgba {
  const colors = [
    [200, 30, 30, 255],
    [20, 20, 20, 255],
    [240, 200, 40, 255],
    [0, 0, 0, 0],
  ]
  const img = rgba(width, height)
  for (let i = 0; i < width * height; i++) img.data.set(colors[(i * 7 + (i >> 3)) % colors.length]!, i * 4)
  return img
}

const whole = (img: Rgba) => ({ x: 0, y: 0, w: img.width, h: img.height })

describe('glattes Skalieren (HD)', () => {
  it('lässt gleiche Größe und einfarbige Flächen unverändert', () => {
    const img = photo(40, 30)
    const same = smoothResample(img, whole(img), 40, 30)
    for (const [x, y] of [
      [0, 0],
      [17, 11],
      [39, 29],
    ] as const) {
      const a = pixel(img, x, y)
      const b = pixel(same, x, y)
      a.forEach((v, i) => expect(Math.abs(v - b[i]!)).toBeLessThanOrEqual(1))
    }
    const red = smoothResample(filled(300, 200, [200, 10, 10, 255]), { x: 0, y: 0, w: 300, h: 200 }, 37, 59)
    expect(pixel(red, 18, 30)).toEqual([200, 10, 10, 255])
  })

  it('mittelt beim Verkleinern statt Pixel auszulassen (kein Flimmern/Aliasing)', () => {
    // Nächster Nachbar würde aus dem Schachbrett je nach Raster reines Schwarz oder Weiß machen.
    const small = smoothResample(checker(256, 256), { x: 0, y: 0, w: 256, h: 256 }, 30, 30)
    for (const [x, y] of [
      [3, 3],
      [15, 20],
      [28, 9],
    ] as const) {
      const [r, , , a] = pixel(small, x, y)
      expect(r).toBeGreaterThan(100)
      expect(r).toBeLessThan(155)
      expect(a).toBe(255)
    }
  })

  it('nimmt keine Farbe von durchsichtigen Pixeln an', () => {
    const img = rgba(8, 2)
    for (let x = 0; x < 4; x++) img.data.set([0, 200, 0, 255], x * 4) // links grün, rechts durchsichtig (aber „rot“)
    for (let x = 4; x < 8; x++) img.data.set([255, 0, 0, 0], x * 4)
    for (let x = 0; x < 8; x++) img.data.set(pixel(img, x, 0), (8 + x) * 4)
    const out = smoothResample(img, whole(img), 2, 1)
    const [r, g, , a] = pixel(out, 0, 0)
    expect(r).toBeLessThan(10)
    expect(g).toBeGreaterThan(190)
    expect(a).toBeGreaterThan(200)
  })

  it('macht außerhalb des Bildes durchsichtig (Einpassen) oder wiederholt den Rand (Füllen)', () => {
    const img = filled(10, 10, [10, 20, 30, 255])
    const crop = { x: -10, y: 0, w: 30, h: 10 }
    const clear = smoothResample(img, crop, 30, 10, { outside: 'transparent' })
    expect(pixel(clear, 1, 5)[3]).toBe(0)
    expect(pixel(clear, 15, 5)).toEqual([10, 20, 30, 255])
    const clamp = smoothResample(img, crop, 30, 10)
    expect(pixel(clamp, 1, 5)).toEqual([10, 20, 30, 255])
  })

  it('legt Bilder richtig übereinander', () => {
    const top = filled(2, 1, [255, 0, 0, 0])
    top.data.set([255, 0, 0, 255], 0)
    const out = composite(top, filled(2, 1, [0, 0, 255, 255]))
    expect(pixel(out, 0, 0)).toEqual([255, 0, 0, 255])
    expect(pixel(out, 1, 0)).toEqual([0, 0, 255, 255])
  })
})

describe('Füllen und Einpassen', () => {
  it('schneidet beim Füllen ab und zeigt beim Einpassen das ganze quadratische Logo', () => {
    const cover = cropFor(1600, 1600, 1, 800, 800)
    expect(cover.w).toBeCloseTo(1000)
    expect(cover.h).toBeCloseTo(1600)
    expect(cover.x).toBeCloseTo(300)
    const contain = cropFor(1600, 1600, 1, 800, 800, undefined, 'contain')
    expect(contain).toEqual({ x: 0, y: -480, w: 1600, h: 2560 })
    // Das ganze Bild liegt im Ausschnitt.
    expect(contain.x).toBeLessThanOrEqual(0)
    expect(contain.y).toBeLessThanOrEqual(0)
    expect(contain.x + contain.w).toBeGreaterThanOrEqual(1600)
    expect(contain.y + contain.h).toBeGreaterThanOrEqual(1600)
  })

  it('verschiebt beim Einpassen nur so weit, dass das Bild im Rahmen bleibt', () => {
    const far = cropFor(1600, 1600, 1, 99_999, -99_999, undefined, 'contain')
    expect(far.x).toBe(0)
    expect(far.y).toBe(-960)
    // Hochkant-Bild: Einpassen = Füllen in der Höhe, Rand links/rechts.
    const tall = cropFor(100, 1000, 1, 50, 500, undefined, 'contain')
    expect(tall).toEqual({ x: -262.5, y: 0, w: 625, h: 1000 })
    // Herangezoomt: wieder ein Ausschnitt im Bild.
    const zoomed = cropFor(1600, 1600, 4, 800, 800, undefined, 'contain')
    expect(zoomed.w).toBe(400)
    expect(zoomed.x).toBe(600)
  })

  it('füllt den Rand beim Einpassen mit dem Hintergrund (Farbe, unscharf oder durchsichtig)', () => {
    const logo = filled(100, 100, [250, 250, 250, 255])
    const crop = cropFor(100, 100, 1, 50, 50, undefined, 'contain')
    const color = renderFace(logo, crop, 4, { style: 'hd', fit: 'contain', backdrop: { kind: 'color', color: [10, 60, 200, 255] } })
    expect(color.width).toBe(40)
    expect(color.height).toBe(64)
    expect(pixel(color, 20, 2)).toEqual([10, 60, 200, 255])
    expect(pixel(color, 20, 32)).toEqual([250, 250, 250, 255])
    const clear = renderFace(logo, crop, 4, { style: 'pixel', fit: 'contain', backdrop: { kind: 'color', color: [0, 0, 0, 0] } })
    expect(pixel(clear, 20, 2)[3]).toBe(0)
    expect(pixel(clear, 20, 32)).toEqual([250, 250, 250, 255])
    const blur = renderFace(photo(100, 100), crop, 4, { style: 'hd', fit: 'contain', backdrop: { kind: 'blur' } })
    expect(pixel(blur, 20, 2)[3]).toBe(255)
  })

  it('schlägt Einpassen für Logos und Füllen für Fotos vor', () => {
    const logo = rgba(400, 400)
    for (let y = 100; y < 300; y++) for (let x = 100; x < 300; x++) logo.data.set([220, 20, 20, 255], (y * 400 + x) * 4)
    expect(suggestFit(logo)).toEqual({ fit: 'contain', backdrop: { kind: 'color', color: DEFAULT_BACKDROP_COLOR } })
    const sticker = filled(500, 300, [255, 255, 255, 255])
    expect(suggestFit(sticker)).toEqual({ fit: 'contain', backdrop: { kind: 'color', color: [255, 255, 255, 255] } })
    expect(suggestFit(photo(640, 480)).fit).toBe('cover')
    // Schon im Seitenverhältnis der Rückseite: Füllen schneidet nichts ab.
    expect(suggestFit(filled(100, 160, [1, 2, 3, 255])).fit).toBe('cover')
  })

  it('wandelt Farben für den Farbwähler um', () => {
    expect(hexColor('#1a2b3c')).toEqual([26, 43, 60, 255])
    expect(colorHex([26, 43, 60, 255])).toBe('#1a2b3c')
    expect(hexColor('kaputt')).toEqual([...DEFAULT_BACKDROP_COLOR])
  })
})

describe('Pixel-Art oder HD', () => {
  it('erkennt einfarbige Blöcke (durchsichtige Pixel zählen unabhängig von ihrer Farbe als gleich)', () => {
    const art = pixelArt(16, 8)
    const up = nearestScale(art, 4)
    expect(isBlockUniform(up, 4)).toBe(true)
    expect(isBlockUniform(up, 8)).toBe(false)
    expect(pixelBlock(up)).toBe(4)
    expect(pixelBlock(art)).toBe(1)
    // Durchsichtiger Block, dessen Pixel verschiedene „Farben“ tragen: zählt als einfarbig.
    const clear = rgba(4, 2)
    clear.data.set([1, 2, 3, 0, 200, 100, 50, 0, 9, 9, 9, 255, 9, 9, 9, 255], 0)
    clear.data.set([7, 7, 7, 0, 0, 0, 0, 0, 9, 9, 9, 255, 9, 9, 9, 255], 16)
    expect(isBlockUniform(clear, 2)).toBe(true)
    clear.data.set([9, 9, 9, 254], 28)
    expect(isBlockUniform(clear, 2)).toBe(false)
    expect(countColors(art)).toBeLessThanOrEqual(4)
  })

  it('schlägt den Stil passend zur Quelle vor', () => {
    // Foto/Gemälde → HD.
    expect(suggestStyle(photo(800, 800), 800)).toBe('hd')
    // Hochskalierte Pixel-Art bleibt Pixel-Art – egal wie groß.
    expect(suggestStyle(nearestScale(pixelArt(64, 32), 16), 256)).toBe('pixel')
    // Winzige Quelle (Rückseite unter 96 Pixel hoch) → Pixel-Art.
    expect(suggestStyle(photo(60, 80), 80)).toBe('pixel')
    // Große, aber flache Zeichnung mit wenigen Farben und hartem Alpha → Pixel-Art.
    expect(suggestStyle(pixelArt(300, 300), 300)).toBe('pixel')
  })

  it('entscheidet „glatt oder scharf“ wie der TRS Client', () => {
    expect(capeLooksSmooth(nearestScale(pixelArt(64, 32), 16))).toBe(false)
    expect(capeLooksSmooth(photo(1024, 512))).toBe(true)
    expect(capeLooksSmooth(photo(64, 32))).toBe(false)
    expect(capeLooksSmooth(photo(100, 50))).toBe(false)
  })
})

describe('HD-Texturen und Hochladen', () => {
  it('übernimmt fertige 1024×512-Texturen unverändert und skaliert andere glatt je Teil', () => {
    const hd = photo(1024, 512)
    const same = textureFromTextureHd(hd, 'full', HD_SCALE)
    expect(Buffer.compare(Buffer.from(same.data.buffer), Buffer.from(hd.data.buffer))).toBe(0)
    expect(same.data === hd.data, 'eine Kopie, nicht dasselbe Array').toBe(false)
    const up = textureFromTextureHd(photo(512, 256), 'full', HD_SCALE)
    expect([up.width, up.height]).toEqual([1024, 512])
    // Unbenutzte Fläche (Elytra-Bereich) bleibt durchsichtig.
    expect(pixel(up, 40 * 16, 5 * 16)[3]).toBe(0)
    const capeOnly = textureFromTextureHd(photo(220, 170), 'cape-only', HD_SCALE)
    expect([capeOnly.width, capeOnly.height]).toEqual([1024, 512])
    expect(pixel(capeOnly, 5 * 16, 5 * 16)[3]).toBe(255)
  })

  it('verkleinert HD-Texturen glatt, wenn die Datei zu groß wird', () => {
    const tex = photo(1024, 512)
    const half = rescaleTextureHd(tex, 16, 8)
    expect([half.width, half.height]).toEqual([512, 256])
    expect(pixel(half, 5 * 8, 5 * 8)[3]).toBe(255)
    // Pixel-Art-Weg dagegen bleibt pixelgenau.
    const px = resample(tex, { x: 0, y: 0, w: 1024, h: 512 }, 512, 256)
    expect(px.width).toBe(512)
  })

  it('baut den Upload-Streifen im HD-Stil ohne Pixelblöcke', async () => {
    const frame = photo(1024, 512)
    const strip = await buildUploadStrip([frame, frame], { base: 16, scale: 16, smooth: false, style: 'hd' })
    expect([strip.width, strip.height]).toEqual([1024, 1024])
    expect(capeLooksSmooth({ ...strip, height: 512, data: strip.data.subarray(0, 1024 * 512 * 4) })).toBe(true)
    const smaller = await buildUploadStrip([frame], { base: 16, scale: 12, smooth: false, style: 'hd' })
    expect([smaller.width, smaller.height]).toEqual([768, 384])
  })

  it('schätzt Verkleinerungen für HD mit der Pixelzahl', () => {
    const bytes = CAPE_MAX_BYTES * 2
    const hd = suggestReductions(bytes, 16, 32, 16, undefined, 'hd')
    const scale = hd.find((r) => r.kind === 'scale')!
    expect(scale.value).toBe(11)
    expect(scale.bytes).toBeLessThanOrEqual(CAPE_MAX_BYTES * 0.95)
    expect(hd.find((r) => r.kind === 'frames')?.value).toBe(15)
    // Pixel-Art bleibt bei pixelgenauen Faktoren.
    const pixelTips = suggestReductions(bytes, 16, 1, 4)
    expect(pixelTips[0]!.value % 4).toBe(0)
  })

  it('verkleinert große Quellen für die schnelle Vorschau', () => {
    const big = photo(2000, 1000)
    const { image, factor } = shrinkSource(big, 500)
    expect([image.width, image.height]).toEqual([500, 250])
    expect(factor).toBeCloseTo(0.25)
    expect(shrinkSource(photo(100, 100), 500).factor).toBe(1)
  })
})

describe('Logo oder Umhang-Streifen?', () => {
  it('zerschneidet quadratische Logos und Fotos nicht in zwei „Frames“', () => {
    // Quadrat = rechnerisch zwei 2:1-Frames – aber ohne freie Flächen einer Umhang-Textur.
    const logo = rgba(1600, 1600)
    for (let y = 150; y < 1450; y++) for (let x = 150; x < 1450; x++) logo.data.set([200, 30, 30, 255], (y * 1600 + x) * 4)
    expect(guessSheet(1600, 1600)).toEqual({ cols: 1, rows: 2, count: 2 })
    expect(guessSheetFor(logo)).toEqual({ cols: 1, rows: 1, count: 1 })
    expect(guessSheetFor(photo(1024, 1024))).toEqual({ cols: 1, rows: 1, count: 1 })
    // 2:1-Foto ist keine Textur, eine echte Umhang-Textur schon.
    expect(plausibleTexture(photo(2048, 1024), { kind: 'full', scale: 32 })).toBe(false)
    const tex = textureFromFace(photo(160, 256), 16)
    expect(plausibleTexture(tex, { kind: 'full', scale: 16 })).toBe(true)
    // Frei, aber einfarbig gefüllt (andere Werkzeuge) zählt auch.
    const filledTex = composite(tex, filled(1024, 512, [30, 30, 30, 255]))
    expect(plausibleTexture(filledTex, { kind: 'full', scale: 16 })).toBe(true)
  })

  it('erkennt echte Streifen (24 Frames in 1024×12288) als Raster', () => {
    const frame = textureFromFace(photo(160, 256), 16)
    const strip = rgba(1024, 512 * 24)
    for (let f = 0; f < 24; f++) strip.data.set(frame.data, f * frame.data.length)
    expect(guessSheetFor(strip)).toEqual({ cols: 1, rows: 24, count: 24 })
    // Mit Frame-Zahl aus TRS Studio wird dem Raster immer vertraut.
    expect(guessSheetFor(rgba(1600, 1600), 2)).toEqual({ cols: 1, rows: 2, count: 2 })
  })
})

describe('fertige Umhang-Streifen', () => {
  it('erkennt Streifen im Server-Format bis 1024×16384 und zerlegt sie in Frames', () => {
    expect(detectTexture(1024, 512 * 24)).toEqual({ kind: 'full', frames: 24, frameHeight: 512, scale: 16 })
    expect(detectTexture(1024, 512 * 32)?.frames).toBe(32)
    expect(detectTexture(352, 272 * 32)).toEqual({ kind: 'cape-only', frames: 32, frameHeight: 272, scale: 16 })
    expect(guessSheet(1024, 512 * 24)).toEqual({ cols: 1, rows: 24, count: 24 })
    // Der Kern liefert solche Streifen ans Webview – die Prüfung dort lässt sie durch.
    const strip16k = { kind: 'image', name: 'Streifen', dataUrl: 'data:image/png;base64,AAAA', width: 1024, height: 16384 }
    expect(trsCapeSourceSchema.safeParse(strip16k).success).toBe(true)
    expect(trsCapeSourceSchema.safeParse({ ...strip16k, height: 16385 }).success).toBe(false)
    const strip = rgba(64 * 2, 32 * 2 * 3)
    const frames = splitSheet(strip, guessSheet(strip.width, strip.height))
    expect(frames).toHaveLength(3)
    expect([frames[0]!.width, frames[0]!.height]).toEqual([128, 64])
  })
})
