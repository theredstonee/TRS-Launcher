// Szenen-Set „Mondnacht“ (Halloween): Friedhof bei Nacht in der Seitenansicht mit Parallaxe – riesiger Vollmond,
// Hexe vor dem Mond, krumme Bäume, Grabsteine, Kürbislaternen zünden auf den Schlägen, ein Geist, das Friedhofstor
// knarrt auf, Fledermäuse; beim Schlag lodert die Riesen-Kürbislaterne auf und ihre Glut wird zum Logo.

import type { IntroDesign } from './designs'
import { canvas, clamp, css, ctx2d, decay, easeInOut, easeOut, hash, hash2, hex, keyframes, mix, range, rgba, rng, type Cv } from './kit'
import { SPRITES, spritePixel, spriteSize, type SpriteName } from '../moonnight/sprites'
import { IntroStage, type ArtView, type IntroStageOptions } from './stage'
import type { IntroCoreCues, IntroLength } from './timeline'

export interface GraveyardCues extends IntroCoreCues {
  /** Kamera [t, x, y, zoom] (Bildmitte in Kunst-Pixeln, Boden = 0). */
  cam: [number, number, number, number][]
  /** Kürbislaternen zünden: [Zeit, Index in LANTERNS]. Nicht genannte bleiben dunkel. */
  lanterns: [number, number][]
  /** Hexe fliegt vor dem Mond vorbei [von, bis]. */
  witch: [number, number] | null
  /** Geist steigt aus den Gräbern [von, bis]. */
  ghost: [number, number] | null
  /** Tor knarrt auf (null = steht schon offen). */
  gate: number | null
  /** Fledermausschwärme quer über den Mond. */
  bats: number[]
}

// Schläge des Halloween-Jingles (~136 BPM): impact − k · 0,441 s
export const GRAVEYARD_CUES: Record<IntroLength, GraveyardCues> = {
  short: {
    duration: 4, fadeIn: 0.3, hush: 1.809, impact: 2.25, logo: 2.95, wordmark: 3.13, fadeOut: 3.57,
    cam: [[0, 478, -52, 1.3], [1.8, 560, -50, 1.55], [2.6, 562, -52, 1.72]],
    lanterns: [[0.486, 7], [0.486, 9], [0.927, 8], [0.927, 10]],
    witch: null, ghost: null, gate: null, bats: [1.368],
  },
  medium: {
    duration: 7, fadeIn: 0.45, hush: 4.459, impact: 4.9, logo: 5.65, wordmark: 5.78, fadeOut: 6.66,
    cam: [[0, 205, -72, 1.0], [2.6, 330, -46, 1.1], [3.6, 440, -48, 1.25], [4.45, 560, -50, 1.55], [5.2, 562, -52, 1.72]],
    lanterns: [[1.372, 3], [1.813, 4], [2.254, 5], [2.695, 6], [3.577, 7], [3.577, 9], [4.018, 8], [4.018, 10]],
    witch: [0.25, 1.9], ghost: [2.1, 3.7], gate: 3.577, bats: [3.136],
  },
  long: {
    duration: 10, fadeIn: 0.3, hush: 7.459, impact: 7.9, logo: 8.65, wordmark: 8.78, fadeOut: 9.66,
    cam: [[0, 232, -152, 1.15], [2.3, 92, -48, 1.05], [5.0, 330, -44, 1.12], [6.2, 440, -48, 1.25], [7.45, 560, -50, 1.55], [8.2, 562, -52, 1.72]],
    lanterns: [[2.608, 0], [3.049, 1], [3.49, 2], [3.931, 3], [4.372, 4], [4.813, 5], [5.254, 6], [6.577, 7], [6.577, 9], [7.018, 8], [7.018, 10]],
    witch: [0.5, 2.4], ghost: [5.0, 6.8], gate: 5.695, bats: [1.5, 6.136],
  },
}

/** Lage der Kürbislaternen (x am Boden). Die letzten vier säumen den Weg zur Riesen-Laterne. */
export const LANTERNS = [40, 95, 150, 205, 265, 320, 372, 505, 528, 592, 615]
const GATE_X = 430
const BIG_X = 560
const MOON = { x: 70, y: -64, r: 44, par: 0.12 }
const VIEW_H = 200
const L0 = -400
const L1 = 1100

type Layer = { cv: Cv; par: number; x0: number; y0: number }

