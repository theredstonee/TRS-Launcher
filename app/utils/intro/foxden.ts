// Szenen-Set „Fuchsbau“: Minecraft-Mischwald (Taiga-Fichten, einzelne Kirschbäume) in der warmen Abenddämmerung,
// Seitenansicht mit Parallaxe und Lichtkarte. Die eckige Minecraft-Sonne sinkt hinter die Hügel → die Kamera fährt
// aus dem Himmel hinab zum Fuchsbau zwischen den Wurzeln → das Lagerfeuer flammt auf (ein Fuchs schläft eingerollt
// daneben) → drei Junge purzeln aus dem Bau und spielen → ein Fuchs bringt Süßbeeren im Maul → er duckt sich und
// macht den Minecraft-Pounce im Gegenlicht vor der Sonne, kopfüber in den Süßbeerenbusch → Beeren, Blätter und
// Kirschblüten wirbeln auf die Kamera zu → sie sammeln sich vor der Sonne und blühen auf dem Schlag zum Logo auf.
//
// Takt: Jingle „Fuchsbau“ (Suno, 101 BPM, ein Schlag = 0,594 s). Sein zweiter Höhepunkt (Bass-Einsatz nach einer
// kurzen Senke) liegt in der Datei bei 9,72 s (`jingleHit`), der Ausklang danach endet mit der Übergabe. Alle
// Ereignisse liegen auf dem Raster impact − k · FOXDEN_BEAT; der Pounce springt auf den ersten großen Einsatz
// (7,33 s in der Datei = impact − 4 Schläge). Anderer Jingle: FOXDEN_BEAT und `jingleHit` (designs.ts) anpassen.

import type { IntroDesign } from './designs'
import { DEN, familyAt, foxCanvas, FOX_OX, FOX_OY, POUNCE, BERRY_DROP, SLEEPER, type FamilyCues, type FoxLook } from './fox'
import { bayer, canvas, clamp, css, ctx2d, decay, easeIn, easeOut, hash, hash2, hex, lerp, mix, range, rng, smooth, type Cv, type Rgb } from './kit'
import { IntroStage, type ArtView, type IntroStageOptions } from './stage'
import type { IntroCoreCues, IntroLength } from './timeline'

/** Ein Schlag des Fuchsbau-Jingles (s). */
export const FOXDEN_BEAT = 0.594

/** Kamera-Stützpunkt [t, x, y, zoom] (Bildmitte in Kunst-Pixeln, Boden = 0). */
export type CamKey = [number, number, number, number]

export interface FoxdenCues extends IntroCoreCues {
  cam: CamKey[]
  /** Lagerfeuer flammt auf (< 0 = brennt schon). */
  fire: number
  /** Laterne auf dem Zaunpfahl (< 0 = brennt schon). */
  lantern: number
  /** Junge purzeln aus dem Bau (je Junges; < 0 = schon draußen). */
  kits: number[]
  /** Fuchs mit Süßbeeren [kommt, legt ab]; null = schon abgelegt. */
  berries: [number, number] | null
  /** Pounce [duckt sich, Absprung, taucht in den Busch]. */
  pounce: [number, number, number]
  /** Beeren/Blätter/Blüten sammeln sich vor der Sonne (bis `impact`). */
  gather: number
}

/** Kamera im Scheitel des Pounce – die Sonne steht genau dahinter. */
export const LEAP_CAM = [POUNCE.apex.x, -50, 1.3] as const
/** Minecraft-Sonne (Parallaxe 0,08): so gelegt, dass sie im Scheitel hinter dem Fuchs steht; sinkt langsam. */
export const SUN = { x: POUNCE.apex.x - 0.92 * LEAP_CAM[0], y: POUNCE.apex.y - 9 - 0.92 * LEAP_CAM[1], half: 20, par: 0.08, sink: 2.5 } as const
export const CAMPFIRE = { x: 264 } as const
export const LANTERN_POST = { x: 450 } as const
export const BUSHES = [576, 600] as const

const k = (impact: number, n: number) => Math.round((impact - n * FOXDEN_BEAT) * 1000) / 1000

function endCam(impact: number, p: [number, number, number], hush: number): CamKey[] {
  return [
    [p[1], 470, -48, 1.28],
    [(p[1] + p[2]) / 2, LEAP_CAM[0], LEAP_CAM[1], LEAP_CAM[2]],
    [p[2], 545, -48, 1.32],
    [hush, 535, -52, 1.42],
    [impact, 530, -54, 1.55],
    [impact + 0.7, 530, -54, 1.68],
  ]
}

function makeCues(): Record<IntroLength, FoxdenCues> {
  // ~10 s nach einem Update: Sonnenuntergang über dem Wald, Kranfahrt zum Bau, Feuer, Junge, Beeren, Pounce
  const L = 7.9
  const lp: [number, number, number] = [k(L, 5), k(L, 4), k(L, 2)]
  const long: FoxdenCues = {
    duration: 10, fadeIn: 0.6, hush: k(L, 1), impact: L, logo: 8.65, wordmark: 8.78, fadeOut: 9.66,
    fire: k(L, 10), lantern: k(L, 6), kits: [k(L, 9), k(L, 8), k(L, 7)], berries: [k(L, 8), k(L, 5.5)], pounce: lp, gather: k(L, 2),
    cam: [[0, 300, -230, 1.0], [k(L, 10), 290, -52, 1.36], [k(L, 7), 330, -50, 1.42], [k(L, 5.5), 405, -50, 1.34], ...endCam(L, lp, k(L, 1))],
  }
  // ~7 s erster Start des Tages: schon unten am Bau
  const M = 4.9
  const mp: [number, number, number] = [k(M, 4.5), k(M, 4), k(M, 2)]
  const medium: FoxdenCues = {
    duration: 7, fadeIn: 0.5, hush: k(M, 1), impact: M, logo: 5.65, wordmark: 5.78, fadeOut: 6.66,
    fire: k(M, 7), lantern: -1, kits: [k(M, 6.5), k(M, 6), k(M, 5.5)], berries: [0.2, k(M, 5)], pounce: mp, gather: k(M, 2),
    cam: [[0, 300, -52, 1.38], [k(M, 5.5), 340, -50, 1.4], [k(M, 4.5), 420, -50, 1.32], ...endCam(M, mp, k(M, 1))],
  }
  // ~4 s bei jedem Start: alles brennt, die Jungen spielen schon – Pounce und Aufblühen
  const S = 2.25
  const sp: [number, number, number] = [0, k(S, 2.5), k(S, 1.5)]
  const short: FoxdenCues = {
    duration: 4, fadeIn: 0.3, hush: k(S, 1), impact: S, logo: 2.95, wordmark: 3.13, fadeOut: 3.57,
    fire: -1, lantern: -1, kits: [-1, -1, -1], berries: null, pounce: sp, gather: k(S, 1.5),
    cam: [[0, 430, -50, 1.3], ...endCam(S, sp, k(S, 1))],
  }
  return { short, medium, long }
}

export const FOXDEN_CUES: Record<IntroLength, FoxdenCues> = makeCues()

export const familyCues = (c: FoxdenCues): FamilyCues => ({ kits: c.kits, berries: c.berries, pounce: c.pounce, beat: FOXDEN_BEAT, impact: c.impact })

// ───────────────────────── reine Helfer (testbar) ─────────────────────────

/** Weiche Kamerafahrt durch die Stützpunkte (monotone kubische Interpolation – kein Stehenbleiben, kein Überschwingen). */
export function camAt(keys: readonly CamKey[], t: number): [number, number, number] {
  const n = keys.length
  const first = keys[0]!
  const last = keys[n - 1]!
  if (t <= first[0]) return [first[1], first[2], first[3]]
  if (t >= last[0]) return [last[1], last[2], last[3]]
  let i = 0
  while (i < n - 2 && t > keys[i + 1]![0]) i++
  const k0 = keys[i]!
  const k1 = keys[i + 1]!
  const dt = k1[0] - k0[0]
  const u = (t - k0[0]) / dt
  const out: [number, number, number] = [0, 0, 0]
  for (let c = 1; c <= 3; c++) {
    const slope = (a: number) => {
      if (a <= 0 || a >= n - 1) return 0
      const p = keys[a - 1]!
      const q = keys[a]!
      const r = keys[a + 1]!
      const s0 = (q[c]! - p[c]!) / (q[0] - p[0])
      const s1 = (r[c]! - q[c]!) / (r[0] - q[0])
      if (s0 * s1 <= 0) return 0
      return (2 * s0 * s1) / (s0 + s1)
    }
    const m0 = slope(i) * dt
    const m1 = slope(i + 1) * dt
    const u2 = u * u
    const u3 = u2 * u
    out[c - 1] = (2 * u3 - 3 * u2 + 1) * k0[c]! + (u3 - 2 * u2 + u) * m0 + (-2 * u3 + 3 * u2) * k1[c]! + (u3 - u2) * m1
  }
  return out
}

