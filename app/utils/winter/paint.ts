import { ctx2d, noise, surface, type Ctx, type Surface } from '../redstone/paint'
import type { Rgb } from '../redstone/palette'
import { CABIN_H, CABIN_W, smooth, type Cloud, type Landscape, type Pine } from './landscape'
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
  paintMountains(R, L, P)
  paintForest(R, L, P)
  for (const t of L.pines) if (t.layer === 0) paintPine(R, t, P, 0.48)
  paintSnowfield(R, L, L.hills, P, 0.2, 22)
  for (const t of L.pines) if (t.layer === 1) paintShadow(R, t, L.hills, P, 0.2)
  for (const t of L.pines) if (t.layer === 1) paintPine(R, t, P, 0.2)
  if (L.cabin) paintCabin(R, L.cabin.x, L.cabin.y, P, 0.12)
  paintSnowfield(R, L, L.ground, P, 0, 34)
  for (const t of L.tufts) paintTuft(R, t.x, t.y, t.h, P)
  for (const t of L.pines) if (t.layer === 2) paintShadow(R, t, L.ground, P, 0)
  paintLanternFrames(R, L, P)
  for (const t of L.pines) if (t.layer === 2) paintPine(R, t, P, 0)
  return R
}

function paintMountains(R: Raster, L: Landscape, P: WinterPalette) {
  const { w, horizon, mountainAmp: amp } = L
  const s = L.seed & 0xffff
  const at = (x: number) => L.mountains[clamp(x, 0, w - 1)]!
  for (let x = 0; x < w; x++) {
    const top = at(x)
    // Fällt der Hang nach rechts ab, scheint der Mond (rechts oben) darauf.
    const slope = at(x + 2) + at(x + 1) - at(x - 1) - at(x - 2)
    const lit = slope > 0 || (slope === 0 && at(x + 3) >= at(x - 3))
    const snowline = horizon - amp * 0.45 + Math.round(5 * smooth(x / 5, s + 21)) + (lit ? 4 : 0)
    for (let y = top; y <= horizon + 4 && y < L.h; y++) {
      const depth = y - top
      let snow = y < snowline || depth === 0
      // Unter der Schneegrenze: Rinnen mit Schnee, nach unten seltener.
      if (!snow && noise(x, y >> 1, 23) < 0.32 * (1 - (y - snowline) / 14)) snow = true
      let c: Rgb
      if (snow) {
        c = depth === 0 ? (lit ? P.snowLit : P.snow) : lit ? P.snow : P.snowShade
        if (!lit && depth > 1 && noise(x >> 1, y >> 2, 29) < 0.14) c = P.snowDeep
      } else c = lit ? P.rock : P.rockShade
      // Dunst: oben schon etwas, zum Fuß hin mehr (Tal im Nebel).
      const mist = banded((y - (horizon - amp * 0.4)) / (amp * 0.4), 3, x, y)
      R.set(x, y, hazed(c, P.haze, (0.4 + 0.36 * mist) * P.hazeScale))
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
function paintShadow(R: Raster, t: Pine, line: Int16Array, P: WinterPalette, haze: number) {
  const len = Math.round(t.h * 0.45)
  for (let k = 0; k < len; k++) {
    const x = t.x - k
    if (x < 0 || x >= R.w) continue
    const y = line[x]!
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

/** Kleine Blockhütte: verschneites Dach, Schornstein, warm erleuchtetes Fenster. */
export function paintCabin(R: Raster, x0: number, ground: number, P: WinterPalette, haze: number) {
  const oy = ground - CABIN_H + 1
  const put = (rx: number, ry: number, c: Rgb) => R.set(x0 + rx, oy + ry, haze > 0 ? hazed(c, P.haze, haze, x0 + rx, oy + ry) : c)
  // Wände aus Stämmen.
  for (let ry = 9; ry < CABIN_H; ry++) {
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
  for (let ry = 11; ry < CABIN_H; ry++) {
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
    put(rx, CABIN_H - 1, P.snowLit)
    if (rx < 3 || rx > 19) put(rx, CABIN_H - 2, P.snow)
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

/** Vorhänge des Polarlichts in `img` (Breite = Landschaft, Höhe = bis zum Horizont). */
export function paintAurora(img: ImageData, L: Landscape, P: WinterPalette, seconds: number, level: number) {
  const d = img.data
  d.fill(0)
  if (level <= 0.02 || P.variant === 'light') return
  const W = img.width
  const H = img.height
  const sky = Math.max(10, L.horizon - L.mountainAmp * 0.45)
  const maxA = P.variant === 'oled' ? 0.85 : 0.75
  const s = (L.seed & 0xffff) + 51
  const t = seconds
  for (let x = 0; x < W; x++) {
    const u = x / W
    const yb = sky * (0.62 + 0.1 * Math.sin(x * 0.019 + t * 0.09) + 0.06 * Math.sin(x * 0.051 - t * 0.13 + 1.3))
    const hc = sky * (0.36 + 0.1 * Math.sin(x * 0.012 + t * 0.05 + 2))
    // Links (hinter dem Text) schwächer, über die Breite in Wellen.
    const across = 0.25 + 0.75 * smoothstep(0.1, 0.45, u)
    const wave = 0.5 + 0.5 * Math.sin(x * 0.023 + t * 0.2)
    const rays = 0.35 + 0.65 * smooth(x * 0.3 + t * 0.7, s)
    const I = level * across * (0.35 + 0.65 * wave) * rays
    if (I < 0.04) continue
    const y0 = Math.max(0, Math.floor(yb - hc))
    const y1 = Math.min(H - 1, Math.ceil(yb + 3))
    for (let y = y0; y <= y1; y++) {
      const v = (y - (yb - hc)) / hc
      const a = (v <= 1 ? Math.pow(Math.max(0, v), 1.25) : Math.max(0, 1 - ((v - 1) * hc) / 3)) * I
      // Flächige Stufen statt Raster: die senkrechten Strahlen geben die Pixel-Struktur.
      const q = Math.round(a * 5) / 5
      if (q <= 0) continue
      const c = v > 0.9 ? P.aurora[0] : v > 0.45 ? P.aurora[1] : P.aurora[2]
      const i = (y * W + x) * 4
      d[i] = c[0]
      d[i + 1] = c[1]
      d[i + 2] = c[2]
      d[i + 3] = Math.round(Math.min(1, q) * maxA * 255)
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
  glow(g: Ctx, s: Pick<WinterFrameState, 'aurora' | 'lit' | 'flicker' | 'tick'>, scale: number) {
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
    if (L.cabin) light(L.cabin.x + 8, L.cabin.y - 4, 16, P.glowWarm, 0.55)
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
