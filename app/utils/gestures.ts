/** Fingergesten der Handy-Oberfläche (rein rechnerisch, damit testbar). */

/** Sheet schließen ab 110 px Weg oder einem schnellen Wisch (> 0,6 px/ms) von mindestens 30 px. */
export function sheetSwipeCloses(distance: number, speed: number): boolean {
  return distance > 110 || (distance > 30 && speed > 0.6)
}

/** Ab dieser Zugstrecke lädt „Zum Aktualisieren ziehen“ neu. */
export const PULL_TRIGGER = 64
/** Position des Lade-Kreises, solange neu geladen wird. */
export const PULL_HOLD = 52
const PULL_MAX = 96

/** Gezogene Strecke mit Widerstand: die Hälfte des Fingerwegs, gedeckelt. */
export function pullDistance(fingerDy: number): number {
  if (!(fingerDy > 0)) return 0
  return Math.min(PULL_MAX, fingerDy * 0.5)
}

export function pullTriggers(pull: number): boolean {
  return pull >= PULL_TRIGGER
}

/** Langer Druck: ab 450 ms, solange sich der Finger kaum (≤ 10 px) bewegt hat. */
export const LONG_PRESS_MS = 450
export function longPressCancelled(dx: number, dy: number): boolean {
  return Math.hypot(dx, dy) > 10
}
