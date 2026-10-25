import { ctx2d, noise, surface, type Ctx, type Surface } from '../redstone/paint'
import type { Rgb } from '../redstone/palette'
import { rng } from '../redstone/scene'
import { smooth } from '../winter/landscape'
import { bayer, Raster } from '../winter/paint'
import type { Puff } from '../winter/snow'
import { DEN_BLOCKS, denParts, type Cloud, type DenParts, type FoxLandscape, type Tree } from './landscape'
import { css, lit, mix, type FoxPalette } from './palette'
import { fireflyAt, leafWide, makeFireflies, type Firefly, type Leaf, type Spark } from './particles'
import { FOX_COLORS, sleepingFoxPixels, SLEEPING_FOX_H, TINY_FOX, type SleepView } from './sprites'

// Malt die Fuchsbau-Landschaft in Texel-Auflösung (Hochskalieren macht CSS, pixelig).
// Himmel und Landschaft entstehen einmal je Aufbau/Theme als fertige Ebenen; je Takt
// kommen nur Wolken, Sterne, Feuer, Rauch, Funken, Laternen, Glühwürmchen, Blätter und
// der atmende Fuchs dazu. Gleiche Regeln wie die Winternacht: flächige Bänder mit
// schmalem 4 × 4-Bayer-Übergang, Dunst auf fernen Ebenen, Licht von der tiefen Sonne.
// Bäume und Boden sind Minecraft-Blöcke (16 × 16-Texturen, je Block gleich).

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v))
const WHITE: Rgb = [255, 255, 255]

/** Verlauf in `steps` flächigen Stufen, nur die schmalen Übergänge gerastert. */
function banded(t: number, steps: number, x: number, y: number): number {
  const q = clamp(t, 0, 1) * steps
  const i = Math.floor(q)
  const f = clamp((q - i - 0.5) * 4 + 0.5, 0, 1)
  return Math.min(steps, i + (f > bayer(x, y) ? 1 : 0)) / steps
}

/** Glattes 2D-Rauschen 0…1 (Gitter-Rauschen, weich interpoliert). */
export function smooth2(x: number, y: number, s: number): number {
  const ix = Math.floor(x)
  const iy = Math.floor(y)
  const fx = x - ix
  const fy = y - iy
  const tx = fx * fx * (3 - 2 * fx)
  const ty = fy * fy * (3 - 2 * fy)
  const a = noise(ix, iy, s) + (noise(ix + 1, iy, s) - noise(ix, iy, s)) * tx
  const b = noise(ix, iy + 1, s) + (noise(ix + 1, iy + 1, s) - noise(ix, iy + 1, s)) * tx
  return a + (b - a) * ty
}

function nh(x: number, y: number, s = 0): number {
  let h = (Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263) + Math.imul(s | 0, 1442695041)) | 0
  h = Math.imul(h ^ (h >>> 13), 1274126177)
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296
}

function get(R: Raster, x: number, y: number): Rgb | null {
  if (x < 0 || y < 0 || x >= R.w || y >= R.h) return null
  const i = (y * R.w + x) * 4
  if (R.data[i + 3] === 0) return null
  return [R.data[i]!, R.data[i + 1]!, R.data[i + 2]!]
}

// --- Minecraft-Texturen (je Block dieselbe, Blätter in 2 × 2-Büscheln) ----------------

type Tex = (u: number, v: number, s: number) => Rgb | null
interface Textures {
  grass: Tex
  dirt: Tex
  spruceLog: Tex
  spruce: Tex
  cherry: Tex
  cherryLog: Tex
}

const clump = (u: number, v: number, s: number) => 0.62 * nh((u + (v & 2)) >> 1, v >> 1, s) + 0.38 * nh(u, v, s + 7)

function textures(P: FoxPalette): Textures {
  return {
    grass: (u, v) => {
      const n = nh(u, v, 3)
      const drip = 3 + (nh(u, 0, 4) > 0.5 ? 1 : 0) + (nh(u, 0, 5) > 0.8 ? 1 : 0)
      if (v < drip) return v === 0 ? P.grass[2] : n > 0.72 ? P.grass[2] : n < 0.28 ? P.grass[0] : P.grass[1]
      return n > 0.82 ? P.dirt[2] : n < 0.3 ? P.dirt[0] : P.dirt[1]
    },
    dirt: (u, v) => {
      const n = clump(u, v, 9)
      return n > 0.72 ? P.dirt[2] : n < 0.32 ? P.dirt[0] : P.dirt[1]
    },
    spruceLog: (u, v) => {
      const n = nh(u, v >> 2, 11)
      if (u % 4 === 0 || (u % 4 === 2 && n > 0.55)) return P.spruceLog[0]
      return n > 0.7 ? P.spruceLog[2] : P.spruceLog[1]
    },
    spruce: (u, v) => {
      const n = clump(u, v, 13)
      if (n < 0.12 && nh(u, v, 14) < 0.5) return null
      return n < 0.3 ? P.spruce[0] : n > 0.74 ? P.spruce[3] : n > 0.5 ? P.spruce[2] : P.spruce[1]
    },
    cherry: (u, v) => {
      const n = clump(u, v, 17)
      if (n < 0.12 && nh(u, v, 18) < 0.5) return null
      return n < 0.28 ? P.cherry[0] : n > 0.76 ? P.cherry[3] : n > 0.5 ? P.cherry[2] : P.cherry[1]
    },
    cherryLog: (u, v) => {
      const n = nh(u, v >> 2, 19)
      if (u % 5 === 1 || (n > 0.8 && u % 5 === 3)) return P.cherryLog[0]
      return n > 0.68 ? P.cherryLog[2] : P.cherryLog[1]
    },
  }
}