/** Leuchtkraft eines Feuers/einer Laterne (0…1+): flammt auf, flackert – ruhig ohne Aufflammen und Flackern. */
export function fireGlow(tIgnite: number, t: number, phase: number, calm: boolean): number {
  if (tIgnite >= 0 && t < tIgnite) return 0
  const age = tIgnite < 0 ? 99 : t - tIgnite
  const on = tIgnite < 0 ? 1 : easeOut(range(age, 0, 0.25))
  if (calm) return on
  const flare = 0.6 * decay(age, 4.5)
  const fl = 0.88 + 0.07 * Math.sin(t * 15 + phase) + 0.05 * Math.sin(t * 8.3 + phase * 2)
  return on * fl + flare
}

/** Windstoß 0…1 (der Pounce reißt Beeren und Blätter los). */
export function gustAt(c: Pick<FoxdenCues, 'pounce' | 'impact'>, t: number): number {
  const p2 = c.pounce[2]
  return smooth(range(t, p2 - 0.05, p2 + 0.2)) * (1 - smooth(range(t, c.impact - 0.4, c.impact)))
}

/** Lage der Sonne (Welt, Parallaxe 0,08) – sinkt langsam, steht im Scheitel des Pounce genau hinter dem Fuchs. */
export function sunAt(c: FoxdenCues, t: number) {
  const apex = (c.pounce[1] + c.pounce[2]) / 2
  return { x: SUN.x, y: SUN.y + SUN.sink * (t - apex) }
}

// ───────────────────────── Farben + Texturen (Minecraft) ─────────────────────────

const H = hex
const SKY = ['#2a1a3a', '#3e2044', '#56284e', '#7a3a5a', '#9a4a5c', '#c05e5a', '#e0785a', '#f09a64', '#ffc27a'].map(H)
const C = {
  sunCore: '#fff2c0',
  sun: '#ffd27a',
  sunGlow: '#ff9a50',
  grass: [H('#4c8030'), H('#5f9a3c'), H('#74b048')],
  dirt: [H('#6e4630'), H('#8a5a3a'), H('#9a6a44')],
  spruceLog: [H('#2c1c0e'), H('#3b2614'), H('#4e3420')],
  spruce: [H('#16291f'), H('#1f3a2e'), H('#2f5a40'), H('#3f7050')],
  cherry: [H('#c95a8c'), H('#e07aa8'), H('#f3a6c8'), H('#ffc6dc')],
  cherryLog: [H('#2a1622'), H('#3b2230'), H('#5a3440')],
  berryLeaf: [H('#244a2a'), H('#2f6a3a'), H('#3f8a4a')],
  berry: '#c8102e',
  berryHi: '#ff5a6a',
  fly: '#fff4a8',
  flyGlow: '#ffe27a',
  fire: '#ff9a40',
}
const PINK = ['#c95a8c', '#e07aa8', '#f3a6c8', '#ffc6dc'] as const

function nh(x: number, y: number, s = 0): number {
  let h = (Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263) + Math.imul(s | 0, 1442695041)) | 0
  h = Math.imul(h ^ (h >>> 13), 1274126177)
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296
}
const pick = (pal: Rgb[], n: number) => pal[Math.min(pal.length - 1, Math.floor(n * pal.length))]!

type TexFn = (u: number, v: number, s: number) => Rgb | null

/**
 * Wie in Minecraft trägt jeder Block derselben Sorte dieselbe 16×16-Textur (das Raster bleibt sichtbar); Blätter
 * haben Büschel aus 2×2-Texeln statt Einzelrauschen, dazu kleine Lücken.
 */
const clump = (u: number, v: number, s: number) => 0.62 * nh((u + (v & 2)) >> 1, v >> 1, s) + 0.38 * nh(u, v, s + 7)
const TEX: Record<string, TexFn> = {
  grass: (u, v) => {
    const n = nh(u, v, 3)
    const drip = 3 + (nh(u, 0, 4) > 0.5 ? 1 : 0) + (nh(u, 0, 5) > 0.8 ? 1 : 0)
    if (v < drip) return v === 0 ? C.grass[2]! : n > 0.72 ? C.grass[2]! : n < 0.28 ? C.grass[0]! : C.grass[1]!
    return n > 0.82 ? C.dirt[2]! : n < 0.3 ? C.dirt[0]! : C.dirt[1]!
  },
  dirt: (u, v) => {
    const n = clump(u, v, 9)
    return n > 0.72 ? C.dirt[2]! : n < 0.32 ? C.dirt[0]! : C.dirt[1]!
  },
  spruceLog: (u, v) => {
    const n = nh(u, v >> 2, 11)
    if (u % 4 === 0 || (u % 4 === 2 && n > 0.55)) return C.spruceLog[0]!
    return n > 0.7 ? C.spruceLog[2]! : C.spruceLog[1]!
  },
  spruce: (u, v) => {
    const n = clump(u, v, 13)
    if (n < 0.12 && nh(u, v, 14) < 0.5) return null
    return n < 0.3 ? C.spruce[0]! : n > 0.74 ? C.spruce[3]! : n > 0.5 ? C.spruce[2]! : C.spruce[1]!
  },
  cherry: (u, v) => {
    const n = clump(u, v, 17)
    if (n < 0.12 && nh(u, v, 18) < 0.5) return null
    return n < 0.28 ? C.cherry[0]! : n > 0.76 ? C.cherry[3]! : n > 0.5 ? C.cherry[2]! : C.cherry[1]!
  },
  cherryLog: (u, v) => {
    const n = nh(u, v >> 2, 19)
    if (u % 5 === 1 || (n > 0.8 && u % 5 === 3)) return C.cherryLog[0]!
    return n > 0.68 ? C.cherryLog[2]! : C.cherryLog[1]!
  },
}

/** Pixel-Raster in Welt-Koordinaten (ImageData – schnell beim einmaligen Malen). */
class Raster {
  readonly d: Uint8ClampedArray
  constructor(
    readonly w: number,
    readonly h: number,
    readonly x0 = 0,
    readonly y0 = 0,
  ) {
    this.d = new Uint8ClampedArray(w * h * 4)
  }
  private idx(x: number, y: number) {
    const ix = Math.floor(x) - this.x0
    const iy = Math.floor(y) - this.y0
    if (ix < 0 || iy < 0 || ix >= this.w || iy >= this.h) return -1
    return (iy * this.w + ix) * 4
  }
  set(x: number, y: number, c: Rgb, a = 255) {
    const i = this.idx(x, y)
    if (i < 0) return
    this.d[i] = c[0]
    this.d[i + 1] = c[1]
    this.d[i + 2] = c[2]
    this.d[i + 3] = a
  }
  has(x: number, y: number) {
    const i = this.idx(x, y)
    return i >= 0 && this.d[i + 3]! > 0
  }
  get(x: number, y: number): Rgb {
    const i = this.idx(x, y)
    return i < 0 ? [0, 0, 0] : [this.d[i]!, this.d[i + 1]!, this.d[i + 2]!]
  }
  /** Block mit Textur (Kantenlänge `bs`, Texel = bs/16), Tönung `tint` (0 = keine) zur Farbe `to`. */
  block(bx: number, by: number, bs: number, tex: TexFn, seed: number, shade = 0, to: Rgb = [0, 0, 0]) {
    const tx = 16 / bs
    // leichte Tönung je Block + dunklere Unterkante (Blockraster lesbar wie im Spiel)
    const tint = (nh(seed, 1, 77) - 0.5) * 0.08
    for (let y = 0; y < bs; y++) {
      for (let x = 0; x < bs; x++) {
        let c = tex(Math.floor(x * tx), Math.floor(y * tx), seed)
        if (!c) continue
        if (tint > 0) c = mix(c, [255, 230, 210], tint)
        else if (tint < 0) c = mix(c, [0, 0, 0], -tint)
        if (bs >= 8 && (y === bs - 1 || x === bs - 1)) c = mix(c, [0, 0, 0], 0.14)
        if (shade) c = mix(c, to, shade)
        this.set(bx + x, by + y, c)
      }
    }
  }
  canvas(): Cv {
    const c = canvas(this.w, this.h)
    ctx2d(c).putImageData(new ImageData(this.d as Uint8ClampedArray<ArrayBuffer>, this.w, this.h), 0, 0)
    return c
  }
}

