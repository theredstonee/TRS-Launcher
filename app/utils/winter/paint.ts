import { ctx2d, noise, surface, type Ctx, type Surface } from '../redstone/paint'
import type { Rgb } from '../redstone/palette'
import { CABINS, smooth, type CabinRect, type Cloud, type Landscape, type Pine } from './landscape'
import { PENGUIN_COLORS, PENGUIN_H, penguinPixels, TINY_PENGUIN, type PenguinView } from './penguin'
import { css, mix, type WinterPalette } from './palette'
import type { Flake, Puff } from './snow'

// Malt die Winterlandschaft in Texel-Auflösung (Hochskalieren macht CSS, pixelig).
// Himmel und Landschaft entstehen einmal je Aufbau/Theme als fertige Ebenen;
// je Takt kommen nur Polarlicht, Funkeln, Laternen, Rauch und Schnee dazu.
// Weiche Verläufe sind gerastert (4 × 4 Bayer) – so bleibt alles Pixel-Art.

const BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5]

/** Schwelle 0…1 des Rasters an einem Texel. */
export function bayer(x: number, y: number): number {
  return (BAYER[(y & 3) * 4 + (x & 3)]! + 0.5) / 16
}

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v))

/** Feste Dunst-Mischung einer Ebene (ohne Raster – sonst rauscht die ganze Fläche). */
function hazed(c: Rgb, haze: Rgb, t: number, _x?: number, _y?: number): Rgb {
  return t <= 0 ? c : mix(c, haze, t)
}

/** Verlauf in `steps` flächigen Stufen, nur die schmalen Übergänge gerastert. */
function banded(t: number, steps: number, x: number, y: number): number {
  const q = clamp(t, 0, 1) * steps
  const i = Math.floor(q)
  const f = clamp((q - i - 0.5) * 4 + 0.5, 0, 1)
  return Math.min(steps, i + (f > bayer(x, y) ? 1 : 0)) / steps
}

/** Ziel für einzelne Pixel (Raster beim Aufbau, Canvas je Takt). */
type Pixels = { set(x: number, y: number, c: Rgb): void }

/** RGBA-Puffer in Texeln. */
export class Raster {
  readonly data: Uint8ClampedArray<ArrayBuffer>
  constructor(
    readonly w: number,
    readonly h: number,
  ) {
    this.data = new Uint8ClampedArray(w * h * 4)
  }

  set(x: number, y: number, c: Rgb, alpha = 255) {
    x |= 0
    y |= 0
    if (x < 0 || y < 0 || x >= this.w || y >= this.h) return
    const i = (y * this.w + x) * 4
    this.data[i] = c[0]
    this.data[i + 1] = c[1]
    this.data[i + 2] = c[2]
    this.data[i + 3] = alpha
  }

  filled(x: number, y: number): boolean {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h) return false
    return this.data[(y * this.w + x) * 4 + 3]! > 0
  }

  toSurface(): Surface {
    const s = surface(this.w, this.h)
    ctx2d(s).putImageData(new ImageData(this.data, this.w, this.h), 0, 0)
    return s
  }
}

// --- Himmel --------------------------------------------------------------------

export function paintSky(L: Landscape, P: WinterPalette): Raster {
  const R = new Raster(L.w, L.h)
  const bands = P.sky.length - 1
  const end = Math.max(4, L.horizon)
  const light = P.variant === 'light'
  for (let y = 0; y < L.h; y++) {
    const pos = clamp((y / end) * bands, 0, bands)
    const i = Math.min(bands - 1, Math.floor(pos))
    // Flächige Bänder, nur die Übergänge gerastert.
    const f = clamp((pos - i - 0.5) * 3.2 + 0.5, 0, 1)
    for (let x = 0; x < L.w; x++) R.set(x, y, f > bayer(x, y) ? P.sky[i + 1]! : P.sky[i]!)
  }

  // Hof um Mond/Sonne: zwei gerasterte Ringe.
  const { x: mx, y: my, r } = L.moon
  const reach = Math.round(r * (light ? 4.2 : 3.2))
  for (let y = Math.max(0, my - reach); y < Math.min(L.h, my + reach); y++) {
    for (let x = Math.max(0, mx - reach); x < Math.min(L.w, mx + reach); x++) {
      const d = Math.hypot(x - mx, y - my)
      if (d <= r || d >= reach) continue
      const t = 1 - (d - r) / (reach - r)
      const q = Math.floor(t * t * 3 + bayer(x, y)) / 3
      if (q <= 0) continue
      const i = (y * L.w + x) * 4
      const base: Rgb = [R.data[i]!, R.data[i + 1]!, R.data[i + 2]!]
      R.set(x, y, mix(base, P.halo, q * (light ? 0.4 : 0.16)))
    }
  }

  if (!light) {
    for (const s of L.stars) {
      R.set(s.x, s.y, s.bright ? P.star : P.starDim)
      if (s.big) {
        for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]] as const) R.set(s.x + dx, s.y + dy, P.starDim)
      }
    }
  }
  paintMoon(R, L, P)
  return R
}

