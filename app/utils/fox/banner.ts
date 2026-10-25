import { rng } from '../redstone/scene'
import { buildFoxLandscape, denParts } from './landscape'
import { fireTop, FoxPainter } from './paint'
import { documentFoxPalette } from './palette'
import { Leaves, Sparks } from './particles'
import { Smoke } from '../winter/snow'

// Titelbild ohne eigenes Banner im Design „Fuchsbau“: ein kleiner Waldrand in der
// Dämmerung, aus dem Seed der Instanz – Sonne, Bau mit Lagerfeuer (manchmal mit einem
// winzigen Fuchs daneben), Kirschbäume und Wolken liegen jedes Mal anders, bleiben für
// dieselbe Instanz aber gleich.

export const FOX_BANNER_COLS = 128
export const FOX_BANNER_ROWS = 48

/** Malt das Motiv in ein neues Canvas (Texel-Auflösung); `null` ohne 2D-Kontext. */
export function paintFoxBanner(seed: number): HTMLCanvasElement | null {
  const r = rng(seed ^ 0xf0b)
  const land = buildFoxLandscape({
    cols: FOX_BANNER_COLS,
    rows: FOX_BANNER_ROWS,
    seed,
    den: r() < 0.7,
    denX: 0.5 + r() * 0.2,
    fox: r() < 0.35,
    sunX: 0.12 + r() * 0.5,
  })
  const canvas = document.createElement('canvas')
  canvas.width = land.w
  canvas.height = land.h
  const g = canvas.getContext('2d')
  if (!g) return null
  const painter = new FoxPainter(land, documentFoxPalette())
  const leaves = new Leaves(land.w, land.h, rng(seed ^ 0x77), 0.5)
  const smoke = new Smoke(rng(seed ^ 0x5c))
  const sparks = new Sparks(rng(seed ^ 0x5d))
  const top = land.den ? fireTop(denParts(land.den)) : null
  const origin = top ? { x: top.x + top.w / 2, y: top.y, w: top.w } : null
  for (let t = 0; t < 60; t++) {
    smoke.step(origin, t)
    sparks.step(origin, t, 0.4)
  }
  painter.frame(g, {
    tick: seed % 97,
    seconds: 20 + (seed % 900),
    lit: 0,
    fire: 0.5,
    calm: false,
    fox: { inhale: false, twitch: false },
    smoke: smoke.puffs,
    sparks: sparks.sparks,
    leaves: leaves.leaves,
  })
  return canvas
}