type Layer = { cv: Cv; par: number; x0: number; y0: number }
const layerOf = (r: Raster, par: number): Layer => ({ cv: r.canvas(), par, x0: r.x0, y0: r.y0 })

/** Halbe Breite (Blöcke) der Nadel-Stufe i: wächst nach unten, mit Einschnitten wie bei Minecraft-Fichten. */
export function spruceHalf(i: number, tiers: number): number {
  if (i === 0 || tiers < 2) return 0
  const max = tiers > 8 ? 3 : tiers > 4 ? 2 : 1
  const base = Math.ceil((i / (tiers - 1)) * max)
  return i % 2 === 1 && base > 1 && i < tiers - 1 ? base - 1 : base
}

/** Fichten-Schattenriss in einer Farbe (ferne Wälder). */
function spruceSil(R: Raster, x: number, ground: number, bs: number, height: number, col: Rgb, rim: Rgb) {
  for (let i = 0; i < height; i++) {
    const h = spruceHalf(i, height)
    const y = ground - (height + 1) * bs + i * bs
    for (let j = -h; j <= h; j++) for (let yy = 0; yy < bs; yy++) for (let xx = 0; xx < bs; xx++) R.set(x + j * bs + xx, y + yy, yy === 0 && R.has(x + j * bs + xx, y - 1) === false ? rim : col)
  }
  for (let y = ground - bs; y < ground; y++) for (let xx = 0; xx < bs; xx++) R.set(x + xx, y, col)
}

/** Minecraft-Fichte: Stamm + Nadel-Stufen (schmal, breit, schmal …), Ecken der breiten Stufen fehlen. */
function spruce(R: Raster, x: number, ground: number, bs: number, height: number, seed: number, shade = 0, to: Rgb = [0, 0, 0]) {
  const r = rng(seed)
  const tiers = height
  const trunkTop = ground - (height + 1) * bs
  for (let y = ground - bs; y >= trunkTop; y -= bs) R.block(x, y, bs, TEX.spruceLog!, seed + y, shade, to)
  const placed = new Set<string>()
  for (let i = 0; i < tiers; i++) {
    const w = 1 + 2 * spruceHalf(i, tiers)
    const y = trunkTop - bs + i * bs
    for (let j = -(w >> 1); j <= w >> 1; j++) {
      if (w >= 5 && Math.abs(j) === w >> 1 && r() < 0.5) continue
      const top = 1 - i / tiers
      R.block(x + j * bs, y, bs, TEX.spruce!, seed + i * 31 + j, clamp(shade + 0.12 * (1 - top)), to)
      placed.add(`${i},${j}`)
    }
  }
  rimLight(R, placed, x, trunkTop - bs, bs, shade)
}

/** Warmer Saum auf Blöcken, über denen nichts liegt (die tiefe Sonne streift die Oberkanten). */
function rimLight(R: Raster, placed: Set<string>, x: number, y0: number, bs: number, shade: number) {
  const rim = H('#ffb878')
  const k = 0.38 * (1 - shade)
  if (k <= 0.02) return
  const rows = Math.max(1, Math.round(bs / 8))
  for (const key of placed) {
    const [i, j] = key.split(',').map(Number) as [number, number]
    if (placed.has(`${i - 1},${j}`)) continue
    for (let yy = 0; yy < rows; yy++) for (let xx = 0; xx < bs; xx++) if (R.has(x + j * bs + xx, y0 + i * bs + yy)) R.set(x + j * bs + xx, y0 + i * bs + yy, mix(R.get(x + j * bs + xx, y0 + i * bs + yy), rim, k * (yy ? 0.5 : 1)))
  }
}

/** Minecraft-Kirschbaum: schräger Stamm, breite rosa Krone, hängende Blätter. */
function cherryTree(R: Raster, x: number, ground: number, bs: number, seed: number, flip = false, shade = 0, to: Rgb = [0, 0, 0]) {
  const r = rng(seed)
  const dir = flip ? -1 : 1
  let cx = x
  let cy = ground - bs
  for (let i = 0; i < 5; i++) {
    R.block(cx, cy, bs, TEX.cherryLog!, seed + i, shade, to)
    if (i % 2 === 1) {
      cx += dir * bs
      R.block(cx, cy, bs, TEX.cherryLog!, seed + i + 50, shade, to)
    }
    cy -= bs
  }
  const rows = [3, 5, 7, 7, 5]
  const top = cy - 3 * bs
  const placed = new Set<string>()
  rows.forEach((w, i) => {
    for (let j = -(w >> 1); j <= w >> 1; j++) {
      if (Math.abs(j) === w >> 1 && (i === 0 || i === rows.length - 1 || r() < 0.35)) continue
      R.block(cx + j * bs, top + i * bs, bs, TEX.cherry!, seed + i * 17 + j, clamp(shade + (i / rows.length) * 0.18), to)
      placed.add(`${i},${j}`)
    }
  })
  rimLight(R, placed, cx, top, bs, shade)
  // hängende Blätter unter der Krone
  for (let j = -2; j <= 2; j++) {
    if (r() < 0.45) continue
    const hx = cx + j * bs
    const hy = top + rows.length * bs
    const len = 1 + Math.floor(r() * 2)
    for (let q = 0; q < len * bs; q++) {
      for (let p = 0; p < bs; p++) {
        if (nh(p, q, seed + j) < 0.45 || p % 3 === 0) continue
        R.set(hx + p, hy + q, mix(pick(C.cherry, nh(p, q, seed + 9 + j)), to, clamp(shade + 0.22)))
      }
    }
  }
}

/** Süßbeerenstrauch (Minecraft, ausgewachsen mit Beeren). */
function berryBush(R: Raster, x: number, ground: number, seed: number) {
  for (let y = -15; y <= -1; y++) {
    for (let xx = 0; xx < 16; xx++) {
      const d = Math.hypot((xx - 7.5) / 8, (y + 7) / 8.5)
      const n = nh(xx, y, seed)
      if (d > 1 - (n - 0.5) * 0.25) continue
      if (n < 0.08) continue
      R.set(x + xx, ground + y, pick(C.berryLeaf, nh(xx, y, seed + 1) * (y < -8 ? 1 : 0.8)))
    }
  }
  const r = rng(seed)
  for (let i = 0; i < 9; i++) {
    const bx = x + 2 + Math.floor(r() * 12)
    const by = ground - 3 - Math.floor(r() * 10)
    R.set(bx, by, H(C.berry))
    R.set(bx + 1, by, H(C.berry))
    R.set(bx, by + 1, H('#8a0a1e'))
    R.set(bx + 1, by + 1, H(C.berry))
    R.set(bx, by, H(C.berryHi))
  }
}

export class FoxdenScene extends IntroStage {
  private readonly c: FoxdenCues
  private readonly fam: FamilyCues
  private readonly sky: Cv
  private readonly far: Layer[] = []
  private readonly near: Layer
  private readonly fore: Layer
  private readonly clouds: { cv: Cv; x: number; y: number }[] = []
  private readonly foxes = new Map<string, Cv>()
  private wc: Cv = canvas(4, 4)
  private wg: CanvasRenderingContext2D = ctx2d(this.wc)
  private light: Cv = canvas(4, 4)
  private lg: CanvasRenderingContext2D = ctx2d(this.light)
  private tmp: Cv = canvas(4, 4)
  private tg: CanvasRenderingContext2D = ctx2d(this.tmp)

  constructor(cv: HTMLCanvasElement, readonly design: IntroDesign, readonly length: IntroLength, options: IntroStageOptions = {}) {
    const cues = FOXDEN_CUES[length]
    super(cv, design.palette, cues, options)
    this.c = cues
    this.fam = familyCues(cues)
    this.sky = this.paintSky()
    this.paintClouds()
    // Luftperspektive: je weiter weg, desto kleiner die Blöcke und desto rosiger/heller im Dunst
    this.far.push(this.paintHills(0.12, 4, -12, 24, H('#b4607a'), H('#d07a82'), 1))
    this.far.push(this.paintHills(0.22, 3, -6, 12, H('#8a4566'), H('#a85a72'), 2, 4))
    this.far.push(this.paintForest(0.4, 3, -2, 5, 10, H('#7a3a5a'), 0.62, 7, 0.14, 2))
    this.far.push(this.paintForest(0.65, 5, -6, 5, 9, H('#5a2a48'), 0.48, 11, 0.22, 3))
    this.near = this.paintNear()
    this.fore = this.paintFore()
  }

