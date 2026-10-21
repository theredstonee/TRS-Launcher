// Szenen-Set „Winternacht“: verschneite Nacht in der Seitenansicht mit Parallaxe – Polarlicht wogt über
// Bergen und Nadelwald, ein zugefrorener See spiegelt es, Eiszapfen klingen, ein Pinguin rutscht übers Eis,
// Frostkristalle wachsen auf den Schlägen vom Bildrand herein; beim Schlag flammt das Polarlicht auf und der
// Eiskristall auf dem See zerspringt zum Logo.

import type { IntroDesign } from './designs'
import { canvas, clamp, css, ctx2d, decay, easeOut, hash2, hex, keyframes, mix, range, rgba, rng, smooth, type Cv } from './kit'
import { colonyAt, PenguinSprites, type ColonyCues } from './penguins'
import { IntroStage, type ArtView, type IntroStageOptions } from './stage'
import type { IntroCoreCues, IntroLength } from './timeline'

export interface FrostCues extends IntroCoreCues, ColonyCues {
  /** Kamera [t, x, y, zoom] (Bildmitte in Kunst-Pixeln, Seeoberfläche = 0). */
  cam: [number, number, number, number][]
  /** Eiszapfen klingen. */
  chimes: number[]
  /** Frostkristalle wachsen einen Schub weiter (auf Schlägen). */
  crystals: number[]
}

// Der Winter-Jingle (~143 BPM, ein Schlag = 0,42 s) hat seinen Schlag schon bei 2,70 s: er setzt erst kurz vor dem
// Höhepunkt ein, davor tragen Wind, Klänge und Effekte. Rutschen, Purzeln, Hüpfen und Frostschübe liegen auf dem
// Schlagraster impact − k · 0,42 s.
export const FROST_CUES: Record<IntroLength, FrostCues> = {
  short: {
    duration: 4, fadeIn: 0.35, hush: 1.83, impact: 2.25, logo: 2.95, wordmark: 3.13, fadeOut: 3.57,
    cam: [[0, 300, -16, 1.32], [1.85, 318, -16, 1.42], [2.6, 322, -22, 1.6]],
    chimes: [0.3], crystals: [0.57, 0.99, 1.41],
    march: null, slides: [0.15, 0.57], slip: null, snowball: [0, 1.6], hops: [0.99, 1.41],
  },
  medium: {
    duration: 7, fadeIn: 0.5, hush: 4.48, impact: 4.9, logo: 5.65, wordmark: 5.78, fadeOut: 6.66,
    cam: [[0, 230, -58, 1.05], [2.4, 280, -18, 1.25], [4.45, 318, -16, 1.42], [5.2, 322, -22, 1.6]],
    chimes: [0.5], crystals: [2.8, 3.64, 4.06],
    march: [0.2, 1.5], slides: [1.12, 1.54, 1.96, 2.38], slip: 2.8, snowball: [1.0, 3.6], hops: [3.22, 3.64, 4.06],
  },
  long: {
    duration: 10, fadeIn: 0.6, hush: 7.48, impact: 7.9, logo: 8.65, wordmark: 8.78, fadeOut: 9.66,
    cam: [[0, 300, -178, 1.0], [2.6, 215, -36, 1.1], [5.0, 290, -18, 1.28], [7.45, 318, -16, 1.42], [8.2, 322, -22, 1.6]],
    chimes: [1.0, 1.7, 2.4], crystals: [5.38, 6.22, 7.06],
    march: [2.4, 3.8], slides: [3.7, 4.12, 4.54, 4.96], slip: 5.38, snowball: [3.4, 6.2], hops: [5.8, 6.22, 6.64, 7.06],
  },
}

const VIEW_H = 200
const L0 = -400
const L1 = 1100
const CRYSTAL_X = 362

type Layer = { cv: Cv; par: number; x0: number; y0: number }