function paintMoon(R: Raster, L: Landscape, P: WinterPalette) {
  const { x: mx, y: my, r } = L.moon
  const light = P.variant === 'light'
  const inside = (dx: number, dy: number) => dx * dx + dy * dy <= r * r + r * 0.8
  for (let dy = -r - 1; dy <= r + 1; dy++) {
    for (let dx = -r - 1; dx <= r + 1; dx++) {
      if (!inside(dx, dy)) continue
      // Rand unten links dunkler, dazu eine schmale Schattensichel.
      const rim = !inside(dx - 1, dy) || !inside(dx, dy + 1)
      const shade = !light && -dx + dy > r * 1.05
      R.set(mx + dx, my + dy, rim || shade ? P.moonShade : P.moon)
    }
  }
  if (light) return
  // Krater: ein paar kleine Flecken.
  const craters: [number, number, number][] = [
    [-0.35, -0.3, 1],
    [0.3, 0.15, r >= 7 ? 2 : 1],
    [-0.1, 0.45, 1],
    [0.45, -0.4, 1],
  ]
  for (const [cx, cy, cr] of craters) {
    const ox = Math.round(mx + cx * r)
    const oy = Math.round(my + cy * r)
    for (let dy = -cr + 1; dy < cr; dy++) {
      for (let dx = -cr + 1; dx < cr; dx++) if (inside(ox + dx - mx, oy + dy - my)) R.set(ox + dx, oy + dy, P.moonCrater)
    }
    if (cr === 1) R.set(ox, oy, P.moonCrater)
  }
}

// --- Landschaft ----------------------------------------------------------------

export function paintLand(L: Landscape, P: WinterPalette): Raster {
  const R = new Raster(L.w, L.h)
  paintDomes(R, L, L.mountainsBack, P, 0.62)
  paintDomes(R, L, L.mountains, P, 0.42)
  paintForest(R, L, P)
  for (const t of L.pines) if (t.layer === 0) paintPine(R, t, P, 0.48)
  paintSnowfield(R, L, L.hills, P, 0.16, Math.max(22, Math.round(L.h * 0.45)))
  for (const t of L.pines) if (t.layer === 1) paintShadow(R, t, P, 0.16)
  for (const t of L.pines) if (t.layer === 1) paintPine(R, t, P, 0.16)
  paintSnowfield(R, L, L.ground, P, 0, 34)
  for (const t of L.tufts) paintTuft(R, t.x, t.y, t.h, P)
  for (const t of L.pines) if (t.layer === 2) paintShadow(R, t, P, 0)
  for (const t of L.pines) if (t.layer === 2) paintPine(R, t, P, 0)
  if (L.cabin) {
    const spec = CABINS[L.cabin.size]
    const x0 = L.cabin.x
    const oy = L.cabin.y - spec.h + 1
    if (L.cabin.size === 'large') paintCabinLarge(R, x0, oy, P)
    else paintCabinSmall(R, x0, oy, P, L.cabin.penguin)
  }
  paintLanternFrames(R, L, P)
  return R
}

/**
 * Runde Schneekuppen: oben eine Schneehaube, die der Kuppe folgt, darunter Fels.
 * Licht von rechts oben – nach rechts abfallende Flanken sind hell.
 */
function paintDomes(R: Raster, L: Landscape, line: Int16Array, P: WinterPalette, haze: number) {
  const { w, horizon } = L
  const s = (L.seed & 0xffff) + Math.round(haze * 100)
  const at = (x: number) => line[clamp(x, 0, w - 1)]!
  let peak = horizon
  for (let x = 0; x < w; x++) peak = Math.min(peak, at(x))
  for (let x = 0; x < w; x++) {
    const top = at(x)
    if (top >= horizon) continue
    const slope = at(x + 2) - at(x - 2)
    // Licht von rechts oben: die nach rechts abfallenden Flanken sind hell.
    const lit = slope > 0 || (slope === 0 && at(x + 5) >= at(x - 5))
    // Schneegrenze nach Höhe (nicht je Spalte), gezackt; darunter Rinnen mit Schnee.
    const snowline = Math.round(horizon - (horizon - peak) * 0.45 + 2.5 * (smooth(x / 3, s) - 0.5))
    const gully = smooth(x / 1.7, s + 3) > 0.72
    for (let y = top; y <= horizon + 2 && y < L.h; y++) {
      const depth = y - top
      let c: Rgb
      if (depth === 0) c = lit ? P.snowLit : P.snow
      else if (y < snowline) c = lit ? P.snow : P.snowShade
      else if (y === snowline && bayer(x, y) < 0.5) c = lit ? P.snowShade : P.snowDeep
      else if (gully && y < snowline + (horizon - snowline) * 0.6 && bayer(x, y) < 0.6) c = lit ? P.snowShade : P.snowDeep
      else c = lit ? P.rock : P.rockShade
      // Dunst: zum Fuß hin mehr (Tal im Nebel).
      const mist = banded((y - (horizon - L.mountainAmp * 0.5)) / (L.mountainAmp * 0.5), 2, x, y)
      R.set(x, y, hazed(c, P.haze, (haze + 0.22 * mist) * P.hazeScale))
    }
  }
}

function paintForest(R: Raster, L: Landscape, P: WinterPalette) {
  const bottom = Math.min(L.h - 1, Math.max(...L.hills) + 2)
  for (let x = 0; x < L.w; x++) {
    const top = L.forest[x]!
    for (let y = top; y <= bottom; y++) {
      const depth = y - top
      let c: Rgb = noise(x, y, 37) < 0.18 ? P.pine : P.pineDark
      if (depth === 0) c = noise(x, 0, 39) < 0.55 ? P.snowShade : P.pine
      else if (depth === 1 && noise(x, 1, 39) < 0.3) c = P.snowDeep
      R.set(x, y, hazed(c, P.haze, 0.5 * P.hazeScale))
    }
  }
}