  protected override onArtResized(w: number, h: number) {
    this.wc = canvas(w, h)
    this.wg = ctx2d(this.wc)
    this.light = canvas(w, h)
    this.lg = ctx2d(this.light)
    this.tmp = canvas(w, h)
    this.tg = ctx2d(this.tmp)
  }

  private foxSprite(look: FoxLook, silhouette: boolean): Cv {
    const key = `${look.pose}|${look.kit ? 1 : 0}|${look.frame & 1}|${look.berries ? 1 : 0}|${silhouette ? 1 : 0}`
    let c = this.foxes.get(key)
    if (!c) {
      c = foxCanvas(look.pose === 'tumble' ? { ...look, pose: 'crouch' } : look, silhouette)
      this.foxes.set(key, c)
    }
    return c
  }

  // ───────────────────────── Malen (einmal) ─────────────────────────

  /** Abendhimmel (Kachel 32 px, gerastert), Parallaxe 0,2: oben Violett, am Horizont Orange-Gold. */
  private paintSky(): Cv {
    const y0 = -340
    const h = 350
    const R = new Raster(32, h, 0, y0)
    const n = SKY.length - 1
    for (let y = y0; y < y0 + h; y++) {
      const lin = clamp((y - y0) / (-6 - y0))
      const pos = lin ** 1.6 * n
      const i = Math.min(n - 1, Math.floor(pos))
      const f = clamp((pos - i - 0.5) * 3 + 0.5)
      for (let x = 0; x < 32; x++) R.set(x, y, f > bayer(x, y) ? SKY[i + 1]! : SKY[i]!)
    }
    return R.canvas()
  }

  /** Minecraft-Wolken: flache Blockplatten, unten vom Abendlicht angestrahlt. */
  private paintClouds() {
    const r = rng(41)
    const lit = H('#ffe2c4')
    const body = H('#f4ac92')
    const under = H('#c8687a')
    for (let i = 0; i < 8; i++) {
      // Minecraft-Wolken: flache Platten aus einem Zellenraster (12×4 px je Zelle)
      const cols = 5 + Math.floor(r() * 9)
      const rows = 2 + Math.floor(r() * 2)
      const cw = 12
      const ch = 4
      const on = (c: number, q: number) => c >= 0 && q >= 0 && c < cols && q < rows && nh(c, q, i * 13) > 0.22 + 0.5 * Math.abs(c / cols - 0.5) + (q === rows - 1 ? 0.15 : 0)
      const R = new Raster(cols * cw, rows * ch + 2, 0, 0)
      for (let c = 0; c < cols; c++) {
        for (let q = 0; q < rows; q++) {
          if (!on(c, q)) continue
          for (let y = 0; y < ch; y++) {
            for (let x = 0; x < cw; x++) {
              const top = y === 0 && !on(c, q - 1)
              const bot = y === ch - 1 && !on(c, q + 1)
              R.set(c * cw + x, q * ch + y, top ? lit : bot ? under : body, 230)
            }
          }
          if (!on(c, q + 1)) for (let x = 0; x < cw; x++) R.set(c * cw + x, q * ch + ch, under, 150)
        }
      }
      this.clouds.push({ cv: R.canvas(), x: -320 + i * 170 + r() * 90, y: -150 + r() * 95 })
    }
  }

  /** Blockige Hügelkette im Dunst (Stufen in Blockgröße), oben mit kleinen Fichten-Schattenrissen. */
  private paintHills(par: number, bs: number, base: number, amp: number, col: Rgb, rim: Rgb, seed: number, spruces = 0): Layer {
    const x0 = -480
    const w = 1500
    const R = new Raster(w, 330, x0, -150)
    const tops: number[] = []
    for (let bx = x0; bx < x0 + w; bx += bs) {
      const hgt = Math.round((amp * (0.55 + 0.45 * Math.sin(bx * 0.006 + seed) * Math.sin(bx * 0.017 + seed * 2)) + (nh(bx, 0, seed) - 0.5) * 5) / bs) * bs
      const top = base - Math.max(bs, hgt)
      tops.push(top)
      for (let y = top; y < 180; y++) for (let x = 0; x < bs; x++) R.set(bx + x, y, y === top ? rim : col)
    }
    if (spruces) {
      const clear = SUN.x + par * 530
      tops.forEach((top, i) => {
        const bx = x0 + i * bs
        if (Math.abs(bx - clear) < 26 || nh(bx, 3, seed) > 0.3) return
        spruceSil(R, bx, top, bs, spruces + Math.floor(nh(bx, 4, seed) * 4), col, rim)
      })
    }
    return layerOf(R, par)
  }

  /** Waldband: Minecraft-Fichten (und einzelne Kirschbäume) in kleiner Blockgröße, mit Dunst getönt. */
  private paintForest(par: number, bs: number, ground: number, hMin: number, hMax: number, to: Rgb, shade: number, seed: number, cherryRate: number, gap: number): Layer {
    const x0 = -520
    const w = 1700
    const R = new Raster(w, 440, x0, -240)
    const r = rng(seed)
    // Boden
    for (let x = x0; x < x0 + w; x += bs) for (let y = ground; y < 200; y += bs) R.block(x, y, bs, y === ground ? TEX.grass! : TEX.dirt!, seed + x, clamp(shade + 0.1 + (y - ground) / 120), to)
    // Lichtung dort, wo die Sonne am Ende steht (damit sie nicht verdeckt wird)
    const clear = SUN.x + par * 530
    for (let x = x0 + bs * 2; x < x0 + w; x += bs * (gap + Math.floor(r() * (gap + 3)))) {
      const sx = Math.round(x / bs) * bs
      if (Math.abs(sx - clear) < 70 + 60 * par) continue
      if (r() < cherryRate) cherryTree(R, sx, ground, bs, Math.floor(r() * 1e6), r() > 0.5, shade, to)
      else spruce(R, sx, ground, bs, hMin + Math.floor(r() * (hMax - hMin)), Math.floor(r() * 1e6), shade, to)
    }
    return layerOf(R, par)
  }

  /** Vordergrund-Welt: Boden, Hügel mit Fuchsbau zwischen Wurzeln, Fichten, Kirschbaum, Süßbeeren, Zaunpfahl. */
  private paintNear(): Layer {
    const bs = 16
    const x0 = -320
    const w = 1300
    const y0 = -240
    const R = new Raster(w, 300, x0, y0)
    const groundTop = (bx: number) => {
      if (bx >= 304 && bx < 432) return -32
      if (bx === 288 || bx === 432) return -16
      return 0
    }
    for (let bx = x0; bx < x0 + w; bx += bs) {
      const top = groundTop(bx)
      for (let y = top; y < 60; y += bs) R.block(bx, y, bs, y === top ? TEX.grass! : TEX.dirt!, bx * 7 + y, clamp((y - top) / 52) * 0.55, H('#1a0e0a'))
    }
    // Fuchsbau: dunkles Loch im Hügel, umrahmt von Wurzeln
    const [h0, h1] = DEN.hole
    for (let y = -26; y <= 0; y++) {
      for (let x = h0; x <= h1; x++) {
        const u = (x - (h0 + h1) / 2) / ((h1 - h0) / 2)
        const arch = -26 + Math.round(10 * u * u)
        if (y < arch) continue
        const depth = clamp((y - arch) / 6) * (1 - Math.abs(u) * 0.4)
        R.set(x, y, mix(H('#3a2418'), H('#0e0806'), depth))
      }
    }
    const root = H('#4e3420')
    const rootD = H('#2c1c0e')
    const roots: [number, number, number, number][] = [
      [h0 - 6, -30, h0 + 2, 0],
      [h1 + 6, -30, h1 - 2, 0],
      [h0 + 4, -30, h0 + 14, -22],
      [h1 - 4, -31, h1 - 16, -23],
      [DEN.x + 30, -32, DEN.x + 44, -8],
    ]
    for (const [ax, ay, bx2, by2] of roots) {
      const n = Math.ceil(Math.hypot(bx2 - ax, by2 - ay))
      for (let i = 0; i <= n; i++) {
        const t = i / n
        const x = Math.round(lerp(ax, bx2, t) + Math.sin(t * 5) * 1.5)
        const y = Math.round(lerp(ay, by2, t))
        const wdt = t < 0.5 ? 3 : 2
        for (let q = 0; q < wdt; q++) R.set(x + q, y, q === 0 ? root : rootD)
      }
    }
    // Pflanzen: Gras, Farn, rosa Blütenteppich nahe dem Kirschbaum
    for (let x = x0; x < x0 + w; x += 3) {
      const top = groundTop(Math.floor(x / bs) * bs)
      if (x > h0 - 8 && x < h1 + 8 && top === -32) continue
      const n = nh(x, 1, 3)
      if (n < 0.35) {
        const hh = 2 + Math.floor(nh(x, 2, 3) * 5)
        for (let j = 1; j <= hh; j++) R.set(x + (j > 3 ? 1 : 0), top - j, j === hh ? C.grass[2]! : C.grass[1]!)
      }
      if (x > 540 && x < 720 && nh(x, 3, 3) < 0.5) R.set(x, top - 1, pick(C.cherry, nh(x, 4, 3)))
    }
    // Zaunpfahl (Fichtenholz) für die Laterne
    for (let y = -16; y < 0; y++) for (let x = 0; x < 4; x++) R.set(LANTERN_POST.x + x, y, x === 0 ? C.spruceLog[2]! : x === 3 ? C.spruceLog[0]! : C.spruceLog[1]!)
    // Bäume: Fichte auf dem Bau, Rahmen-Fichten, Kirschbaum über den Büschen
    spruce(R, 400, -32, bs, 7, 901)
    spruce(R, 16, 0, bs, 11, 902)
    spruce(R, 160, 0, bs, 7, 905)
    spruce(R, 752, 0, bs, 10, 903)
    cherryTree(R, 690, 0, bs, 904, true)
    for (const bx of BUSHES) berryBush(R, bx - 8, 0, bx)
    berryBush(R, 520, 0, 17)
    return layerOf(R, 1)
  }