function spriteCanvas(name: SpriteName, recolor?: Record<string, string>): Cv {
  const { w, h } = spriteSize(name)
  const c = canvas(w, h)
  const g = ctx2d(c)
  const pal = SPRITES[name].pal as Record<string, string>
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const col = spritePixel(name, x, y)
      if (!col) continue
      let out = col
      if (recolor) for (const [k, v] of Object.entries(recolor)) if (pal[k] === col) out = v
      g.fillStyle = out
      g.fillRect(x, y, 1, 1)
    }
  }
  return c
}

function charCanvas(rows: readonly string[], pal: Record<string, string>): Cv {
  const c = canvas(Math.max(...rows.map((r) => r.length)), rows.length)
  const g = ctx2d(c)
  rows.forEach((row, y) => {
    for (let x = 0; x < row.length; x++) {
      const col = pal[row[x]!]
      if (!col) continue
      g.fillStyle = col
      g.fillRect(x, y, 1, 1)
    }
  })
  return c
}

/** Pixel-Linie mit Dicke (für Äste). */
function thickLine(g: CanvasRenderingContext2D, x0: number, y0: number, x1: number, y1: number, w0: number, w1: number) {
  const n = Math.ceil(Math.hypot(x1 - x0, y1 - y0))
  for (let i = 0; i <= n; i++) {
    const k = n ? i / n : 0
    const w = Math.max(1, Math.round(w0 + (w1 - w0) * k))
    g.fillRect(Math.round(x0 + (x1 - x0) * k - w / 2), Math.round(y0 + (y1 - y0) * k - w / 2), w, w)
  }
}

/** Krummer Baum (rekursiv verzweigt). */
function crookedTree(g: CanvasRenderingContext2D, x: number, y: number, h: number, seed: number) {
  const r = rng(seed)
  const branch = (bx: number, by: number, ang: number, len: number, w: number, depth: number) => {
    const bend = (r() - 0.5) * 0.7
    const mx = bx + Math.cos(ang) * len * 0.5
    const my = by + Math.sin(ang) * len * 0.5
    const ex = mx + Math.cos(ang + bend) * len * 0.5
    const ey = my + Math.sin(ang + bend) * len * 0.5
    thickLine(g, bx, by, mx, my, w, w * 0.8)
    thickLine(g, mx, my, ex, ey, w * 0.8, w * 0.6)
    if (depth <= 0 || len < 4) return
    const n = depth > 2 ? 2 : 2 + (r() > 0.5 ? 1 : 0)
    for (let i = 0; i < n; i++) branch(ex, ey, ang + bend + (r() - 0.5) * 1.6 + (i === 0 ? -0.5 : 0.5), len * (0.55 + r() * 0.2), Math.max(1, w * 0.6), depth - 1)
  }
  branch(x, y, -Math.PI / 2 + (r() - 0.5) * 0.3, h * 0.55, Math.max(2, h / 12), 4)
}

export class GraveyardScene extends IntroStage {
  private readonly c: GraveyardCues
  private readonly layers: Layer[] = []
  private readonly fore: Layer
  private readonly moon: Cv
  private readonly s: Record<string, Cv>
  private readonly stones: { x: number; w: number; h: number; kind: number; candle: boolean }[] = []

  constructor(cv: HTMLCanvasElement, readonly design: IntroDesign, readonly length: IntroLength, options: IntroStageOptions = {}) {
    const cues = GRAVEYARD_CUES[length]
    super(cv, design.palette, cues, options)
    this.c = cues
    this.s = {
      pumpkin: spriteCanvas('pumpkin', { y: '#2a1206' }),
      pumpkinLit: spriteCanvas('pumpkin'),
      ghost: spriteCanvas('ghost'),
      batUp: charCanvas(['k.......k', 'kk.....kk', 'kkk.v.kkk', '.kkkkkkk.', '...kkk...'], { k: '#120c1e', v: '#c9b2ff' }),
      batDown: charCanvas(['...k.k...', '..kvkvk..', '.kkkkkkk.', 'kk.kkk.kk', 'k.......k'], { k: '#120c1e', v: '#c9b2ff' }),
      witch: charCanvas(
        [
          '.......k..........',
          '......kkk.........',
          '.....kkkkk........',
          '...kkkkkkkkk......',
          '......kkkk........',
          '.....kkkkkk.......',
          '....kkkkkkkk......',
          'yyykkkkkkkkkkkkkkk',
          'yyyy..kk.kk.......',
          'yyy...............',
        ],
        { k: '#0b0714', y: '#2a1f12' },
      ),
    }
    this.moon = this.paintMoon()
    // Hintergrund-Ebenen (einmal gemalt, nur verschoben)
    this.layers.push(this.paintHills(0.28, -70, '#1d1533', '#3d3060', 11, true))
    this.layers.push(this.paintTrees(0.55, '#150f25', 23))
    this.layers.push(this.paintGround())
    this.fore = this.paintFore()
  }