/** Verschneite Fläche (Hügel oder Vordergrund) mit Oberkante, Verlauf und Neigung. */
function paintSnowfield(R: Raster, L: Landscape, line: Int16Array, P: WinterPalette, haze: number, fade: number) {
  const at = (x: number) => line[clamp(x, 0, L.w - 1)]!
  const tones = [P.snowLit, P.snow, P.snowShade, P.snowDeep]
  const s = (L.seed & 0xffff) + fade
  for (let x = 0; x < L.w; x++) {
    const top = at(x)
    // Nur echte Hänge (über ein paar Spalten gemittelt) fangen Licht – sonst Streifen.
    const slope = at(x + 4) + at(x + 2) - at(x - 2) - at(x - 4)
    const lit = slope >= 3
    const shaded = slope <= -3
    for (let y = Math.max(0, top); y < L.h; y++) {
      const depth = y - top
      let c: Rgb
      if (depth < 2 || (lit && depth < 4)) c = shaded ? P.snow : P.snowLit
      else {
        // Flächige Stufen nach unten: Schnee → Schatten → tiefer Schatten.
        const step = Math.round(banded((depth - 2) / fade, 2, x, y) * 2)
        let k = 1 + step
        // Verwehungen: kurze waagrechte Rippen im Schnee.
        const rib = noise((x + Math.round(4 * smooth(y / 3, s))) >> 2, y, s)
        if (rib < 0.05) k = Math.max(0, k - 1)
        else if (rib > 0.965) k = Math.min(3, k + 1)
        c = tones[clamp(k, 0, 3)]!
      }
      R.set(x, y, hazed(c, P.haze, haze * P.hazeScale))
    }
  }
}

/** Schatten einer Tanne auf dem Schnee, nach links (Licht von rechts oben). */
function paintShadow(R: Raster, t: Pine, P: WinterPalette, haze: number) {
  const len = Math.round(t.h * 0.45)
  // Flach auf der Ebene, auf der die Tanne steht.
  const y = t.base - 1
  for (let k = 0; k < len; k++) {
    const x = t.x - k
    if (x < 0 || x >= R.w) continue
    const fade = k / len
    if (fade > 0.5 && fade - 0.5 > bayer(x, y) * 0.5) continue
    R.set(x, y, hazed(P.snowShade, P.haze, haze, x, y))
    if (k < len * 0.6) R.set(x, y + 1, hazed(P.snowShade, P.haze, haze, x, y + 1))
  }
}

/** Tanne mit Etagen, Schnee auf jedem Absatz, Licht von rechts. */
export function paintPine(R: Raster, t: Pine, P: WinterPalette, haze: number) {
  const { x: cx, base, h } = t
  const detail = t.layer > 0
  const trunkH = Math.max(1, Math.round(h * (detail ? 0.1 : 0.14)))
  const top = base - h
  const crownH = h - trunkH
  if (crownH < 2) return
  const maxHalf = Math.max(1, h * (detail ? 0.25 : 0.3))
  const tiers = clamp(Math.round(crownH / (detail ? 6.5 : 3.5)), 2, 12)
  const half = new Int16Array(crownH)
  const ledge = new Uint8Array(crownH)
  const s = t.seed & 0xffff
  for (let i = 0; i < crownH; i++) {
    let best = 0
    let isLedge = 0
    for (let k = 0; k < tiers; k++) {
      const ts = Math.floor(((crownH * k) / tiers) * 0.9)
      const te = Math.floor((crownH * (k + 1)) / tiers)
      if (i < ts || i > te) continue
      const local = (i - ts) / Math.max(1, te - ts)
      const width = (0.22 + 0.78 * local) * maxHalf * (0.3 + (0.7 * (k + 1)) / tiers)
      if (width >= best) {
        best = width
        isLedge = i === te ? 1 : 0
      }
    }
    let hw = Math.round(best)
    if (detail && hw > 2 && noise(i, s, 41) < 0.25) hw -= 1
    half[i] = i === 0 ? 0 : hw
    ledge[i] = isLedge
  }
  const put = (x: number, y: number, c: Rgb) => R.set(x, y, haze > 0 ? hazed(c, P.haze, haze * P.hazeScale) : c)

  // Stamm (unter der Krone sichtbar).
  const tw = h >= 40 ? 2 : 1
  for (let y = base - trunkH - 1; y < base; y++) {
    for (let k = 0; k < tw; k++) put(cx - (tw > 1 ? 1 : 0) + k, y, k === tw - 1 && tw > 1 ? mix(P.trunk, P.wood, 0.5) : P.trunk)
  }
  for (let i = 0; i < crownH; i++) {
    const y = top + i
    const hw = half[i]!
    const above = i > 0 ? half[i - 1]! : -1
    const above2 = i > 1 ? half[i - 2]! : -1
    for (let dx = -hw; dx <= hw; dx++) {
      const x = cx + dx
      const ad = Math.abs(dx)
      let c: Rgb
      if (ad > above) c = dx > hw * 0.2 ? P.snowLit : dx < -hw * 0.45 ? P.snowShade : P.snow
      else if (detail && ad > above2 && noise(x, y, 43) < 0.6) c = dx > 0 ? P.snow : P.snowShade
      else if (!detail) c = dx > 0 && ad >= hw - 1 ? P.pine : P.pineDark
      else {
        const n = noise(x, y, 47)
        c = dx > hw * 0.35 ? P.pineLit : dx < -hw * 0.25 ? P.pineDark : P.pine
        if (n < 0.14) c = c === P.pineLit ? P.pine : P.pineDark
        else if (n > 0.965) c = dx > 0 ? P.snow : P.snowShade
      }
      put(x, y, c)
    }
    // Hängende Zweigspitzen unter jedem Absatz.
    if (detail && ledge[i] && hw > 2) {
      put(cx - hw - 1, y + 1, P.pineDark)
      put(cx + hw + 1, y + 1, P.pine)
      put(cx + hw + 1, y, P.snow)
    }
  }
}

