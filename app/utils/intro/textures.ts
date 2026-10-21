// Pixel-Texturen (16×16 je Fläche, aus der Palette erzeugt) und isometrische Quader-Sprites daraus.
// Projektion (Kunst-Pixel): x läuft nach rechts unten, y nach links unten, z nach oben;
// ein Block ist 32 px breit (Oberseite 32×16, Seiten 16 hoch) – gleiche Formeln in `world.ts`/`renderer.ts`.

import type { IntroPalette } from './designs'
import { canvas, clamp, ctx2d, hex, mix, rng, scale, type Cv, type Rgb } from './kit'

/** Halbe Blockbreite bzw. Höhe einer Blockeinheit in Kunst-Pixeln. */
export const HW = 16
export const HH = 8
export const BZ = 16

export type Tex = Rgb[] // 256 Texel, null-frei

export interface Faces {
  top: Tex
  /** Linke Seite (+y). */
  left: Tex
  /** Rechte Seite (+x). */
  right: Tex
  /** Selbstleuchtend (keine Seiten-Abdunklung). */
  emissive?: boolean
}

const T = 16
const idx = (x: number, y: number) => (y & 15) * T + (x & 15)

function fill(f: (x: number, y: number) => Rgb): Tex {
  const out: Tex = new Array(T * T)
  for (let y = 0; y < T; y++) for (let x = 0; x < T; x++) out[idx(x, y)] = f(x, y)
  return out
}

/** Steinziegel (Rückwand): Reihen à 4 px, versetzt. */
export function bricks(p: IntroPalette, seed: number, mossy = 0): Tex {
  const r = rng(seed)
  const shades = [0, 1, 2].map(() => Math.floor(r() * 3))
  const s = p.stone.map(hex)
  const mortar = hex(p.mortar)
  const moss = hex(p.moss)
  const noise = Array.from({ length: 256 }, () => r())
  return fill((x, y) => {
    const row = y >> 2
    const xo = (x + (row & 1) * 4) & 15
    if ((y & 3) === 3 || (xo & 7) === 7) return mortar
    const brick = (row * 2 + (xo >> 3)) % 3
    const base = s[1 + (shades[brick]! % 2)]!
    const n = noise[idx(x, y)]!
    let c: Rgb = n > 0.85 ? s[2]! : n < 0.12 ? s[0]! : base
    if ((y & 3) === 0) c = mix(c, s[3]!, 0.35)
    if (mossy && n * (1 - y / 22) > 1 - mossy) c = mix(c, moss, 0.7)
    return c
  })
}

/** Bodenfliesen 8×8 mit Fase. */
export function tiles(p: IntroPalette, seed: number, mossy = 0): Tex {
  const r = rng(seed)
  const s = p.stone.map(hex)
  const moss = hex(p.moss)
  const tone = [r(), r(), r(), r()]
  const noise = Array.from({ length: 256 }, () => r())
  const frost = p.frost ? hex(p.frost) : null
  return fill((x, y) => {
    const tx = x & 7
    const ty = y & 7
    const k = tone[(x >> 3) + (y >> 3) * 2]!
    let c: Rgb = k > 0.66 ? s[2]! : k > 0.25 ? s[1]! : mix(s[1]!, s[0]!, 0.5)
    if (tx === 0 || ty === 0) c = mix(c, s[3]!, 0.45)
    if (tx === 7 || ty === 7) c = s[0]!
    const n = noise[idx(x, y)]!
    if (n > 0.9) c = mix(c, s[3]!, 0.3)
    if (n < 0.08) c = mix(c, s[0]!, 0.6)
    if (mossy && n > 1 - mossy) c = mix(c, moss, 0.75)
    if (frost && n > 0.7 && (tx + ty) % 5 === 0) c = mix(c, frost, 0.55)
    return c
  })
}

/** Bruchstein (Bodenvariation). */
export function cobble(p: IntroPalette, seed: number): Tex {
  const r = rng(seed)
  const s = p.stone.map(hex)
  const pts = Array.from({ length: 9 }, () => [r() * 16, r() * 16, r()] as const)
  return fill((x, y) => {
    let best = 1e9
    let second = 1e9
    let tone = 0
    for (const [px, py, t] of pts) {
      for (const ox of [-16, 0, 16]) {
        for (const oy of [-16, 0, 16]) {
          const d = (x + 0.5 - px - ox) ** 2 + (y + 0.5 - py - oy) ** 2
          if (d < best) {
            second = best
            best = d
            tone = t
          } else if (d < second) second = d
        }
      }
    }
    if (Math.sqrt(second) - Math.sqrt(best) < 1.1) return s[0]!
    return tone > 0.6 ? s[2]! : tone > 0.25 ? s[1]! : mix(s[1]!, s[3]!, 0.3)
  })
}