/** Block mit Textur (Kantenlänge `bs`), getönt Richtung `to` um `shade`. */
function block(R: Raster, bx: number, by: number, bs: number, tex: Tex, seed: number, shade = 0, to: Rgb = [0, 0, 0]) {
  const tx = 16 / bs
  const tint = (nh(seed, 1, 77) - 0.5) * 0.08
  for (let y = 0; y < bs; y++) {
    for (let x = 0; x < bs; x++) {
      let c = tex(Math.floor(x * tx), Math.floor(y * tx), seed)
      if (!c) continue
      if (tint > 0) c = mix(c, [255, 230, 210], tint)
      else if (tint < 0) c = mix(c, [0, 0, 0], -tint)
      // Blockraster lesbar wie im Spiel: Unter- und rechte Kante etwas dunkler.
      if (bs >= 4 && (y === bs - 1 || x === bs - 1)) c = mix(c, [0, 0, 0], 0.14)
      if (shade > 0) c = mix(c, to, shade)
      R.set(bx + x, by + y, c)
    }
  }
}

// --- Himmel --------------------------------------------------------------------

export function paintSky(L: FoxLandscape, P: FoxPalette): Raster {
  const R = new Raster(L.w, L.h)
  const bands = P.sky.length - 1
  const end = Math.max(4, L.horizon)
  const light = P.variant === 'light'
  for (let y = 0; y < L.h; y++) {
    // Oben länger Violett, unten rasch Gold (wie eine echte Dämmerung).
    const lin = clamp(y / end, 0, 1)
    const pos = Math.pow(lin, 1.15) * bands
    const i = Math.min(bands - 1, Math.floor(pos))
    const f = clamp((pos - i - 0.5) * 3.2 + 0.5, 0, 1)
    for (let x = 0; x < L.w; x++) R.set(x, y, f > bayer(x, y) ? P.sky[i + 1]! : P.sky[i]!)
  }

  // Hof um die Sonne: drei gerasterte Ringe, dazu ein breiter warmer Schein am Horizont.
  const { x: sx, y: sy, half } = L.sun
  const reach = Math.round(half * (light ? 5 : 6.5))
  for (let y = Math.max(0, sy - reach); y < Math.min(L.h, sy + reach); y++) {
    for (let x = Math.max(0, sx - reach * 2); x < Math.min(L.w, sx + reach * 2); x++) {
      // Etwas breiter als hoch – die Sonne steht tief.
      const d = Math.hypot((x - sx) * 0.62, y - sy)
      if (d >= reach) continue
      const t = 1 - d / reach
      const q = Math.floor(t * t * 3 + bayer(x, y)) / 3
      if (q <= 0) continue
      const i = (y * L.w + x) * 4
      const base: Rgb = [R.data[i]!, R.data[i + 1]!, R.data[i + 2]!]
      R.set(x, y, mix(base, P.halo, q * (light ? 0.38 : 0.3)))
    }
  }

  if (!light) {
    for (const s of L.stars) R.set(s.x, s.y, s.bright ? P.star : P.starDim)
  }

  // Eckige Minecraft-Sonne: heller Kern, warmer Rand.
  for (let dy = -half; dy < half; dy++) {
    for (let dx = -half; dx < half; dx++) {
      const edge = Math.min(dx + half, half - 1 - dx, dy + half, half - 1 - dy)
      R.set(sx + dx, sy + dy, edge < Math.max(1, Math.round(half * 0.3)) ? P.sun : P.sunCore)
    }
  }
  return R
}

/** Minecraft-Wolke: flache Platte aus Zellen (6 × 2 Texel), oben hell, unten vom Abendlicht rosa. */
export function paintCloud(c: Cloud, P: FoxPalette): Surface {
  const cw = 6
  const ch = 2
  const on = (i: number, q: number) => i >= 0 && q >= 0 && i < c.cols && q < c.rows && nh(i, q, c.seed & 0xffff) > 0.08 + 0.55 * Math.abs(i / c.cols - 0.5) + (q === 0 ? 0.12 : 0)
  const R = new Raster(c.cols * cw, c.rows * ch + 1)
  for (let i = 0; i < c.cols; i++) {
    for (let q = 0; q < c.rows; q++) {
      if (!on(i, q)) continue
      for (let y = 0; y < ch; y++) {
        for (let x = 0; x < cw; x++) {
          const top = y === 0 && !on(i, q - 1)
          R.set(i * cw + x, q * ch + y, top ? P.cloudLit : P.cloud)
        }
      }
      if (!on(i, q + 1)) for (let x = 0; x < cw; x++) R.set(i * cw + x, q * ch + ch, P.cloudUnder)
    }
  }
  return R.toSurface()
}

// --- Landschaft ----------------------------------------------------------------

export function paintLand(L: FoxLandscape, P: FoxPalette): Raster {
  const R = new Raster(L.w, L.h)
  const T = textures(P)
  paintHills(R, L, L.hillsBack, P.hillBack, P.hillBackRim, P, 0.35)
  paintHillCrowns(R, L, L.hillsBack, P, 0.5, L.h >= 90 ? 4 : 2, 31)
  paintHills(R, L, L.hills, P.hill, P.hillRim, P, 0.15)
  paintHillCrowns(R, L, L.hills, P, 0.32, L.h >= 90 ? 3 : 2, 37)
  paintForestFloor(R, L, P)
  for (const t of L.trees) if (t.layer === 0) paintTree(R, L, t, P, T, 0.62, P.forestFar)
  paintMeadow(R, L, P)
  for (const s of L.shrubs) paintShrub(R, L, s, P)
  for (const t of L.trees) if (t.layer === 1) paintTree(R, L, t, P, T, 0.34 * P.hazeScale, mix(P.forestMid, P.haze, 0.35))
  paintGround(R, L, P, T)
  for (const t of L.tufts) paintTuft(R, t.x, t.y, t.h, P)
  for (const t of L.trees) if (t.layer === 2) paintTree(R, L, t, P, T, 0, P.deep)
  if (L.den) paintDen(R, L, P, T)
  paintFence(R, L, P)
  if (L.den) paintFireLight(R, L, P)
  return R
}

