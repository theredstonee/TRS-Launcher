// Halloween 2026 „Nacht der langen Schatten“: kleine Pixel-Helfer der Mondnacht.
// Werte 1:1 aus dem freigegebenen Entwurf (halloween-2026-mockup) – nicht „verbessern“,
// Website und TRS Client rechnen mit denselben Zahlen.

export type Rgb = [number, number, number]

/** mulberry32 – gleicher Zufall wie im Entwurf, damit die Szenen identisch aussehen. */
export const rng = (seed: number) => {
  let s = seed
  return () => {
    s = (s + 0x6d2b79f5) | 0
    let t = Math.imul(s ^ (s >>> 15), 1 | s)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

const BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5]
/** 4×4-Bayer-Schwelle 0…15/16 – damit Verläufe Pixel-Art bleiben. */
export const bay = (x: number, y: number) => (BAYER[(y & 3) * 4 + (x & 3)] ?? 0) / 16

export const hex = (c: string): Rgb => {
  const n = parseInt(c.slice(1), 16)
  return [n >> 16, (n >> 8) & 255, n & 255]
}

export const mix = (a: Rgb, b: Rgb, k: number): Rgb => [
  a[0] + (b[0] - a[0]) * k,
  a[1] + (b[1] - a[1]) * k,
  a[2] + (b[2] - a[2]) * k,
]

/** Offscreen-Canvas; in Tests (jsdom) ohne 2D-Kontext. */
export function layer(w: number, h: number): HTMLCanvasElement {
  const c = document.createElement('canvas')
  c.width = w
  c.height = h
  return c
}
