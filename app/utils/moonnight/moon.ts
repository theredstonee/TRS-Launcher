// Ladebalken „Mondlauf“: ein 13×13-Mond nimmt mit dem Fortschritt zu –
// Neumond bei 0 %, Vollmond bei 100 % (Entwurf `moonPhase`/`setBar`).

export const MOON_SIZE = 13
const LIT = '#eef1f8'
const EDGE = '#b9bfd0'
const DARK = '#2a2342'

/** Farbe des Mond-Pixels (x, y) bei Phase f (0…1) oder `null` außerhalb der Scheibe. */
export function moonPixel(x: number, y: number, f: number): string | null {
  const u = (x - 6) / 6
  const v = (y - 6) / 6
  if (u * u + v * v > 1.05) return null
  const term = (1 - 2 * f) * Math.sqrt(Math.max(0, 1 - v * v))
  const lit = u > term
  return lit ? (u - term < 0.25 ? EDGE : LIT) : DARK
}

export function drawMoonPhase(cv: HTMLCanvasElement, f: number): void {
  const g = cv.getContext('2d')
  if (!g) return
  g.clearRect(0, 0, MOON_SIZE, MOON_SIZE)
  for (let y = 0; y < MOON_SIZE; y++) {
    for (let x = 0; x < MOON_SIZE; x++) {
      const c = moonPixel(x, y, f)
      if (!c) continue
      g.fillStyle = c
      g.fillRect(x, y, 1, 1)
    }
  }
}

export interface MoonState {
  /** Position der Mondmitte 0…1 auf der Schiene. */
  pos: number
  /** Gefüllter Anteil der Schiene 0…1 (unbestimmt: 0). */
  fill: number
  /** Mondphase 0…1. */
  phase: number
  /** Vollmond-Glühen (bestimmt und ≥ 100 %). */
  full: boolean
}

/**
 * Stand des Mondlaufs. Unbestimmt gleitet der Mond hin und her und durchläuft
 * dabei die Phasen (Entwurf: Position `0.5 − 0.5·cos(1,2 t)`, Phase `0.5 − 0.5·cos(0,7 t)`).
 */
export function moonState(seconds: number, percent: number, indeterminate: boolean, powered = false): MoonState {
  if (powered) return { pos: 1, fill: 1, phase: 1, full: true }
  if (indeterminate) {
    return {
      pos: 0.5 - 0.5 * Math.cos(seconds * 1.2),
      fill: 0,
      phase: 0.5 - 0.5 * Math.cos(seconds * 0.7),
      full: false,
    }
  }
  const p = Math.min(1, Math.max(0, (Number.isFinite(percent) ? percent : 0) / 100))
  return { pos: p, fill: p, phase: p, full: p >= 1 }
}

/** Phase auf 1/24 runden – der Mond wird nur neu gemalt, wenn sich etwas sichtbar ändert. */
export const phaseKey = (f: number) => Math.round(Math.min(1, Math.max(0, f)) * 24)
