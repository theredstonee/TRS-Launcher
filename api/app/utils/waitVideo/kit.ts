// Werkzeugkasten des Musikvideos: Mathe, Easing, Zufall mit Startwert, Leinwände und Pixel-Sprites.
// Alles hier ist rein (keine Szene, kein DOM außer Canvas-Erzeugung).

export const clamp = (v: number, a = 0, b = 1) => (v < a ? a : v > b ? b : v)
export const lerp = (a: number, b: number, t: number) => a + (b - a) * t
/** 0…1 zwischen a und b (geklemmt). */
export const range = (v: number, a: number, b: number) => clamp((v - a) / (b - a))
export const smooth = (t: number) => t * t * (3 - 2 * t)
export const easeOut = (t: number) => 1 - (1 - t) ** 3
export const easeIn = (t: number) => t * t * t
export const easeInOut = (t: number) => (t < 0.5 ? 4 * t * t * t : 1 - (-2 * t + 2) ** 3 / 2)
export const easeOutBack = (t: number, s = 1.7) => 1 + (s + 1) * (t - 1) ** 3 + s * (t - 1) ** 2
export const easeOutElastic = (t: number) => (t <= 0 ? 0 : t >= 1 ? 1 : 2 ** (-10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1)
/** Kurzer Ausschlag 0→1→0 über die Dauer `d` ab Zeitpunkt 0. */
export const blip = (t: number, d: number) => (t < 0 || t > d ? 0 : Math.sin(Math.PI * t / d))
/** Abklingen nach einem Ereignis (t ≥ 0). */
export const decay = (t: number, k = 6) => (t < 0 ? 0 : Math.exp(-t * k))

/** Deterministischer Zufall 0…1 aus einer Ganzzahl (für zeitbasierte, wiederholbare Partikel). */
export function hash(n: number): number {
  let x = Math.imul(n ^ 0x9e3779b9, 0x85ebca6b)
  x = Math.imul(x ^ (x >>> 13), 0xc2b2ae35)
  x ^= x >>> 16
  return (x >>> 0) / 4294967296
}
export const hash2 = (a: number, b: number) => hash(a * 73856093 ^ b * 19349663)

/** Zufallsfolge mit Startwert (für einmal aufgebaute Ebenen). */
export function rng(seed: number): () => number {
  let s = seed >>> 0 || 1
  return () => {
    s = (s + 0x6d2b79f5) >>> 0
    let t = s
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export type Cv = HTMLCanvasElement

export function canvas(w: number, h: number): Cv {
  const c = document.createElement('canvas')
  c.width = Math.max(1, Math.ceil(w))
  c.height = Math.max(1, Math.ceil(h))
  return c
}

export function ctx2d(c: Cv): CanvasRenderingContext2D {
  const g = c.getContext('2d')
  if (!g) throw new Error('canvas 2d unavailable')
  g.imageSmoothingEnabled = false
  return g
}

/** Ebene einmal zeichnen und als Leinwand behalten. */
export function layer(w: number, h: number, draw: (g: CanvasRenderingContext2D) => void): Cv {
  const c = canvas(w, h)
  draw(ctx2d(c))
  return c
}

/**
 * Sprite aus Zeichen-Zeilen: jedes Zeichen ist ein Pixel, Farbe aus `pal` (Leerzeichen/'.' = durchsichtig).
 * `scale` vergrößert pixelgenau.
 */
export function sprite(rows: readonly string[], pal: Record<string, string>, scale = 1): Cv {
  const h = rows.length
  const w = Math.max(...rows.map((r) => r.length))
  return layer(w * scale, h * scale, (g) => {
    for (let y = 0; y < h; y++) {
      const row = rows[y]!
      for (let x = 0; x < row.length; x++) {
        const col = pal[row[x]!]
        if (!col) continue
        g.fillStyle = col
        g.fillRect(x * scale, y * scale, scale, scale)
      }
    }
  })
}

/** Spiegelt eine Leinwand waagerecht. */
export function flipX(src: Cv): Cv {
  return layer(src.width, src.height, (g) => {
    g.translate(src.width, 0)
    g.scale(-1, 1)
    g.drawImage(src, 0, 0)
  })
}

const BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5].map((v) => (v + 0.5) / 16)
/** Schwelle der 4×4-Bayer-Matrix (für Dithering). */
export const bayer = (x: number, y: number) => BAYER[(y & 3) * 4 + (x & 3)]!

/**
 * Pixeliger Verlauf mit Dithering: `stops` von oben nach unten (vertical) bzw. links nach rechts.
 * Zeichnet ganze Pixel in `g` (Leinwand in Kunst-Pixeln).
 */
export function ditherFill(g: CanvasRenderingContext2D, x0: number, y0: number, w: number, h: number, stops: readonly string[], vertical = true) {
  const n = stops.length - 1
  const len = vertical ? h : w
  for (let i = 0; i < len; i++) {
    const f = (i / Math.max(1, len - 1)) * n
    const k = Math.min(n - 1, Math.floor(f))
    const frac = f - k
    const across = vertical ? w : h
    for (let j = 0; j < across; j++) {
      const x = vertical ? j : i
      const y = vertical ? i : j
      g.fillStyle = frac > bayer(x0 + x, y0 + y) ? stops[k + 1]! : stops[k]!
      g.fillRect(x0 + x, y0 + y, 1, 1)
    }
  }
}

/** Hex-Farbe `#rrggbb` + Deckkraft → rgba(). */
export function rgba(hex: string, a: number): string {
  const n = Number.parseInt(hex.slice(1), 16)
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${clamp(a)})`
}

/** Weicher Lichtkreis (additiv) in aktuellen Koordinaten. */
export function glow(g: CanvasRenderingContext2D, x: number, y: number, r: number, hex: string, a: number) {
  if (a <= 0.003 || r <= 0) return
  const grd = g.createRadialGradient(x, y, 0, x, y, r)
  grd.addColorStop(0, rgba(hex, a))
  grd.addColorStop(0.35, rgba(hex, a * 0.45))
  grd.addColorStop(1, rgba(hex, 0))
  const op = g.globalCompositeOperation
  g.globalCompositeOperation = 'lighter'
  g.fillStyle = grd
  g.fillRect(x - r, y - r, r * 2, r * 2)
  g.globalCompositeOperation = op
}

/** Abgerundetes Rechteck als Pfad. */
export function roundRect(g: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  g.beginPath()
  g.moveTo(x + r, y)
  g.arcTo(x + w, y, x + w, y + h, r)
  g.arcTo(x + w, y + h, x, y + h, r)
  g.arcTo(x, y + h, x, y, r)
  g.arcTo(x, y, x + w, y, r)
  g.closePath()
}
