import { noise } from '../redstone/paint'
import { rng } from '../redstone/scene'

// Aufbau der Winterlandschaft (nur Daten, ohne Canvas – testbar): eine weite
// verschneite Ebene, hinten am Horizont eine niedrige Kette runder Schneekuppen
// mit einem schmalen Waldstreifen davor, vorn rechts die Blockhütte (mit dem
// Pinguin), Sterne und Mond – und auf der Startseite die Eislaternen bis zum
// Spielen-Knopf. Alles in Texeln (1 Texel = 3 Gerätepixel wie in der
// Redstone-Szene), aus dem Seed stabil. Links bleibt Platz für die Schrift.

export interface Pine {
  x: number
  /** Unterkante (Boden, auf dem der Stamm steht). */
  base: number
  h: number
  /** 0 = fern (Silhouette), 1 = Ebene, 2 = vorn. */
  layer: 0 | 1 | 2
  seed: number
}

export interface Star {
  x: number
  y: number
  bright: boolean
  big: boolean
  twinkle: boolean
}

export interface Cloud {
  x: number
  y: number
  w: number
  h: number
  seed: number
  /** Texel je Takt. */
  speed: number
}

/** Rechteck in Hütten-Koordinaten (inklusive Ränder). */
export interface CabinRect {
  x0: number
  y0: number
  x1: number
  y1: number
}

/** Maße einer Hütte; alle Angaben relativ zur linken oberen Ecke, `h - 1` = Bodenzeile. */
export interface CabinSpec {
  w: number
  h: number
  /** Oberkante des Schornsteins (Rauch steigt darüber auf). */
  chimney: { x: number; y: number }
  /** Tür: Rahmen von x0 bis x1, Türblatt darin, Oberkante y0. */
  door: CabinRect
  /** Glasflächen der Fenster (warm erleuchtet). */
  windows: CabinRect[]
  /** Laterne am Weg (Mitte des Glases), falls vorhanden. */
  lantern: { x: number; y: number } | null
}

export type CabinSize = 'large' | 'small'

/** Große Hütte vorn (Startseite, Hintergrund) und kleine (Banner, Vorschau). */
export const CABINS: Record<CabinSize, CabinSpec> = {
  large: {
    w: 46,
    h: 36,
    chimney: { x: 33, y: 0 },
    door: { x0: 27, y0: 22, x1: 34, y1: 35 },
    windows: [
      { x0: 9, y0: 21, x1: 17, y1: 27 },
      { x0: 38, y0: 21, x1: 40, y1: 24 },
    ],
    lantern: { x: 22, y: 22 },
  },
  small: {
    w: 22,
    h: 17,
    chimney: { x: 16, y: 0 },
    door: { x0: 13, y0: 11, x1: 16, y1: 16 },
    windows: [{ x0: 6, y0: 11, x1: 10, y1: 14 }],
    lantern: null,
  },
}

export interface Landscape {
  w: number
  h: number
  seed: number
  /** Fuß der fernen Kuppen. */
  horizon: number
  /** Höhe der höchsten Kuppe über dem Horizont (Texel). */
  mountainAmp: number
  /** Hintere (höhere, blassere) und vordere Kuppenkette. */
  mountainsBack: Int16Array
  mountains: Int16Array
  forest: Int16Array
  /** Oberkante der weiten Ebene. */
  hills: Int16Array
  /** Vordere Schneewehe (Laternen, Hütte stehen darauf). */
  ground: Int16Array
  pines: Pine[]
  stars: Star[]
  moon: { x: number; y: number; r: number }
  /** `y` = Bodenzeile der Hütte; `penguin` = kleiner Pinguin vor der Tür (nur Banner). */
  cabin: { x: number; y: number; size: CabinSize; penguin: boolean } | null
  /** Eislaternen (Mitte des Glases) – nur mit Anker. */
  lanterns: { x: number; y: number }[]
  /** Glitzernde Schneepunkte. */
  sparkles: { x: number; y: number }[]
  /** Halme, die aus dem Schnee ragen. */
  tufts: { x: number; y: number; h: number }[]
  clouds: Cloud[]
}

export interface LandscapeOptions {
  cols: number
  rows: number
  seed: number
  /** Texel-Zeile der Mitte des Ankers (Spielen-Knopf), -1 = keiner. */
  anchorRow?: number
  /** Texel-Spalte, an der die Laternenreihe endet (linker Rand des Knopfs). */
  anchorEnd?: number
  /** Oberkante und rechter Rand des Ankers – die Hütte steht darüber. */
  anchorTop?: number
  anchorRight?: number
  /** Hütte zeigen (Standard: wenn Platz ist); `false` = nie. */
  cabin?: boolean
  /** Kleiner Pinguin vor der Tür der kleinen Hütte (Banner). */
  penguin?: boolean
  /** Waagrechte Lage von Mond/Sonne, 0…1 (Standard rechts). */
  moonX?: number
  /** Hütte waagrecht, 0…1 (Standard ganz rechts). */
  cabinX?: number
}

