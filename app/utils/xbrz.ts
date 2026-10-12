import { rgba, type Rgba } from './capeImage'

/*
 * xBRZ-Hochskalierer (2×, 3×, 4×) für Pixel-Art – eigene TypeScript-Umsetzung des
 * Verfahrens von Zenju (xBRZ 1.8, GPL-3.0 wie der Launcher): für jede Ecke zwischen
 * vier Pixeln wird aus einem 4×4-Fenster entschieden, ob dort eine Kante verläuft
 * (flach, steil, diagonal oder nur eine runde Ecke), und der Zielblock wird dann mit
 * Farbverläufen geglättet. Farbabstand im YCbCr-Raum, Alpha wie in xBRZ „ARGB“
 * (durchsichtige Pixel geben keine Farbe ab). Läuft komplett lokal, ohne Download.
 */

export type XbrzFactor = 2 | 3 | 4

const CFG = {
  luminanceWeight: 1,
  equalColorTolerance: 30,
  centerDirectionBias: 4,
  dominantDirectionThreshold: 3.6,
  steepDirectionThreshold: 2.2,
}

const NONE = 0
const NORMAL = 1
const DOMINANT = 2

// Farbe als 0xAARRGGBB (vorzeichenlos).
const A = (p: number) => p >>> 24
const R = (p: number) => (p >>> 16) & 255
const G = (p: number) => (p >>> 8) & 255
const B = (p: number) => p & 255
const pack = (a: number, r: number, g: number, b: number) => ((a << 24) | (r << 16) | (g << 8) | b) >>> 0

function distYCbCr(p1: number, p2: number): number {
  const r = R(p1) - R(p2)
  const g = G(p1) - G(p2)
  const b = B(p1) - B(p2)
  // ITU-R BT.2020
  const kb = 0.0593
  const kr = 0.2627
  const kg = 1 - kb - kr
  const y = kr * r + kg * g + kb * b
  const cb = (0.5 / (1 - kb)) * (b - y)
  const cr = (0.5 / (1 - kr)) * (r - y)
  return Math.sqrt((CFG.luminanceWeight * y) ** 2 + cb * cb + cr * cr)
}

/** Abstand mit Alpha: gleich durchsichtig = Farbabstand gewichtet, sonst plus Alpha-Unterschied. */
export function colorDistance(p1: number, p2: number): number {
  const a1 = A(p1) / 255
  const a2 = A(p2) / 255
  const d = distYCbCr(p1, p2)
  return a1 < a2 ? a1 * d + 255 * (a2 - a1) : a2 * d + 255 * (a1 - a2)
}

const eq = (p1: number, p2: number) => colorDistance(p1, p2) < CFG.equalColorTolerance

/** Zwischenfarbe M/N vorne, Rest hinten – gewichtet mit Alpha (kein Abdunkeln an Rändern). */
function gradient(m: number, n: number, front: number, back: number): number {
  const wf = A(front) * m
  const wb = A(back) * (n - m)
  const ws = wf + wb
  if (ws === 0) return 0
  const mix = (cf: number, cb: number) => Math.floor((cf * wf + cb * wb) / ws)
  return pack(Math.floor(ws / n), mix(R(front), R(back)), mix(G(front), G(back)), mix(B(front), B(back)))
}

// Kernel 3×3 (a b c / d e f / g h i): Quell-Index je Ziel für 0°, 90°, 180°, 270°.
const ROTATIONS = [
  [0, 1, 2, 3, 4, 5, 6, 7, 8],
  [6, 3, 0, 7, 4, 1, 8, 5, 2],
  [8, 7, 6, 5, 4, 3, 2, 1, 0],
  [2, 5, 8, 1, 4, 7, 0, 3, 6],
] as const

/** Ziel-Block (n×n ab `base`) mit gedrehten Koordinaten (Zeile i, Spalte j). */
class Block {
  private readonly out: Uint32Array
  private readonly base: number
  private readonly width: number
  private readonly n: number
  private readonly rot: number

  constructor(out: Uint32Array, base: number, width: number, n: number, rot: number) {
    this.out = out
    this.base = base
    this.width = width
    this.n = n
    this.rot = rot
  }

  private index(i: number, j: number): number {
    const n = this.n
    let row = i
    let col = j
    if (this.rot === 1) {
      row = n - 1 - j
      col = i
    } else if (this.rot === 2) {
      row = n - 1 - i
      col = n - 1 - j
    } else if (this.rot === 3) {
      row = j
      col = n - 1 - i
    }
    return this.base + row * this.width + col
  }

  grad(m: number, d: number, i: number, j: number, col: number) {
    const k = this.index(i, j)
    this.out[k] = gradient(m, d, col, this.out[k]!)
  }

  set(i: number, j: number, col: number) {
    this.out[this.index(i, j)] = col
  }
}

interface Scaler {
  shallow(b: Block, c: number): void
  steep(b: Block, c: number): void
  steepAndShallow(b: Block, c: number): void
  diagonal(b: Block, c: number): void
  corner(b: Block, c: number): void
}