/** Verschneite Tanne. */
function pine(g: CanvasRenderingContext2D, x: number, base: number, h: number, dark: string, snow: string, r: () => number) {
  const w = h * 0.42
  for (let y = 0; y < h; y++) {
    const k = y / h
    const tier = (k * 5) % 1
    const half = Math.round((w / 2) * k * (0.6 + tier * 0.5))
    g.fillStyle = dark
    g.fillRect(x - half, base - h + y, half * 2 + 1, 1)
    if (tier < 0.18 && half > 1) {
      g.fillStyle = snow
      g.fillRect(x - half, base - h + y, Math.max(1, Math.round(half * (0.8 + r() * 0.6))), 1)
    }
  }
  g.fillStyle = dark
  g.fillRect(x - 1, base - 2, 3, 3)
}

interface Dendrite {
  x0: number
  y0: number
  x1: number
  y1: number
  /** Ab welchem Wachstumsschub sichtbar. */
  level: number
}

export class FrostScene extends IntroStage {
  private readonly c: FrostCues
  private readonly layers: Layer[] = []
  private readonly fore: Layer
  private readonly crystal: Cv
  private readonly pen = new PenguinSprites()
  private readonly frost: Dendrite[][] = []

  constructor(cv: HTMLCanvasElement, readonly design: IntroDesign, readonly length: IntroLength, options: IntroStageOptions = {}) {
    const cues = FROST_CUES[length]
    super(cv, design.palette, cues, options)
    this.c = cues
    this.layers.push(this.paintMountains())
    this.layers.push(this.paintForest(0.5, -6, 26, 46, '#0d1830', '#9fbbe0', 31))
    this.layers.push(this.paintLake())
    this.layers.push(this.paintForest(0.85, 4, 44, 70, '#08101e', '#cfe3ff', 57, true))
    this.fore = this.paintIcicles()
    this.crystal = this.paintCrystal()
    // Frostranken für die vier Ecken (Bildschirm-Anteile, wachsen in Schüben)
    for (let corner = 0; corner < 4; corner++) {
      const r = rng(900 + corner)
      const list: Dendrite[] = []
      const grow = (x: number, y: number, ang: number, len: number, level: number) => {
        if (len < 0.008 || level > 6) return
        const x1 = x + Math.cos(ang) * len
        const y1 = y + Math.sin(ang) * len
        list.push({ x0: x, y0: y, x1, y1, level })
        // Seitenzweige im 60°-Raster wie Eiskristalle
        for (const side of [-1, 1]) if (r() > 0.25) grow(x + Math.cos(ang) * len * (0.4 + r() * 0.3), y + Math.sin(ang) * len * (0.4 + r() * 0.3), ang + side * (Math.PI / 3), len * 0.45, level + 1)
        grow(x1, y1, ang + (r() - 0.5) * 0.25, len * 0.72, level + 1)
      }
      const cx = corner % 2
      const cy = corner >> 1
      const base = Math.atan2(cy ? -1 : 1, cx ? -1 : 1)
      for (let i = 0; i < 3; i++) grow(cx, cy, base + (i - 1) * 0.42, 0.11 + r() * 0.04, 0)
      this.frost.push(list)
    }
  }

  // ───────────────────────── Malen (einmal) ─────────────────────────

  private paintMountains(): Layer {
    const x0 = L0
    const y0 = -260
    const c = canvas(L1 - L0, 300)
    const g = ctx2d(c)
    const r = rng(14)
    const peaks: { x: number; h: number }[] = []
    for (let x = -40; x < c.width + 40; x += 60 + Math.floor(r() * 70)) peaks.push({ x, h: 34 + r() * 46 })
    for (let x = 0; x < c.width; x++) {
      let top = 0
      for (const pk of peaks) top = Math.max(top, pk.h - Math.abs(x - pk.x) * (0.9 + (pk.h % 7) * 0.05))
      const ty = Math.round(-14 - top - y0)
      g.fillStyle = '#16223a'
      g.fillRect(x, ty, 1, c.height - ty)
      // Schneekappen: schmal, gezackt, Grat hell
      const cap = Math.round(Math.min(12, Math.max(0, top - 30) * 0.35) + (r() > 0.5 ? 1 : 0) * Math.min(3, Math.max(0, top - 30)))
      if (cap > 0) {
        g.fillStyle = '#7f97ba'
        g.fillRect(x, ty, 1, cap)
        g.fillStyle = '#c9dbf2'
        g.fillRect(x, ty, 1, Math.max(1, Math.round(cap * 0.4)))
      }
    }
    return { cv: c, par: 0.25, x0, y0 }
  }

