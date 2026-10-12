import { noise } from '../redstone/paint'
import { rng } from '../redstone/scene'

// Aufbau der Winterlandschaft (nur Daten, ohne Canvas – testbar): Höhenlinien
// der Ebenen von hinten nach vorn, Bäume, Sterne, Mond, Hütte und – auf der
// Startseite – die Eislaternen bis zum Spielen-Knopf. Alles in Texeln
// (1 Texel = 3 Gerätepixel wie in der Redstone-Szene), aus dem Seed stabil.

export interface Pine {
  x: number
  /** Unterkante (Boden, auf dem der Stamm steht). */
  base: number
  h: number
  /** 0 = fern (Silhouette), 1 = Hügel, 2 = vorn. */
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

export interface Landscape {
  w: number
  h: number
  seed: number
  /** Fuß der fernen Berge. */
  horizon: number
  /** Ausschlag der Berge (Texel). */
  mountainAmp: number
  mountains: Int16Array
  forest: Int16Array
  hills: Int16Array
  ground: Int16Array
  pines: Pine[]
  stars: Star[]
  moon: { x: number; y: number; r: number }
  cabin: { x: number; y: number } | null
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
  /** Texel-Zeile des Ankers (Spielen-Knopf), -1 = keiner. */
  anchorRow?: number
  /** Texel-Spalte, an der die Laternenreihe endet (linker Rand des Knopfs). */
  anchorEnd?: number
}

export const CABIN_W = 22
export const CABIN_H = 17
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

/** Grat-Rauschen: spitze Gipfel statt runder Hügel. */
function ridge(x: number, seed: number): number {
  return 1 - Math.abs(2 * smooth(x, seed) - 1)
}

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v))