/** Blockige Hügel im Dunst; Oberkante als heller Saum, zur Sonne hin wärmer, unten neblig. */
function paintHills(R: Raster, L: FoxLandscape, line: Int16Array, col: Rgb, rim: Rgb, P: FoxPalette, mist: number) {
  const bottom = Math.min(L.h - 1, L.horizon + 3)
  for (let x = 0; x < L.w; x++) {
    const top = line[x]!
    if (top > bottom) continue
    const toSun = clamp(1 - Math.abs(x - L.sun.x) / (L.w * 0.35), 0, 1)
    for (let y = top; y <= bottom; y++) {
      let c = y === top || (y === top + 1 && line[x - 1] !== undefined && line[x - 1]! > top) ? rim : col
      if (y === top) c = mix(c, P.sunCore, 0.25 * toSun)
      const fog = banded((y - (L.horizon - L.hillAmp * 0.5)) / (L.hillAmp * 0.6), 2, x, y)
      R.set(x, y, mix(c, P.haze, (mist * fog) * P.hazeScale))
    }
  }
}

/**
 * Saum aus Kirschkronen auf den Hügelkämmen: blockige rosa Kuppen (eine bis zwei Blockhöhen),
 * oben heller, im Dunst wie die Hügel; um die Sonne bleibt der Kamm frei.
 */
function paintHillCrowns(R: Raster, L: FoxLandscape, line: Int16Array, P: FoxPalette, mist: number, bs: number, seed: number) {
  const s = (L.seed & 0xffff) + seed
  const fog = mist * P.hazeScale
  for (let bx = 0; bx < L.w; bx += bs) {
    if (smooth(bx / 11, s) < 0.48 || Math.abs(bx - L.sun.x) < L.sun.half * 1.6) continue
    const rows = nh(bx, 0, s + 1) > 0.55 ? 2 : 1
    const top = line[Math.min(L.w - 1, bx)]!
    for (let k = 0; k < rows; k++) {
      // Obere Lage etwas schmaler: Kuppen statt Kästen.
      const inset = k === rows - 1 && rows > 1 ? 1 : 0
      for (let x = bx + inset; x < Math.min(L.w, bx + bs - inset); x++) {
        for (let y = top - (k + 1) * bs; y < top - k * bs; y++) {
          const lit = y === top - (k + 1) * bs
          const c = lit ? P.cherry[3] : nh(x, y, s + 2) < 0.3 ? P.cherry[1] : P.cherry[2]
          R.set(x, y, mix(c, P.haze, fog))
        }
      }
    }
  }
}

/** Boden unter dem fernen Waldband (dunkler Streifen bis zur Wiese). */
function paintForestFloor(R: Raster, L: FoxLandscape, P: FoxPalette) {
  for (let x = 0; x < L.w; x++) {
    for (let y = L.forest[x]!; y <= L.meadow[x]! + 1 && y < L.h; y++) {
      const c = noise(x, y, 37) < 0.2 ? P.spruce[1] : P.spruce[0]
      R.set(x, y, mix(c, P.forestFar, 0.62 * P.hazeScale))
    }
  }
}

/** Wiese im Abendlicht: oben warm von der Sonne angestrahlt, nach vorn tiefer im Schatten. */
function paintMeadow(R: Raster, L: FoxLandscape, P: FoxPalette) {
  const depth = Math.max(10, Math.round((L.h - L.horizon) * 0.7))
  const s = L.seed & 0xffff
  for (let x = 0; x < L.w; x++) {
    const top = L.meadow[x]!
    // Unter der Sonne ein heller Schimmer auf dem Gras.
    const sheen = clamp(1 - Math.abs(x - L.sun.x) / (L.sun.half * 9), 0, 1)
    for (let y = Math.max(0, top); y < L.h; y++) {
      const d = y - top
      let k = Math.round(banded(d / depth, 3, x, y) * 3)
      // Grasstreifen: kurze waagrechte Bahnen etwas heller oder dunkler.
      const rib = noise((x + Math.round(5 * smooth(y / 3, s))) >> 3, y, s + 2)
      if (rib < 0.03) k = Math.max(0, k - 1)
      else if (rib > 0.975) k = Math.min(3, k + 1)
      let c = P.meadow[clamp(k, 0, 3)]!
      if (d < 2) c = mix(P.meadow[0], P.rim, 0.25)
      if (sheen > 0 && d < depth * 0.5) {
        const glow = banded(sheen * (1 - d / (depth * 0.5)), 3, x, y)
        if (glow > 0) c = mix(c, P.rim, glow * 0.35)
      }
      // Blütenteppiche (Pink Petals): Flecken aus rosa Blüten im Gras, fern im Dunst.
      // Flache, waagrechte Flecken (2D-Rauschen, breiter als hoch) wie liegende Teppiche.
      const patch = smooth2(x / 14, y / 4, s + 40)
      if (d > 2 && patch > 0.7 && nh(x, y, s + 43) < 0.3 + (patch - 0.7) * 3) {
        const pink = lit(P.petals[nh(x, y, s + 44) < 0.5 ? 0 : 2]!, P.ambient)
        c = mix(pink, P.haze, Math.max(0, 0.35 - d / depth) * P.hazeScale)
      }
      R.set(x, y, c)
    }
  }
  for (const f of L.flowers) {
    if (f.y >= L.h) continue
    const c = lit(P.flowers[f.c], P.ambient)
    R.set(f.x, f.y, c)
    if (f.y - 1 >= L.meadow[f.x]!) R.set(f.x, f.y + 1, P.meadow[2])
  }
}

/** Vordere Reihe Grasblöcke, darunter Erde, die nach unten im Schatten versinkt. */
function paintGround(R: Raster, L: FoxLandscape, P: FoxPalette, T: Textures) {
  const bs = L.bs
  for (let bx = 0; bx < L.w; bx += bs) {
    const top = L.ground[bx]!
    for (let by = top, row = 0; by < L.h; by += bs, row++) {
      const shade = clamp(row * 0.34 + 0.06, 0, 0.9)
      block(R, bx, by, bs, row === 0 ? T.grass : T.dirt, bx * 7 + row, shade, P.deep)
    }
    // Blütenblätter auf den Grasblöcken (fleckweise, wie Pink Petals im Spiel).
    if (smooth(bx / 37, (L.seed & 0xffff) + 45) > 0.45) {
      for (let x = bx; x < Math.min(L.w, bx + bs); x++) {
        for (let k = 0; k < Math.min(3, bs); k++) {
          if (nh(x, top + k, 46) < (k === 0 ? 0.42 : 0.2)) R.set(x, top + k, mix(P.cherry[nh(x, top + k, 47) < 0.5 ? 2 : 3]!, P.deep, 0.06 + k * 0.08))
        }
      }
    }
    // Stufe nach oben: die Seite des höheren Blocks zeigt Erde.
    const next = L.ground[bx + bs]
    if (next !== undefined && next < top) for (let y = next; y < top; y++) R.set(bx + bs - 1, y, mix(P.dirt[0], P.deep, 0.3))
  }
}