  private paintForest(par: number, base: number, hMin: number, hMax: number, dark: string, snow: string, seed: number, shores = false): Layer {
    const x0 = L0
    const y0 = -200
    const c = canvas(L1 - L0, 260)
    const g = ctx2d(c)
    const r = rng(seed)
    const gy = base - y0
    if (!shores) {
      g.fillStyle = dark
      g.fillRect(0, gy - 4, c.width, 8)
    }
    for (let x = 0; x < c.width; x += 7 + Math.floor(r() * 10)) {
      const wx = x + x0
      // Nahe Bäume nur an den Ufern (der See bleibt frei)
      if (shores && wx > 40 && wx < 760) continue
      pine(g, x, gy + 2, hMin + r() * (hMax - hMin), dark, snow, r)
    }
    if (shores) {
      g.fillStyle = '#dfe9f7'
      for (let x = 0; x < c.width; x++) {
        const wx = x + x0
        if (wx > 40 && wx < 760) continue
        g.fillRect(x, gy + 1, 1, 6 + Math.round(2 * Math.sin(x * 0.2)))
      }
    }
    return { cv: c, par, x0, y0 }
  }

  private paintLake(): Layer {
    const x0 = L0
    const y0 = -10
    const c = canvas(L1 - L0, 170)
    const g = ctx2d(c)
    const r = rng(3)
    const top = hex('#22385c')
    const bot = hex('#0a1222')
    for (let y = 0; y < c.height; y++) {
      g.fillStyle = css(mix(top, bot, clamp((y - 10) / 120)))
      g.fillRect(0, y, c.width, 1)
    }
    // Uferlinie (Schnee)
    g.fillStyle = '#cfe0f5'
    g.fillRect(0, 8, c.width, 2)
    g.fillStyle = '#8aa6cc'
    g.fillRect(0, 10, c.width, 1)
    // Risse und Glanzstreifen im Eis
    for (let i = 0; i < 70; i++) {
      let x = r() * c.width
      let y = 14 + r() * 140
      g.fillStyle = r() > 0.5 ? 'rgba(200,225,255,0.35)' : 'rgba(10,20,40,0.6)'
      for (let k = 0; k < 10 + r() * 20; k++) {
        g.fillRect(Math.round(x), Math.round(y), 1, 1)
        x += 1 + r()
        y += (r() - 0.5) * 1.4
      }
    }
    for (let i = 0; i < 40; i++) {
      g.fillStyle = 'rgba(220,240,255,0.18)'
      g.fillRect(Math.floor(r() * c.width), 14 + Math.floor(r() * 140), 6 + Math.floor(r() * 20), 1)
    }
    return { cv: c, par: 1, x0, y0 }
  }

  private paintIcicles(): Layer {
    const c = canvas(L1 - L0 + 800, 60)
    const g = ctx2d(c)
    const r = rng(8)
    g.fillStyle = '#dfeeff'
    for (let x = 0; x < c.width; x++) if (x % 520 <= 140) g.fillRect(x, 0, 1, 5)
    for (let x = 0; x < c.width; x += 3 + Math.floor(r() * 6)) {
      // nur in Büscheln (rahmen das Bild, verdecken es nicht)
      const cluster = x % 520
      if (cluster > 140) continue
      const len = 4 + Math.floor(r() * 22 * Math.sin((cluster / 140) * Math.PI))
      for (let y = 0; y < len; y++) {
        const w = Math.max(1, Math.round(3 * (1 - y / len)))
        g.fillStyle = y < len * 0.3 ? '#eaf6ff' : y % 4 === 0 ? '#ffffff' : '#9fd0f5'
        g.fillRect(x, 5 + y, w, 1)
      }
    }
    return { cv: c, par: 1.7, x0: L0, y0: 0 }
  }

