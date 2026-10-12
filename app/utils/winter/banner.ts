import { rng } from '../redstone/scene'
import { buildLandscape, CABINS } from './landscape'
import { WinterPainter } from './paint'
import { documentWinterPalette } from './palette'
import { Smoke, Snowfall } from './snow'

// Titelbild ohne eigenes Banner im Design „Winternacht“: eine kleine verschneite
// Ebene mit niedrigen Kuppen, aus dem Seed der Instanz – Mond, Hütte (rechts,
// manchmal mit Pinguin), Polarlicht und Wald liegen jedes Mal anders, bleiben für
// dieselbe Instanz aber gleich.

export const WINTER_BANNER_COLS = 128
export const WINTER_BANNER_ROWS = 48

/** Malt das Motiv in ein neues Canvas (Texel-Auflösung); `null` ohne 2D-Kontext. */
export function paintWinterBanner(seed: number): HTMLCanvasElement | null {
  const r = rng(seed ^ 0xb4a)
  const land = buildLandscape({
    cols: WINTER_BANNER_COLS,
    rows: WINTER_BANNER_ROWS,
    seed,
    cabin: r() < 0.65,
    cabinX: 0.55 + r() * 0.15,
    penguin: r() < 0.4,
    moonX: 0.12 + r() * 0.76,
  })
  const canvas = document.createElement('canvas')
  canvas.width = land.w
  canvas.height = land.h
  const g = canvas.getContext('2d')
  if (!g) return null
  const painter = new WinterPainter(land, documentWinterPalette())
  const snow = new Snowfall(land.w, land.h, rng(seed ^ 0x77), 0.6)
  const smoke = new Smoke(rng(seed ^ 0x5c))
  const spec = land.cabin ? CABINS[land.cabin.size] : null
  const chimney = land.cabin && spec ? { x: land.cabin.x + spec.chimney.x, y: land.cabin.y - spec.h + spec.chimney.y } : null
  for (let t = 0; t < 60; t++) smoke.step(chimney, t)
  const aurora = r() < 0.7 ? 0.55 + r() * 0.45 : 0
  painter.frame(g, { tick: seed % 97, seconds: 20 + (seed % 900), aurora, lit: 0, flicker: false, flakes: snow.flakes, smoke: smoke.puffs })
  return canvas
}