  // ───────────────────────── Malen (einmal) ─────────────────────────

  private paintMoon(): Cv {
    const r = MOON.r
    const c = canvas(r * 2 + 2, r * 2 + 2)
    const g = ctx2d(c)
    const lite = hex('#eef1f8')
    const dark = hex('#a9afc2')
    const craters = [[-12, -8, 7], [10, 6, 9], [-4, 16, 5], [18, -14, 4], [-20, 10, 4], [4, -20, 5]]
    for (let y = -r; y <= r; y++) {
      for (let x = -r; x <= r; x++) {
        const d = (x * x + y * y) / (r * r)
        if (d > 1) continue
        let k = d * 0.25 + (hash2(x + 99, y + 99) > 0.93 ? 0.15 : 0)
        for (const [cx, cy, cr] of craters) {
          const dd = ((x - cx!) ** 2 + (y - cy!) ** 2) / (cr! * cr!)
          if (dd < 1) k += dd > 0.6 ? 0.45 : 0.3
        }
        g.fillStyle = css(mix(lite, dark, clamp(k)))
        g.fillRect(x + r + 1, y + r + 1, 1, 1)
      }
    }
    return c
  }

  private paintHills(par: number, base: number, color: string, rim: string, seed: number, church: boolean): Layer {
    const x0 = L0
    const y0 = -260
    const c = canvas(L1 - L0, 360)
    const g = ctx2d(c)
    const r = rng(seed)
    const ph = [r() * 6, r() * 6, r() * 6]
    for (let x = 0; x < c.width; x++) {
      const wx = x + x0
      const h = base - 22 * Math.sin(wx * 0.006 + ph[0]!) - 12 * Math.sin(wx * 0.017 + ph[1]!) - 5 * Math.sin(wx * 0.05 + ph[2]!)
      const top = Math.round(h - y0)
      g.fillStyle = color
      g.fillRect(x, top, 1, c.height - top)
      g.fillStyle = rim
      g.fillRect(x, top, 1, 1)
    }
    if (church) {
      // Kirchenruine mit Turm und erleuchtetem Fenster
      const cx = 420 - x0
      const gy = Math.round(base - 22 * Math.sin(420 * 0.006 + ph[0]!) - 12 * Math.sin(420 * 0.017 + ph[1]!) - y0) + 2
      g.fillStyle = color
      g.fillRect(cx - 18, gy - 26, 36, 26)
      g.fillRect(cx + 10, gy - 52, 12, 52)
      for (let i = 0; i < 10; i++) g.fillRect(cx + 16 - Math.floor(i / 2), gy - 62 + i, 1 + i, 1)
      g.fillRect(cx + 15, gy - 70, 2, 8)
      g.fillRect(cx + 13, gy - 67, 6, 1)
      for (let i = 0; i < 14; i++) g.fillRect(cx - 18 + i, gy - 26 - Math.round(i * 0.7), 36 - 2 * i, 1)
      g.fillStyle = '#ffb347'
      g.fillRect(cx + 14, gy - 40, 3, 5)
      g.fillRect(cx - 8, gy - 16, 2, 4)
      g.fillRect(cx - 2, gy - 16, 2, 4)
    }
    return { cv: c, par, x0, y0 }
  }

  private paintTrees(par: number, color: string, seed: number): Layer {
    const x0 = L0
    const y0 = -260
    const c = canvas(L1 - L0, 300)
    const g = ctx2d(c)
    const r = rng(seed)
    g.fillStyle = color
    // Hügelkante
    for (let x = 0; x < c.width; x++) {
      const wx = x + x0
      const top = Math.round(-14 - 6 * Math.sin(wx * 0.011) - 3 * Math.sin(wx * 0.04) - y0)
      g.fillRect(x, top, 1, c.height - top)
    }
    for (let x = 20; x < c.width; x += 70 + Math.floor(r() * 80)) {
      const wx = x + x0
      const top = -14 - 6 * Math.sin(wx * 0.011) - 3 * Math.sin(wx * 0.04) - y0
      crookedTree(g, x, top, 40 + r() * 50, Math.floor(r() * 1e6))
    }
    return { cv: c, par, x0, y0 }
  }