/** Kleine Blockhütte (Banner, Vorschau): verschneites Dach, Schornstein, warmes Fenster. */
export function paintCabinSmall(R: Raster, x0: number, oy: number, P: WinterPalette, penguin: boolean) {
  const H = CABINS.small.h
  const put = (rx: number, ry: number, c: Rgb) => R.set(x0 + rx, oy + ry, c)
  // Wände aus Stämmen.
  for (let ry = 9; ry < H; ry++) {
    for (let rx = 3; rx <= 18; rx++) {
      const end = rx === 3 || rx === 18
      let c = ry % 2 === 0 ? P.wood : P.woodDark
      if (end && ry % 2 === 0) c = mix(P.wood, P.snowLit, 0.25)
      put(rx, ry, c)
    }
  }
  // Fenster (warm) mit Kreuz und verschneitem Sims.
  for (let ry = 10; ry <= 15; ry++) {
    for (let rx = 5; rx <= 11; rx++) {
      const frame = ry === 10 || ry === 15 || rx === 5 || rx === 11 || rx === 8 || ry === 12
      put(rx, ry, frame ? P.windowFrame : ry === 11 && rx < 8 ? mix(P.windowOn, [255, 255, 255], 0.35) : P.windowOn)
    }
  }
  for (let rx = 4; rx <= 12; rx++) put(rx, 15, rx === 4 || rx === 12 ? P.snow : P.snowLit)
  // Tür.
  for (let ry = 11; ry < H; ry++) {
    for (let rx = 13; rx <= 16; rx++) put(rx, ry, rx === 13 || ry === 11 ? P.windowFrame : P.woodDark)
  }
  put(15, 14, P.windowOn)
  // Schornstein.
  for (let ry = 0; ry <= 5; ry++) {
    for (let rx = 15; rx <= 17; rx++) put(rx, ry, ry === 0 ? P.snowLit : (rx + ry) % 3 === 0 ? P.rockShade : P.rock)
  }
  // Dach: ganz verschneit, rechts im Licht, Kante unten im Schatten.
  for (let ry = 1; ry <= 8; ry++) {
    const inset = 8 - ry
    for (let rx = inset; rx <= 21 - inset; rx++) {
      if (rx >= 15 && rx <= 17 && ry <= 5) continue
      let c: Rgb = rx > 10 ? P.snowLit : P.snow
      if (ry === 8) c = P.snowShade
      else if (rx === inset) c = P.snowShade
      put(rx, ry, c)
    }
  }
  // Traufe und Eiszapfen.
  for (let rx = 3; rx <= 18; rx++) put(rx, 9, P.woodDark)
  for (const [rx, len] of [[1, 2], [2, 1], [6, 1], [19, 2], [20, 1]] as const) {
    for (let k = 0; k < len; k++) put(rx, 9 + k, k === len - 1 ? P.snowShade : P.snowLit)
  }
  // Schneewehe an der Wand.
  for (let rx = 1; rx <= 20; rx++) {
    if (rx > 4 && rx < 18) continue
    put(rx, H - 1, P.snowLit)
    if (rx < 3 || rx > 19) put(rx, H - 2, P.snow)
  }
  // Ganz klein: der Pinguin steht neben der Tür.
  if (penguin) {
    TINY_PENGUIN.forEach((row, y) => {
      for (let x = 0; x < row.length; x++) {
        const c = row[x]!
        if (c !== '.') put(18 + x, H - TINY_PENGUIN.length + y, PENGUIN_COLORS[c]!)
      }
    })
  }
}

/**
 * Große Blockhütte vorn: dickes Schneedach mit Eiszapfen, Schornstein, Stammwände
 * mit Balkenköpfen, zwei warm erleuchtete Fenster, Tür und eine Laterne am Weg.
 * Maße siehe `CABINS.large` (46 × 36). Fenster und Tür malt der Takt darüber neu,
 * wenn der Pinguin hinausschaut oder die Tür öffnet.
 */
