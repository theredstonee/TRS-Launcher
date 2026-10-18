import { makeScene, SCENE_H } from './scene'

// Titelbild ohne eigenes Banner, solange Halloween läuft: ein Standbild der Mondnacht aus
// dem Seed der Instanz – Mond, Gräber, Kürbisse und Nebel liegen jedes Mal anders, bleiben
// für dieselbe Instanz aber gleich. Ohne Blitz (ruhiger Moment wie das Standbild der Startseite).

/** Seitenverhältnis wie das Redstone-Titelbild (256 × 96). */
export const MOONNIGHT_BANNER_W = Math.round((SCENE_H * 256) / 96)

/** Malt das Motiv in ein neues Canvas (Texel-Auflösung); `null` ohne 2D-Kontext. */
export function paintMoonnightBanner(seed: number): HTMLCanvasElement | null {
  const canvas = document.createElement('canvas')
  canvas.width = MOONNIGHT_BANNER_W
  canvas.height = SCENE_H
  const moonX = 0.15 + ((seed >>> 3) % 71) / 100
  const frame = makeScene(canvas, (seed % 100_000) + 1, moonX, { flicker: false, lightning: false })
  if (!frame) return null
  // Ein ruhiger Moment je Instanz (vor der ersten Hexe).
  frame(1.5 + (seed % 9) * 0.1)
  return canvas
}
