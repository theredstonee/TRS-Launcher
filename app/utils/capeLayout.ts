// Aufbau einer Umhang-Textur (64×32 im Vanilla-Raster, mal Faktor s): welche
// Fläche wohin gehört. Grundlage für die Hilfslinien im Editor, für Spiegeln und
// Füllen „innerhalb eines Teils“ und fürs Hochskalieren je Teil (keine Farben
// über die Kanten der UV-Inseln hinweg).

export type CapeRegionId =
  | 'capeTop'
  | 'capeBottom'
  | 'capeLeft'
  | 'capeOuter'
  | 'capeRight'
  | 'capeInner'

export interface CapeRect {
  x: number
  y: number
  w: number
  h: number
}

export interface CapeRegion extends CapeRect {
  id: CapeRegionId
  /** `face` = große Fläche (außen/innen), `edge` = Kante/Seite. */
  kind: 'face' | 'edge'
}

/**
 * Würfel-UV wie in Minecraft: Umhang `texOffs(0, 0)` mit 10×16×1. Außen = die
 * Seite, die man von hinten sieht. Der Elytra-Bereich gehört nicht dazu: TRS-Umhänge
 * werden nie als Elytra genutzt (im Spiel bleibt immer die Vanilla-Elytra).
 */
export const CAPE_REGIONS: readonly CapeRegion[] = [
  { id: 'capeTop', kind: 'edge', x: 1, y: 0, w: 10, h: 1 },
  { id: 'capeBottom', kind: 'edge', x: 11, y: 0, w: 10, h: 1 },
  { id: 'capeLeft', kind: 'edge', x: 0, y: 1, w: 1, h: 16 },
  { id: 'capeOuter', kind: 'face', x: 1, y: 1, w: 10, h: 16 },
  { id: 'capeRight', kind: 'edge', x: 11, y: 1, w: 1, h: 16 },
  { id: 'capeInner', kind: 'face', x: 12, y: 1, w: 10, h: 16 },
]

/** Fläche im Raster mit Faktor `s` (Pixel der Textur). */
export function scaleRect(region: CapeRect, s: number): CapeRect {
  return { x: region.x * s, y: region.y * s, w: region.w * s, h: region.h * s }
}

/** Teil der Textur unter dem Pixel (x, y) bei Faktor `s` – `null` = ungenutzter Bereich. */
export function capeRegionAt(x: number, y: number, s: number): CapeRegion | null {
  const gx = Math.floor(x / s)
  const gy = Math.floor(y / s)
  return CAPE_REGIONS.find((r) => gx >= r.x && gx < r.x + r.w && gy >= r.y && gy < r.y + r.h) ?? null
}

/** Liegt der Pixel in irgendeinem Teil (sonst sieht ihn niemand)? */
export function isCapePixel(x: number, y: number, s: number): boolean {
  return capeRegionAt(x, y, s) !== null
}