export function paintCabinLarge(R: Raster, x0: number, oy: number, P: WinterPalette) {
  const spec = CABINS.large
  const H = spec.h
  const put = (rx: number, ry: number, c: Rgb) => R.set(x0 + rx, oy + ry, c)
  const woodHi = mix(P.wood, P.snowLit, 0.18)
  const logEnd = mix(P.wood, P.snowLit, 0.32)

  // Schornstein aus Feldsteinen (hinter dem Dach).
  for (let ry = 1; ry <= 10; ry++) {
    for (let rx = 31; rx <= 35; rx++) {
      const mortar = ry % 3 === 0 || (rx + (ry % 6 < 3 ? 0 : 2)) % 4 === 0
      put(rx, ry, mortar ? P.rockShade : rx === 35 ? P.rockShade : P.rock)
    }
  }
  for (let rx = 30; rx <= 36; rx++) put(rx, 0, rx === 30 || rx === 36 ? P.snow : P.snowLit)

  // Wände: Stämme zu je drei Zeilen (Licht oben, Schatten unten), Balkenköpfe an den Ecken.
  for (let ry = 16; ry < H; ry++) {
    const k = (ry - 16) % 3
    for (let rx = 4; rx <= 41; rx++) put(rx, ry, k === 0 ? woodHi : k === 1 ? P.wood : P.woodDark)
    if (k === 1) {
      for (const rx of [3, 42]) put(rx, ry, logEnd)
      for (const rx of [2, 43]) put(rx, ry, P.woodDark)
    }
  }

  // Fenster links (groß) mit Kreuz, Glanz oben und verschneitem Sims.
  for (let ry = 20; ry <= 28; ry++) {
    for (let rx = 8; rx <= 18; rx++) {
      const frame = ry === 20 || ry === 28 || rx === 8 || rx === 18
      put(rx, ry, frame ? P.windowFrame : P.windowOn)
    }
  }
  paintWindowLight(R, x0, oy, spec.windows[0]!, P)
  for (let rx = 7; rx <= 19; rx++) {
    put(rx, 29, rx === 7 || rx === 19 ? P.snow : P.snowLit)
    put(rx, 30, P.woodDark)
  }
  // Fenster rechts (klein).
  for (let ry = 20; ry <= 25; ry++) {
    for (let rx = 37; rx <= 41; rx++) put(rx, ry, ry === 20 || ry === 25 || rx === 37 || rx === 41 ? P.windowFrame : P.windowOn)
  }
  paintWindowLight(R, x0, oy, spec.windows[1]!, P)
  for (let rx = 36; rx <= 42; rx++) put(rx, 26, P.snowLit)

  paintDoor(R, x0, oy, P, 0)

  // Dach: dicker Schnee, rechts im Licht; die Kante hängt über die Wände.
  const ridge = new Uint8Array(spec.w)
  for (let ry = 3; ry <= 15; ry++) {
    const inset = Math.round((15 - ry) * 1.75)
    for (let rx = inset; rx <= 45 - inset; rx++) {
      let c: Rgb = rx > 23 ? P.snowLit : P.snow
      if (rx === inset || rx === inset + 1) c = P.snowShade
      if (ry >= 14) c = ry === 15 ? P.snowDeep : P.snowShade
      // Oberkante (erste Zeile dieser Spalte): heller Grat.
      if (!ridge[rx] && ry < 13) c = rx > 22 ? P.sparkle : P.snowLit
      ridge[rx] = 1
      put(rx, ry, c)
    }
  }
  // Traufe, dann Eiszapfen in verschiedenen Längen.
  for (let rx = 2; rx <= 43; rx++) put(rx, 16, P.woodDark)
  for (const [rx, len] of [[2, 3], [5, 1], [9, 2], [14, 1], [20, 3], [25, 1], [30, 2], [36, 1], [40, 3], [43, 2]] as const) {
    for (let k = 0; k < len; k++) put(rx, 16 + k, k === len - 1 ? P.snowShade : P.snowLit)
  }

  // Laterne am Weg (warmes Licht) auf einem Pfosten.
  const l = spec.lantern!
  for (let ry = l.y + 3; ry < H; ry++) put(l.x, ry, P.woodDark)
  for (let rx = l.x - 2; rx <= l.x + 2; rx++) put(rx, l.y - 2, P.windowFrame)
  for (let rx = l.x - 1; rx <= l.x + 1; rx++) {
    put(rx, l.y - 3, P.snowLit)
    put(rx, l.y + 2, P.windowFrame)
  }
  for (let ry = l.y - 1; ry <= l.y + 1; ry++) {
    put(l.x - 2, ry, P.windowFrame)
    put(l.x + 2, ry, P.windowFrame)
    for (let rx = l.x - 1; rx <= l.x + 1; rx++) put(rx, ry, rx === l.x && ry === l.y ? mix(P.windowOn, [255, 255, 255], 0.6) : P.windowOn)
  }

  // Schneewehen an den Ecken und vor der Wand.
  for (let rx = 0; rx <= 45; rx++) {
    const corner = rx < 6 || rx > 39
    if (corner) {
      const hgt = rx < 6 ? 6 - rx : rx - 39
      for (let k = 0; k < Math.min(4, hgt); k++) put(rx, H - 1 - k, k === Math.min(4, hgt) - 1 ? P.snowLit : P.snow)
    } else if (rx < spec.door.x0 || rx > spec.door.x1) put(rx, H - 1, P.snowLit)
  }
}

/** Fensterglas (ohne Sprossen, damit man den Pinguin sieht) mit Glanz oben links. */
function paintWindowLight(R: Pixels, x0: number, oy: number, g: CabinRect, P: WinterPalette) {
  const hi = mix(P.windowOn, [255, 255, 255], 0.35)
  const mx = Math.round((g.x0 + g.x1) / 2)
  for (let ry = g.y0; ry <= g.y1; ry++) {
    for (let rx = g.x0; rx <= g.x1; rx++) {
      let c = P.windowOn
      if (ry === g.y0 && rx < mx) c = hi
      R.set(x0 + rx, oy + ry, c)
    }
  }
}

/** Tür: 0 zu, 1 halb, 2 offen (dann warmes Licht von innen). */
function paintDoor(R: Pixels, x0: number, oy: number, P: WinterPalette, open: 0 | 1 | 2) {
  const d = CABINS.large.door
  const put = (rx: number, ry: number, c: Rgb) => R.set(x0 + rx, oy + ry, c)
  for (let ry = d.y0; ry <= d.y1; ry++) {
    for (let rx = d.x0; rx <= d.x1; rx++) {
      const frame = rx === d.x0 || rx === d.x1 || ry === d.y0
      if (frame) {
        put(rx, ry, P.windowFrame)
        continue
      }
      const leaf = rx - d.x0 - 1
      const width = d.x1 - d.x0 - 1
      const inside = mix(P.windowOn, P.woodDark, ry > d.y1 - 2 ? 0.6 : 0.35)
      if (open === 0) put(rx, ry, leaf % 2 === 0 ? P.wood : P.woodDark)
      else if (open === 1) put(rx, ry, leaf < width / 2 ? inside : leaf % 2 === 0 ? P.wood : P.woodDark)
      else put(rx, ry, leaf >= width - 1 ? P.woodDark : inside)
    }
  }
  if (open === 0) {
    // Kleines Fenster in der Tür und Klinke.
    put(d.x0 + 3, d.y0 + 2, P.windowOn)
    put(d.x0 + 4, d.y0 + 2, P.windowOn)
    put(d.x0 + 3, d.y0 + 3, P.windowOn)
    put(d.x0 + 4, d.y0 + 3, P.windowOn)
    put(d.x1 - 2, d.y0 + 7, mix(P.windowOn, [255, 255, 255], 0.3))
  }
}