  private paintCrystal(): Cv {
    const c = canvas(56, 64)
    const g = ctx2d(c)
    const prism = (cx: number, h: number, w: number, lean: number) => {
      for (let y = 0; y < h; y++) {
        const tip = y < w ? (w - y) : 0
        const half = Math.max(0, Math.round(w / 2 - tip * 0.5))
        const x = Math.round(cx + lean * (h - y) * 0.15)
        const yy = 62 - h + y
        for (let i = -half; i <= half; i++) {
          const edge = i === -half || i === half
          g.fillStyle = edge ? '#ffffff' : i < 0 ? '#bfe6ff' : i === 0 ? '#e8f7ff' : '#5fa8e0'
          g.fillRect(x + i, yy, 1, 1)
        }
      }
    }
    prism(16, 30, 7, -1)
    prism(40, 26, 7, 1.2)
    prism(22, 44, 9, -0.4)
    prism(34, 40, 9, 0.5)
    prism(28, 58, 11, 0)
    return c
  }

  // ───────────────────────── Bild ─────────────────────────

  private layerAt(v: ArtView, l: Layer, dy = 0) {
    const bx = Math.round(l.x0 - v.cx * l.par + (v.cx - v.ox))
    const by = Math.round(l.y0 - v.cy * l.par + (v.cy - v.oy)) + dy
    this.ag.drawImage(l.cv, bx, by)
  }

  private at(v: ArtView, x: number, y: number, par = 1) {
    return { x: Math.round(x - v.cx * par + (v.cx - v.ox)), y: Math.round(y - v.cy * par + (v.cy - v.oy)) }
  }

  /** Polarlicht-Stärke (Grundpegel, Pulse bei Kristallschüben, Aufflammen beim Schlag). */
  private auroraLevel(t: number) {
    const c = this.c
    let l = 0.75 + 0.35 * smooth(range(t, 0, 2))
    if (!this.calm) for (const k of c.crystals) l += 0.35 * decay(t - k, 3) * (t >= k ? 1 : 0)
    if (t >= c.impact) l += this.calm ? 1.2 * smooth(range(t, c.impact, c.impact + 0.6)) * decay(t - c.impact - 0.6, 1.2) : 2.2 * decay(t - c.impact, 2.2)
    return l
  }

