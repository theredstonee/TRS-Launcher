import type { Design } from '~/types'
import { buildPalette } from './redstone/palette'
import { TEX, Sprites, paintFloor, paintFrame } from './redstone/paint'
import { buildScene, rng } from './redstone/scene'
import { buildFoxLandscape } from './fox/landscape'
import { FoxPainter } from './fox/paint'
import { buildFoxPalette } from './fox/palette'
import { Leaves } from './fox/particles'
import { buildLandscape } from './winter/landscape'
import { WinterPainter } from './winter/paint'
import { buildWinterPalette } from './winter/palette'
import { Snowfall } from './winter/snow'

// Vorschaubilder der Designs für die Einstellungen: echte Mini-Szenen, gemalt
// mit denselben Pinseln wie der Hintergrund – jeweils mit dem Akzent, den das
// Design ab Werk mitbringt (Redstone, Eisblau bzw. Fuchsorange), hell oder dunkel.

/** Helle Deepslate-Töne (wie `:root[data-theme="light"]` in main.css). */
const REDSTONE_LIGHT: Record<string, string> = {
  '--color-base-950': '#eeeef3',
  '--color-base-900': '#ffffff',
  '--color-base-850': '#f6f6fa',
  '--color-base-800': '#e6e6ee',
  '--color-base-700': '#d4d4df',
  '--color-base-600': '#a9a9bb',
  '--color-base-400': '#5f5f75',
}

/** Größe der Vorschau in Texeln (angezeigt mit 2 Pixeln je Texel). */
export const PREVIEW_COLS = 192
export const PREVIEW_ROWS = 64

const cache = new Map<string, string>()

/** PNG-Data-URL der Vorschau; `null` ohne Canvas. */
export function designPreview(design: Design, light: boolean): string | null {
  if (typeof document === 'undefined') return null
  const key = `${design}|${light}`
  const hit = cache.get(key)
  if (hit) return hit
  try {
    const canvas = document.createElement('canvas')
    const g = canvas.getContext('2d')
    if (!g) return null
    if (design === 'winter') {
      const land = buildLandscape({ cols: PREVIEW_COLS, rows: PREVIEW_ROWS, seed: 0x5a0, penguin: true, cabinX: 0.66 })
      canvas.width = land.w
      canvas.height = land.h
      const painter = new WinterPainter(land, buildWinterPalette(() => '', light ? 'light' : 'dark'))
      const snow = new Snowfall(land.w, land.h, rng(7), 0.8)
      painter.frame(g, { tick: 0, seconds: 0, aurora: light ? 0 : 0.9, lit: 0, flicker: false, flakes: snow.flakes, smoke: [] })
    } else if (design === 'fox') {
      const land = buildFoxLandscape({ cols: PREVIEW_COLS, rows: PREVIEW_ROWS, seed: 0xf0c5, fox: true, denX: 0.52, sunX: 0.3 })
      canvas.width = land.w
      canvas.height = land.h
      const painter = new FoxPainter(land, buildFoxPalette(() => '', light ? 'light' : 'dark'))
      const leaves = new Leaves(land.w, land.h, rng(7), 0.7)
      painter.frame(g, { tick: 0, seconds: 1, lit: 0, fire: 0.6, calm: false, fox: { inhale: false, twitch: false }, smoke: [], sparks: [], leaves: leaves.leaves })
    } else {
      const cols = PREVIEW_COLS / TEX
      const rows = PREVIEW_ROWS / TEX
      // Seed mit Fackel, Leitung und drei leuchtenden Lampen.
      const circuit = buildScene({ cols, rows, busRow: -1, busEnd: 0, seed: 9 })
      for (let i = 0; i < 20; i++) circuit.step()
      const palette = buildPalette((name) => (light ? (REDSTONE_LIGHT[name] ?? '') : ''), light)
      canvas.width = cols * TEX
      canvas.height = rows * TEX
      paintFrame(g, paintFloor(circuit, palette), circuit, new Sprites(palette), palette, [], () => false)
    }
    const url = canvas.toDataURL('image/png')
    cache.set(key, url)
    return url
  } catch {
    return null
  }
}