  /** Vordergrund ganz vorn: Minecraft-Gras und -Farn in Büscheln (unten), im Gegenlicht dunkel mit warmen Spitzen. */
  private paintFore(): Layer {
    const R = new Raster(1800, 50, -500, -48)
    const r = rng(61)
    const dark = H('#141c10')
    const mid = H('#1f2c16')
    const tip = H('#b8683c')
    for (let cx = -480; cx < 1280; cx += 180 + Math.floor(r() * 220)) {
      if (cx > 170 && cx < 840) continue
      const plants = 2 + Math.floor(r() * 4)
      for (let p = 0; p < plants; p++) {
        const px = cx + p * 16 + Math.floor(r() * 6)
        const tall = r() > 0.55
        for (let x = 0; x < 16; x++) {
          if (nh(x, p, cx) < 0.35) continue
          const h = Math.floor((tall ? 14 : 6) + nh(x, 1, cx + p) * (tall ? 22 : 9))
          for (let j = 0; j < h; j++) R.set(px + x, -1 - j, j >= h - 2 ? tip : j > h * 0.5 ? mid : dark)
        }
      }
    }
    return layerOf(R, 1.32)
  }

  // ───────────────────────── Bild ─────────────────────────

  private at(v: ArtView, x: number, y: number, par = 1) {
    return { x: Math.round(x - v.cx * par + (v.cx - v.ox)), y: Math.round(y - v.cy * par + (v.cy - v.oy)) }
  }

  private draw(g: CanvasRenderingContext2D, v: ArtView, l: Layer) {
    const p = this.at(v, l.x0, l.y0, l.par)
    if (p.x > v.bw || p.y > v.bh || p.x + l.cv.width < 0 || p.y + l.cv.height < 0) return
    g.drawImage(l.cv, p.x, p.y)
  }

  /** Dämmerung 0 (hell) … 1 (spät): die Sonne sinkt, das Lagerfeuer wird wichtiger. */
  private dusk(t: number) {
    return smooth(range(t, -2, this.c.impact + 1))
  }

  protected renderScene(t: number) {
    const c = this.c
    const [cx0, cy0, zoom] = camAt(c.cam, t)
    const punch = t >= c.impact ? (this.calm ? 0.05 : 0.1) * decay(t - c.impact, 4) : 0
    const v = this.beginArt(200 / (zoom * (1 + punch)), cx0, cy0)
    const lights: Light[] = []
    if (t <= c.logo + 0.3) {
      this.drawSky(t, v)
      for (const l of this.far) this.draw(this.ag, v, l)
      this.buildLights(t, v, lights)
      this.drawWorld(t, v)
      this.drawEmissive(t, v)
      this.drawParticles(t, v)
      this.draw(this.ag, v, this.fore)
      this.blitArt(v)
      this.drawBloom(v, lights)
      this.drawScreenFx(t, v)
    }
    this.drawPost('#7a3a5a', '#ffc27a', 0.68)
    const P = this.gatherScreen(t, v)
    const R = this.clusterRadius(t) * v.S
    const origin = (i: number, j: number) => ({ x: P.x + ((i + 0.5) / 16 - 0.5) * 2 * R, y: P.y + ((j + 0.5) / 16 - 0.5) * 2 * R, s: Math.max(2, (R * 2) / 16) })
    this.drawFinale(t, origin, '#ffc27a', '#fff0d8')
    this.drawAfterglow(t, P)
  }

  // ── Himmel: Verlauf, Sonne, Wolken ──

  private drawSky(t: number, v: ArtView) {
    const ag = this.ag
    const top = this.at(v, 0, -340, 0.2).y
    ag.fillStyle = css(SKY[0]!)
    ag.fillRect(0, 0, v.bw, Math.max(0, top))
    ag.fillStyle = css(SKY[SKY.length - 1]!)
    ag.fillRect(0, top + this.sky.height, v.bw, v.bh)
    for (let x = 0; x < v.bw; x += 32) ag.drawImage(this.sky, x, top)
    // erste Sterne oben (stehend im ruhigen Modus)
    const dusk = this.dusk(t)
    for (let i = 0; i < 70; i++) {
      const s = this.at(v, -400 + hash2(i, 1) * 1500, -340 + hash2(i, 2) * 150, 0.05)
      if (s.x < 0 || s.y < 0 || s.x >= v.bw || s.y >= v.bh) continue
      const tw = this.calm ? 0.8 : 0.6 + 0.4 * Math.sin(t * (1 + hash2(i, 3) * 2) + i)
      ag.globalAlpha = (0.2 + 0.5 * dusk) * tw * hash2(i, 4)
      ag.fillStyle = '#ffe8d8'
      ag.fillRect(s.x, s.y, 1, 1)
    }
    ag.globalAlpha = 1
    // eckige Minecraft-Sonne mit Hof
    const sp = sunAt(this.c, t)
    const s = this.at(v, sp.x, sp.y, SUN.par)
    const hs = SUN.half
    ag.globalCompositeOperation = 'lighter'
    ag.globalAlpha = 0.55
    ag.drawImage(this.glowSprite(C.sunGlow), s.x - hs * 6, s.y - hs * 6, hs * 12, hs * 12)
    ag.globalAlpha = 0.6
    ag.drawImage(this.glowSprite(C.sun), s.x - hs * 2.4, s.y - hs * 2.4, hs * 4.8, hs * 4.8)
    ag.globalAlpha = 1
    ag.globalCompositeOperation = 'source-over'
    ag.fillStyle = C.sun
    ag.fillRect(s.x - hs, s.y - hs, hs * 2, hs * 2)
    ag.fillStyle = C.sunCore
    ag.fillRect(s.x - hs + 4, s.y - hs + 4, hs * 2 - 8, hs * 2 - 8)
    // Wolken (ziehen langsam)
    for (const cl of this.clouds) {
      const q = this.at(v, cl.x + t * 4, cl.y, 0.1)
      if (q.x > v.bw || q.x + cl.cv.width < 0) continue
      ag.drawImage(cl.cv, q.x, q.y)
    }
  }

  // ── Licht ──