  private paintGround(): Layer {
    const x0 = L0
    const y0 = -200
    const c = canvas(L1 - L0, 320)
    const g = ctx2d(c)
    const r = rng(77)
    const gy = -y0
    // Boden mit Grasbüscheln und Weg
    g.fillStyle = '#100a19'
    g.fillRect(0, gy, c.width, c.height - gy)
    for (let x = 0; x < c.width; x++) {
      const tuft = r() > 0.6 ? 1 + Math.floor(r() * 3) : 0
      g.fillStyle = r() > 0.5 ? '#2b2140' : '#1f1830'
      g.fillRect(x, gy - tuft, 1, tuft + 1)
      if (r() > 0.97) {
        g.fillStyle = '#4b3f6e'
        g.fillRect(x, gy - tuft - 1, 1, 1)
      }
    }
    for (let i = 0; i < 260; i++) {
      g.fillStyle = r() > 0.5 ? '#1a1228' : '#140e20'
      g.fillRect(Math.floor(r() * c.width), gy + 2 + Math.floor(r() * 60), 2 + Math.floor(r() * 4), 1)
    }
    // Großer toter Baum links
    g.fillStyle = '#0d0916'
    crookedTree(g, 150 - x0, gy, 120, 991)
    // Erdhügel der Riesen-Laterne
    for (let x = -60; x <= 60; x++) {
      const h = Math.round(16 * Math.cos((x / 60) * (Math.PI / 2)) ** 1.4)
      g.fillStyle = '#120b1c'
      g.fillRect(BIG_X + x - x0, gy - h, 1, h)
      g.fillStyle = '#2f2448'
      g.fillRect(BIG_X + x - x0, gy - h, 1, 1)
    }
    // Grabsteine
    for (let x = -60; x < 400; x += 22 + Math.floor(r() * 26)) {
      if (Math.abs(x - 150) < 14) continue
      const w = 9 + Math.floor(r() * 7)
      const h = 13 + Math.floor(r() * 10)
      const kind = Math.floor(r() * 3)
      this.stones.push({ x, w, h, kind, candle: r() > 0.55 })
      this.paintStone(g, x - x0, gy, w, h, kind, r)
    }
    // Zaun (Tor wird dynamisch gezeichnet)
    g.fillStyle = '#0b0812'
    for (let x = 380; x <= 482; x += 6) {
      if (x > GATE_X - 14 && x < GATE_X + 14) continue
      g.fillRect(x - x0, gy - 22, 1, 22)
      g.fillRect(x - x0 - 1, gy - 23, 3, 1)
      g.fillRect(x - x0, gy - 25, 1, 2)
    }
    g.fillRect(380 - x0, gy - 18, GATE_X - 14 - 380, 1)
    g.fillRect(GATE_X + 14 - x0, gy - 18, 482 - GATE_X - 14, 1)
    g.fillRect(380 - x0, gy - 7, GATE_X - 14 - 380, 1)
    g.fillRect(GATE_X + 14 - x0, gy - 7, 482 - GATE_X - 14, 1)
    // Torpfosten
    for (const px of [GATE_X - 15, GATE_X + 13]) {
      g.fillStyle = '#1c1530'
      g.fillRect(px - x0, gy - 30, 3, 30)
      g.fillStyle = '#4b3f6e'
      g.fillRect(px - x0, gy - 31, 3, 1)
    }
    return { cv: c, par: 1, x0, y0 }
  }

