import { noise } from '../redstone/paint'
import { rng } from '../redstone/scene'
import { smooth } from '../winter/landscape'

// Aufbau der Fuchsbau-Landschaft (nur Daten, ohne Canvas – testbar): ein Minecraft-Wald
// in der Abenddämmerung von der Seite. Hinten blockige Hügel im rosa Dunst mit kleinen
// Fichten, davor ein dichtes Waldband (Taiga-Fichten, ab und zu ein Kirschbaum), eine
// Wiese im Abendlicht und vorn eine Reihe Grasblöcke. Rechts liegt der Fuchsbau: ein
// kleiner Grashügel mit dunklem Loch zwischen Wurzeln, eine Fichte darauf, davor das
// Lagerfeuer – und daneben schläft ein einzelner Fuchs. Auf der Startseite läuft ein
// Fichtenzaun mit Laternen bis zum Spielen-Knopf. Alles in Texeln (1 Texel = 3
// Gerätepixel wie in den anderen Szenen), aus dem Seed stabil.

export type TreeKind = 'spruce' | 'cherry'

export interface Tree {
  kind: TreeKind
  /** Linke Kante des Stamm-Blocks. */
  x: number
  /** Unterkante (Boden, auf dem der Stamm steht). */
  base: number
  /** Kantenlänge eines Blocks in Texeln. */
  bs: number
  /** Nadel-Stufen (Fichte) bzw. Stammhöhe in Blöcken (Kirsche). */
  tiers: number
  /** 0 = fernes Waldband, 1 = Gruppen auf der Wiese, 2 = vorn (am Bau, Bildrand). */
  layer: 0 | 1 | 2
  seed: number
  /** Kirschbaum: Stamm neigt sich nach links. */
  flip: boolean
}

export interface Star {
  x: number
  y: number
  bright: boolean
  twinkle: boolean
}

export interface Cloud {
  x: number
  y: number
  /** Zellen (je 6 × 2 Texel) – flache Minecraft-Wolken. */
  cols: number
  rows: number
  seed: number
  /** Texel je Takt. */
  speed: number
}

export type DenSize = 'large' | 'small'

/** Maße des Baus in Blöcken (Breite) – `bs` je Größe. */
export const DEN_BLOCKS = 12
export const DEN_BS: Record<DenSize, number> = { large: 8, small: 3 }

export interface Den {
  /** Linke Kante. */
  x: number
  /** Bodenzeile (alles steht darauf). */
  y: number
  size: DenSize
  bs: number
  /** Der schlafende Fuchs am Feuer (klein nur als winzige Figur). */
  fox: boolean
}

/** Lage der Teile des Baus (Texel, absolut) – Maler, Licht und Tests rechnen damit. */
export interface DenParts {
  /** Lagerfeuer: linke Kante, Bodenzeile, Breite (= ein Block). */
  fire: { x: number; y: number; w: number }
  /** Schlafender Fuchs (nur großer Bau): Fußpunkt links unten, Blick nach links (zum Feuer). */
  fox: { x: number; y: number } | null
  /** Hügel: linke Kante, Höhe je Blockspalte (in Blöcken). */
  hill: { x: number; heights: number[] }
  /** Loch im Hügel (Bogen). */
  hole: { x0: number; x1: number; top: number; y: number }
  /** Laterne auf dem Zaunpfahl (Mitte des Glases). */
  lantern: { x: number; y: number }
  /** Süßbeerenstrauch: linke Kante, Bodenzeile, Größe (Texel). */
  bush: { x: number; y: number; size: number }
}


