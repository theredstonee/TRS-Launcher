// Werkzeugkasten der Start-Animation: Mathe, Easing, Zufall mit Startwert, Farben, Leinwände.
// Bewusst ohne Nuxt-/Vue-Abhängigkeit – dieselben Module laufen im Launcher und in der eigenständigen Vorschau.

export const clamp = (v: number, a = 0, b = 1) => (v < a ? a : v > b ? b : v)
export const lerp = (a: number, b: number, t: number) => a + (b - a) * t
/** 0…1 zwischen a und b (geklemmt). */
export const range = (v: number, a: number, b: number) => (b === a ? (v >= b ? 1 : 0) : clamp((v - a) / (b - a)))
export const smooth = (t: number) => t * t * (3 - 2 * t)
export const easeOut = (t: number) => 1 - (1 - t) ** 3
export const easeIn = (t: number) => t * t * t
export const easeInOut = (t: number) => (t < 0.5 ? 4 * t * t * t : 1 - (-2 * t + 2) ** 3 / 2)
export const easeOutBack = (t: number, s = 1.7) => 1 + (s + 1) * (t - 1) ** 3 + s * (t - 1) ** 2
export const easeOutExpo = (t: number) => (t >= 1 ? 1 : 1 - 2 ** (-10 * t))
/** Abklingen nach einem Ereignis (t ≥ 0), davor 0. */
export const decay = (t: number, k = 6) => (t < 0 ? 0 : Math.exp(-t * k))
/** Kurzer Ausschlag 0→1→0 über die Dauer `d`. */
export const blip = (t: number, d: number) => (t < 0 || t > d ? 0 : Math.sin((Math.PI * t) / d))

/** Deterministischer Zufall 0…1 aus einer Ganzzahl (zeitbasierte, wiederholbare Partikel). */
export function hash(n: number): number {
  let x = Math.imul(n ^ 0x9e3779b9, 0x85ebca6b)
  x = Math.imul(x ^ (x >>> 13), 0xc2b2ae35)
  x ^= x >>> 16
  return (x >>> 0) / 4294967296
}
export const hash2 = (a: number, b: number) => hash((a * 73856093) ^ (b * 19349663))
export const hash3 = (a: number, b: number, c: number) => hash((a * 73856093) ^ (b * 19349663) ^ (c * 83492791))

/** Zufallsfolge mit Startwert (mulberry32). */
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

export type Rgb = [number, number, number]

export function hex(c: string): Rgb {
  const n = Number.parseInt(c.slice(1), 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}
export const mix = (a: Rgb, b: Rgb, k: number): Rgb => [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k]
export const scale = (a: Rgb, k: number): Rgb => [a[0] * k, a[1] * k, a[2] * k]
export const css = (c: Rgb, a = 1) =>
  a >= 1
    ? `rgb(${Math.round(clamp(c[0], 0, 255))},${Math.round(clamp(c[1], 0, 255))},${Math.round(clamp(c[2], 0, 255))})`
    : `rgba(${Math.round(clamp(c[0], 0, 255))},${Math.round(clamp(c[1], 0, 255))},${Math.round(clamp(c[2], 0, 255))},${clamp(a)})`
/** Hex-Farbe + Deckkraft → rgba(). */
export const rgba = (h: string, a: number) => css(hex(h), a)

export type Cv = HTMLCanvasElement

export function canvas(w: number, h: number): Cv {
  const c = document.createElement('canvas')
  c.width = Math.max(1, Math.ceil(w))
  c.height = Math.max(1, Math.ceil(h))
  return c
}

export function ctx2d(c: Cv, alpha = true): CanvasRenderingContext2D {
  const g = c.getContext('2d', { alpha })
  if (!g) throw new Error('canvas 2d unavailable')
  g.imageSmoothingEnabled = false
  return g
}

/** Weicher Lichtkreis (additiv) in aktuellen Koordinaten. */
export function glow(g: CanvasRenderingContext2D, x: number, y: number, r: number, color: string, a: number) {
  if (a <= 0.003 || r <= 0.5) return
  const c = hex(color)
  const grd = g.createRadialGradient(x, y, 0, x, y, r)
  grd.addColorStop(0, css(c, a))
  grd.addColorStop(0.3, css(c, a * 0.5))
  grd.addColorStop(0.65, css(c, a * 0.14))
  grd.addColorStop(1, css(c, 0))
  const op = g.globalCompositeOperation
  g.globalCompositeOperation = 'lighter'
  g.fillStyle = grd
  g.fillRect(x - r, y - r, r * 2, r * 2)
  g.globalCompositeOperation = op
}

const BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5].map((v) => (v + 0.5) / 16)
/** Schwelle der 4×4-Bayer-Matrix (Dithering). */
export const bayer = (x: number, y: number) => BAYER[(y & 3) * 4 + (x & 3)]!

/** Kamera-/Wert-Stützpunkte [t, …werte] – weich (easeInOut) je Abschnitt interpoliert, davor/danach gehalten. */
export function keyframes(keys: readonly (readonly number[])[], t: number): number[] {
  const first = keys[0]!
  if (t <= first[0]!) return first.slice(1)
  for (let i = 1; i < keys.length; i++) {
    const a = keys[i - 1]!
    const b = keys[i]!
    if (t <= b[0]!) {
      const k = easeInOut(range(t, a[0]!, b[0]!))
      return a.slice(1).map((v, j) => lerp(v, b[j + 1]!, k))
    }
  }
  return keys[keys.length - 1]!.slice(1)
}