/** Busch auf der Wiese: runder Blätterball in Büscheln, oben vom Abendlicht gesäumt, fern im Dunst. */
function paintShrub(R: Raster, L: FoxLandscape, s: { x: number; y: number; size: number }, P: FoxPalette) {
  const far = 1 - clamp((s.y - L.meadow[clamp(s.x, 0, L.w - 1)]!) / Math.max(8, (L.h - L.horizon) * 0.5), 0, 1)
  const w = Math.round(s.size * 1.6)
  const h = s.size
  for (let yy = -h; yy < 0; yy++) {
    for (let xx = 0; xx < w; xx++) {
      const d = Math.hypot((xx + 0.5 - w / 2) / (w / 2), (yy + 0.5 + h * 0.45) / (h * 0.55))
      const n = nh((s.x + xx) >> 1, (s.y + yy) >> 1, 31)
      if (d > 1.02 - (n - 0.5) * 0.35) continue
      const top = yy === -h || get(R, s.x + xx, s.y + yy - 1) === null || d > 0.8 && yy < -h * 0.4
      let c = n > 0.66 ? P.berryLeaf[2] : n < 0.3 ? P.berryLeaf[0] : P.berryLeaf[1]
      c = lit(c, P.ambient)
      if (top && yy < -h * 0.3) c = mix(c, P.rim, 0.35)
      R.set(s.x + xx, s.y + yy, mix(c, P.haze, far * 0.45 * P.hazeScale))
    }
  }
}

function paintTuft(R: Raster, x: number, y: number, h: number, P: FoxPalette) {
  for (let k = 1; k <= h; k++) R.set(x, y - k, k === h ? P.grass[2] : P.grass[1])
  if (h > 1) R.set(x + 1, y - 1, P.grass[0])
}

// --- Bäume ------------------------------------------------------------------------

/**
 * Halbe Breite (Blöcke) der Nadel-Stufe i: wächst nach unten im Zickzack (schmal, breit,
 * schmal …) wie bei Minecraft-Fichten; die unterste Stufe ist die breiteste.
 */
export function spruceHalf(i: number, tiers: number): number {
  if (i === 0 || tiers < 2) return 0
  const max = tiers > 8 ? 3 : tiers > 4 ? 2 : 1
  const grow = Math.ceil((i / (tiers - 1)) * max)
  return (tiers - 1 - i) % 2 === 1 ? Math.max(1, grow - 1) : grow
}

function paintTree(R: Raster, L: FoxLandscape, t: Tree, P: FoxPalette, T: Textures, haze: number, to: Rgb) {
  // Licht von der Sonne: die ihr zugewandte Seite bekommt den Saum.
  const side = t.x < L.sun.x ? 1 : -1
  if (t.kind === 'cherry') cherryTree(R, t, P, T, haze, to, side)
  else spruce(R, t, P, T, haze, to, side)
}

/** Minecraft-Fichte: Stamm + Nadel-Stufen (schmal, breit, schmal …), Ecken der breiten Stufen fehlen manchmal. */
function spruce(R: Raster, t: Tree, P: FoxPalette, T: Textures, haze: number, to: Rgb, side: number) {
  const { bs, tiers, base: ground, x } = t
  const r = rng(t.seed)
  const trunkTop = ground - (tiers + 1) * bs
  const placed = new Set<string>()
  if (bs <= 2) {
    // Fern: Schattenriss in zwei Tönen.
    const body = mix(P.spruce[1], to, haze)
    const dark = mix(P.spruce[0], to, haze)
    for (let y = ground - bs; y >= trunkTop; y -= bs) for (let k = 0; k < bs; k++) for (let q = 0; q < bs; q++) R.set(x + k, y + q, mix(P.spruceLog[1], to, haze))
    for (let i = 0; i < tiers; i++) {
      const hw = spruceHalf(i, tiers)
      const y = trunkTop - bs + i * bs
      for (let j = -hw; j <= hw; j++) {
        for (let q = 0; q < bs; q++) for (let k = 0; k < bs; k++) R.set(x + j * bs + k, y + q, j * side > 0 ? body : dark)
        placed.add(`${i},${j}`)
      }
    }
    rimLight(R, placed, x, trunkTop - bs, bs, P, haze, side)
    return
  }
  for (let y = ground - bs; y >= trunkTop; y -= bs) block(R, x, y, bs, T.spruceLog, t.seed + y, haze, to)
  for (let i = 0; i < tiers; i++) {
    const w = 1 + 2 * spruceHalf(i, tiers)
    const y = trunkTop - bs + i * bs
    for (let j = -(w >> 1); j <= w >> 1; j++) {
      if (w >= 5 && Math.abs(j) === w >> 1 && r() < 0.5) continue
      const fall = 0.12 * (i / tiers)
      // Abseits der Sonne etwas dunkler.
      const away = j * side < 0 ? 0.1 : 0
      block(R, x + j * bs, y, bs, T.spruce, t.seed + i * 31 + j, clamp(haze + fall + away, 0, 1), haze > 0 ? to : P.deep)
      placed.add(`${i},${j}`)
    }
  }
  rimLight(R, placed, x, trunkTop - bs, bs, P, haze, side)
}