/** Polierte Säule: senkrechte Kanten. */
export function pillar(p: IntroPalette, seed: number): Tex {
  const r = rng(seed)
  const s = p.stone.map(hex)
  return fill((x, y) => {
    if (x === 0 || x === 15) return s[0]!
    if (x === 1) return s[3]!
    if (y === 0 || y === 15) return s[1]!
    const n = r()
    return n > 0.9 ? s[3]! : x > 11 ? s[1]! : s[2]!
  })
}

/** Türblock mit Eisenbändern und Nieten. */
export function door(p: IntroPalette, seed: number): Tex {
  const r = rng(seed)
  const d = p.door.map(hex)
  const band = hex(p.doorBand)
  return fill((x, y) => {
    if (y === 2 || y === 13) return x % 5 === 2 ? mix(band, [255, 255, 255], 0.4) : band
    if (y === 3 || y === 14) return d[0]!
    if (x === 0 || x === 15) return d[0]!
    const n = r()
    return n > 0.88 ? d[2]! : (x + y * 3) % 7 === 0 ? d[0]! : d[1]!
  })
}

/** Redstone-Lampe (aus/an). */
export function lamp(p: IntroPalette, on: boolean, seed: number): Tex {
  const r = rng(seed)
  const off = p.lampOff.map(hex)
  const lit = p.lampOn.map(hex)
  return fill((x, y) => {
    const edge = x === 0 || y === 0 || x === 15 || y === 15
    if (edge) return on ? scale(lit[0]!, 0.7) : off[0]!
    const cx = Math.abs(x - 7.5)
    const cy = Math.abs(y - 7.5)
    const n = r()
    const cross = (Math.abs(x - y) < 1.2 || Math.abs(x + y - 15) < 1.2) && cx < 6 && cy < 6
    if (on) {
      if (cx < 2 && cy < 2) return lit[2]!
      if (cross) return lit[1]!
      return n > 0.6 ? lit[1]! : lit[0]!
    }
    if (cross) return off[1]!
    return n > 0.7 ? mix(off[0]!, off[1]!, 0.5) : off[0]!
  })
}

/** Holzbretter (Kolbenkopf, Banner-Stange). */
export function planks(p: IntroPalette, seed: number): Tex {
  const r = rng(seed)
  const w = p.wood.map(hex)
  return fill((x, y) => {
    if ((y & 3) === 3) return scale(w[0]!, 0.7)
    const n = r()
    return n > 0.8 ? w[1]! : (x * 7 + y) % 11 === 0 ? scale(w[0]!, 0.85) : mix(w[0]!, w[1]!, 0.45)
  })
}

/** Kolbenkörper seitlich: oben Holz, darunter Stein. */
export function pistonSide(p: IntroPalette, seed: number): Tex {
  const wood = planks(p, seed)
  const stone = cobble(p, seed + 1)
  return fill((x, y) => (y < 4 ? wood[idx(x, y)]! : stone[idx(x, y)]!))
}

/** Kolbenkopf von vorn: Holz mit Eisenkern. */
export function pistonFace(p: IntroPalette, seed: number): Tex {
  const wood = planks(p, seed)
  const iron = hex(p.iron)
  return fill((x, y) => {
    if (x >= 6 && x <= 9 && y >= 6 && y <= 9) return x === 6 || y === 6 ? iron : scale(iron, 0.7)
    return wood[idx(x, y)]!
  })
}

/** Verstärker-Oberseite (Steinplatte mit Pfeil). */
export function repeaterTop(p: IntroPalette, seed: number): Tex {
  const r = rng(seed)
  const s = p.stone.map(hex)
  const dust = hex(p.wireOff)
  return fill((x, y) => {
    if (x === 0 || y === 0 || x === 15 || y === 15) return s[1]!
    if (Math.abs(x - 7.5) < 1 && y > 2 && y < 13) return dust
    const n = r()
    return n > 0.8 ? s[3]! : s[2]!
  })
}

/** Kürbislaterne: Gesicht auf beiden sichtbaren Seiten, leuchtet. */
export function pumpkinFace(lit: boolean): Tex {
  const o = hex('#d9772f')
  const od = hex('#8a3f16')
  const y1 = hex(lit ? '#ffe27a' : '#3a1a08')
  const y2 = hex(lit ? '#fff6c8' : '#2a1206')
  return fill((x, y) => {
    const eye = (y >= 4 && y <= 6 && ((x >= 3 && x <= 5) || (x >= 10 && x <= 12)))
    const mouth = y >= 9 && y <= 11 && x >= 3 && x <= 12 && !(y === 9 && (x === 6 || x === 9)) && !(y === 11 && (x === 4 || x === 11))
    if (eye || mouth) return y === 5 || y === 10 ? y2 : y1
    return x % 4 === 0 ? od : o
  })
}
export function pumpkinTop(): Tex {
  const o = hex('#c96a25')
  const od = hex('#8a3f16')
  const g = hex('#4d7a45')
  return fill((x, y) => (Math.abs(x - 7.5) < 1.5 && Math.abs(y - 7.5) < 1.5 ? g : (x + y) % 4 === 0 ? od : o))
}