/** Was je Takt an der Hütte neu gemalt wird: Fenster (mit Pinguin), Tür, Pinguin draußen. */
export function paintCabinLive(g: Ctx, x0: number, oy: number, P: WinterPalette, v: PenguinView) {
  const spec = CABINS.large
  const px = (rx: number, ry: number, c: Rgb) => {
    g.fillStyle = css(c)
    g.fillRect(x0 + rx, oy + ry, 1, 1)
  }
  const target = { set: (x: number, y: number, c: Rgb) => {
    g.fillStyle = css(c)
    g.fillRect(x, y, 1, 1)
  } }
  const win = spec.windows[0]!
  if (v.phase === 'window') {
    // Fenster neu, dann Kopf mit Mütze hinter der Scheibe, Kreuz wieder darüber.
    paintWindowLight(target, x0, oy, win, P)
    for (const [x, y, c] of penguinPixels({ step: 0, wave: null })) {
      const rx = v.x + x
      const ry = win.y0 + y + v.dip
      if (ry > win.y1 || rx < win.x0 || rx > win.x1) continue
      px(rx, ry, PENGUIN_COLORS[c]!)
    }
  }
  if (v.door !== 0) paintDoor(target, x0, oy, P, v.door)
  if (v.phase !== 'window' && v.phase !== 'opening' && v.phase !== 'closing') {
    const bob = v.step === 1 ? 1 : 0
    for (const [x, y, c] of penguinPixels(v)) px(v.x + x, spec.h - PENGUIN_H + y - (v.phase === 'walkOut' || v.phase === 'walkIn' ? bob : 0), PENGUIN_COLORS[c]!)
  }
}

function paintTuft(R: Raster, x: number, y: number, h: number, P: WinterPalette) {
  const c = mix(P.trunk, P.snowShade, 0.35)
  for (let k = 1; k <= h; k++) R.set(x, y - k, c)
  for (let k = 1; k < h; k++) R.set(x + 1, y - k, mix(c, P.snowDeep, 0.4))
  R.set(x - 1, y - 1, c)
}

/** Pfosten, Rahmen und Schneehauben der Laternen; das Glas malt jeder Takt. */
function paintLanternFrames(R: Raster, L: Landscape, P: WinterPalette) {
  const f = P.lanternFrame
  L.lanterns.forEach((l, i) => {
    const { x, y } = l
    const g = L.ground[clamp(x, 0, L.w - 1)]!
    for (let py = y + 2; py <= g; py++) R.set(x, py, P.woodDark)
    for (const dx of [-1, 1]) R.set(x + dx, g, P.snowLit)
    for (let dy = -1; dy <= 1; dy++) {
      R.set(x - 2, y + dy, f)
      R.set(x + 2, y + dy, f)
    }
    for (let dx = -2; dx <= 2; dx++) R.set(x + dx, y - 2, f)
    for (let dx = -1; dx <= 1; dx++) R.set(x + dx, y + 2, f)
    for (let dx = -1; dx <= 1; dx++) R.set(x + dx, y - 3, dx === 1 ? P.snowLit : P.snow)
    // Durchhängende Schnur zur nächsten Laterne.
    const next = L.lanterns[i + 1]
    if (!next) return
    const a = x + 3
    const b = next.x - 3
    for (let px = a; px <= b; px++) {
      const t = (px - a) / Math.max(1, b - a)
      R.set(px, Math.round(y - 2 + 2.6 * Math.sin(Math.PI * t)), f)
    }
  })
}

/** Wolke als kleine Fläche (nur am Tag). */
export function paintCloud(c: Cloud, P: WinterPalette): Surface {
  const R = new Raster(c.w, c.h)
  const bumps = 3 + (c.seed % 3)
  for (let y = 0; y < c.h; y++) {
    for (let x = 0; x < c.w; x++) {
      let inside = y >= c.h * 0.45 && x >= 1 && x < c.w - 1
      for (let k = 0; k < bumps && !inside; k++) {
        const bx = ((k + 0.5) / bumps) * c.w
        const br = c.h * (0.38 + noise(k, 0, c.seed & 0xffff) * 0.25)
        const by = c.h - br
        inside = (x - bx) ** 2 + ((y - by) * 1.15) ** 2 <= br * br
      }
      if (!inside) continue
      const shade = y >= c.h - 2 || (y > c.h * 0.6 && x < c.w * 0.3)
      R.set(x, y, shade ? P.cloudShade : P.cloud)
    }
  }
  return R.toSurface()
}

// --- Polarlicht ------------------------------------------------------------------

const smoothstep = (a: number, b: number, v: number) => {
  const t = clamp((v - a) / (b - a), 0, 1)
  return t * t * (3 - 2 * t)
}

/**
 * Polarlicht wie echt: Vorhänge mit scharfer, hellgrüner Unterkante, darüber grüne
 * Strahlen, die nach oben ausbleichen und in Violett übergehen; Falten wandern langsam.
 * `img`: Breite = Landschaft, Höhe = bis zum Horizont.
 */