  protected renderScene(t: number) {
    const c = this.c
    const [cx, cy, zoom] = keyframes(c.cam, t) as [number, number, number]
    const punch = t >= c.impact ? 0.12 * decay(t - c.impact, 4) : 0
    const v = this.beginArt(VIEW_H / (zoom * (1 + punch)), cx, cy)
    const ag = this.ag
    const aur = this.auroraLevel(t)
    if (t <= c.logo + 0.3) {
      // Himmel
      const horizon = this.at(v, 0, -10, 0.2).y
      const sky = ag.createLinearGradient(0, 0, 0, v.bh)
      sky.addColorStop(0, '#03060d')
      sky.addColorStop(clamp(horizon / v.bh - 0.3, 0, 1), '#0a1426')
      sky.addColorStop(clamp(horizon / v.bh, 0, 1), '#1b2c4c')
      sky.addColorStop(1, '#1b2c4c')
      ag.fillStyle = sky
      ag.fillRect(0, 0, v.bw, v.bh)
      for (let i = 0; i < 160; i++) {
        const s = this.at(v, -400 + hash2(i, 1) * 1500, -340 + hash2(i, 2) * 320, 0.05)
        if (s.x < 0 || s.y < 0 || s.x >= v.bw || s.y >= v.bh) continue
        const tw = 0.5 + 0.5 * Math.sin(t * (1 + hash2(i, 3) * 3) + i)
        ag.fillStyle = `rgba(230,242,255,${0.2 + 0.6 * tw * hash2(i, 4)})`
        ag.fillRect(s.x, s.y, 1, 1)
      }
      this.drawAurora(t, v, aur)
      // Ebenen: Berge, Wald, See (mit Spiegelung), Uferwald
      this.layerAt(v, this.layers[0]!)
      this.layerAt(v, this.layers[1]!)
      this.layerAt(v, this.layers[2]!)
      this.drawReflection(t, v, aur)
      this.layerAt(v, this.layers[3]!)
      this.drawCrystal(t, v, aur)
      this.drawColony(t, v)
      this.drawSnow(t, v, 0.6, 50, 1, 0.45)
      this.drawSnow(t, v, 1, 70, 1, 0.75)
      // Eiszapfen ganz vorn (oben am Bildrand, mit Parallaxe), funkeln beim Klingen
      const fx = Math.round(this.fore.x0 - v.cx * this.fore.par + (v.cx - v.ox))
      ag.drawImage(this.fore.cv, fx, 0)
      for (const ch of c.chimes) {
        const age = t - ch
        if (age < 0 || age > 1.2) continue
        ag.globalCompositeOperation = 'lighter'
        for (let i = 0; i < 6; i++) {
          const px = ((hash2(i, ch * 10) * v.bw) | 0) + 0
          const py = 8 + ((hash2(i + 3, ch * 10) * 30) | 0)
          ag.globalAlpha = (this.calm ? 0.35 : 1) * decay(age - i * 0.05, 4) * (age > i * 0.05 ? 1 : 0)
          ag.drawImage(this.glowSprite('#dff4ff'), px - 6, py - 6, 12, 12)
          ag.fillStyle = '#ffffff'
          ag.fillRect(px, py - 2, 1, 5)
          ag.fillRect(px - 2, py, 5, 1)
        }
        ag.globalAlpha = 1
        ag.globalCompositeOperation = 'source-over'
      }
      this.drawSnow(t, v, 1.5, 30, 2, 0.9)
      this.blitArt(v)
      this.drawFrost(t)
    }
    this.drawPost('#3a6ab0', '#7fd3ff', 0.72)
    const b = this.crystalBox(v)
    const origin = (i: number, j: number) => {
      const s = this.toScreen(v, v.ox + b.x + 14 + (i + 0.5) * (28 / 16), v.oy + b.y + 18 + (j + 0.5) * (40 / 16))
      return { x: s.x, y: s.y, s: v.S * 1.6 }
    }
    this.drawFinale(t, origin, '#dff4ff', '#eaf8ff')
  }

  private drawAurora(t: number, v: ArtView, level: number) {
    const ag = this.ag
    ag.globalCompositeOperation = 'lighter'
    const green = hex('#5af0b4')
    const teal = hex('#4ac8ff')
    const violet = hex('#b07aff')
    for (let x = 0; x < v.bw; x += 2) {
      const wx = x - (v.cx - v.ox) + v.cx * 0.1
      for (let band = 0; band < 2; band++) {
        const ph = band * 2.1
        const wave = Math.sin(wx * 0.011 + t * 0.55 + ph) * 18 + Math.sin(wx * 0.027 - t * 0.9 + ph) * 8 + Math.sin(wx * 0.004 + t * 0.2) * 22
        const base = this.at(v, 0, -58 + band * 26 + wave, 0.1).y
        const fold = 0.5 + 0.5 * Math.sin(wx * 0.045 + t * 1.4 + ph * 2)
        const len = 34 + 30 * fold
        const a = clamp((0.1 + 0.16 * fold) * level * (band ? 0.7 : 1), 0, 0.9)
        const col = mix(green, teal, 0.5 + 0.5 * Math.sin(wx * 0.006 + t * 0.3 + ph))
        for (let k = 0; k < 6; k++) {
          const kk = k / 6
          const cc = k > 3 ? mix(col, violet, (k - 3) / 3) : col
          ag.fillStyle = css(cc, a * (k === 0 ? 1.4 : 1 - kk * 0.8))
          ag.fillRect(x, Math.round(base - len * kk), 2, Math.ceil(len / 6))
        }
      }
    }
    ag.globalCompositeOperation = 'source-over'
  }