  private buildLights(t: number, v: ArtView, lights: Light[]) {
    const c = this.c
    const add = (x: number, y: number, r: number, color: string, a: number, bloom = 0, par = 1) => {
      const q = this.at(v, x, y, par)
      lights.push({ x: q.x, y: q.y, r, color, a, bloom })
    }
    const dusk = this.dusk(t)
    const sp = sunAt(c, t)
    // Gegenlicht der Sonne (warmer Saum oben)
    add(sp.x, sp.y, 230, '#ffb070', 0.32 - 0.1 * dusk, 0, SUN.par)
    const fire = fireGlow(c.fire, t, 1, this.calm)
    if (fire > 0) add(CAMPFIRE.x + 8, -8, 150, C.fire, 1.0 * Math.min(1.25, fire), 0.65)
    const lan = fireGlow(c.lantern, t, 4, this.calm)
    if (lan > 0) add(LANTERN_POST.x + 2, -21, 90, '#ffc060', 0.9 * Math.min(1.2, lan), 0.5)
    for (const f of this.fireflies(t)) add(f.x, f.y, 12, C.flyGlow, 0.35 * f.a, 0.3)
    const g = this.gatherK(t)
    const P = this.gatherScreen(t, v)
    const pq = { x: (P.x - this.W / 2) / v.S + v.cx - v.ox, y: (P.y - this.H / 2) / v.S + v.cy - v.oy }
    if (g > 0 && t < c.impact) lights.push({ x: pq.x, y: pq.y, r: 30 + 60 * g, color: '#ffc27a', a: 0.6 * g, bloom: 0.5 * g })
    if (t >= c.impact) {
      const kk = easeOut(range(t, c.impact, c.impact + (this.calm ? 1.1 : 0.5)))
      lights.push({ x: pq.x, y: pq.y, r: 60 + 520 * kk, color: '#ffd8a0', a: this.calm ? 0.9 : 1.4, bloom: this.calm ? 0.6 : 1.1 })
    }
    const lg = this.lg
    lg.setTransform(1, 0, 0, 1, 0, 0)
    lg.globalAlpha = 1
    lg.globalCompositeOperation = 'source-over'
    // Grundlicht: warme Dämmerung, wird zum Abend hin dunkler und violetter
    lg.fillStyle = css(mix(H('#e4b4a8'), H('#7a5a80'), dusk))
    lg.fillRect(0, 0, v.bw, v.bh)
    lg.globalCompositeOperation = 'lighter'
    for (const l of lights) {
      if (l.a <= 0.01) continue
      if (l.x + l.r < 0 || l.y + l.r < 0 || l.x - l.r > v.bw || l.y - l.r > v.bh) continue
      const col = H(l.color)
      const g2 = lg.createRadialGradient(l.x, l.y, 0, l.x, l.y, l.r)
      g2.addColorStop(0, css(col, Math.min(1, l.a)))
      g2.addColorStop(0.25, css(col, Math.min(1, l.a * 0.62)))
      g2.addColorStop(0.6, css(col, l.a * 0.2))
      g2.addColorStop(1, css(col, 0))
      lg.fillStyle = g2
      lg.fillRect(l.x - l.r, l.y - l.r, l.r * 2, l.r * 2)
    }
    lg.globalCompositeOperation = 'source-over'
  }

  /** Inhalt von `wc` mit der Lichtkarte multiplizieren und auf den Kunst-Puffer legen. */
  private applyLit(v: ArtView) {
    const tg = this.tg
    tg.setTransform(1, 0, 0, 1, 0, 0)
    tg.globalAlpha = 1
    tg.globalCompositeOperation = 'copy'
    tg.drawImage(this.light, 0, 0)
    tg.globalCompositeOperation = 'destination-in'
    tg.drawImage(this.wc, 0, 0)
    tg.globalCompositeOperation = 'source-over'
    const wg = this.wg
    wg.globalCompositeOperation = 'multiply'
    wg.drawImage(this.tmp, 0, 0, v.bw, v.bh, 0, 0, v.bw, v.bh)
    wg.globalCompositeOperation = 'source-over'
    this.ag.drawImage(this.wc, 0, 0)
  }

  // ── Welt (beleuchtet): Boden, Bäume, Bau, Lagerfeuer-Holz, Laterne, Füchse ──

  private drawWorld(t: number, v: ArtView) {
    const wg = this.wg
    wg.setTransform(1, 0, 0, 1, 0, 0)
    wg.globalAlpha = 1
    wg.globalCompositeOperation = 'source-over'
    wg.imageSmoothingEnabled = false
    wg.clearRect(0, 0, v.bw, v.bh)
    this.draw(wg, v, this.near)
    // abgelegte Süßbeeren (hüpfen kurz)
    const bb = this.c.berries
    if (bb && t >= bb[1]) {
      const age = t - bb[1]
      const hop = Math.round(Math.abs(Math.sin(age * 9)) * 5 * Math.exp(-age * 4))
      const q = this.at(v, BERRY_DROP.x + 2, -2 - hop)
      const pix: [number, number, string][] = [
        [0, 0, C.berry], [1, 0, C.berry], [0, 1, '#8a0a1e'], [1, 1, C.berry], [0, 0, C.berryHi],
        [3, 1, C.berry], [4, 1, C.berry], [3, 2, '#8a0a1e'], [4, 2, C.berry], [3, 1, C.berryHi],
        [2, -1, '#3f6a2a'], [1, 2, C.berry], [2, 2, '#8a0a1e'],
      ]
      for (const [dx, dy, col] of pix) {
        wg.fillStyle = col
        wg.fillRect(q.x + dx, q.y + dy, 1, 1)
      }
    }
    // Busch wackelt nach dem Pounce
    const p2 = this.c.pounce[2]
    if (t > p2 && t < p2 + 0.8) {
      const q = this.at(v, BUSHES[0] - 8, -16)
      const wob = Math.round(Math.sin((t - p2) * 40) * 1.5 * (1 - (t - p2) / 0.8))
      wg.drawImage(this.near.cv, BUSHES[0] - 8 - this.near.x0, -16 - this.near.y0, 16, 16, q.x + wob, q.y, 16, 16)
    }
    // Lagerfeuer: gekreuzte Stämme
    const cf = this.at(v, CAMPFIRE.x, 0)
    const logs: [number, number, number, string][] = [
      [0, -3, 16, '#3b2614'],
      [2, -5, 12, '#4e3420'],
      [1, -2, 14, '#2c1c0e'],
    ]
    for (const [dx, dy, w, col] of logs) {
      wg.fillStyle = col
      wg.fillRect(cf.x + dx, cf.y + dy, w, 2)
      wg.fillStyle = '#6a4a2c'
      wg.fillRect(cf.x + dx, cf.y + dy, 1, 2)
      wg.fillRect(cf.x + dx + w - 1, cf.y + dy, 1, 2)
    }
    // Laterne auf dem Pfahl (Rahmen)
    const lp = this.at(v, LANTERN_POST.x - 1, -24)
    wg.fillStyle = '#2a2a32'
    wg.fillRect(lp.x, lp.y, 6, 8)
    wg.fillRect(lp.x + 2, lp.y - 2, 2, 2)
    wg.fillStyle = '#4a3a30'
    wg.fillRect(lp.x + 1, lp.y + 2, 4, 4)
    // Füchse (nicht im Sprung)
    for (const f of familyAt(this.fam, t)) {
      if (f.airborne) continue
      if (f.id === 'hunter' && f.look.pose === 'peek') continue
      this.drawFox(wg, v, f.x, f.y, f.look, f.flip, f.rot, false, f.shade)
    }
    this.applyLit(v)
  }

  private drawFox(g: CanvasRenderingContext2D, v: ArtView, x: number, y: number, look: FoxLook, flip: boolean, rot: number, silhouette: boolean, shade = 0) {
    const spr = this.foxSprite(look, silhouette)
    const q = this.at(v, x, y)
    if (q.x < -40 || q.x > v.bw + 40) return
    g.save()
    g.translate(q.x, q.y)
    if (flip) g.scale(-1, 1)
    if (look.pose === 'tumble') {
      g.translate(0, -5)
      g.rotate(rot)
      g.translate(0, 5)
    } else if (rot) g.rotate(rot)
    if (shade > 0) g.globalAlpha = 1 - shade * 0.7
    g.drawImage(spr, -FOX_OX, -FOX_OY)
    g.restore()
  }

  // ── Leuchtendes: Flammen, Laternenglas, Glühwürmchen, Fuchs im Gegenlicht ──

  private fireflies(t: number) {
    const out: { x: number; y: number; a: number }[] = []
    const wake = this.c.fire < 0 ? 1 : smooth(range(t, this.c.fire, this.c.fire + 2.5))
    const n = Math.round(4 + 24 * wake)
    for (let i = 0; i < n; i++) {
      const bx = 120 + hash2(i, 31) * 560
      const by = -46 + hash2(i, 32) * 42
      const sp = 0.5 + hash2(i, 33)
      const ph = hash2(i, 34) * 20
      const a = this.calm ? 0.55 + 0.15 * Math.sin(t * 0.8 + ph) : Math.max(0, Math.sin(t * sp * 1.6 + ph))
      if (a < 0.08) continue
      out.push({ x: bx + Math.sin(t * 0.6 * sp + ph) * 9, y: by + Math.cos(t * 0.8 * sp + ph * 1.3) * 5, a })
    }
    return out
  }

