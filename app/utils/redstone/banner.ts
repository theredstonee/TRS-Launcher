import { documentDesign } from '../design'
import { HALLOWEEN_EVENT } from '../halloween'
import { paintMoonnightBanner } from '../moonnight/banner'
import { paintFoxBanner } from '../fox/banner'
import { paintWinterBanner } from '../winter/banner'
import { documentPalette } from './palette'
import { TEX, Sprites, paintFloor, paintFrame } from './paint'
import { buildScene } from './scene'

// Titelbild für Instanzen ohne eigenes Banner: eine kleine Redstone-Schaltung,
// aus der Instanz-ID erzeugt – jede Instanz bekommt so ihr eigenes, stabiles
// Motiv im Stil der Startseite. Ergebnis ist eine winzige PNG-Data-URL
// (256 × 96 Texel), die das Bild-Element pixelig hochskaliert.
// Im Design „Winternacht“ statt der Schaltung eine kleine Winterlandschaft, im „Fuchsbau“
// ein Waldrand in der Dämmerung mit Bau und Lagerfeuer, zu Halloween
// (jedes Design) ein Standbild der Mondnacht.

export const BANNER_COLS = 16
export const BANNER_ROWS = 6

export function bannerSeed(text: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return h >>> 0
}

const cache = new Map<string, string>()

/** Data-URL des Motivs; `null`, wenn kein Canvas verfügbar ist. */
export function generatedBanner(id: string): string | null {
  if (typeof document === 'undefined') return null
  const root = document.documentElement
  const design = documentDesign()
  // Halloween geht vor dem Design (wie die Hintergrund-Szene).
  const halloween = root.dataset.event === HALLOWEEN_EVENT
  const key = `${id}|${halloween ? HALLOWEEN_EVENT : design}|${root.dataset.theme ?? 'dark'}|${root.dataset.accent ?? 'redstone'}`
  const hit = cache.get(key)
  if (hit) return hit
  try {
    const seed = bannerSeed(id)
    let canvas: HTMLCanvasElement | null
    if (halloween) canvas = paintMoonnightBanner(seed)
    else if (design === 'winter') canvas = paintWinterBanner(seed)
    else if (design === 'fox') canvas = paintFoxBanner(seed)
    else {
      const circuit = buildScene({ cols: BANNER_COLS, rows: BANNER_ROWS, busRow: -1, busEnd: 0, seed })
      // Jede Instanz zeigt einen anderen Moment ihrer Schaltung.
      for (let i = 0, n = 6 + (seed % 11); i < n; i++) circuit.step()
      const palette = documentPalette()
      canvas = document.createElement('canvas')
      canvas.width = BANNER_COLS * TEX
      canvas.height = BANNER_ROWS * TEX
      const g = canvas.getContext('2d')
      if (!g) return null
      paintFrame(g, paintFloor(circuit, palette), circuit, new Sprites(palette), palette, [], () => false)
    }
    if (!canvas) return null
    const url = canvas.toDataURL('image/png')
    if (cache.size > 64) cache.clear()
    cache.set(key, url)
    return url
  } catch {
    return null
  }
}