export function paintAurora(img: ImageData, L: Landscape, P: WinterPalette, seconds: number, level: number) {
  const d = img.data
  d.fill(0)
  if (level <= 0.02 || P.variant === 'light') return
  const W = img.width
  const H = img.height
  const sky = Math.max(10, L.horizon - L.mountainAmp * 0.45)
  const maxA = P.variant === 'oled' ? 0.95 : 0.88
  const s = (L.seed & 0xffff) + 51
  const t = seconds
  const [edge, core, deep, top] = P.aurora
  // Zwei Vorhänge: vorn kräftig, hinten höher und blasser.
  const curtains = [
    { base: 0.74, height: 0.5, phase: 0, power: 1 },
    { base: 0.5, height: 0.38, phase: 2.1, power: 0.55 },
  ]
  for (let x = 0; x < W; x++) {
    const u = x / W
    // Links (hinter dem Text) schwächer.
    const across = 0.3 + 0.7 * smoothstep(0.08, 0.4, u)
    for (const c of curtains) {
      // Gewundene Unterkante wie ein Band im Wind.
      const yb = sky * (c.base + 0.07 * Math.sin(x * 0.016 + t * 0.07 + c.phase) + 0.04 * Math.sin(x * 0.047 - t * 0.12 + c.phase * 2))
      const hc = sky * c.height * (0.75 + 0.35 * smooth(x * 0.02 + t * 0.03, s + c.phase * 10))
      // Falten (hell, wo das Band umschlägt) und feine senkrechte Strahlen.
      const fold = 0.55 + 0.45 * Math.pow(Math.abs(Math.sin(x * 0.034 + t * 0.11 + c.phase)), 0.6)
      const ray = 0.25 + 0.75 * Math.pow(smooth(x * 0.42 + t * 0.6, s + 3), 1.6)
      const shimmer = 0.85 + 0.15 * Math.sin(t * 2.3 + x * 0.9)
      const I = level * across * fold * ray * shimmer * c.power
      if (I < 0.05) continue
      const y0 = Math.max(0, Math.floor(yb - hc))
      const y1 = Math.min(H - 1, Math.ceil(yb + 2))
      for (let y = y0; y <= y1; y++) {
        // v = 0 an der Unterkante, 1 oben.
        const v = (yb - y) / hc
        let a: number
        if (v < 0) a = Math.max(0, 1 + v * (hc / 2)) * 0.6
        else a = Math.exp(-v * 2.4) * (v < 0.05 ? 1.15 : 1)
        a *= I
        // Pixel-Stufen statt weichem Verlauf.
        const q = Math.round(a * 6) / 6
        if (q <= 0) continue
        const col = v < 0.06 ? edge : v < 0.42 ? core : v < 0.72 ? deep : top
        const i = (y * W + x) * 4
        const alpha = Math.round(Math.min(1, q) * maxA * 255)
        if (alpha <= d[i + 3]!) continue
        d[i] = col[0]
        d[i + 1] = col[1]
        d[i + 2] = col[2]
        d[i + 3] = alpha
      }
    }
  }
}

// --- Bild je Takt ------------------------------------------------------------------

export interface WinterFrameState {
  tick: number
  seconds: number
  /** Polarlicht 0…1. */
  aurora: number
  /** Wie viele Laternen leuchten (von links). */
  lit: number
  /** Laternen flackern leicht (Spiel läuft). */
  flicker: boolean
  flakes: readonly Flake[]
  smoke: readonly Puff[]
  /** Pinguin an der großen Hütte (sonst weggelassen). */
  penguin?: PenguinView | null
}

/** Hält die fertigen Ebenen einer Landschaft und setzt daraus jedes Bild zusammen. */
export class WinterPainter {
  readonly sky: Surface
  readonly land: Surface
  private readonly clouds: Surface[]
  private readonly auroraSurface: Surface | null
  private readonly auroraImage: ImageData | null
  private auroraDrawn = false

  constructor(
    readonly L: Landscape,
    readonly P: WinterPalette,
  ) {
    this.sky = paintSky(L, P).toSurface()
    this.land = paintLand(L, P).toSurface()
    this.clouds = P.variant === 'light' ? L.clouds.map((c) => paintCloud(c, P)) : []
    const ah = Math.max(1, Math.min(L.h, L.horizon))
    if (P.variant === 'light') {
      this.auroraSurface = null
      this.auroraImage = null
    } else {
      this.auroraSurface = surface(L.w, ah)
      this.auroraImage = new ImageData(L.w, ah)
    }
  }