  private paintStone(g: CanvasRenderingContext2D, x: number, gy: number, w: number, h: number, kind: number, r: () => number) {
    const body = '#2a2440'
    const rim = '#8a86a8'
    const shade = '#1c172c'
    const tilt = r() > 0.6 ? (r() > 0.5 ? 1 : -1) : 0
    for (let yy = 0; yy < h; yy++) {
      const off = Math.round((tilt * yy) / h)
      let x0 = 0
      let x1 = w
      if (kind === 0) {
        // runder Kopf
        const top = h - yy
        const rr = w / 2
        if (top > h - rr) {
          const dy = top - (h - rr)
          const half = Math.sqrt(Math.max(0, rr * rr - dy * dy))
          x0 = Math.round(rr - half)
          x1 = Math.round(rr + half)
        }
      } else if (kind === 1) {
        // Kreuz
        const cyy = h - yy
        const bar = cyy > h * 0.55 && cyy < h * 0.75
        x0 = bar ? 0 : Math.round(w / 2 - 2)
        x1 = bar ? w : Math.round(w / 2 + 2)
      }
      const yTop = gy - h + yy
      g.fillStyle = body
      g.fillRect(x + x0 - off, yTop, x1 - x0, 1)
      g.fillStyle = shade
      g.fillRect(x + x1 - 2 - off, yTop, 2, 1)
      if (yy === 0 || (kind === 0 && yy < 3)) {
        g.fillStyle = rim
        g.fillRect(x + x0 - off, yTop, Math.max(1, x1 - x0 - 2), 1)
      } else {
        g.fillStyle = rim
        g.fillRect(x + x0 - off, yTop, 1, 1)
      }
    }
    if (kind !== 1) {
      g.fillStyle = shade
      g.fillRect(x + 3, gy - h + 6, w - 6, 1)
      g.fillRect(x + 3, gy - h + 9, w - 7, 1)
    }
  }

  private paintFore(): Layer {
    const x0 = L0
    const y0 = 0
    const c = canvas(L1 - L0 + 600, 140)
    const g = ctx2d(c)
    const r = rng(5)
    g.fillStyle = '#050309'
    for (let x = 0; x < c.width; x++) {
      const h = 18 + 10 * Math.sin(x * 0.02) + 6 * Math.sin(x * 0.07) + (r() > 0.85 ? r() * 10 : 0)
      g.fillRect(x, Math.round(30 - h), 1, c.height)
    }
    for (let x = 30; x < c.width; x += 90 + Math.floor(r() * 120)) {
      g.fillStyle = '#050309'
      for (let i = 0; i < 5; i++) thickLine(g, x, 30, x + (r() - 0.5) * 40, 30 - 20 - r() * 30, 2, 1)
    }
    return { cv: c, par: 1.6, x0, y0 }
  }

  // ───────────────────────── Bild ─────────────────────────

  private layerAt(v: ArtView, l: Layer) {
    const bx = Math.round(l.x0 - v.cx * l.par + (v.cx - v.ox))
    const by = Math.round(l.y0 - v.cy * l.par + (v.cy - v.oy))
    this.ag.drawImage(l.cv, bx, by)
  }

  /** Kunst-Welt-Punkt einer Parallaxe-Ebene → Puffer-Koordinaten. */
  private at(v: ArtView, x: number, y: number, par = 1) {
    return { x: Math.round(x - v.cx * par + (v.cx - v.ox)), y: Math.round(y - v.cy * par + (v.cy - v.oy)) }
  }