/** Kleine Hütte (für ältere Aufrufer/Tests). */
export const CABIN_W = CABINS.small.w
export const CABIN_H = CABINS.small.h
export const LANTERN_GAP = 13

/** Glattes 1D-Rauschen 0…1. */
export function smooth(x: number, seed: number): number {
  const i = Math.floor(x)
  const f = x - i
  const a = noise(i, 0, seed)
  const b = noise(i + 1, 0, seed)
  const t = f * f * (3 - 2 * f)
  return a + (b - a) * t
}

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v))

/**
 * Kette runder Kuppen: nebeneinander gesetzte Kosinus-Glocken (oben rund, zu den
 * Seiten weich auslaufend), höchstens `amp` hoch. Breite Glocken halten die
 * Steigung unter einem Texel je Spalte – so bleibt die Silhouette glatt.
 */
/**
 * Bergkette wie echte Berge: Gipfel mit ungleich steilen Flanken, Nebengipfeln und
 * gezackten Graten – aber ohne Nadeln (höchstens ein, zwei Texel Sprung je Spalte).
 */
function peaks(w: number, amp: number, rand: () => number, spread: number, seed: number): Float32Array {
  const out = new Float32Array(w)
  let c = -rand() * amp * 2
  while (c < w + amp * 3) {
    const top = amp * (0.55 + rand() * 0.45)
    // Flanken: eine steiler, eine flacher – Steigung bleibt unter etwa 0,9 Texel je Spalte.
    const left = Math.max(5, top * (1.2 + rand() * 0.9) * spread)
    const right = Math.max(5, top * (1.2 + rand() * 0.9) * spread)
    // Nebengipfel auf einer Flanke.
    const sub = rand() < 0.7 ? { at: (rand() < 0.5 ? -1 : 1) * (0.35 + rand() * 0.25), h: 0.55 + rand() * 0.25 } : null
    for (let x = Math.max(0, Math.floor(c - left)); x < Math.min(w, Math.ceil(c + right)); x++) {
      const dx = x - c
      const r = dx < 0 ? left : right
      const u = Math.abs(dx) / r
      // Leicht hohle Flanken wie bei Bergen, dazu grober und feiner Grat.
      let v = top * Math.pow(1 - u, 1.12)
      if (sub) {
        const su = Math.abs(dx / r - sub.at) / 0.22
        if (su < 1 && Math.sign(dx) === Math.sign(sub.at)) v = Math.max(v, top * sub.h * (1 - su))
      }
      const ridge = (smooth(x / 3.5, seed) - 0.5) * 0.09 + (smooth(x / 1.4, seed + 7) - 0.5) * 0.035
      v += top * ridge * (1 - u * 0.6)
      if (v > out[x]!) out[x] = v
    }
    c += (left + right) * (0.45 + rand() * 0.35)
  }
  // Nadeln glätten: Sprung je Spalte begrenzen.
  for (let pass = 0; pass < 2; pass++)
    for (let x = 1; x < w; x++) {
      if (out[x]! > out[x - 1]! + 1.6) out[x] = out[x - 1]! + 1.6
      if (out[x - 1]! > out[x]! + 1.6) out[x - 1] = out[x]! + 1.6
    }
  return out
}