export function denParts(d: Den): DenParts {
  const b = d.bs
  const heights = [1, 2, 2, 2, 2, 2, 1]
  const hx = d.x + 4 * b
  const top2 = d.y - 2 * b
  return {
    fire: { x: d.x + Math.round(0.6 * b), y: d.y, w: b },
    fox: d.fox && d.size === 'large' ? { x: d.x + Math.round(1.75 * b), y: d.y } : null,
    hill: { x: hx, heights },
    hole: { x0: hx + Math.round(1.35 * b), x1: hx + Math.round(3.3 * b), top: d.y - Math.round(1.45 * b), y: d.y },
    lantern: { x: hx + Math.round(1.1 * b), y: top2 - Math.max(4, Math.round(b * 0.95)) },
    bush: { x: d.x + 10 * b + Math.round(b * 0.3), y: d.y, size: Math.round(b * 1.6) },
  }
}

export interface FoxLandscape {
  w: number
  h: number
  seed: number
  /** Fuß der fernen Hügel. */
  horizon: number
  /** Höhe der höchsten fernen Hügel (Texel). */
  hillAmp: number
  /** Blockige Hügelketten: hinten (blass) und davor. */
  hillsBack: Int16Array
  hills: Int16Array
  /** Boden des fernen Waldbands. */
  forest: Int16Array
  /** Oberkante der Wiese. */
  meadow: Int16Array
  /** Vordere Reihe Grasblöcke (Bau, Zaun stehen darauf). */
  ground: Int16Array
  /** Blockgröße der vorderen Reihe. */
  bs: number
  trees: Tree[]
  stars: Star[]
  /** Eckige Minecraft-Sonne: Mitte und halbe Kantenlänge. */
  sun: { x: number; y: number; half: number }
  den: Den | null
  /** Laternen auf dem Fichtenzaun (Mitte des Glases) – nur mit Anker. */
  lanterns: { x: number; y: number }[]
  /** Blumen auf der Wiese (Farbe 0 = rosa, 1 = gelb, 2 = weiß). */
  flowers: { x: number; y: number; c: 0 | 1 | 2 }[]
  /** Grashalme vorn. */
  tufts: { x: number; y: number; h: number }[]
  /** Büsche und Farn auf der Wiese (Mitte unten, Größe in Texeln). */
  shrubs: { x: number; y: number; size: number }[]
  clouds: Cloud[]
}

export interface FoxLandscapeOptions {
  cols: number
  rows: number
  seed: number
  /** Texel-Zeile der Mitte des Ankers (Spielen-Knopf), -1 = keiner. */
  anchorRow?: number
  /** Texel-Spalte, an der der Zaun endet (linker Rand des Knopfs). */
  anchorEnd?: number
  /** Oberkante und rechter Rand des Ankers – der Bau liegt darüber. */
  anchorTop?: number
  anchorRight?: number
  /** Bau zeigen (Standard: wenn Platz ist); `false` = nie. */
  den?: boolean
  /** Fuchs am Feuer (Standard: beim großen Bau). */
  fox?: boolean
  /** Bau waagrecht, 0…1 (Standard rechts). */
  denX?: number
  /** Sonne waagrecht, 0…1. */
  sunX?: number
}

/** Abstand der Laternen auf dem Zaun. */
export const LANTERN_GAP = 14

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v))

/** Blockige Hügelkette: Höhe je Blockspalte (Stufen in Blockgröße), zwei Wellen + etwas Rauschen. */
function blockHills(w: number, bs: number, amp: number, seed: number): Float32Array {
  const out = new Float32Array(w)
  const s = seed & 0xffff
  for (let bx = 0; bx < w; bx += bs) {
    const u = bx / Math.max(1, w)
    const wave = 0.55 + 0.3 * Math.sin(u * 7.1 + s * 0.37) * Math.sin(u * 15.3 + s * 0.11) + 0.25 * (smooth(bx / 40, s + 3) - 0.5)
    const hgt = Math.max(bs, Math.round((amp * clamp(wave, 0.12, 1)) / bs) * bs)
    for (let x = bx; x < Math.min(w, bx + bs); x++) out[x] = hgt
  }
  return out
}