  /** Spiegelung des Polarlichts im Eis (senkrechte, weiche Streifen). */
  private drawReflection(t: number, v: ArtView, level: number) {
    const ag = this.ag
    const lake = this.at(v, 0, 0).y + 2
    if (lake >= v.bh) return
    ag.globalCompositeOperation = 'lighter'
    for (let x = 0; x < v.bw; x += 2) {
      const wx = x - (v.cx - v.ox) + v.cx * 0.1
      const fold = 0.5 + 0.5 * Math.sin(wx * 0.045 + t * 1.4)
      const a = clamp(0.05 * level * fold, 0, 0.4)
      const len = 30 + 40 * fold
      ag.fillStyle = css(mix(hex('#5af0b4'), hex('#4ac8ff'), fold), a)
      for (let y = 0; y < len; y += 3) ag.fillRect(x, lake + y + ((Math.sin(t * 2 + x) * 1) | 0), 2, 2)
    }
    ag.globalCompositeOperation = 'source-over'
  }

  private crystalBox(v: ArtView) {
    const q = this.at(v, CRYSTAL_X, 8)
    return { x: q.x - 28, y: q.y - 64, w: 56, h: 64 }
  }

  private drawCrystal(t: number, v: ArtView, level: number) {
    const c = this.c
    const ag = this.ag
    const b = this.crystalBox(v)
    if (b.x > v.bw + 60 || b.x + b.w < -60) return
    const grow = c.crystals.reduce((n, k) => n + easeOut(range(t, k, k + 0.35)), 0) / Math.max(1, c.crystals.length)
    const pre = t >= c.hush && t < c.impact ? (this.calm ? range(t, c.hush, c.impact) : 0.5 + 0.5 * Math.sin((t - c.hush) * 50)) : 0
    ag.globalCompositeOperation = 'lighter'
    ag.globalAlpha = clamp(0.3 + grow * 0.5 + pre * 0.4)
    ag.drawImage(this.glowSprite('#7fd3ff'), b.x - 40, b.y - 30, b.w + 80, b.h + 60)
    ag.globalAlpha = 1
    ag.globalCompositeOperation = 'source-over'
    // Wächst mit den Schüben aus dem Eis
    const h = Math.round(b.h * (0.35 + 0.65 * grow))
    if (t < c.impact + 0.08) ag.drawImage(this.crystal, 0, b.h - h, b.w, h, b.x, b.y + b.h - h, b.w, h)
    // Spiegelbild
    ag.globalAlpha = 0.25
    ag.save()
    ag.translate(0, (b.y + b.h) * 2)
    ag.scale(1, -1)
    if (t < c.impact + 0.08) ag.drawImage(this.crystal, 0, b.h - h, b.w, h, b.x, b.y + b.h - h, b.w, h)
    ag.restore()
    ag.globalAlpha = 1
    void level
  }