  protected renderScene(t: number) {
    const c = this.c
    const [cx, cy, zoom] = keyframes(c.cam, t) as [number, number, number]
    const punch = t >= c.impact ? 0.12 * decay(t - c.impact, 4) : 0
    const shake = t >= c.impact ? 3 * decay(t - c.impact, 6) : 0
    const v = this.beginArt(VIEW_H / (zoom * (1 + punch)), cx + Math.sin(t * 61) * shake, cy + Math.cos(t * 53) * shake)
    const ag = this.ag
    const sceneGone = t > c.logo + 0.3
    if (!sceneGone) {
      // Himmel
      const sky = ag.createLinearGradient(0, 0, 0, v.bh)
      const horizon = this.at(v, 0, -10, 0.2).y
      sky.addColorStop(0, '#07050e')
      sky.addColorStop(clamp(horizon / v.bh - 0.35, 0, 1), '#150e2a')
      sky.addColorStop(clamp(horizon / v.bh, 0, 1), '#3a2560')
      sky.addColorStop(1, '#3a2560')
      ag.fillStyle = sky
      ag.fillRect(0, 0, v.bw, v.bh)
      for (let i = 0; i < 120; i++) {
        const s = this.at(v, -400 + hash2(i, 1) * 1500, -320 + hash2(i, 2) * 300, 0.06)
        if (s.x < 0 || s.y < 0 || s.x >= v.bw || s.y >= v.bh) continue
        const tw = 0.5 + 0.5 * Math.sin(t * (1 + hash2(i, 3) * 3) + i)
        ag.fillStyle = `rgba(236,232,255,${0.25 + 0.6 * tw * hash2(i, 4)})`
        ag.fillRect(s.x, s.y, 1, 1)
      }
      // Mond + Hof
      const m = this.at(v, MOON.x, MOON.y, MOON.par)
      ag.globalCompositeOperation = 'lighter'
      ag.drawImage(this.glowSprite('#b9a2ea'), m.x - MOON.r * 3, m.y - MOON.r * 3, MOON.r * 6, MOON.r * 6)
      ag.drawImage(this.glowSprite('#e6e9f2'), m.x - MOON.r * 1.6, m.y - MOON.r * 1.6, MOON.r * 3.2, MOON.r * 3.2)
      ag.globalCompositeOperation = 'source-over'
      ag.drawImage(this.moon, m.x - MOON.r - 1, m.y - MOON.r - 1)
      // Hexe vor dem Mond
      if (c.witch) {
        const k = range(t, c.witch[0], c.witch[1])
        if (k > 0 && k < 1) {
          const wx = MOON.x - 110 + k * 220
          const wy = MOON.y + 18 - Math.sin(k * Math.PI) * 26 + Math.sin(t * 6) * 1.5
          const w = this.at(v, wx, wy, MOON.par)
          ag.drawImage(this.s.witch!, w.x - 9, w.y - 5)
        }
      }
      // Fledermausschwärme über den Mond
      for (const bt of c.bats) {
        const age = t - bt
        if (age < 0 || age > 2.6) continue
        for (let i = 0; i < 26; i++) {
          const a2 = age - hash2(i, bt * 100) * 0.6
          if (a2 < 0) continue
          const bx = MOON.x + 150 - a2 * (110 + hash2(i, 3) * 60)
          const by = MOON.y - 30 + hash2(i, 4) * 70 + Math.sin(a2 * 7 + i) * 5
          const b = this.at(v, bx, by, MOON.par + 0.15)
          const spr = Math.floor(t * 14 + i) % 2 ? this.s.batUp! : this.s.batDown!
          ag.drawImage(spr, b.x, b.y)
        }
      }
      // Ebenen
      for (const l of this.layers) this.layerAt(v, l)
      // Kerzen auf Gräbern
      ag.globalCompositeOperation = 'lighter'
      for (const s of this.stones) {
        if (!s.candle) continue
        const q = this.at(v, s.x + s.w + 2, -1)
        const fl = this.flick(0.7 + 0.3 * Math.sin(t * 17 + s.x))
        ag.drawImage(this.glowSprite('#ff9a3a'), q.x - 8, q.y - 12, 16, 16)
        ag.globalCompositeOperation = 'source-over'
        ag.fillStyle = '#e8e0d0'
        ag.fillRect(q.x, q.y - 3, 1, 3)
        ag.fillStyle = fl > 0.85 ? '#fff2b0' : '#ffb040'
        ag.fillRect(q.x, q.y - 5, 1, 2)
        ag.globalCompositeOperation = 'lighter'
      }
      ag.globalCompositeOperation = 'source-over'
      // Tor
      this.drawGate(t, v)
      // Geist
      if (c.ghost) {
        const k = range(t, c.ghost[0], c.ghost[1])
        if (k > 0 && k < 1) {
          const gx = 300 + k * 70
          const gy = -8 - easeOut(Math.min(1, k * 3)) * 26 + Math.sin(t * 3) * 3
          const q = this.at(v, gx, gy)
          ag.globalAlpha = 0.75 * Math.sin(Math.PI * k) * this.flick(0.8 + 0.2 * Math.sin(t * 9))
          ag.drawImage(this.s.ghost!, q.x - 12, q.y - 12, 24, 24)
          ag.globalAlpha = 1
          ag.globalCompositeOperation = 'lighter'
          ag.globalAlpha = 0.3 * Math.sin(Math.PI * k)
          ag.drawImage(this.glowSprite('#cfd5e3'), q.x - 22, q.y - 22, 44, 44)
          ag.globalAlpha = 1
          ag.globalCompositeOperation = 'source-over'
        }
      }
      // Kürbislaternen
      const litAt = new Map<number, number>()
      for (const [lt, idx] of c.lanterns) litAt.set(idx, lt)
      LANTERNS.forEach((lx, idx) => {
        const lt = litAt.get(idx)
        const lit = lt !== undefined && t >= lt
        const q = this.at(v, lx, 0)
        if (q.x < -30 || q.x > v.bw + 30) return
        if (lit) {
          const ign = easeOut(range(t, lt!, lt! + 0.15))
          const fl = this.flick(0.85 + 0.15 * Math.sin(t * 13 + idx) * Math.sin(t * 7 + idx * 2))
          ag.globalCompositeOperation = 'lighter'
          ag.globalAlpha = ign * fl
          ag.drawImage(this.glowSprite('#ff8a2a'), q.x - 34, q.y - 30, 68, 50)
          ag.globalAlpha = this.calm ? 0 : 0.6 * decay(t - lt!, 5)
          ag.drawImage(this.glowSprite('#fff2b0'), q.x - 20, q.y - 26, 40, 40)
          ag.globalAlpha = 1
          ag.globalCompositeOperation = 'source-over'
        }
        ag.drawImage(lit ? this.s.pumpkinLit! : this.s.pumpkin!, q.x - 6, q.y - 11)
      })
      // Riesen-Kürbislaterne
      this.drawBigPumpkin(t, v)
      // Nebel
      this.drawFog(t, v)
      // Vordergrund
      this.layerAt(v, this.fore)
      this.blitArt(v)
      // HD-Glühen des Mondes
      const ms = this.toScreen(v, m.x + v.ox, m.y + v.oy)
      const g = this.g
      g.globalCompositeOperation = 'lighter'
      g.globalAlpha = 0.18
      const R = MOON.r * v.S * 3.2
      g.drawImage(this.glowSprite('#cfc6ff'), ms.x - R, ms.y - R, R * 2, R * 2)
      g.globalAlpha = 1
      g.globalCompositeOperation = 'source-over'
    }
    this.drawPost('#5a4a9a', '#ff7a2a', 0.75)
    const face = this.bigFace(v)
    this.drawFinale(t, face, '#ffb347', '#ffe2b8')
  }