export function buildFoxLandscape(o: FoxLandscapeOptions): FoxLandscape {
  const w = Math.max(8, Math.floor(o.cols))
  const h = Math.max(8, Math.floor(o.rows))
  const seed = o.seed >>> 0
  const rand = rng(seed ^ 0xf0c5)
  const s = seed & 0xffff
  const anchored = (o.anchorRow ?? -1) >= 0
  const anchorEnd = clamp(Math.floor(o.anchorEnd ?? 0), 0, w)

  const size: DenSize | null = w >= 160 && h >= 90 ? 'large' : w >= 100 && h >= 44 ? 'small' : null
  const bs = size === 'large' ? DEN_BS.large : size === 'small' ? DEN_BS.small : 2

  // Vordere Grasblöcke: mit Anker knapp unter dem Knopf (der Zaun steht darauf).
  const groundBase = anchored ? clamp((o.anchorRow ?? 0) + 6, Math.round(h * 0.55), h - 3) : Math.round(h * 0.86)
  const horizon = Math.max(4, anchored ? Math.min(Math.round(h * 0.5), groundBase - Math.max(14, Math.round(h * 0.26))) : Math.round(h * 0.55))
  const hillAmp = Math.max(4, Math.round(h * (anchored ? 0.13 : 0.12)))
  const forestBase = horizon + Math.max(2, Math.round(h * 0.014))
  const meadowBase = forestBase + Math.max(2, Math.round(h * 0.02))

  const farBs = h >= 90 ? 4 : 2
  const back = blockHills(w, farBs, hillAmp, s + 1)
  const front = blockHills(w, Math.max(2, farBs - 1), hillAmp * 0.55, s + 17)
  const hillsBack = new Int16Array(w)
  const hills = new Int16Array(w)
  const forest = new Int16Array(w)
  const meadow = new Int16Array(w)
  const ground = new Int16Array(w)
  for (let x = 0; x < w; x++) {
    hillsBack[x] = horizon - Math.round(back[x]!)
    hills[x] = horizon + 1 - Math.round(front[x]!)
    forest[x] = Math.round(forestBase - 1.2 * smooth(x / 9, s + 5))
    meadow[x] = Math.round(meadowBase - h * 0.01 * smooth(x / 70 + 2, s + 6))
  }
  // Vorn: Blockstufen (eine Blockhöhe hoch oder runter), unter Zaun und Bau eben.
  const step = Math.max(2, Math.round(bs / 2))
  for (let bx = 0; bx < w; bx += bs) {
    const flat = anchored && bx < anchorEnd + bs
    const lift = flat ? 0 : smooth(bx / 90, s + 9) > 0.62 ? step : smooth(bx / 50, s + 10) < 0.28 ? -step : 0
    for (let x = bx; x < Math.min(w, bx + bs); x++) ground[x] = groundBase - lift
  }

  // Fuchsbau rechts. Startseite: über dem Spielen-Knopf (sonst verdeckt er ihn).
  let den: Den | null = null
  if (o.den !== false && size) {
    const dw = DEN_BLOCKS * bs
    const margin = Math.max(4, Math.round(w * 0.05))
    let x: number
    let base: number
    if (anchored && o.anchorTop != null && o.anchorRight != null && o.anchorRight > dw * 0.7) {
      x = clamp(Math.round(o.anchorRight) - dw, 2, w - dw)
      base = clamp(Math.round(o.anchorTop) - 2, meadowBase + 4, h - 1)
    } else {
      x = o.denX == null ? w - dw - margin : Math.round(clamp(w * o.denX, 2, w - dw - 2))
      let top = h
      for (let k = x; k < x + dw; k++) top = Math.min(top, ground[clamp(k, 0, w - 1)]!)
      base = top
      // Boden unter dem Bau einebnen.
      for (let k = x - bs; k < x + dw + bs; k++) if (k >= 0 && k < w) ground[k] = top
    }
    den = { x, y: base, size, bs, fox: o.fox ?? size === 'large' }
  }

  const trees: Tree[] = []
  const treeSeed = () => (rand() * 1e9) | 0
  const nearDen = (x: number, pad: number) => !!den && x > den.x - pad && x < den.x + DEN_BLOCKS * den.bs + pad

  // Sonne: tief über den Hügeln, hält Abstand zum Bau.
  const half = Math.round(clamp(Math.min(w, h) * 0.038, 4, 10))
  let sunX = Math.round(w * (o.sunX ?? (anchored ? 0.6 : 0.56)))
  if (den && Math.abs(sunX - (den.x + DEN_BLOCKS * den.bs * 0.6)) < half * 4) sunX = Math.round(den.x - half * 3)
  sunX = clamp(sunX, half + 2, w - half - 2)
  // Zu zwei Dritteln über den Hügeln an ihrer Stelle – sie geht gerade unter.
  let ridge = horizon
  for (let x = sunX - half; x <= sunX + half; x++) ridge = Math.min(ridge, horizon - Math.round(back[clamp(x, 0, w - 1)]!))
  const sun = { x: sunX, y: Math.round(ridge - half * 0.45), half }

  // Fernes Waldband: kleine Fichten (2er-Blöcke) dicht an dicht, ab und zu ein Kirschbaum; um die Sonne frei.
  const fb = h >= 90 ? 2 : 1
  for (let x = 0; x < w; ) {
    const patch = smooth(x / 30, s + 11)
    const clear = Math.abs(x - sun.x) < half * 1.6
    if (patch > 0.26 && !clear) {
      const cherry = rand() < 0.1
      trees.push({ kind: cherry ? 'cherry' : 'spruce', x, base: forest[x]! + 1, bs: fb, tiers: cherry ? 3 : 3 + Math.floor(rand() * 3 + patch * 2), layer: 0, seed: treeSeed(), flip: rand() < 0.5 })
    }
    x += fb * (2 + Math.floor(rand() * 2))
  }
  // Baumgruppen auf der Wiese, eher rechts (links steht die Schrift).
  const mb = h >= 90 ? 4 : 2
  const groups = Math.max(1, Math.round(w / (anchored ? 210 : 170)))
  for (let g = 0; g < groups; g++) {
    // Startseite: rechts (links steht die Schrift); als Seiten-Hintergrund überall.
    const gx = Math.round(w * (anchored ? 0.4 + rand() * 0.55 : 0.08 + rand() * 0.86))
    const depth = Math.round(rand() * Math.max(1, h * 0.03))
    for (let k = 0, n = 2 + Math.floor(rand() * 3); k < n; k++) {
      const x = gx + Math.round((k - n / 2) * mb * (2.5 + rand() * 1.5))
      // Die Sonne bleibt frei, der Bau auch.
      if (x < 0 || x >= w || nearDen(x, mb * 3) || Math.abs(x - sun.x) < half + mb * 5) continue
      const cherry = k === 1 && rand() < 0.45
      trees.push({ kind: cherry ? 'cherry' : 'spruce', x, base: meadow[x]! + 1 + depth, bs: mb, tiers: cherry ? 4 : 5 + Math.floor(rand() * 4), layer: 1, seed: treeSeed(), flip: rand() < 0.5 })
    }
  }
  // Am Bau: eine Fichte auf dem Hügel, ein Kirschbaum hinter dem Feuer; groß dazu eine Fichte am Bildrand.
  if (den) {
    const p = denParts(den)
    const b = den.bs
    const big = den.size === 'large'
    // Der Kirschbaum steht etwas weiter hinten (kleinere Blöcke) und rahmt das Feuer.
    const cb = big ? 5 : 2
    trees.push({ kind: 'cherry', x: den.x - Math.round(b * 0.4), base: den.y - (big ? 3 : 1), bs: cb, tiers: 4, layer: 1, seed: treeSeed(), flip: true })
    trees.push({ kind: 'spruce', x: p.hill.x + 5 * b, base: den.y - 2 * b, bs: b, tiers: big ? 6 : 6, layer: 2, seed: treeSeed(), flip: false })
    const edge = den.x + DEN_BLOCKS * b + Math.round(b * 1.2)
    if (big && edge < w) trees.push({ kind: 'spruce', x: edge, base: ground[clamp(edge, 0, w - 1)]!, bs: b, tiers: 9, layer: 2, seed: treeSeed(), flip: false })
  }
  if (!anchored && w > 220 && size === 'large') trees.push({ kind: 'spruce', x: -Math.round(bs * 0.5), base: ground[0]!, bs, tiers: 10, layer: 2, seed: treeSeed(), flip: false })
  trees.sort((a, b) => a.layer - b.layer || a.base - b.base)

  // Erste Sterne oben im Violett.
  const stars: Star[] = []
  const skyBottom = Math.round((horizon - hillAmp) * 0.55)
  const count = Math.max(0, Math.round((w * skyBottom) / 1100))
  for (let i = 0; i < count; i++) {
    const x = Math.floor(rand() * w)
    const y = Math.floor(Math.pow(rand(), 2.2) * skyBottom)
    stars.push({ x, y, bright: rand() < 0.25, twinkle: rand() < 0.3 })
  }

  // Zaun mit Laternen bis zum Spielen-Knopf.
  const lanterns: FoxLandscape['lanterns'] = []
  if (anchored && anchorEnd > 12) {
    // Laternen etwas über der Knopfmitte – so bleibt Platz für Pfosten und Latten.
    const y = clamp((o.anchorRow ?? 0) - 4, 6, groundBase - 8)
    for (let x = 6; x <= anchorEnd - 5; x += LANTERN_GAP) lanterns.push({ x, y })
  }

  const flowers: FoxLandscape['flowers'] = []
  for (let i = 0, n = Math.round(w * 0.08); i < n; i++) {
    const x = Math.floor(rand() * w)
    const top = meadow[x]!
    const y = top + 2 + Math.floor(Math.pow(rand(), 0.7) * Math.max(1, ground[x]! - top - 4))
    if (y < ground[x]! - 1 && noise(x >> 3, 0, s + 21) > 0.35) flowers.push({ x, y, c: (rand() < 0.5 ? 0 : rand() < 0.6 ? 1 : 2) as 0 | 1 | 2 })
  }

  const tufts: FoxLandscape['tufts'] = []
  for (let x = 2; x < w; x += 2 + Math.floor(rand() * 5)) {
    if (smooth(x / 14, s + 13) > 0.45 && !lanterns.some((l) => Math.abs(l.x - x) < 2) && !nearDen(x, 0)) {
      tufts.push({ x, y: ground[x]!, h: 1 + Math.floor(rand() * 3) })
    }
  }

  // Büsche auf der Wiese: hinten klein, vorn größer.
  const shrubs: FoxLandscape['shrubs'] = []
  for (let i = 0, n = Math.round(w / 26); i < n; i++) {
    const x = Math.floor(rand() * w)
    const top = meadow[x]!
    const room = ground[x]! - top - 3
    if (room < 4 || nearDen(x, 4) || lanterns.some((l) => Math.abs(l.x - x) < 4)) continue
    const depth = Math.pow(rand(), 0.8)
    const y = top + 2 + Math.round(depth * room)
    shrubs.push({ x, y, size: Math.max(2, Math.round((2 + depth * 5) * (h >= 90 ? 1 : 0.6))) })
  }
  shrubs.sort((a, b) => a.y - b.y)

  const clouds: Cloud[] = []
  const skyTop = horizon - hillAmp
  for (let i = 0, n = Math.max(2, Math.round(w / 120)); i < n; i++) {
    const cols = 5 + Math.floor(rand() * 8)
    clouds.push({
      x: Math.round(rand() * (w + cols * 6)) - cols * 6,
      y: Math.round(h * 0.05 + rand() * Math.max(4, skyTop * 0.5)),
      cols,
      rows: 2 + Math.floor(rand() * 2),
      seed: treeSeed(),
      speed: 0.015 + rand() * 0.025,
    })
  }

  return { w, h, seed, horizon, hillAmp, hillsBack, hills, forest, meadow, ground, bs, trees, stars, sun, den, lanterns, flowers, tufts, shrubs, clouds }
}