/** Warmer Saum auf Blöcken, über denen nichts liegt, und an der Seite zur Sonne. */
function rimLight(R: Raster, placed: Set<string>, x: number, y0: number, bs: number, P: FoxPalette, haze: number, side: number) {
  const k = 0.42 * (1 - haze)
  if (k <= 0.03) return
  const rows = Math.max(1, Math.round(bs / 6))
  for (const key of placed) {
    const [i, j] = key.split(',').map(Number) as [number, number]
    const bx = x + j * bs
    const by = y0 + i * bs
    const paint = (px: number, py: number, a: number) => {
      const c = get(R, px, py)
      if (c) R.set(px, py, mix(c, P.rim, a))
    }
    if (!placed.has(`${i - 1},${j}`)) for (let yy = 0; yy < rows; yy++) for (let xx = 0; xx < bs; xx++) paint(bx + xx, by + yy, k * (yy ? 0.5 : 1))
    if (!placed.has(`${i},${j + side}`)) {
      const ex = side > 0 ? bx + bs - 1 : bx
      for (let yy = 0; yy < bs; yy++) paint(ex, by + yy, k * 0.55)
    }
  }
}

/** Minecraft-Kirschbaum: schräger Stamm, breite rosa Krone, hängende Blätter. */
function cherryTree(R: Raster, t: Tree, P: FoxPalette, T: Textures, haze: number, to: Rgb, side: number) {
  const { bs, seed } = t
  const r = rng(seed)
  const dir = t.flip ? -1 : 1
  let cx = t.x
  let cy = t.base - bs
  const sil = bs <= 2
  const logCol = mix(P.cherryLog[1], to, haze)
  const put = (bx: number, by: number, tex: Tex, s: number, sh: number, flat: Rgb) => {
    if (sil) for (let q = 0; q < bs; q++) for (let k = 0; k < bs; k++) R.set(bx + k, by + q, flat)
    else block(R, bx, by, bs, tex, s, sh, haze > 0 ? to : P.deep)
  }
  for (let i = 0; i < t.tiers; i++) {
    put(cx, cy, T.cherryLog, seed + i, haze, logCol)
    if (i % 2 === 1) {
      cx += dir * bs
      put(cx, cy, T.cherryLog, seed + i + 50, haze, logCol)
    }
    cy -= bs
  }
  // Breite, flache Krone wie bei Minecraft-Kirschen (oben schmal, Mitte breit).
  const rows = [5, 9, 9, 7]
  const top = cy - 2 * bs
  const placed = new Set<string>()
  rows.forEach((w, i) => {
    for (let j = -(w >> 1); j <= w >> 1; j++) {
      if (Math.abs(j) === w >> 1 && (i === 0 || i === rows.length - 1 ? r() < 0.7 : r() < 0.25)) continue
      const flat = mix(j * side > 0 ? P.cherry[2] : P.cherry[1], to, haze)
      put(cx + j * bs, top + i * bs, T.cherry, seed + i * 17 + j, clamp(haze + (i / rows.length) * 0.18 + (j * side < 0 ? 0.08 : 0), 0, 1), flat)
      placed.add(`${i},${j}`)
    }
  })
  rimLight(R, placed, cx, top, bs, P, haze, side)
  if (sil) return
  // Hängende Blätter unter der Krone.
  for (let j = -2; j <= 2; j++) {
    if (r() < 0.45) continue
    const hx = cx + j * bs
    const hy = top + rows.length * bs
    const len = 1 + Math.floor(r() * 2)
    for (let q = 0; q < len * bs; q++) {
      for (let p = 0; p < bs; p++) {
        if (nh(p, q, seed + j) < 0.45 || p % 3 === 0) continue
        const c = P.cherry[Math.min(3, Math.floor(nh(p, q, seed + 9 + j) * 4))]!
        R.set(hx + p, hy + q, mix(c, haze > 0 ? to : P.deep, clamp(haze + 0.22, 0, 1)))
      }
    }
  }
}

// --- Fuchsbau ------------------------------------------------------------------------

function paintDen(R: Raster, L: FoxLandscape, P: FoxPalette, T: Textures) {
  const d = L.den!
  const p = denParts(d)
  const b = d.bs
  // Grashügel aus Blöcken.
  p.hill.heights.forEach((n, i) => {
    for (let k = 0; k < n; k++) {
      const by = d.y - (k + 1) * b
      block(R, p.hill.x + i * b, by, b, k === n - 1 ? T.grass : T.dirt, d.x * 3 + i * 11 + k, 0.08 * (n - 1 - k), P.deep)
    }
  })
  // Loch: dunkler Bogen, nach innen tiefer.
  const { x0, x1, top, y } = p.hole
  const mid = (x0 + x1) / 2
  const hw = (x1 - x0) / 2
  for (let yy = top; yy < y; yy++) {
    for (let xx = x0; xx <= x1; xx++) {
      const u = (xx - mid) / hw
      const arch = top + Math.round((y - top) * 0.55 * u * u)
      if (yy < arch) continue
      const depth = clamp((yy - arch) / Math.max(2, (y - top) * 0.45), 0, 1) * (1 - Math.abs(u) * 0.35)
      R.set(xx, yy, mix(P.hole[0], P.hole[1], banded(depth, 3, xx, yy)))
    }
  }
  // Wurzeln um den Eingang.
  const roots: [number, number, number, number][] = [
    [x0 - 2, top - 2, x0, y - 1],
    [x1 + 2, top - 2, x1, y - 1],
    [x0, top - 2, Math.round(mid) - 1, top],
    [Math.round(mid) + 2, top - 3, x1 - 1, top],
  ]
  if (b >= 6)
    for (const [ax, ay, bx2, by2] of roots) {
      const n = Math.ceil(Math.hypot(bx2 - ax, by2 - ay))
      for (let i = 0; i <= n; i++) {
        const t = i / n
        const rx = Math.round(ax + (bx2 - ax) * t + Math.sin(t * 5) * 0.8)
        const ry = Math.round(ay + (by2 - ay) * t)
        R.set(rx, ry, P.root[0])
        if (t < 0.5) R.set(rx + 1, ry, P.root[1])
      }
    }
  paintBush(R, p, P, b)
  paintCampfire(R, p, P)
  // Zaunpfahl mit Laterne auf dem Hügel (nur groß – klein wäre sie nur ein Fleck).
  if (b < 6) return
  const lx = p.lantern.x
  const postTop = p.lantern.y + 3
  for (let yy = postTop; yy < d.y - 2 * b; yy++) {
    R.set(lx, yy, P.fence[1])
    if (b >= 6) R.set(lx + 1, yy, P.fence[0])
  }
  paintLanternFrame(R, lx, p.lantern.y, P, b >= 6)
}