  /** Riesen-Kürbislaterne: Lage (Puffer) des Gesichts. */
  private bigBox(v: ArtView) {
    const q = this.at(v, BIG_X, -13)
    return { x: q.x - 22, y: q.y - 34, w: 44, h: 36 }
  }

  private bigFace(v: ArtView) {
    const b = this.bigBox(v)
    return (i: number, j: number) => {
      const s = this.toScreen(v, v.ox + b.x + 8 + (i + 0.5) * (28 / 16), v.oy + b.y + 8 + (j + 0.5) * (22 / 16))
      return { x: s.x, y: s.y, s: v.S * 1.8 }
    }
  }

  private drawBigPumpkin(t: number, v: ArtView) {
    const c = this.c
    const ag = this.ag
    const b = this.bigBox(v)
    if (b.x > v.bw + 40 || b.x + b.w < -40) return
    const pre = t >= c.hush && t < c.impact ? (this.calm ? 0.45 * range(t, c.hush, c.impact) : Math.sin((t - c.hush) * 40) > 0.3 ? 0.5 : 0.1) : 0
    const lit = t >= c.impact ? 1 : pre
    // Körper (Ellipse mit Rippen)
    for (let y = 0; y < b.h; y++) {
      for (let x = 0; x < b.w; x++) {
        const u = (x + 0.5 - b.w / 2) / (b.w / 2)
        const w = (y + 0.5 - b.h / 2 - 2) / (b.h / 2 - 2)
        if (u * u + w * w > 1) continue
        const rib = Math.abs(Math.sin(u * 4.2)) < 0.18
        const shadeK = 0.55 + 0.45 * (1 - (u + 0.4) * 0.5) * (1 - w * 0.3)
        let col = mix(hex('#8a3f16'), hex('#e0802f'), clamp(shadeK))
        if (rib) col = mix(col, hex('#6a2a10'), 0.6)
        // Mondlicht-Rand oben links, sonst Nacht – erst der Schlag bringt die volle Farbe
        const rim = u * u + w * w > 0.82 && u < 0.1 && w < 0.1
        col = mix(col, hex('#2a1838'), 0.62 * (1 - clamp(lit)))
        if (rim) col = mix(col, hex('#cfc6ff'), 0.35)
        // Gesicht: dreieckige Augen, Nase, gezackter Mund
        const fx = u
        const fy = w
        const eyeL = fy > -0.5 && fy < -0.12 && Math.abs(fx + 0.38) < (fy + 0.5) * 0.7
        const eyeR = fy > -0.5 && fy < -0.12 && Math.abs(fx - 0.38) < (fy + 0.5) * 0.7
        const nose = fy > -0.05 && fy < 0.12 && Math.abs(fx) < (fy + 0.05) * 0.8
        const mouthY = 0.28 + 0.1 * Math.cos(fx * 3)
        const tooth = Math.floor((fx + 1) * 6) % 2 === 0
        const mouth = Math.abs(fx) < 0.62 && fy > mouthY - (tooth ? 0.0 : 0.08) && fy < mouthY + 0.2 - (tooth ? 0.08 : 0)
        if (eyeL || eyeR || nose || mouth) {
          col = lit > 0 ? mix(hex('#3a1606'), mix(hex('#ffb347'), hex('#fff6c8'), clamp(lit - 0.4)), lit) : hex('#1a0a04')
        }
        ag.fillStyle = css(col)
        ag.fillRect(b.x + x, b.y + y, 1, 1)
      }
    }
    // Stiel
    ag.fillStyle = '#3d5a2c'
    ag.fillRect(b.x + b.w / 2 - 2, b.y - 3, 4, 5)
    ag.fillStyle = '#5a7a3c'
    ag.fillRect(b.x + b.w / 2 - 1, b.y - 5, 2, 3)
    if (lit > 0) {
      ag.globalCompositeOperation = 'lighter'
      ag.globalAlpha = lit
      ag.drawImage(this.glowSprite('#ff8a2a'), b.x - 40, b.y - 40, b.w + 80, b.h + 80)
      if (t >= c.impact) {
        const k = easeOut(range(t, c.impact, c.impact + (this.calm ? 1.1 : 0.5)))
        ag.globalAlpha = (1 - k * 0.5) * (this.calm ? 0.6 : 1)
        ag.drawImage(this.glowSprite('#fff2b0'), b.x - 120 * k, b.y - 120 * k, b.w + 240 * k, b.h + 240 * k)
      }
      ag.globalAlpha = 1
      ag.globalCompositeOperation = 'source-over'
    }
  }