  frame(g: Ctx, s: WinterFrameState) {
    const { L, P } = this
    g.clearRect(0, 0, L.w, L.h)
    g.drawImage(this.sky, 0, 0)

    // Wolken ziehen langsam nach links (nur am Tag).
    this.clouds.forEach((img, i) => {
      const c = L.clouds[i]!
      const span = L.w + c.w * 2
      const x = Math.floor((((c.x + c.w - s.tick * c.speed) % span) + span) % span) - c.w
      g.drawImage(img, x, c.y)
    })

    // Funkelnde Sterne.
    if (P.variant !== 'light') {
      const phase = Math.floor(s.tick / 5)
      L.stars.forEach((st, i) => {
        if (!st.twinkle) return
        const n = noise(i, phase, 61)
        if (n < 0.3) {
          g.fillStyle = css(P.starDim)
          g.fillRect(st.x, st.y, 1, 1)
        } else if (n > 0.86) {
          g.fillStyle = css(P.star)
          g.fillRect(st.x, st.y, 1, 1)
          g.fillStyle = css(P.starDim)
          g.fillRect(st.x - 1, st.y, 3, 1)
          g.fillRect(st.x, st.y - 1, 1, 3)
          g.fillStyle = css(P.star)
          g.fillRect(st.x, st.y, 1, 1)
        }
      })
    }

    if (this.auroraSurface && this.auroraImage) {
      if (s.aurora > 0.02) {
        paintAurora(this.auroraImage, L, P, s.seconds, s.aurora)
        ctx2d(this.auroraSurface).putImageData(this.auroraImage, 0, 0)
        this.auroraDrawn = true
        g.drawImage(this.auroraSurface, 0, 0)
      } else if (this.auroraDrawn) {
        ctx2d(this.auroraSurface).clearRect(0, 0, L.w, L.h)
        this.auroraDrawn = false
      }
    }

    g.drawImage(this.land, 0, 0)

    // Der Pinguin: im Fenster, in der Tür oder draußen.
    if (s.penguin && L.cabin?.size === 'large') {
      paintCabinLive(g, L.cabin.x, L.cabin.y - CABINS.large.h + 1, P, s.penguin)
    }

    // Laternenglas.
    L.lanterns.forEach((l, i) => {
      const on = i < s.lit
      const flick = on && s.flicker && noise(i, s.tick >> 1, 67) < 0.12
      g.fillStyle = css(on ? (flick ? mix(P.lanternOn, P.lanternOff, 0.35) : P.lanternOn) : P.lanternOff)
      g.fillRect(l.x - 1, l.y - 1, 3, 3)
      g.fillStyle = css(on ? P.lanternCore : mix(P.lanternOff, P.snowLit, 0.3))
      if (on) g.fillRect(l.x, l.y, 1, 1)
      else g.fillRect(l.x + 1, l.y - 1, 1, 1)
    })

    // Glitzern im Schnee.
    const sp = Math.floor(s.tick / 2)
    g.fillStyle = css(P.sparkle)
    L.sparkles.forEach((p, i) => {
      if (noise(i, sp, 71) > 0.035) return
      g.fillRect(p.x, p.y, 1, 1)
    })

    // Rauch aus dem Schornstein.
    for (const p of s.smoke) {
      const age = 1 - p.life / p.max
      if (age > 0.55 && age - 0.55 > bayer(p.x | 0, p.y | 0) * 0.45) continue
      g.fillStyle = css(P.smoke)
      const size = age < 0.25 ? 1 : age < 0.6 ? 2 : 3
      g.fillRect(Math.round(p.x), Math.round(p.y), size, size - (size > 1 ? 1 : 0))
    }

    // Schneefall: fern (blass), mittel, nah (2 × 2).
    for (const f of s.flakes) {
      const x = Math.round(f.x)
      const y = Math.round(f.y)
      if (f.size === 0) {
        g.fillStyle = css(P.flakeFar)
        g.fillRect(x, y, 1, 1)
      } else if (f.size === 1) {
        g.fillStyle = css(P.flake)
        g.fillRect(x, y, 1, 1)
      } else {
        g.fillStyle = css(P.flake)
        g.fillRect(x, y, 2, 1)
        g.fillRect(x, y + 1, 1, 1)
        g.fillStyle = css(P.flakeFar)
        g.fillRect(x + 1, y + 1, 1, 1)
      }
    }
  }

  /** Weiches Licht auf der groben Ebene (`scale` Texel je Pixel). */
  glow(g: Ctx, s: Pick<WinterFrameState, 'aurora' | 'lit' | 'flicker' | 'tick' | 'penguin'>, scale: number) {
    const { L, P } = this
    g.clearRect(0, 0, g.canvas.width, g.canvas.height)
    const light = (x: number, y: number, r: number, c: Rgb, a: number) => {
      const gx = x / scale
      const gy = y / scale
      const gr = Math.max(1, r / scale)
      const grad = g.createRadialGradient(gx, gy, 0, gx, gy, gr)
      grad.addColorStop(0, `rgb(${c[0] | 0} ${c[1] | 0} ${c[2] | 0} / ${a})`)
      grad.addColorStop(1, `rgb(${c[0] | 0} ${c[1] | 0} ${c[2] | 0} / 0)`)
      g.fillStyle = grad
      g.fillRect(gx - gr, gy - gr, gr * 2, gr * 2)
    }
    const dayFactor = P.variant === 'light' ? 0.5 : 1
    light(L.moon.x, L.moon.y, L.moon.r * 6, P.glowMoon, 0.3 * dayFactor)
    if (L.cabin) {
      // Warmes Licht aus Fenstern, Laterne und offener Tür.
      const spec = CABINS[L.cabin.size]
      const oy = L.cabin.y - spec.h + 1
      const big = L.cabin.size === 'large'
      for (const w of spec.windows) light(L.cabin.x + (w.x0 + w.x1) / 2, oy + (w.y0 + w.y1) / 2, big ? 14 : 12, P.glowWarm, big ? 0.5 : 0.55)
      if (spec.lantern) light(L.cabin.x + spec.lantern.x, oy + spec.lantern.y, 12, P.glowWarm, 0.6)
      if (big && s.penguin && s.penguin.door > 0) {
        const d = spec.door
        light(L.cabin.x + (d.x0 + d.x1) / 2, oy + d.y1 - 3, 16, P.glowWarm, s.penguin.door === 2 ? 0.6 : 0.35)
      }
    }
    L.lanterns.forEach((l, i) => {
      if (i >= s.lit) return
      const a = s.flicker && noise(i, s.tick >> 1, 67) < 0.12 ? 0.5 : 0.85
      light(l.x, l.y, 15, P.glowAccent, a)
    })
    if (s.aurora > 0.02 && P.variant !== 'light') {
      const sky = L.horizon - L.mountainAmp * 0.45
      const grad = g.createLinearGradient(0, (sky * 0.2) / scale, 0, (sky * 0.8) / scale)
      const c = P.aurora[1]
      grad.addColorStop(0, `rgb(${c[0] | 0} ${c[1] | 0} ${c[2] | 0} / 0)`)
      grad.addColorStop(0.7, `rgb(${c[0] | 0} ${c[1] | 0} ${c[2] | 0} / ${0.22 * s.aurora})`)
      grad.addColorStop(1, `rgb(${c[0] | 0} ${c[1] | 0} ${c[2] | 0} / 0)`)
      g.fillStyle = grad
      g.fillRect(0, 0, g.canvas.width, (sky * 0.9) / scale)
    }
  }
}
