import { rgba, type Crop, type Rgba } from './capeImage'

// Glattes Skalieren für HD-Umhänge („HD – wie Bild“): trennbarer Lanczos-3-Filter
// (beim Verkleinern auf die Zielgröße verbreitert, also echtes Mitteln statt
// Pixel-Auslassen), gerechnet mit vormultipliziertem Alpha, damit durchsichtige
// Pixel keine Farbe abgeben. Kein Einrasten auf Pixel, keine Farbreduktion.
// Rein, ohne DOM – getestet in tests/cape-hd.test.ts.

/** Was außerhalb des Bildes liegt: Randpixel wiederholen oder durchsichtig (für „Einpassen“). */
export type Outside = 'clamp' | 'transparent'
/** Filter: Lanczos 3 (scharf, Standard) oder Dreieck (bilinear, weich – für den Unschärfe-Hintergrund). */
export type Kernel = 'lanczos3' | 'triangle'

const KERNELS: Record<Kernel, { support: number; fn: (x: number) => number }> = {
  lanczos3: {
    support: 3,
    fn: (x) => {
      if (x === 0) return 1
      if (x <= -3 || x >= 3) return 0
      const px = Math.PI * x
      return (3 * Math.sin(px) * Math.sin(px / 3)) / (px * px)
    },
  },
  triangle: { support: 1, fn: (x) => Math.max(0, 1 - Math.abs(x)) },
}

interface Taps {
  /** Je Zielpixel: Start in `index`/`weight` und Anzahl. */
  start: Int32Array
  count: Int32Array
  /** Quellindex (−1 = durchsichtig außerhalb des Bildes). */
  index: Int32Array
  weight: Float32Array
}

/** Gewichte einer Achse: `dst` Zielpixel aus dem Bereich [from, from + len) einer Quelle der Länge `size`. */
function taps(size: number, from: number, len: number, dst: number, kernel: Kernel, outside: Outside): Taps {
  const k = KERNELS[kernel]
  const ratio = len / dst
  const stretch = Math.max(1, ratio)
  const support = k.support * stretch
  const start = new Int32Array(dst)
  const count = new Int32Array(dst)
  const index: number[] = []
  const weight: number[] = []
  for (let i = 0; i < dst; i++) {
    const center = from + (i + 0.5) * ratio
    const lo = Math.floor(center - support)
    const hi = Math.ceil(center + support)
    start[i] = index.length
    let sum = 0
    const first = index.length
    for (let j = lo; j <= hi; j++) {
      const w = k.fn((j + 0.5 - center) / stretch)
      if (w === 0) continue
      let src = j
      if (j < 0 || j >= size) {
        if (outside === 'transparent') src = -1
        else src = j < 0 ? 0 : size - 1
      }
      index.push(src)
      weight.push(w)
      sum += w
    }
    if (sum !== 0) for (let n = first; n < index.length; n++) weight[n]! /= sum
    count[i] = index.length - first
  }
  return { start, count, index: Int32Array.from(index), weight: Float32Array.from(weight) }
}

/**
 * Ausschnitt `crop` (Pixel, gebrochen erlaubt, darf über das Bild hinausragen) auf
 * genau `dw`×`dh` bringen – glatt, in voller Farbtiefe. Verkleinern mittelt alle
 * beteiligten Pixel, Vergrößern interpoliert weich.
 */