export function buildLandscape(o: LandscapeOptions): Landscape {
  const w = Math.max(8, Math.floor(o.cols))
  const h = Math.max(8, Math.floor(o.rows))
  const seed = o.seed >>> 0
  const rand = rng(seed ^ 0x5eed)
  const anchored = (o.anchorRow ?? -1) >= 0
  const anchorEnd = clamp(Math.floor(o.anchorEnd ?? 0), 0, w)

  // Vorderster Boden: mit Anker knapp unter dem Knopf (Laternen stehen darauf).
  const groundBase = anchored ? clamp((o.anchorRow ?? 0) + 6, Math.round(h * 0.55), h - 3) : Math.round(h * 0.9)
  const hillBase = Math.min(Math.round(h * 0.76), groundBase - Math.max(6, Math.round(h * 0.1)))
  const forestBase = Math.min(Math.round(h * 0.66), hillBase - Math.max(4, Math.round(h * 0.07)))
  const horizon = forestBase + 1
  const mountainAmp = Math.max(10, Math.round(Math.min(horizon * 0.62, h * 0.3)))

  const mountains = new Int16Array(w)
  const forest = new Int16Array(w)
  const hills = new Int16Array(w)
  const ground = new Int16Array(w)
  const s = seed & 0xffff
  for (let x = 0; x < w; x++) {
    const m = 0.62 * ridge(x / 46 + 3.1, s + 1) + 0.28 * ridge(x / 19 + 7.7, s + 2) + 0.1 * smooth(x / 6, s + 3)
    mountains[x] = Math.round(horizon - mountainAmp * Math.pow(m, 1.35))
    forest[x] = Math.round(forestBase - h * 0.035 * smooth(x / 38, s + 4) - 1.5 * smooth(x / 9, s + 5))
    hills[x] = Math.round(hillBase - h * 0.06 * smooth(x / 64 + 2, s + 6) - h * 0.02 * smooth(x / 21, s + 7))
    // Unter den Laternen fast eben, sonst sanfte Verwehungen.
    const flat = anchored && x < anchorEnd + 4
    const drift = flat ? 1.2 * smooth(x / 12, s + 8) : h * 0.035 * smooth(x / 52, s + 9) + 2.2 * smooth(x / 14, s + 10)
    ground[x] = Math.round(groundBase - drift)
  }

  // Hütte auf dem Hügel, rechts der Mitte (auf der Startseite vor dem Knopf).
  let cabin: Landscape['cabin'] = null
  if (w >= 120 && h >= 50) {
    const cx = Math.round(anchored ? Math.min(w - CABIN_W - 6, Math.max(anchorEnd + 18, w * 0.7)) : w * 0.64)
    let top = h
    for (let x = cx; x < cx + CABIN_W; x++) top = Math.min(top, hills[x] ?? top)
    // Boden unter der Hütte einebnen.
    for (let x = cx - 2; x < cx + CABIN_W + 2; x++) if (x >= 0 && x < w) hills[x] = top + 1
    cabin = { x: cx, y: top + 1 }
  }

  const pines: Pine[] = []
  const nearCabin = (x: number, pad: number) => cabin && x > cabin.x - pad && x < cabin.x + CABIN_W + pad
  // Ferne Wälder auf dem Rücken: dicht, in Gruppen.
  for (let x = 0; x < w; ) {
    const patch = smooth(x / 30, s + 11)
    if (patch > 0.32) pines.push({ x, base: forest[x]! + 1, h: 4 + Math.round(rand() * 6 + patch * 3), layer: 0, seed: (rand() * 1e9) | 0 })
    x += 2 + Math.floor(rand() * 3)
  }
  // Tannen auf dem Hügel, in lockeren Gruppen.
  for (let x = 2; x < w; ) {
    const patch = smooth(x / 44 + 5, s + 12)
    if (patch > 0.5 && !nearCabin(x, 8)) {
      pines.push({ x, base: hills[x]! + 1, h: Math.round(clamp(h * (0.09 + rand() * 0.06), 9, 30)), layer: 1, seed: (rand() * 1e9) | 0 })
    }
    x += 5 + Math.floor(rand() * 6)
  }
  // Hütte bekommt zwei Tannen daneben.
  if (cabin) {
    for (const dx of [-7, CABIN_W + 5]) {
      const x = cabin.x + dx
      if (x > 0 && x < w) pines.push({ x, base: hills[x]! + 1, h: Math.round(clamp(h * 0.12, 12, 26)), layer: 1, seed: (rand() * 1e9) | 0 })
    }
  }
  // Große Tannen vorn als Rahmen: links und rechts am Rand.
  const nearMax = anchored ? Math.min(h * 0.62, groundBase - 4) : h * 0.55
  const nearMin = anchored ? h * 0.38 : h * 0.32
  const edges: number[] = [-3 - Math.floor(rand() * 4), Math.round(w * 0.06 + rand() * w * 0.04)]
  edges.push(w - Math.round(w * 0.05 + rand() * 6), w + 2)
  if (!anchored && w > 260) edges.push(Math.round(w * 0.88 + rand() * 10))
  for (const x of edges) {
    const gx = clamp(x, 0, w - 1)
    pines.push({ x, base: ground[gx]! + 1, h: Math.round(nearMin + rand() * (nearMax - nearMin)), layer: 2, seed: (rand() * 1e9) | 0 })
  }
  pines.sort((a, b) => a.layer - b.layer || a.base - b.base)

  // Mond oben rechts, Sterne mehr nach oben hin.
  const r = Math.round(clamp(Math.min(w, h) * 0.035, 5, 10))
  const moon = { x: Math.round(w * (anchored ? 0.84 : 0.8)), y: Math.round(clamp(h * 0.17, r + 3, horizon - mountainAmp - r)), r }
  const stars: Star[] = []
  const skyBottom = horizon - Math.round(mountainAmp * 0.4)
  const count = Math.round((w * skyBottom) / 260)
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
  for (let i = 0, n = Math.round(w * 0.35); i < n; i++) {
    const x = Math.floor(rand() * w)
    const onGround = rand() < 0.6
    const top = onGround ? ground[x]! : hills[x]!
    const y = top + 1 + Math.floor(rand() * (onGround ? Math.max(1, h - top - 2) : 6))
    if (y < h) sparkles.push({ x, y })
  }

  const tufts: Landscape['tufts'] = []
  for (let x = 3; x < w; x += 4 + Math.floor(rand() * 9)) {
    if (smooth(x / 18, s + 13) > 0.62 && !lanterns.some((l) => Math.abs(l.x - x) < 3)) tufts.push({ x, y: ground[x]!, h: 2 + Math.floor(rand() * 3) })
  }

  const clouds: Cloud[] = []
  for (let i = 0, n = Math.max(2, Math.round(w / 110)); i < n; i++) {
    const cw = Math.round(22 + rand() * 30)
    clouds.push({
      x: Math.round(rand() * (w + cw)) - cw,
      y: Math.round(h * 0.05 + rand() * Math.max(4, horizon - mountainAmp - h * 0.12)),
      w: cw,
      h: Math.round(6 + rand() * 5),
      seed: (rand() * 1e9) | 0,
      speed: 0.02 + rand() * 0.03,
    })
  }

  return { w, h, seed, horizon, mountainAmp, mountains, forest, hills, ground, pines, stars, moon, cabin, lanterns, sparkles, tufts, clouds }
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