/** Süßbeerenstrauch: runder Blätterbusch in 2er-Büscheln, rote Beeren mit hellem Glanz. */
function paintBush(R: Raster, p: DenParts, P: FoxPalette, b: number) {
  const { x, y, size } = p.bush
  const w = Math.round(size * 1.25)
  const h = size
  const cell = b >= 6 ? 2 : 1
  for (let yy = -h; yy < 0; yy++) {
    for (let xx = 0; xx < w; xx++) {
      const tx = Math.floor(xx / cell)
      const ty = Math.floor(yy / cell)
      const dd = Math.hypot((xx - w / 2) / (w / 2), (yy + h * 0.48) / (h * 0.52))
      const n = nh(tx, ty, x)
      if (dd > 1 - (n - 0.5) * 0.3 || n < 0.08) continue
      const k = Math.min(2, Math.floor(nh(tx, ty, x + 1) * (yy < -h * 0.5 ? 3 : 2.2)))
      R.set(x + xx, y + yy, P.berryLeaf[k]!)
    }
  }
  const r = rng(x)
  for (let i = 0, n = Math.max(3, Math.round(size / 2)); i < n; i++) {
    const bx = x + 1 + Math.floor(r() * (w - 3))
    const by = y - 2 - Math.floor(r() * (h - 3))
    R.set(bx, by, P.berryHi)
    R.set(bx + 1, by, P.berry)
    if (cell > 1) {
      R.set(bx, by + 1, P.berry)
      R.set(bx + 1, by + 1, mix(P.berry, P.deep, 0.4))
    }
  }
}

/** Gekreuzte Stämme des Lagerfeuers (die Flammen malt der Takt). */
function paintCampfire(R: Raster, p: DenParts, P: FoxPalette) {
  const { x, y, w } = p.fire
  const big = w >= 6
  // Unten ein Stamm quer, die Enden heller (Jahresringe).
  for (let k = 0; k < w; k++) {
    R.set(x + k, y - 1, P.log[0])
    if (big) R.set(x + k, y - 2, k === 0 || k === w - 1 ? P.log[2] : P.log[1])
  }
  // Darüber zwei Stämme von vorn (Stirnseiten) links und rechts.
  const top = big ? y - 3 : y - 2
  for (const k of big ? [0, 1, w - 2, w - 1] : [0, w - 1]) R.set(x + k, top, k === 0 || k === w - 1 ? P.log[2] : P.log[1])
}

/** Rahmen, Kappe und Henkel einer Laterne; das Glas malt jeder Takt. */
function paintLanternFrame(R: Raster, x: number, y: number, P: FoxPalette, big: boolean) {
  const f = P.lanternFrame
  for (let dy = -1; dy <= 1; dy++) {
    R.set(x - 2, y + dy, f)
    R.set(x + 2, y + dy, f)
  }
  for (let dx = -2; dx <= 2; dx++) {
    R.set(x + dx, y - 2, f)
    R.set(x + dx, y + 2, f)
  }
  for (let dx = -1; dx <= 1; dx++) R.set(x + dx, y - 3, mix(f, P.rim, 0.35))
  if (big) R.set(x, y - 4, f)
}

/** Fichtenzaun bis zum Spielen-Knopf: Pfosten, zwei Latten, eine Laterne auf jedem Pfosten. */
function paintFence(R: Raster, L: FoxLandscape, P: FoxPalette) {
  L.lanterns.forEach((l, i) => {
    const g = L.ground[clamp(l.x, 0, L.w - 1)]!
    for (let py = l.y + 3; py < g; py++) {
      R.set(l.x, py, P.fence[1])
      R.set(l.x + 1, py, P.fence[0])
    }
    paintLanternFrame(R, l.x, l.y, P, false)
    const next = L.lanterns[i + 1]
    if (!next) return
    // Zwei Latten zwischen den Pfosten.
    for (const ry of [l.y + 5, l.y + Math.max(9, Math.round((g - l.y) * 0.72))]) {
      if (ry >= g - 1) continue
      for (let px = l.x + 2; px < next.x; px++) {
        R.set(px, ry, P.fence[1])
        R.set(px, ry + 1, P.fence[0])
      }
    }
  })
}

/** Feste Lichtkarte des Lagerfeuers: warmer Schein auf Boden, Hügel und Bäumen (gestuft, gerastert). */
function paintFireLight(R: Raster, L: FoxLandscape, P: FoxPalette) {
  const d = L.den!
  const p = denParts(d)
  const cx = p.fire.x + p.fire.w / 2
  const cy = p.fire.y - p.fire.w
  const reach = d.bs * (d.size === 'large' ? 6 : 5)
  const strength = P.variant === 'light' ? 0.22 : P.variant === 'oled' ? 0.5 : 0.4
  for (let y = Math.max(0, Math.floor(cy - reach)); y < Math.min(L.h, cy + reach); y++) {
    for (let x = Math.max(0, Math.floor(cx - reach * 1.3)); x < Math.min(L.w, cx + reach * 1.3); x++) {
      const dd = Math.hypot((x - cx) / 1.3, y - cy) / reach
      if (dd >= 1) continue
      const c = get(R, x, y)
      if (!c) continue
      const k = banded((1 - dd) * (1 - dd), 4, x, y)
      if (k > 0) R.set(x, y, mix(c, lit(P.glowFire, [255, 230, 200]), k * strength))
    }
  }
}

// --- Bild je Takt ------------------------------------------------------------------

export interface FoxFrameState {
  tick: number
  seconds: number
  /** Wie viele Zaunlaternen leuchten (von links). */
  lit: number
  /** Feuer 0…1 (ruhig … das Spiel läuft, lodert). */
  fire: number
  /** Bewegung reduziert: nichts flackert, Glühwürmchen stehen. */
  calm: boolean
  fox: SleepView
  smoke: readonly Puff[]
  sparks: readonly Spark[]
  leaves: readonly Leaf[]
}