const SCALERS: Record<XbrzFactor, Scaler> = {
  2: {
    shallow: (b, c) => {
      b.grad(1, 4, 1, 0, c)
      b.grad(3, 4, 1, 1, c)
    },
    steep: (b, c) => {
      b.grad(1, 4, 0, 1, c)
      b.grad(3, 4, 1, 1, c)
    },
    steepAndShallow: (b, c) => {
      b.grad(1, 4, 1, 0, c)
      b.grad(1, 4, 0, 1, c)
      b.grad(5, 6, 1, 1, c)
    },
    diagonal: (b, c) => b.grad(1, 2, 1, 1, c),
    corner: (b, c) => b.grad(21, 100, 1, 1, c),
  },
  3: {
    shallow: (b, c) => {
      b.grad(1, 4, 2, 0, c)
      b.grad(1, 4, 1, 2, c)
      b.grad(3, 4, 2, 1, c)
      b.set(2, 2, c)
    },
    steep: (b, c) => {
      b.grad(1, 4, 0, 2, c)
      b.grad(1, 4, 2, 1, c)
      b.grad(3, 4, 1, 2, c)
      b.set(2, 2, c)
    },
    steepAndShallow: (b, c) => {
      b.grad(1, 4, 2, 0, c)
      b.grad(1, 4, 0, 2, c)
      b.grad(3, 4, 2, 1, c)
      b.grad(3, 4, 1, 2, c)
      b.set(2, 2, c)
    },
    diagonal: (b, c) => {
      b.grad(1, 8, 1, 2, c)
      b.grad(1, 8, 2, 1, c)
      b.grad(7, 8, 2, 2, c)
    },
    corner: (b, c) => b.grad(45, 100, 2, 2, c),
  },
  4: {
    shallow: (b, c) => {
      b.grad(1, 4, 3, 0, c)
      b.grad(1, 4, 2, 2, c)
      b.grad(3, 4, 3, 1, c)
      b.grad(3, 4, 2, 3, c)
      b.set(3, 2, c)
      b.set(3, 3, c)
    },
    steep: (b, c) => {
      b.grad(1, 4, 0, 3, c)
      b.grad(1, 4, 2, 2, c)
      b.grad(3, 4, 1, 3, c)
      b.grad(3, 4, 3, 2, c)
      b.set(2, 3, c)
      b.set(3, 3, c)
    },
    steepAndShallow: (b, c) => {
      b.grad(3, 4, 3, 1, c)
      b.grad(3, 4, 1, 3, c)
      b.grad(1, 4, 3, 0, c)
      b.grad(1, 4, 0, 3, c)
      b.grad(1, 3, 2, 2, c)
      b.set(3, 3, c)
      b.set(3, 2, c)
      b.set(2, 3, c)
    },
    diagonal: (b, c) => {
      b.grad(1, 2, 3, 2, c)
      b.grad(1, 2, 2, 3, c)
      b.set(3, 3, c)
    },
    corner: (b, c) => {
      b.grad(68, 100, 3, 3, c)
      b.grad(9, 100, 3, 2, c)
      b.grad(9, 100, 2, 3, c)
    },
  },
}

/** Ecken zwischen F, G, J, K des 4×4-Fensters (a … p, F = Pixel selbst) → [f, g, j, k]. */
function preProcessCorners(k: Uint32Array, out: Uint8Array) {
  out.fill(NONE)
  const b = k[1]!
  const c = k[2]!
  const e = k[4]!
  const f = k[5]!
  const g = k[6]!
  const h = k[7]!
  const i = k[8]!
  const j = k[9]!
  const kk = k[10]!
  const l = k[11]!
  const n = k[13]!
  const o = k[14]!
  if ((f === g && j === kk) || (f === j && g === kk)) return
  const dist = colorDistance
  const jg = dist(i, f) + dist(f, c) + dist(n, kk) + dist(kk, h) + CFG.centerDirectionBias * dist(j, g)
  const fk = dist(e, j) + dist(j, o) + dist(b, g) + dist(g, l) + CFG.centerDirectionBias * dist(f, kk)
  if (jg < fk) {
    const dominant = CFG.dominantDirectionThreshold * jg < fk
    if (f !== g && f !== j) out[0] = dominant ? DOMINANT : NORMAL
    if (kk !== j && kk !== g) out[3] = dominant ? DOMINANT : NORMAL
  } else if (fk < jg) {
    const dominant = CFG.dominantDirectionThreshold * fk < jg
    if (j !== f && j !== kk) out[2] = dominant ? DOMINANT : NORMAL
    if (g !== f && g !== kk) out[1] = dominant ? DOMINANT : NORMAL
  }
}

// Ecken-Info je Pixel: 2 Bit je Ecke (Bit 0–1 oben links, 2–3 oben rechts, 4–5 unten rechts, 6–7 unten links).
const topR = (b: number) => (b >> 2) & 3
const bottomR = (b: number) => (b >> 4) & 3
const bottomL = (b: number) => (b >> 6) & 3
const rotateInfo = (b: number, rot: number) => (rot ? ((b << (2 * rot)) | (b >> (8 - 2 * rot))) & 0xff : b)