  private drawEmissive(t: number, v: ArtView) {
    const ag = this.ag
    const c = this.c
    // Lagerfeuer-Flammen (Minecraft-artig, gestuft)
    const fire = fireGlow(c.fire, t, 1, this.calm)
    if (fire > 0) {
      const cf = this.at(v, CAMPFIRE.x, 0)
      const hgt = Math.min(1.15, fire)
      for (let x = 2; x < 14; x++) {
        const col = Math.abs(x - 7.5)
        const ph = this.calm ? 0 : Math.sin(t * 11 + x * 1.7) * 1.5 + Math.sin(t * 6.3 + x)
        const h = Math.max(0, Math.round((9 - col * 1.2 + ph) * hgt))
        for (let y = 0; y < h; y++) {
          const k = y / Math.max(1, h)
          ag.fillStyle = k < 0.35 ? '#fff2a8' : k < 0.7 ? '#ffb340' : '#ff6a1a'
          ag.fillRect(cf.x + x, cf.y - 5 - y, 1, 1)
        }
      }
      ag.fillStyle = '#ff8a2a'
      ag.fillRect(cf.x + 3, cf.y - 4, 10, 1)
      ag.globalCompositeOperation = 'lighter'
      ag.globalAlpha = 0.6 * clamp(fire)
      ag.drawImage(this.glowSprite('#ff9a40'), cf.x - 16, cf.y - 34, 48, 44)
      ag.globalAlpha = 1
      ag.globalCompositeOperation = 'source-over'
    }
    // Laternenglas
    const lan = fireGlow(c.lantern, t, 4, this.calm)
    if (lan > 0) {
      const lp = this.at(v, LANTERN_POST.x - 1, -24)
      ag.globalAlpha = clamp(lan)
      ag.fillStyle = '#ffb347'
      ag.fillRect(lp.x + 1, lp.y + 2, 4, 4)
      ag.fillStyle = '#fff0b0'
      ag.fillRect(lp.x + 2, lp.y + 3, 2, 2)
      ag.globalCompositeOperation = 'lighter'
      ag.globalAlpha = 0.5 * clamp(lan)
      ag.drawImage(this.glowSprite('#ffc060'), lp.x - 9, lp.y - 8, 24, 24)
      ag.globalCompositeOperation = 'source-over'
      ag.globalAlpha = 1
    }
    // Glühwürmchen
    ag.globalCompositeOperation = 'lighter'
    for (const f of this.fireflies(t)) {
      const q = this.at(v, f.x, f.y)
      if (q.x < -4 || q.y < -4 || q.x > v.bw + 4 || q.y > v.bh + 4) continue
      ag.globalAlpha = 0.35 * f.a
      ag.drawImage(this.glowSprite(C.flyGlow), q.x - 4, q.y - 4, 9, 9)
      ag.globalAlpha = f.a
      ag.fillStyle = C.fly
      ag.fillRect(q.x, q.y, 1, 1)
    }
    ag.globalAlpha = 1
    ag.globalCompositeOperation = 'source-over'
    // Fuchs im Pounce: Schattenriss mit warmem Saum vor der Sonne; schaut danach mit Beere aus dem Busch
    for (const f of familyAt(this.fam, t)) {
      if (f.airborne) this.drawFox(ag, v, f.x, f.y, f.look, f.flip, f.rot, true)
      else if (f.id === 'hunter' && f.look.pose === 'peek') {
        // hinter den Blättern: Kopf über dem Busch
        this.drawFox(ag, v, f.x, f.y, f.look, f.flip, 0, false)
      }
    }
  }

  // ── Teilchen in der Welt: Rauch, Funken, Kirschblüten, Beeren-Ausbruch ──

  private drawParticles(t: number, v: ArtView) {
    const ag = this.ag
    const c = this.c
    const fire = fireGlow(c.fire, t, 1, this.calm)
    const gust = gustAt(c, t)
    // Lagerfeuer-Rauch (graue Quadrate steigen hoch auf) und Funken
    if (fire > 0) {
      const age0 = c.fire < 0 ? 99 : t - c.fire
      for (let i = 0; i < 16; i++) {
        const life = 3.2
        const ph = ((t + hash(i + 100) * life) % life) / life
        if (age0 < ph * life) continue
        const x = CAMPFIRE.x + 6 + Math.sin(ph * 5 + i) * 3 + ph * 14 + gust * ph * 30
        const y = -8 - ph * 120
        const q = this.at(v, x, y)
        const s = 2 + Math.floor(ph * 4)
        ag.globalAlpha = 0.32 * (1 - ph) * Math.min(1, age0)
        ag.fillStyle = ph < 0.2 ? '#9a8a88' : '#7a6a74'
        ag.fillRect(q.x, q.y, s, s)
      }
      ag.globalCompositeOperation = 'lighter'
      for (let i = 0; i < 12; i++) {
        const life = 1.1
        const ph = ((t * (0.9 + hash(i + 120) * 0.4) + hash(i + 121) * life) % life) / life
        const q = this.at(v, CAMPFIRE.x + 4 + hash(i + 122) * 8 + Math.sin(t * 5 + i) * 3, -8 - ph * 46)
        ag.globalAlpha = (1 - ph) * clamp(fire) * (this.calm ? 0.6 : 0.5 + 0.5 * Math.sin(t * 20 + i))
        ag.fillStyle = ph < 0.4 ? '#ffd27a' : '#ff7a2a'
        ag.fillRect(q.x, q.y, 1, 1)
      }
      ag.globalCompositeOperation = 'source-over'
      ag.globalAlpha = 1
    }
    // Kirschblüten rieseln vom Kirschbaum (wenige) + mit dem Windstoß mehr
    for (let i = 0; i < 26 + Math.round(gust * 40); i++) {
      const life = 4
      const ph = ((t * 0.5 + hash(i + 200)) % 1)
      const x = 560 + hash(i + 201) * 170 + ph * 40 + Math.sin(t * 1.5 + i) * 5 + gust * 80 * ph
      const y = -120 + ph * life * 32
      const q = this.at(v, x, y)
      ag.fillStyle = PINK[1 + (i % 3)]!
      ag.fillRect(q.x, q.y, Math.sin(t * 3 + i) > -0.3 ? 2 : 1, 1)
    }
    // Ausbruch aus dem Busch: Beeren, Blätter, Blüten fliegen hoch
    const p2 = c.pounce[2]
    if (t >= p2) {
      const age = t - p2
      const gk = this.gatherK(t)
      const P = this.gatherScreen(t, v)
      const pq = { x: (P.x - this.W / 2) / v.S + v.cx - v.ox, y: (P.y - this.H / 2) / v.S + v.cy - v.oy }
      for (let i = 0; i < 64; i++) {
        const ang = -Math.PI / 2 + (hash(i + 300) - 0.5) * 2.6
        const sp = 80 + hash(i + 301) * 150
        const x0 = BUSHES[0] + (hash(i + 302) - 0.5) * 12
        let x = x0 + Math.cos(ang) * sp * age * 0.8 + Math.sin(age * 4 + i) * 4 * age
        let y = -8 + Math.sin(ang) * sp * age + 60 * age * age
        const q = this.at(v, x, y)
        x = q.x
        y = q.y
        if (gk > 0) {
          const kk = easeIn(range(t, c.gather + hash(i + 303) * 0.2, c.gather + 0.55 + hash(i + 303) * 0.25))
          if (kk >= 1) continue
          x = lerp(x, pq.x, kk)
          y = lerp(y, pq.y, kk)
        }
        const kind = i % 3
        ag.fillStyle = kind === 0 ? C.berry : kind === 1 ? css(C.berryLeaf[2]!) : PINK[2]!
        ag.fillRect(Math.round(x), Math.round(y), kind === 0 ? 2 : 2, kind === 0 ? 2 : 1)
        if (kind === 0) {
          ag.fillStyle = C.berryHi
          ag.fillRect(Math.round(x), Math.round(y), 1, 1)
        }
      }
    }
  }

  // ── Sammeln vor der Sonne → Aufblühen ──

  private gatherK(t: number) {
    return smooth(range(t, this.c.gather, this.c.impact))
  }

  private clusterRadius(t: number) {
    const c = this.c
    const g = this.gatherK(t)
    const open = t >= c.impact ? easeOut(range(t, c.impact, c.impact + 0.35)) : 0
    return 2 + 5 * g + 6 * open
  }

  /** Sammelpunkt (Bildschirm) – vor der Sonne. */
  private gatherScreen(t: number, v: ArtView) {
    const sp = sunAt(this.c, Math.min(t, this.c.impact))
    const q = this.at(v, sp.x, sp.y, SUN.par)
    return { x: (q.x + v.ox - v.cx) * v.S + this.W / 2, y: (q.y + v.oy - v.cy) * v.S + this.H / 2 }
  }