  /** Pinguin-Kolonie (nach Tiefe sortiert), Schneeball, Schatten und Schneespritzer. */
  private drawColony(t: number, v: ArtView) {
    const ag = this.ag
    const { penguins, ball } = colonyAt(this.c, t)
    const S = 2
    if (ball) {
      const q = this.at(v, ball.x, ball.y)
      const r = Math.round(ball.r)
      ag.fillStyle = 'rgba(10,20,40,0.3)'
      ag.fillRect(q.x - r, q.y - 1, r * 2, 2)
      for (let y = -r; y <= r; y++) {
        for (let x = -r; x <= r; x++) {
          if (x * x + y * y > r * r) continue
          const shadeK = (x + y) / (2 * r)
          const speck = ((x + ball.spin) * 7 + y * 13) % 11 === 0
          ag.fillStyle = speck ? '#b9cde6' : shadeK > 0.35 ? '#c9daf0' : '#f4f8ff'
          ag.fillRect(q.x + x, q.y - r + y - 1, 1, 1)
        }
      }
    }
    for (const p of penguins) {
      const spr = this.pen.get(p)
      const q = this.at(v, p.x, p.y)
      const w = spr.width * S
      const h = spr.height * S
      // Schatten auf dem Eis
      ag.fillStyle = `rgba(8,16,32,${p.lift > 0 ? 0.18 : 0.3})`
      ag.fillRect(q.x - Math.round(w * 0.35), q.y - 1, Math.round(w * 0.7), 2)
      const lift = Math.round(p.lift)
      if (p.pose === 'tumble') {
        ag.save()
        ag.translate(q.x, q.y - h / 2)
        ag.rotate((p.rot % 4) * (Math.PI / 2))
        ag.drawImage(spr, -w / 2, -h / 2, w, h)
        ag.restore()
      } else {
        ag.drawImage(spr, q.x - Math.round(w / 2), q.y - h - lift, w, h)
      }
      // Schneespritzer beim Bauchrutschen
      if (p.pose === 'slide') {
        for (let i = 0; i < 5; i++) {
          const ph = (t * 5 + i * 0.2) % 1
          ag.fillStyle = `rgba(240,248,255,${1 - ph})`
          ag.fillRect(q.x - Math.round(w / 2) - 2 - Math.round(ph * 12), q.y - 2 - Math.round(ph * 7 * ((i % 3) / 2)), 1, 1)
        }
      }
    }
  }

  private drawSnow(t: number, v: ArtView, par: number, n: number, size: number, alpha: number) {
    const ag = this.ag
    ag.fillStyle = `rgba(240,248,255,${alpha})`
    for (let i = 0; i < n; i++) {
      const sx = hash2(i, par * 100) * 3000 + Math.sin(t * 0.8 + i) * 5 + t * 6 * par
      const sy = hash2(i + 7, par * 100) * 3000 + t * 18 * par
      const x = ((sx - v.cx * par) % v.bw + v.bw) % v.bw
      const y = ((sy - v.cy * par) % v.bh + v.bh) % v.bh
      ag.fillRect(Math.round(x), Math.round(y), size, size)
    }
  }

  /** Frostranken wachsen in Schüben von den Ecken herein (scharf in Bildschirmauflösung). */
  private drawFrost(t: number) {
    const c = this.c
    const g = this.g
    let level = 0
    c.crystals.forEach((k, i) => (level = Math.max(level, i + easeOut(range(t, k, k + 0.4)))))
    if (t >= c.impact) level = 6 + 2 * easeOut(range(t, c.impact, c.impact + 0.3))
    if (level <= 0) return
    const W = this.W
    const H = this.H
    const sc = Math.min(W, H) * 1.05
    const lw = Math.max(1, Math.round(H / 500))
    g.globalCompositeOperation = 'lighter'
    this.frost.forEach((list, corner) => {
      const right = corner % 2 === 1
      const bottom = corner >> 1 === 1
      const X = (x: number) => (right ? W - (1 - x) * sc : x * sc)
      const Y = (y: number) => (bottom ? H - (1 - y) * sc : y * sc)
      for (const d of list) {
        const vis = clamp(level * 1.25 - d.level)
        if (vis <= 0) continue
        const x0 = X(d.x0)
        const y0 = Y(d.y0)
        const x1 = X(d.x1)
        const y1 = Y(d.y1)
        g.strokeStyle = rgba('#cfeeff', 0.22 * (1 - d.level / 8))
        g.lineWidth = lw * (d.level < 2 ? 2 : 1)
        g.beginPath()
        g.moveTo(x0, y0)
        g.lineTo(x0 + (x1 - x0) * vis, y0 + (y1 - y0) * vis)
        g.stroke()
      }
    })
    g.globalCompositeOperation = 'source-over'
  }
}