export interface BoxSprite {
  cv: Cv
  /** Versatz des Sprites zur projizierten Box-Ecke (min x, min y, min z). */
  ox: number
  oy: number
}

/**
 * Isometrischer Quader [0,w]×[0,dy]×[0,h] (Blockeinheiten) aus drei Flächentexturen. Texturen liegen weltfest
 * (16 Texel je Block), Teil-Quader zeigen also nur einen Ausschnitt.
 */
export function boxSprite(f: Faces, w = 1, dy = 1, h = 1): BoxSprite {
  const W = Math.ceil(HW * (w + dy))
  const H = Math.ceil(BZ * h + HH * (w + dy))
  const cv = canvas(W, H)
  const g = ctx2d(cv)
  const img = g.createImageData(W, H)
  const minX = -HW * dy
  const minY = -BZ * h
  const shadeTop = 1
  const shadeLeft = f.emissive ? 0.9 : 0.74
  const shadeRight = f.emissive ? 0.8 : 0.54
  for (let py = 0; py < H; py++) {
    for (let px = 0; px < W; px++) {
      const sx = px + 0.5 + minX
      const sy = py + 0.5 + minY
      let c: Rgb | null = null
      let k = 1
      // Oberseite (z = h)
      const syt = sy + BZ * h
      const X = (sx / HW + syt / HH) / 2
      const Y = (syt / HH - sx / HW) / 2
      if (X >= 0 && X < w && Y >= 0 && Y < dy) {
        c = f.top[idx(Math.floor(X * 16), Math.floor(Y * 16))]!
        k = shadeTop
        // helle Vorderkanten
        if (X > w - 0.07 || Y > dy - 0.07) k *= 1.16
      } else {
        // linke Seite (y = dy)
        const XL = sx / HW + dy
        const ZL = (HH * (XL + dy) - sy) / BZ
        if (XL >= 0 && XL < w && ZL >= 0 && ZL < h) {
          c = f.left[idx(Math.floor(XL * 16), Math.floor((h - ZL) * 16))]!
          k = shadeLeft * (ZL > h - 0.07 ? 1.12 : 1)
        } else {
          // rechte Seite (x = w)
          const YR = w - sx / HW
          const ZR = (HH * (w + YR) - sy) / BZ
          if (YR >= 0 && YR < dy && ZR >= 0 && ZR < h) {
            c = f.right[idx(Math.floor((dy - YR) * 16), Math.floor((h - ZR) * 16))]!
            k = shadeRight * (ZR > h - 0.07 ? 1.12 : 1) * (YR > dy - 0.06 ? 1.15 : 1)
          }
        }
      }
      if (!c) continue
      const o = (py * W + px) * 4
      img.data[o] = clamp(c[0] * k, 0, 255)
      img.data[o + 1] = clamp(c[1] * k, 0, 255)
      img.data[o + 2] = clamp(c[2] * k, 0, 255)
      img.data[o + 3] = 255
    }
  }
  g.putImageData(img, 0, 0)
  return { cv, ox: minX, oy: minY }
}

/** Abziehbild auf einer Oberseite (32×16 Raute), z. B. Redstone-Staub. `pix(x,y)` liefert Farbe oder null. */
export function topDecal(pix: (u: number, v: number) => string | null): BoxSprite {
  const W = HW * 2
  const H = HH * 2
  const cv = canvas(W, H)
  const g = ctx2d(cv)
  for (let py = 0; py < H; py++) {
    for (let px = 0; px < W; px++) {
      const sx = px + 0.5 - HW
      const sy = py + 0.5
      const X = (sx / HW + sy / HH) / 2
      const Y = (sy / HH - sx / HW) / 2
      if (X < 0 || X >= 1 || Y < 0 || Y >= 1) continue
      const c = pix(Math.floor(X * 16), Math.floor(Y * 16))
      if (!c) continue
      g.fillStyle = c
      g.fillRect(px, py, 1, 1)
    }
  }
  return { cv, ox: -HW, oy: 0 }
}

/** Sprite aus Zeichen-Zeilen (ein Zeichen = ein Pixel, '.' = leer). */
export function charSprite(rows: readonly string[], pal: Record<string, string>): Cv {
  const h = rows.length
  const w = Math.max(...rows.map((r) => r.length))
  const cv = canvas(w, h)
  const g = ctx2d(cv)
  for (let y = 0; y < h; y++) {
    const row = rows[y]!
    for (let x = 0; x < row.length; x++) {
      const col = pal[row[x]!]
      if (!col) continue
      g.fillStyle = col
      g.fillRect(x, y, 1, 1)
    }
  }
  return cv
}