  /** Bildschirm-Effekte: Blätterwirbel auf die Kamera zu, Sammeln in den glühenden Kern. */
  private drawScreenFx(t: number, v: ArtView) {
    const c = this.c
    const { W, H: Hh } = this
    const u = Hh / 720
    const P = this.gatherScreen(t, v)
    const gk = this.gatherK(t)
    const p2 = c.pounce[2]
    const cols = [PINK[2]!, css(C.berryLeaf[2]!), PINK[3]!, '#ffd27a', C.berry, PINK[1]!]
    // ein paar große, unscharfe Blätter ganz nah (Tiefe)
    for (let i = 0; i < 7; i++) {
      const par = 1.9 + hash(i + 400) * 1.1
      const span = W + 300
      const x = ((hash(i + 401) * 5000 + t * 18 * par * u - v.cx * v.S * (par - 1) * 0.4) % span + span) % span - 150
      const y = ((hash(i + 402) * 3000 + t * 22 * par * u - v.cy * v.S * (par - 1) * 0.4) % (Hh + 300) + Hh + 300) % (Hh + 300) - 150
      this.leaf(x, y, Math.round((3 + hash(i + 403) * 3) * u * par), PINK[2 + (i % 2)]!, 0.5 * (1 - gk), t * 2 + i)
    }
    // Wirbel nach dem Pounce: auf die Kamera zu
    if (t > p2 && t < c.impact) {
      for (let i = 0; i < 110; i++) {
        const ts = p2 + hash(i + 500) * 0.5
        const life = 0.9 + hash(i + 501) * 0.6
        const tau = t - ts
        if (tau < 0 || tau > life) continue
        const kk = tau / life
        const Z = lerp(1, 0.18, easeIn(kk))
        const ang = hash(i + 502) * Math.PI * 2 + tau * (3 + hash(i + 503) * 3)
        const rad = (14 + 60 * kk) * (0.5 + hash(i + 504))
        const bp = this.at(v, BUSHES[0], -10)
        const bx = bp.x + v.ox - v.cx
        const by = bp.y + v.oy - v.cy
        let x = W / 2 + ((bx + Math.cos(ang) * rad - tau * 30) / Z) * v.S * 0.6
        let y = Hh / 2 + ((by + Math.sin(ang) * rad * 0.7 - tau * 40) / Z) * v.S * 0.6
        if (gk > 0) {
          const g2 = easeIn(range(t, c.gather + hash(i + 507) * 0.3, c.impact - 0.05))
          x = lerp(x, P.x, g2)
          y = lerp(y, P.y, g2)
        }
        const s = Math.max(2, Math.round((v.S * 0.7) / Z))
        const a = smooth(range(kk, 0, 0.1)) * (1 - smooth(range(kk, 0.75, 1))) * (1 - smooth(range(gk, 0.2, 0.7)))
        this.leaf(x, y, Math.min(s, 36 * u), cols[i % cols.length]!, a, tau * 8 + i)
      }
    }
    // Sammeln: eine Spirale mit drei Armen dreht sich und zieht sich in den glühenden Kern vor der Sonne zusammen
    if (gk > 0 && t < c.impact) {
      const gold = ['#ffd27a', PINK[3]!, PINK[2]!, '#ffb060', PINK[1]!]
      const Rmax = Math.hypot(W, Hh) * 0.5
      for (let i = 0; i < 120; i++) {
        const arm = (i % 3) * ((Math.PI * 2) / 3)
        const sArm = 0.08 + 0.92 * hash(i + 601)
        for (let tr = 2; tr >= 0; tr--) {
          const g2 = smooth(range(t - tr * 0.035, c.gather, c.impact))
          const r = Rmax * sArm * (1 - g2 ** 1.7)
          const ang = arm + sArm * 3.6 + g2 * 5.5 + (hash(i + 602) - 0.5) * 0.18
          const sz = Math.max(2, Math.round(v.S * (0.6 + 1.5 * sArm * (1 - g2)) * (tr ? 0.75 : 1)))
          const a = smooth(range(g2, 0, 0.12)) * (tr ? 0.4 : 1)
          this.leaf(P.x + Math.cos(ang) * r, P.y + Math.sin(ang) * r * 0.62, sz, gold[i % gold.length]!, a, t * 6 + i)
        }
      }
      // glühender Kern
      const g = this.g
      const R = this.clusterRadius(t) * v.S
      const pre = t >= c.hush ? (this.calm ? range(t, c.hush, c.impact) : 0.5 + 0.5 * Math.sin((t - c.hush) * 24)) : 0
      g.globalCompositeOperation = 'lighter'
      g.globalAlpha = clamp(0.5 * gk + 0.4 * pre)
      g.drawImage(this.glowSprite('#ffc27a'), P.x - R * 3, P.y - R * 3, R * 6, R * 6)
      g.globalAlpha = clamp(0.8 * gk)
      g.fillStyle = '#fff0d0'
      const s = Math.max(2, Math.round(R * 0.6))
      g.fillRect(Math.round(P.x - s / 2), Math.round(P.y - s / 2), s, s)
      g.globalAlpha = 1
      g.globalCompositeOperation = 'source-over'
    }
  }

  /** Blatt/Blüte/Beere in Bildschirm-Pixeln (dreht sich: schmal ↔ breit). */
  private leaf(x: number, y: number, s: number, col: string, a: number, phase: number) {
    if (a <= 0.01 || x < -s * 2 || y < -s * 2 || x > this.W + s * 2 || y > this.H + s * 2) return
    const g = this.g
    const w = Math.sin(phase) > -0.2 ? s * 2 : s
    g.globalAlpha = clamp(a)
    g.fillStyle = col
    g.fillRect(Math.round(x - w / 2), Math.round(y - s / 2), Math.round(w), Math.round(s))
    g.globalAlpha = 1
  }

  /** Nach dem Aufblühen: Blätter und Blüten fliegen aus und rieseln über das Logo. */
  private drawAfterglow(t: number, P: { x: number; y: number }) {
    const c = this.c
    if (t < c.impact) return
    const age = t - c.impact
    const { W, H: Hh } = this
    const u = Hh / 720
    const cols = [PINK[2]!, PINK[3]!, PINK[1]!, '#ffd27a', C.berry]
    for (let i = 0; i < 80; i++) {
      const ang = hash(i + 700) * Math.PI * 2
      const sp = (220 + hash(i + 701) * 600) * u * (this.calm ? 0.7 : 1)
      const kk = 1 - Math.exp(-age * 2.4)
      const x = P.x + (Math.cos(ang) * sp * kk) / 2.4 + Math.sin(age * 2 + i) * 10 * u
      const y = P.y + ((Math.sin(ang) * sp * kk) / 2.4) * 0.8 + age * age * 18 * u + age * 20 * u
      this.leaf(x, y, Math.max(2, Math.round((3 + hash(i + 702) * 3) * u)), cols[i % cols.length]!, clamp(1.4 - age / 2.2), age * 5 + i)
    }
    const fall = smooth(range(t, c.impact + 0.4, c.logo))
    if (fall > 0) {
      for (let i = 0; i < 26; i++) {
        const x = ((hash(i + 800) * W + Math.sin(t * 0.9 + i) * 30 * u + t * 14 * u) % (W + 40)) - 20
        const y = ((hash(i + 801) * Hh + t * (28 + hash(i + 802) * 26) * u) % (Hh + 40)) - 20
        this.leaf(x, y, Math.max(2, Math.round((3 + hash(i + 803) * 3) * u)), cols[i % 3]!, 0.65 * fall, t * 2.5 + i)
      }
    }
  }

  private drawBloom(v: ArtView, lights: Light[]) {
    const g = this.g
    g.globalCompositeOperation = 'lighter'
    for (const l of lights) {
      if (!l.bloom || l.a < 0.05) continue
      const x = (l.x + v.ox - v.cx) * v.S + this.W / 2
      const y = (l.y + v.oy - v.cy) * v.S + this.H / 2
      const r = Math.min(l.r, 180) * v.S * 0.45
      if (x + r < 0 || y + r < 0 || x - r > this.W || y - r > this.H) continue
      g.globalAlpha = clamp(l.bloom * Math.min(1, l.a) * 0.5)
      g.drawImage(this.glowSprite(l.color), x - r, y - r, r * 2, r * 2)
    }
    g.globalAlpha = 1
    g.globalCompositeOperation = 'source-over'
  }
}

interface Light {
  x: number
  y: number
  r: number
  color: string
  a: number
  bloom?: number
}

// (für Tests/Vorschau) Orte der Szene
export const FOXDEN_PLACES = { den: DEN, sleeper: SLEEPER, berryDrop: BERRY_DROP, bushes: BUSHES, campfire: CAMPFIRE, lantern: LANTERN_POST } as const