/** Hält die fertigen Ebenen einer Landschaft und setzt daraus jedes Bild zusammen. */
export class FoxPainter {
  readonly sky: Surface
  readonly land: Surface
  private readonly clouds: Surface[]
  readonly flies: Firefly[]
  readonly parts: DenParts | null

  constructor(
    readonly L: FoxLandscape,
    readonly P: FoxPalette,
  ) {
    this.sky = paintSky(L, P).toSurface()
    this.land = paintLand(L, P).toSurface()
    this.clouds = L.clouds.map((c) => paintCloud(c, P))
    this.parts = L.den ? denParts(L.den) : null
    // Glühwürmchen über der Wiese, dichter am Bau; am Tag nur wenige.
    const n = Math.round((L.w / (P.variant === 'light' ? 70 : 18)) * (L.h >= 90 ? 1 : 0.6))
    const top = Math.min(...Array.from(L.meadow))
    this.flies = makeFireflies(rng(L.seed ^ 0xf1), n, { x0: L.w * 0.25, x1: L.w, y0: top + 2, y1: Math.max(top + 6, L.h * 0.92) })
    if (this.parts && L.den!.size === 'large') {
      const near = makeFireflies(rng(L.seed ^ 0xf2), Math.max(3, Math.round(n / 3)), {
        x0: L.den!.x - L.den!.bs * 2,
        x1: L.den!.x + DEN_BLOCKS * L.den!.bs,
        y0: L.den!.y - L.den!.bs * 5,
        y1: L.den!.y - 2,
      })
      this.flies.push(...near)
    }
  }

  frame(g: Ctx, s: FoxFrameState) {
    const { L, P } = this
    g.clearRect(0, 0, L.w, L.h)
    g.drawImage(this.sky, 0, 0)

    // Wolken ziehen langsam nach rechts.
    this.clouds.forEach((img, i) => {
      const c = L.clouds[i]!
      const cw = img.width
      const span = L.w + cw * 2
      const x = Math.floor((((c.x + cw + s.tick * c.speed) % span) + span) % span) - cw
      g.drawImage(img, x, c.y)
    })

    // Funkelnde erste Sterne.
    if (P.variant !== 'light' && !s.calm) {
      const phase = Math.floor(s.tick / 6)
      L.stars.forEach((st, i) => {
        if (!st.twinkle) return
        const n = noise(i, phase, 61)
        if (n < 0.35) px(g, st.x, st.y, P.starDim)
        else if (n > 0.88) px(g, st.x, st.y, P.star)
      })
    }

    g.drawImage(this.land, 0, 0)

    const parts = this.parts
    if (parts && L.den) {
      // Der schlafende Fuchs atmet neben dem Feuer.
      if (parts.fox) this.paintFox(g, parts.fox.x, parts.fox.y, s.fox, parts)
      else if (L.den.size === 'small' && L.den.fox) this.paintTinyFox(g, parts)
      this.paintFlames(g, parts, s)
      // Laterne am Bau.
      if (L.den.size === 'large') this.paintGlass(g, parts.lantern.x, parts.lantern.y, P.flame[1], P.flame[0], s.calm ? false : noise(1, s.tick >> 1, 67) < 0.08)
    }

    // Laternen auf dem Zaun (Akzent).
    L.lanterns.forEach((l, i) => {
      const on = i < s.lit
      const flick = on && !s.calm && s.fire > 0.95 && noise(i, s.tick >> 1, 67) < 0.1
      if (on) this.paintGlass(g, l.x, l.y, P.lanternOn, P.lanternCore, flick)
      else {
        g.fillStyle = css(P.lanternOff)
        g.fillRect(l.x - 1, l.y - 1, 3, 3)
        px(g, l.x + 1, l.y - 1, mix(P.lanternOff, WHITE, 0.25))
      }
    })

    // Rauch über dem Feuer.
    for (const p of s.smoke) {
      const age = 1 - p.life / p.max
      if (age > 0.5 && age - 0.5 > bayer(p.x | 0, p.y | 0) * 0.5) continue
      g.fillStyle = css(mix(P.smoke, P.sky[3]!, age * 0.5))
      const size = age < 0.3 ? 1 : age < 0.65 ? 2 : 3
      g.fillRect(Math.round(p.x), Math.round(p.y), size, size)
    }
    // Funken.
    for (const sp of s.sparks) {
      const age = 1 - sp.life / sp.max
      if (!s.calm && noise(Math.round(sp.x), s.tick, 71) < 0.25) continue
      px(g, Math.round(sp.x), Math.round(sp.y), age < 0.4 ? P.spark : P.ember)
    }

    // Glühwürmchen.
    for (const f of this.flies) {
      const v = fireflyAt(f, s.seconds, s.calm)
      if (v.a < 0.15) continue
      px(g, v.x, v.y, v.a > 0.55 ? P.fly : mix(P.fly, P.flyGlow, 0.6))
    }

    // Treibende Blätter und Kirschblüten.
    for (const f of s.leaves) {
      const x = Math.round(f.x)
      const y = Math.round(f.y)
      const c = f.petal ? P.petals[f.tone % P.petals.length]! : P.leaves[f.tone % P.leaves.length]!
      const col = lit(c, P.ambient)
      g.fillStyle = css(f.size ? col : mix(col, P.haze, 0.3))
      g.fillRect(x, y, leafWide(f, s.tick) ? 2 : 1, 1)
    }
  }

  /** Schlafender Fuchs, im Abendlicht getönt; die Seite zum Feuer warm angestrahlt. */
  private paintFox(g: Ctx, x0: number, base: number, v: SleepView, parts: DenParts) {
    const { P } = this
    const oy = base - SLEEPING_FOX_H
    const fireX = parts.fire.x + parts.fire.w / 2
    // Er liegt im Schein des Feuers: weniger Dämmerung als die Welt ringsum.
    const amb = mix(P.ambient, WHITE, 0.55)
    for (const [x, y, c] of sleepingFoxPixels(v)) {
      let col = lit(FOX_COLORS[c]!, amb)
      // Feuerschein: zur Feuerseite stärker.
      const near = clamp(1 - (x0 + x - fireX) / 22, 0, 1)
      col = mix(col, P.glowFire, (P.variant === 'light' ? 0.05 : 0.12) * near)
      px(g, x0 + x, oy + y, col)
    }
  }