  private drawGate(t: number, v: ArtView) {
    const c = this.c
    const ag = this.ag
    const open = c.gate === null ? 1 : easeInOut(range(t, c.gate, c.gate + 0.65))
    const wobble = c.gate !== null && t > c.gate && t < c.gate + 0.9 ? Math.sin((t - c.gate) * 30) * 0.03 * (1 - range(t, c.gate, c.gate + 0.9)) : 0
    const ang = (open + wobble) * 1.35
    const wing = 12
    for (const side of [-1, 1]) {
      const hinge = this.at(v, GATE_X + side * 13, 0)
      const w = Math.max(1, Math.round(wing * Math.cos(ang)))
      for (let i = 0; i < w; i++) {
        const k = i / Math.max(1, w - 1)
        const x = hinge.x - side * i
        const lift = Math.round(Math.sin(ang) * k * 3)
        ag.fillStyle = '#0b0812'
        if (i % 3 === 0) {
          ag.fillRect(x, hinge.y - 24 - lift, 1, 24)
          ag.fillRect(x, hinge.y - 26 - lift, 1, 2)
        }
        ag.fillRect(x, hinge.y - 19 - lift, 1, 1)
        ag.fillRect(x, hinge.y - 8 - lift, 1, 1)
        if (Math.abs(i - w / 2) < 1 && w > 3) ag.fillRect(x, hinge.y - 16 - lift, 1, 6)
      }
    }
  }

  private drawFog(t: number, v: ArtView) {
    const ag = this.ag
    const p = this.p
    ag.globalCompositeOperation = 'screen'
    for (let i = 0; i < 14; i++) {
      const par = i % 2 ? 0.8 : 1.15
      const fx = ((hash(i + 3) * 1400 + t * (8 + hash(i) * 10)) % 1400) - 300
      const q = this.at(v, fx, -4 - hash(i + 9) * 16, par)
      const r = 50 + hash(i + 5) * 50
      if (q.x + r < 0 || q.x - r > v.bw) continue
      const grd = ag.createRadialGradient(0, 0, 0, 0, 0, r)
      grd.addColorStop(0, rgba(p.fog, 0.2))
      grd.addColorStop(1, rgba(p.fog, 0))
      ag.save()
      ag.translate(q.x, q.y)
      ag.scale(1, 0.35)
      ag.fillStyle = grd
      ag.fillRect(-r, -r, r * 2, r * 2)
      ag.restore()
    }
    ag.globalCompositeOperation = 'source-over'
  }
}