export function buildLandscape(o: LandscapeOptions): Landscape {
  const w = Math.max(8, Math.floor(o.cols))
  const h = Math.max(8, Math.floor(o.rows))
  const seed = o.seed >>> 0
  const rand = rng(seed ^ 0x5eed)
  const anchored = (o.anchorRow ?? -1) >= 0
  const anchorEnd = clamp(Math.floor(o.anchorEnd ?? 0), 0, w)

  // Vordere Wehe: mit Anker knapp unter dem Knopf (Laternen stehen darauf).
  const groundBase = anchored ? clamp((o.anchorRow ?? 0) + 6, Math.round(h * 0.55), h - 3) : Math.round(h * 0.86)
  // Weite Ebene: Horizont knapp über der Mitte, die Kuppen dahinter bleiben niedrig.
  const horizon = Math.max(4, anchored ? Math.min(Math.round(h * 0.5), groundBase - Math.max(14, Math.round(h * 0.24))) : Math.round(h * 0.56))
  const mountainAmp = Math.max(4, Math.round(h * (anchored ? 0.19 : 0.15)))
  const forestBase = horizon + Math.max(2, Math.round(h * 0.012))
  const hillBase = forestBase + Math.max(3, Math.round(h * 0.03))

  const back = peaks(w, mountainAmp, rand, 1.25, seed & 0xffff)
  const front = peaks(w, mountainAmp * 0.62, rand, 1, (seed & 0xffff) + 13)
  const mountainsBack = new Int16Array(w)
  const mountains = new Int16Array(w)
  const forest = new Int16Array(w)
  const hills = new Int16Array(w)
  const ground = new Int16Array(w)
  const s = seed & 0xffff
  for (let x = 0; x < w; x++) {
    mountainsBack[x] = horizon - Math.round(back[x]!)
    mountains[x] = horizon - Math.round(front[x]!)
    forest[x] = Math.round(forestBase - 1.4 * smooth(x / 7, s + 5))
    hills[x] = Math.round(hillBase - h * 0.012 * smooth(x / 80 + 2, s + 6))
    // Unter den Laternen fast eben, sonst sanfte Verwehungen.
    const flat = anchored && x < anchorEnd + 4
    const drift = flat ? 1.2 * smooth(x / 12, s + 8) : h * 0.02 * smooth(x / 60, s + 9) + 1.6 * smooth(x / 16, s + 10)
    ground[x] = Math.round(groundBase - drift)
  }

  // Blockhütte vorn rechts. Startseite: über dem Spielen-Knopf (sonst verdeckt er sie).
  let cabin: Landscape['cabin'] = null
  const size: CabinSize | null = w >= 160 && h >= 90 ? 'large' : w >= 100 && h >= 44 ? 'small' : null
  if (o.cabin !== false && size) {
    const spec = CABINS[size]
    const margin = Math.max(4, Math.round(w * 0.05))
    let x: number
    let base: number
    if (anchored && o.anchorTop != null && o.anchorRight != null && o.anchorRight > spec.w) {
      x = clamp(Math.round(o.anchorRight) - spec.w - 2, 4, w - spec.w - 2)
      base = clamp(Math.round(o.anchorTop) - 2, hillBase + 3, h - 1)
    } else {
      x = o.cabinX == null ? w - spec.w - margin : Math.round(clamp(w * o.cabinX, 4, w - spec.w - 4))
      let top = h
      for (let k = x; k < x + spec.w; k++) top = Math.min(top, ground[clamp(k, 0, w - 1)]!)
      base = top + 1
      // Boden unter der Hütte einebnen.
      for (let k = x - 2; k < x + spec.w + 2; k++) if (k >= 0 && k < w) ground[k] = top
    }
    cabin = { x, y: base, size, penguin: size === 'small' && !!o.penguin }
  }

  const pines: Pine[] = []
  const nearCabin = (x: number, pad: number) => !!cabin && x > cabin.x - pad && x < cabin.x + CABINS[cabin.size].w + pad
  const pineSeed = () => (rand() * 1e9) | 0
  // Schmaler Waldstreifen vor den Kuppen: kleine Tannen in Gruppen.
  const tiny = Math.max(3, Math.round(h * 0.022))
  for (let x = 0; x < w; ) {
    const patch = smooth(x / 26, s + 11)
    if (patch > 0.3) pines.push({ x, base: forest[x]! + 1, h: tiny + Math.round(rand() * tiny * 0.9 + patch * 2), layer: 0, seed: pineSeed() })
    x += 2 + Math.floor(rand() * 3)
  }
  // Ein paar Tannengruppen in der Ebene, eher rechts (links steht die Schrift).
  const groups = Math.max(1, Math.round(w / 140))
  for (let g = 0; g < groups; g++) {
    const gx = Math.round(w * (0.38 + rand() * 0.55))
    const depth = Math.round(rand() * Math.max(1, h * 0.04))
    for (let k = 0, n = 2 + Math.floor(rand() * 3); k < n; k++) {
      const x = gx + Math.round((k - n / 2) * (4 + rand() * 4))
      if (x < 0 || x >= w || nearCabin(x, 6)) continue
      pines.push({ x, base: hills[x]! + 1 + depth, h: Math.round(clamp(h * (0.05 + rand() * 0.04), 6, 22)), layer: 1, seed: pineSeed() })
    }
  }
  // Große Tannen an der Hütte (dahinter/daneben) und eine am linken Rand.
  if (cabin) {
    const spec = CABINS[cabin.size]
    const big = Math.round(clamp(spec.h * 1.35, 14, h * 0.55))
    const right = cabin.x + spec.w + 3
    if (right < w + 4) pines.push({ x: right, base: cabin.y + 1, h: big, layer: 2, seed: pineSeed() })
    pines.push({ x: cabin.x - 4, base: cabin.y - Math.round(spec.h * 0.1), h: Math.round(big * 0.8), layer: 2, seed: pineSeed() })
  }
  if (!anchored && w > 200) pines.push({ x: -2, base: ground[0]! + 1, h: Math.round(h * 0.42), layer: 2, seed: pineSeed() })
  pines.sort((a, b) => a.layer - b.layer || a.base - b.base)

  // Mond oben rechts, Sterne mehr nach oben hin.
  const r = Math.round(clamp(Math.min(w, h) * 0.035, 5, 10))
  const skyTop = horizon - mountainAmp
  const moon = { x: Math.round(w * (o.moonX ?? (anchored ? 0.84 : 0.8))), y: Math.round(clamp(h * 0.15, r + 3, skyTop - r - 2)), r }
  const stars: Star[] = []
  const skyBottom = horizon - Math.round(mountainAmp * 0.6)
  const count = Math.max(0, Math.round((w * skyBottom) / 260))
  for (let i = 0; i < count; i++) {
    const x = Math.floor(rand() * w)
    const y = Math.floor(Math.pow(rand(), 1.5) * skyBottom)
    if ((x - moon.x) ** 2 + (y - moon.y) ** 2 < (r * 3) ** 2) continue
    const big = rand() < 0.06
    stars.push({ x, y, big, bright: big || rand() < 0.35, twinkle: rand() < 0.22 })
  }

  const lanterns: Landscape['lanterns'] = []
  if (anchored && anchorEnd > 10) {
    const y = clamp(o.anchorRow ?? 0, 6, groundBase - 4)
    for (let x = 5; x <= anchorEnd - 4; x += LANTERN_GAP) lanterns.push({ x, y })
  }

  const sparkles: Landscape['sparkles'] = []
  for (let i = 0, n = Math.round(w * 0.4); i < n; i++) {
    const x = Math.floor(rand() * w)
    const top = hills[x]!
    const y = top + 1 + Math.floor(rand() * Math.max(1, h - top - 2))
    if (y < h) sparkles.push({ x, y })
  }

  const tufts: Landscape['tufts'] = []
  for (let x = 3; x < w; x += 4 + Math.floor(rand() * 9)) {
    if (smooth(x / 18, s + 13) > 0.62 && !lanterns.some((l) => Math.abs(l.x - x) < 3) && !nearCabin(x, 2)) {
      tufts.push({ x, y: ground[x]!, h: 2 + Math.floor(rand() * 3) })
    }
  }

  const clouds: Cloud[] = []
  for (let i = 0, n = Math.max(2, Math.round(w / 110)); i < n; i++) {
    const cw = Math.round(22 + rand() * 30)
    clouds.push({
      x: Math.round(rand() * (w + cw)) - cw,
      y: Math.round(h * 0.05 + rand() * Math.max(4, skyTop - h * 0.14)),
      w: cw,
      h: Math.round(6 + rand() * 5),
      seed: pineSeed(),
      speed: 0.02 + rand() * 0.03,
    })
  }

  return { w, h, seed, horizon, mountainAmp, mountainsBack, mountains, forest, hills, ground, pines, stars, moon, cabin, lanterns, sparkles, tufts, clouds }
}

/** Polarlicht-Stärke 0…1 über die Zeit: hin und wieder ein paar Minuten, sanft ein- und ausgeblendet. */
export function auroraEnvelope(seconds: number, seed: number): number {
  // Der erste Auftritt beginnt gleich nach dem Start, danach etwa alle zwei Minuten
  // für 34–56 s, mit leicht verschobenem Beginn je Durchgang.
  const t0 = seconds - 2
  if (t0 < 0) return 0
  const k = Math.floor(t0 / AURORA_PERIOD)
  const delay = k === 0 ? 0 : noise(k, 2, seed) * 30
  const on = 34 + noise(k, 1, seed) * 22
  const t = t0 - k * AURORA_PERIOD - delay
  if (t < 0 || t > on) return 0
  const fade = 7
  if (t < fade) return ease(t / fade)
  if (t > on - fade) return ease((on - t) / fade)
  return 1
}

/** Länge eines Polarlicht-Durchgangs (Sekunden). */
export const AURORA_PERIOD = 120

function ease(t: number): number {
  const c = clamp(t, 0, 1)
  return c * c * (3 - 2 * c)
}