  /** Banner/Vorschau: winziger Fuchs neben dem Feuer. */
  private paintTinyFox(g: Ctx, parts: DenParts) {
    const x0 = parts.fire.x + parts.fire.w + 1
    const oy = parts.fire.y - TINY_FOX.length
    TINY_FOX.forEach((row, y) => {
      for (let x = 0; x < row.length; x++) {
        const c = row[x]!
        if (c !== '.') px(g, x0 + x, oy + y, lit(FOX_COLORS[c]!, this.P.ambient))
      }
    })
  }

  /** Lagerfeuer: Glut zwischen den Stämmen, gestufte Flammen, die je Takt flackern. */
  private paintFlames(g: Ctx, parts: DenParts, s: FoxFrameState) {
    const { P } = this
    const { x, y, w } = parts.fire
    const big = w >= 6
    const base = big ? y - 3 : y - 2
    // Glut.
    for (let k = big ? 2 : 1; k < w - (big ? 2 : 1); k++) px(g, x + k, base, noise(k, s.tick >> 2, 73) < 0.3 && !s.calm ? P.flame[0] : P.ember)
    const tall = (big ? w * 1.15 : w * 1.4) * (0.75 + 0.35 * s.fire)
    for (let k = 1; k < w - 1; k++) {
      const off = Math.abs(k - (w - 1) / 2) / ((w - 1) / 2)
      const jitter = s.calm ? 0 : (noise(k, s.tick, 79) - 0.5) * (big ? 3 : 1.5) + Math.sin(s.tick * 0.5 + k) * 0.8
      const hgt = Math.max(0, Math.round(tall * (1 - off * 0.75) + jitter))
      for (let j = 0; j < hgt; j++) {
        const t = j / Math.max(1, hgt)
        const c = t < 0.35 ? P.flame[0] : t < 0.72 ? P.flame[1] : P.flame[2]
        px(g, x + k, base - 1 - j, c)
      }
    }
    // Eine lose Flammenzunge oben.
    if (!s.calm && big && noise(3, s.tick, 83) < 0.4) {
      const k = 2 + Math.floor(noise(4, s.tick, 83) * (w - 4))
      px(g, x + k, Math.round(base - 1 - tall - 1 - noise(5, s.tick, 83) * 2), P.flame[2])
    }
  }

  private paintGlass(g: Ctx, x: number, y: number, on: Rgb, core: Rgb, flick: boolean) {
    g.fillStyle = css(flick ? mix(on, this.P.lanternFrame, 0.35) : on)
    g.fillRect(x - 1, y - 1, 3, 3)
    px(g, x, y, core)
  }

  /** Weiches Licht auf der groben Ebene (`scale` Texel je Pixel). */
  glow(g: Ctx, s: Pick<FoxFrameState, 'lit' | 'fire' | 'tick' | 'seconds' | 'calm'>, scale: number) {
    const { L, P } = this
    g.clearRect(0, 0, g.canvas.width, g.canvas.height)
    const light = (x: number, y: number, r: number, c: Rgb, a: number) => {
      if (a <= 0.01) return
      const gx = x / scale
      const gy = y / scale
      const gr = Math.max(1, r / scale)
      const grad = g.createRadialGradient(gx, gy, 0, gx, gy, gr)
      grad.addColorStop(0, `rgb(${c[0] | 0} ${c[1] | 0} ${c[2] | 0} / ${a})`)
      grad.addColorStop(0.4, `rgb(${c[0] | 0} ${c[1] | 0} ${c[2] | 0} / ${a * 0.45})`)
      grad.addColorStop(1, `rgb(${c[0] | 0} ${c[1] | 0} ${c[2] | 0} / 0)`)
      g.fillStyle = grad
      g.fillRect(gx - gr, gy - gr, gr * 2, gr * 2)
    }
    const day = P.variant === 'light'
    light(L.sun.x, L.sun.y, L.sun.half * 9, P.glowSun, day ? 0.35 : 0.42)
    const parts = this.parts
    if (parts && L.den) {
      const b = L.den.bs
      // Lagerfeuer pulsiert sanft (ruhig: gleichmäßig).
      const pulse = s.calm ? 0.85 : 0.78 + 0.12 * Math.sin(s.seconds * 2.3) + 0.08 * (noise(9, s.tick >> 1, 89) - 0.5)
      const power = 0.7 + 0.3 * s.fire
      light(parts.fire.x + parts.fire.w / 2, parts.fire.y - b, b * (L.den.size === 'large' ? 6.5 : 6) * power, P.glowFire, pulse * power * (day ? 0.55 : 0.85))
      if (L.den.size === 'large') light(parts.lantern.x, parts.lantern.y, b * 2.4, P.flame[1], s.calm ? 0.55 : 0.5 + 0.08 * Math.sin(s.seconds * 3.1))
    }
    L.lanterns.forEach((l, i) => {
      if (i >= s.lit) return
      light(l.x, l.y, 15, P.glowAccent, 0.8)
    })
    if (!day) {
      for (const f of this.flies) {
        const v = fireflyAt(f, s.seconds, s.calm)
        if (v.a > 0.35) light(v.x, v.y, 5, P.glowFly, 0.35 * v.a)
      }
    }
  }
}

function px(g: Ctx, x: number, y: number, c: Rgb) {
  g.fillStyle = css(c)
  g.fillRect(x, y, 1, 1)
}

/** Fuß der Flammen (für Rauch und Funken). */
export function fireTop(parts: DenParts): { x: number; y: number; w: number } {
  const big = parts.fire.w >= 6
  return { x: parts.fire.x + 1, y: parts.fire.y - (big ? 3 : 2) - Math.round(parts.fire.w * 0.9), w: parts.fire.w - 2 }
}

/** Testhilfe: steht an (x, y) nach dem Malen etwas? */
export function rasterHas(R: Raster, x: number, y: number) {
  return R.filled(x, y)
}

export { ctx2d, surface }