function blendPixel(scaler: Scaler, ker: Uint32Array, out: Uint32Array, base: number, width: number, n: number, info: number, rot: number) {
  const blend = rotateInfo(info, rot)
  if (bottomR(blend) < NORMAL) return
  const map = ROTATIONS[rot]!
  const at = (idx: number) => ker[map[idx]!]!
  const b = at(1)
  const c = at(2)
  const d = at(3)
  const e = at(4)
  const f = at(5)
  const g = at(6)
  const h = at(7)
  const i = at(8)
  let doLineBlend = true
  if (bottomR(blend) < DOMINANT) {
    // Kein zweites Glätten in einer Nachbar-Drehung (einzelne Pixel, „Mario-Augen“).
    if (topR(blend) !== NONE && !eq(e, g)) doLineBlend = false
    else if (bottomL(blend) !== NONE && !eq(e, c)) doLineBlend = false
    // L-Formen: nur die Ecke runden.
    else if (!eq(e, i) && eq(g, h) && eq(h, i) && eq(i, f) && eq(f, c)) doLineBlend = false
  }
  const px = colorDistance(e, f) <= colorDistance(e, h) ? f : h
  const block = new Block(out, base, width, n, rot)
  if (!doLineBlend) {
    scaler.corner(block, px)
    return
  }
  const fg = colorDistance(f, g)
  const hc = colorDistance(h, c)
  const shallow = CFG.steepDirectionThreshold * fg <= hc && e !== g && d !== g
  const steep = CFG.steepDirectionThreshold * hc <= fg && e !== c && b !== c
  if (shallow) {
    if (steep) scaler.steepAndShallow(block, px)
    else scaler.shallow(block, px)
  } else if (steep) scaler.steep(block, px)
  else scaler.diagonal(block, px)
}

/** RGBA → gepackte Farben; ganz durchsichtige Pixel werden einheitlich 0. */
export function packRgba(src: Rgba): Uint32Array {
  const out = new Uint32Array(src.width * src.height)
  const d = src.data
  for (let p = 0; p < out.length; p++) {
    const a = d[p * 4 + 3]!
    out[p] = a === 0 ? 0 : pack(a, d[p * 4]!, d[p * 4 + 1]!, d[p * 4 + 2]!)
  }
  return out
}

export function unpackRgba(px: Uint32Array, width: number, height: number): Rgba {
  const out = rgba(width, height)
  for (let p = 0; p < px.length; p++) {
    const v = px[p]!
    out.data[p * 4] = R(v)
    out.data[p * 4 + 1] = G(v)
    out.data[p * 4 + 2] = B(v)
    out.data[p * 4 + 3] = A(v)
  }
  return out
}

/** Bild mit xBRZ um `factor` vergrößern (Ränder werden wie bei xBRZ fortgesetzt). */
export function xbrzScale(src: Rgba, factor: XbrzFactor): Rgba {
  const w = src.width
  const h = src.height
  if (!w || !h) return rgba(0, 0)
  const scaler = SCALERS[factor]
  const px = packRgba(src)
  const tw = w * factor
  const out = new Uint32Array(tw * h * factor)
  const info = new Uint8Array(w * h)
  const ker = new Uint32Array(16)
  const res = new Uint8Array(4)
  const at = (x: number, y: number) => px[Math.min(h - 1, Math.max(0, y)) * w + Math.min(w - 1, Math.max(0, x))]!
  const kernel = (x: number, y: number) => {
    for (let ky = 0; ky < 4; ky++) for (let kx = 0; kx < 4; kx++) ker[ky * 4 + kx] = at(x - 1 + kx, y - 1 + ky)
  }
  const mark = (x: number, y: number, bits: number) => {
    info[y * w + x] = info[y * w + x]! | bits
  }
  // 1. Alle Ecken bewerten: Ecke unten rechts von (x, y) = zwischen F, G, J, K.
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      kernel(x, y)
      preProcessCorners(ker, res)
      mark(x, y, res[0]! << 4) // F: unten rechts
      if (x + 1 < w) mark(x + 1, y, res[1]! << 6) // G: unten links
      if (y + 1 < h) mark(x, y + 1, res[2]! << 2) // J: oben rechts
      if (x + 1 < w && y + 1 < h) mark(x + 1, y + 1, res[3]!) // K: oben links
    }
  }
  // 2. Blöcke füllen und die Ecken glätten.
  const ker3 = new Uint32Array(9)
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const base = y * factor * tw + x * factor
      const color = px[y * w + x]!
      for (let by = 0; by < factor; by++) out.fill(color, base + by * tw, base + by * tw + factor)
      const blend = info[y * w + x]!
      if (!blend) continue
      for (let ky = 0; ky < 3; ky++) for (let kx = 0; kx < 3; kx++) ker3[ky * 3 + kx] = at(x - 1 + kx, y - 1 + ky)
      for (let rot = 0; rot < 4; rot++) blendPixel(scaler, ker3, out, base, tw, factor, blend, rot)
    }
  }
  return unpackRgba(out, tw, h * factor)
}