export function smoothResample(
  src: Rgba,
  crop: Crop,
  dw: number,
  dh: number,
  options: { outside?: Outside; kernel?: Kernel } = {},
): Rgba {
  const outside = options.outside ?? 'clamp'
  const kernel = options.kernel ?? 'lanczos3'
  const out = rgba(dw, dh)
  if (dw < 1 || dh < 1 || src.width < 1 || src.height < 1) return out
  const tx = taps(src.width, crop.x, crop.w, dw, kernel, outside)
  const ty = taps(src.height, crop.y, crop.h, dh, kernel, outside)
  // Nur die Quellzeilen, die überhaupt gebraucht werden.
  let rowLo = src.height
  let rowHi = -1
  for (const r of ty.index) {
    if (r < 0) continue
    if (r < rowLo) rowLo = r
    if (r > rowHi) rowHi = r
  }
  if (rowHi < rowLo) return out
  const rows = rowHi - rowLo + 1
  const s = src.data
  // 1. Waagerecht: je benötigter Quellzeile dw Pixel (vormultipliziert, Gleitkomma).
  const mid = new Float32Array(rows * dw * 4)
  for (let r = 0; r < rows; r++) {
    const rowBase = (rowLo + r) * src.width
    const o = r * dw * 4
    for (let x = 0; x < dw; x++) {
      let cr = 0
      let cg = 0
      let cb = 0
      let ca = 0
      const begin = tx.start[x]!
      const end = begin + tx.count[x]!
      for (let n = begin; n < end; n++) {
        const sx = tx.index[n]!
        if (sx < 0) continue
        const i = (rowBase + sx) * 4
        const a = s[i + 3]!
        if (a === 0) continue
        const w = tx.weight[n]! * a
        cr += s[i]! * w
        cg += s[i + 1]! * w
        cb += s[i + 2]! * w
        ca += w
      }
      const p = o + x * 4
      mid[p] = cr
      mid[p + 1] = cg
      mid[p + 2] = cb
      mid[p + 3] = ca
    }
  }
  // 2. Senkrecht, dann Alpha wieder herausrechnen.
  const d = out.data
  for (let y = 0; y < dh; y++) {
    const begin = ty.start[y]!
    const end = begin + ty.count[y]!
    for (let x = 0; x < dw; x++) {
      let cr = 0
      let cg = 0
      let cb = 0
      let ca = 0
      for (let n = begin; n < end; n++) {
        const sy = ty.index[n]!
        if (sy < 0) continue
        const w = ty.weight[n]!
        const p = ((sy - rowLo) * dw + x) * 4
        cr += mid[p]! * w
        cg += mid[p + 1]! * w
        cb += mid[p + 2]! * w
        ca += mid[p + 3]! * w
      }
      const o = (y * dw + x) * 4
      if (ca <= 0.5) continue
      d[o] = cr / ca
      d[o + 1] = cg / ca
      d[o + 2] = cb / ca
      d[o + 3] = ca
    }
  }
  return out
}

/** `top` über `bottom` legen (Quelle-über-Ziel, gleiche Größe) – Ergebnis neu. */
export function composite(top: Rgba, bottom: Rgba): Rgba {
  const out = rgba(top.width, top.height)
  const t = top.data
  const b = bottom.data
  const o = out.data
  for (let i = 0; i < o.length; i += 4) {
    const ta = t[i + 3]! / 255
    const ba = b[i + 3]! / 255
    const a = ta + ba * (1 - ta)
    if (a <= 0) continue
    for (let c = 0; c < 3; c++) o[i + c] = (t[i + c]! * ta + b[i + c]! * ba * (1 - ta)) / a
    o[i + 3] = a * 255
  }
  return out
}

/** Einfarbige Fläche. */
export function solid(width: number, height: number, color: readonly [number, number, number, number]): Rgba {
  const out = rgba(width, height)
  for (let i = 0; i < out.data.length; i += 4) {
    out.data[i] = color[0]
    out.data[i + 1] = color[1]
    out.data[i + 2] = color[2]
    out.data[i + 3] = color[3]
  }
  return out
}

/**
 * Unscharfer Hintergrund für „Einpassen“: das Bild füllend (Mitte) stark verkleinert,
 * weich wieder vergrößert und etwas abgedunkelt – wie bei Hochkant-Videos.
 */
export function blurredBackdrop(src: Rgba, cover: Crop, dw: number, dh: number, darken = 0.7): Rgba {
  const sw = Math.max(2, Math.round(dw / 16))
  const sh = Math.max(2, Math.round(dh / 16))
  const tiny = smoothResample(src, cover, sw, sh)
  const out = smoothResample(tiny, { x: 0, y: 0, w: sw, h: sh }, dw, dh, { kernel: 'triangle' })
  const d = out.data
  for (let i = 0; i < d.length; i += 4) {
    d[i] = d[i]! * darken
    d[i + 1] = d[i + 1]! * darken
    d[i + 2] = d[i + 2]! * darken
    // Durchsichtige Quellen: Hintergrund trotzdem deckend.
    if (d[i + 3]! < 255) d[i + 3] = 255
  }
  return out
}
