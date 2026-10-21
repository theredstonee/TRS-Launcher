// Gemeinsame Bühne aller Szenen-Sets der Start-Animation: Leinwand (HiDPI, gedeckelt), Kunst-Puffer mit
// Subpixel-Kamera, Glüh-Sprites, Farbstimmung/Vignette/Körnung, Logo-Enthüllung mit Schriftzug, Blitz und Einblenden.
// Ein Szenen-Set (Dungeon, Friedhof, Frost …) erbt davon und zeichnet nur seine Welt.

import type { IntroPalette } from './designs'
import { canvas, clamp, css, ctx2d, easeInOut, easeOut, easeOutBack, glow, hash, hash2, hex, lerp, mix, range, rgba, smooth, type Cv } from './kit'
import { INTRO_REDUCED_DURATION, type IntroCoreCues } from './timeline'

export const INTRO_FONT = '"Pixelify Sans Variable", "Pixelify Sans", "Segoe UI", system-ui, sans-serif'

export interface IntroStageOptions {
  reduced?: boolean
  /**
   * Ruhig („Ohne Flackern“): gleiche Szenen, Kamera und Musik, aber kein Flackern, keine Lichtblitze, keine
   * Helligkeits-Pulse auf den Schlägen – der Schlag wird ein weiches Aufblühen statt eines weißen Blitzes.
   */
  calm?: boolean
  /** Größte Leinwand in Gerätepixeln (Leistung, z. B. Handy kleiner). */
  maxPixels?: number
  /** Obergrenze für devicePixelRatio. */
  dprCap?: number
  /** Schriftzug unter dem Logo. */
  wordmark?: string
  font?: string
}

/** Kunst-Puffer-Ansicht: Gerätepixel je Kunst-Pixel, ganzzahliger Ursprung, Größe, Kamera (Kunst-Welt). */
export interface ArtView {
  S: number
  ox: number
  oy: number
  bw: number
  bh: number
  cx: number
  cy: number
}

/** Startlage eines Logo-Texels (Gerätepixel) für die Verwandlung. */
export type LogoOrigin = (i: number, j: number) => { x: number; y: number; s: number }

export interface IntroScene {
  readonly duration: number
  readonly fadeOut: number
  setLogo(img: CanvasImageSource | null): void
  resize(): void
  render(t: number): void
}

const LOGO_FALLBACK = (() => {
  // Redstone-Block 16×16 (Rand + Muster), falls das Logo-Bild fehlt.
  const out: (string | null)[] = []
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 16; x++) {
      if (x === 0 || y === 0 || x === 15 || y === 15) out.push(null)
      else if (x === 1 || y === 1 || x === 14 || y === 14) out.push('#3a0e0a')
      else {
        const h = hash2(x, y)
        out.push(h > 0.82 ? '#ff5f50' : h < 0.25 ? '#8f1810' : '#d8261a')
      }
    }
  }
  return out
})()

export abstract class IntroStage implements IntroScene {
  protected readonly g: CanvasRenderingContext2D
  protected W = 1
  protected H = 1
  protected frameNo = 0
  protected art: Cv = canvas(4, 4)
  protected ag: CanvasRenderingContext2D = ctx2d(this.art)
  protected readonly opts: Required<IntroStageOptions>
  private readonly glowCache = new Map<string, Cv>()
  private readonly grain: CanvasPattern[] = []
  private logoGrid: (string | null)[] = LOGO_FALLBACK
  readonly duration: number
  readonly fadeOut: number

  constructor(
    protected readonly cv: HTMLCanvasElement,
    protected readonly p: IntroPalette,
    protected readonly core: IntroCoreCues,
    options: IntroStageOptions = {},
  ) {
    const g = cv.getContext('2d', { alpha: false })
    if (!g) throw new Error('canvas 2d unavailable')
    this.g = g
    this.opts = {
      reduced: options.reduced ?? false,
      calm: options.calm ?? false,
      maxPixels: options.maxPixels ?? 2560 * 1440,
      dprCap: options.dprCap ?? 2,
      wordmark: options.wordmark ?? 'TRS LAUNCHER',
      font: options.font ?? INTRO_FONT,
    }
    this.duration = this.opts.reduced ? INTRO_REDUCED_DURATION : core.duration
    this.fadeOut = this.opts.reduced ? INTRO_REDUCED_DURATION - 0.5 : core.fadeOut
    for (let k = 0; k < 3; k++) {
      const n = canvas(160, 160)
      const ng = ctx2d(n)
      const img = ng.createImageData(160, 160)
      for (let i = 0; i < img.data.length; i += 4) {
        const v = Math.random() * 255
        img.data[i] = v
        img.data[i + 1] = v
        img.data[i + 2] = v
        img.data[i + 3] = 255
      }
      ng.putImageData(img, 0, 0)
      const pat = g.createPattern(n, 'repeat')
      if (pat) this.grain.push(pat)
    }
  }

  /** Logo als 16×16-Texelraster übernehmen (Bild mit 16 gleich großen Zellen, z. B. icon.png). */
  setLogo(img: CanvasImageSource | null) {
    if (!img) return
    try {
      const c = canvas(16, 16)
      const g = ctx2d(c)
      g.drawImage(img, 0, 0, 16, 16)
      const d = g.getImageData(0, 0, 16, 16).data
      const out: (string | null)[] = []
      for (let i = 0; i < 256; i++) {
        const a = d[i * 4 + 3]!
        out.push(a < 40 ? null : css([d[i * 4]!, d[i * 4 + 1]!, d[i * 4 + 2]!]))
      }
      if (out.some(Boolean)) this.logoGrid = out
    } catch {
      // Unlesbares Bild (z. B. fremder Ursprung) – Ersatzlogo bleibt.
    }
  }

  /** Leinwand an die CSS-Größe anpassen (HiDPI, gedeckelt). */
  resize() {
    const r = this.cv.getBoundingClientRect()
    const dpr = Math.min(this.opts.dprCap, globalThis.devicePixelRatio || 1)
    let w = Math.max(1, Math.round(r.width * dpr))
    let h = Math.max(1, Math.round(r.height * dpr))
    const over = (w * h) / this.opts.maxPixels
    if (over > 1) {
      w = Math.round(w / Math.sqrt(over))
      h = Math.round(h / Math.sqrt(over))
    }
    if (this.cv.width !== w || this.cv.height !== h) {
      this.cv.width = w
      this.cv.height = h
    }
    this.W = w
    this.H = h
  }

  get size() {
    return { w: this.W, h: this.H }
  }

  /** Ruhige Fassung (ohne Flackern/Blitze). */
  protected get calm() {
    return this.opts.calm
  }

  /** Flacker-Faktor: im ruhigen Modus konstant 1. */
  protected flick(v: number) {
    return this.opts.calm ? 1 : v
  }

  /** Ein Bild zur Zeit t. */
  render(t: number) {
    this.frameNo++
    const g = this.g
    g.setTransform(1, 0, 0, 1, 0, 0)
    g.globalAlpha = 1
    g.globalCompositeOperation = 'source-over'
    g.imageSmoothingEnabled = false
    g.fillStyle = this.p.void
    g.fillRect(0, 0, this.W, this.H)
    try {
      if (this.opts.reduced) {
        const a = smooth(range(t, 0.05, 0.6)) * (1 - smooth(range(t, INTRO_REDUCED_DURATION - 0.5, INTRO_REDUCED_DURATION)))
        this.drawLogoStage(t, a, 1, 0, 1)
      } else this.renderScene(t)
    } catch (err) {
      // Ein Fehler darf die Animation nie anhalten
      if (import.meta.env?.DEV) console.error(err)
    }
  }

  /** Szene samt Logo-Übergang zeichnen. */
  protected abstract renderScene(t: number): void

  // ───────────────────────── Kunst-Puffer ─────────────────────────

  /**
   * Kunst-Puffer für eine Kamera vorbereiten: `viewH` = sichtbare Höhe in Kunst-Pixeln, (cx, cy) = Bildmitte in
   * Kunst-Welt-Koordinaten. Der Puffer wird geleert und wächst nur bei Bedarf.
   */
  protected beginArt(viewH: number, cx: number, cy: number, clear = true): ArtView {
    const S = this.H / viewH
    const bw = Math.ceil(this.W / S) + 2
    const bh = Math.ceil(this.H / S) + 2
    const ox = Math.floor(cx - this.W / (2 * S))
    const oy = Math.floor(cy - this.H / (2 * S))
    if (this.art.width < bw || this.art.height < bh) {
      this.art = canvas(Math.max(bw, this.art.width), Math.max(bh, this.art.height))
      this.ag = ctx2d(this.art)
      this.onArtResized(this.art.width, this.art.height)
    }
    const ag = this.ag
    ag.setTransform(1, 0, 0, 1, 0, 0)
    ag.globalAlpha = 1
    ag.globalCompositeOperation = 'source-over'
    ag.imageSmoothingEnabled = false
    if (clear) ag.clearRect(0, 0, bw, bh)
    return { S, ox, oy, bw, bh, cx, cy }
  }

  /** Für Szenen mit eigenen Zusatzpuffern gleicher Größe. */
  protected onArtResized(_w: number, _h: number) {}

  /** Kunst-Puffer scharf auf den Bildschirm (Subpixel-Versatz für weiche Fahrten). */
  protected blitArt(v: ArtView) {
    const dx = (v.ox - v.cx) * v.S + this.W / 2
    const dy = (v.oy - v.cy) * v.S + this.H / 2
    this.g.imageSmoothingEnabled = false
    this.g.drawImage(this.art, 0, 0, v.bw, v.bh, dx, dy, v.bw * v.S, v.bh * v.S)
  }

  /** Kunst-Welt → Gerätepixel. */
  protected toScreen(v: ArtView, x: number, y: number) {
    return { x: (x - v.cx) * v.S + this.W / 2, y: (y - v.cy) * v.S + this.H / 2 }
  }

  protected glowSprite(color: string): Cv {
    let c = this.glowCache.get(color)
    if (!c) {
      c = canvas(64, 64)
      const g = ctx2d(c)
      glow(g, 32, 32, 32, color, 1)
      this.glowCache.set(color, c)
    }
    return c
  }

  // ───────────────────────── Nachbearbeitung ─────────────────────────

  /** Farbstimmung (oben `top`, unten `bottom`), Vignette, Körnung. */
  protected drawPost(top: string, bottom: string, vignette = 0.7) {
    const g = this.g
    const { W, H } = this
    g.setTransform(1, 0, 0, 1, 0, 0)
    g.globalAlpha = 1
    g.globalCompositeOperation = 'soft-light'
    const grade = g.createLinearGradient(0, 0, 0, H)
    grade.addColorStop(0, rgba(top, 0.35))
    grade.addColorStop(1, rgba(bottom, 0.25))
    g.fillStyle = grade
    g.fillRect(0, 0, W, H)
    g.globalCompositeOperation = 'source-over'
    const vg = g.createRadialGradient(W / 2, H / 2, Math.min(W, H) * 0.3, W / 2, H / 2, Math.hypot(W, H) * 0.6)
    vg.addColorStop(0, 'rgba(0,0,0,0)')
    vg.addColorStop(1, `rgba(0,0,0,${vignette})`)
    g.fillStyle = vg
    g.fillRect(0, 0, W, H)
    const pat = this.grain[this.opts.calm ? 0 : this.frameNo % this.grain.length]
    if (pat) {
      g.save()
      // Körnung: ruhig = stehend und schwächer (kein Rauschen-Flimmern)
      g.globalAlpha = this.opts.calm ? 0.025 : 0.05
      g.globalCompositeOperation = 'overlay'
      if (!this.opts.calm) g.translate((this.frameNo * 37) % 160, (this.frameNo * 71) % 160)
      g.fillStyle = pat
      g.fillRect(-160, -160, W + 320, H + 320)
      g.restore()
    }
  }

  /** Übergang Szene → Logo nach dem Schlag, Blitz und Einblenden aus Schwarz. */
  protected drawFinale(t: number, origin: LogoOrigin | null, fromColor: string, flashColor: string) {
    const c = this.core
    const g = this.g
    if (t >= c.impact) {
      const m = range(t, c.impact + 0.06, c.logo)
      const dark = smooth(range(t, c.impact + 0.2, c.logo + 0.05))
      g.fillStyle = rgba(this.p.void, dark * 0.96)
      g.fillRect(0, 0, this.W, this.H)
      this.drawLogoStage(t, 1, m, t - c.impact, dark, origin, fromColor)
    }
    // Blitz beim Schlag – ruhig: weiches Aufblühen (langsam an, langsam ab, nie grell)
    const flash = t < c.impact ? 0 : this.opts.calm ? 0.28 * smooth(range(t, c.impact, c.impact + 0.35)) * Math.exp(-Math.max(0, t - c.impact - 0.35) * 1.6) : Math.exp(-(t - c.impact) * 5.5)
    if (flash > 0.01) {
      g.globalCompositeOperation = 'lighter'
      g.fillStyle = rgba(flashColor, flash * 0.9)
      g.fillRect(0, 0, this.W, this.H)
      g.globalCompositeOperation = 'source-over'
    }
    const fin = 1 - smooth(range(t, 0, c.fadeIn))
    if (fin > 0.003) {
      g.fillStyle = `rgba(0,0,0,${fin})`
      g.fillRect(0, 0, this.W, this.H)
    }
  }

  // ───────────────────────── Logo ─────────────────────────

  /** Ganzzahlige Texelgröße und Lage des fertigen Logos (scharf, mittig). */
  logoBox() {
    const L = Math.max(2, Math.floor((Math.min(this.W, this.H * 1.2) * 0.3) / 16))
    const x0 = Math.round(this.W / 2 - 8 * L)
    const y0 = Math.round(this.H * 0.44 - 8 * L)
    return { L, x0, y0 }
  }

  /**
   * Logo-Bühne: `a` Deckkraft, `m` Verwandlung 0 (Ursprung in der Szene) … 1 (Logo), `age` Sekunden seit dem Schlag,
   * `bg` Stärke des Hintergrund-Glühens, `origin` Startlage der Texel, `fromColor` deren Startfarbe.
   */
  protected drawLogoStage(t: number, a: number, m: number, age: number, bg: number, origin: LogoOrigin | null = null, fromColor = '#ffffff') {
    if (a <= 0.003) return
    const g = this.g
    const p = this.p
    const { L, x0, y0 } = this.logoBox()
    const cx = x0 + 8 * L
    const cy = y0 + 8 * L
    const reduced = this.opts.reduced
    g.save()
    g.globalAlpha = a
    // Glühen + Strahlen hinter dem Logo
    if (bg > 0.01) {
      const breathe = reduced ? 1 : 1 + 0.06 * Math.sin(t * 2.2)
      g.globalCompositeOperation = 'lighter'
      g.globalAlpha = a * bg * 0.75
      const gr = 16 * L * 1.7 * breathe
      g.drawImage(this.glowSprite(p.logoGlow), cx - gr, cy - gr, gr * 2, gr * 2)
      if (!reduced) {
        const rays = 14
        const R = Math.hypot(this.W, this.H) * 0.6
        for (let i = 0; i < rays; i++) {
          const ang = (i / rays) * Math.PI * 2 + t * 0.12 + hash(i) * 0.3
          const wdt = 0.035 + hash(i + 3) * 0.05
          const al = (0.05 + hash(i + 5) * 0.06) * (0.7 + 0.3 * Math.sin(t * 1.3 + i))
          const grd = g.createRadialGradient(cx, cy, 8 * L, cx, cy, R)
          grd.addColorStop(0, rgba(p.ray, al))
          grd.addColorStop(1, rgba(p.ray, 0))
          g.fillStyle = grd
          g.beginPath()
          g.moveTo(cx, cy)
          g.arc(cx, cy, R, ang - wdt, ang + wdt)
          g.closePath()
          g.fill()
        }
      }
      g.globalCompositeOperation = 'source-over'
      g.globalAlpha = a
    }
    // Funken nach dem Schlag
    if (!reduced && age > 0 && age < 2.4) {
      g.globalCompositeOperation = 'lighter'
      for (let i = 0; i < 70; i++) {
        const ang = hash(i * 3) * Math.PI * 2
        const sp = (0.25 + hash(i * 3 + 1) * 0.6) * Math.min(this.W, this.H)
        const k = 1 - Math.exp(-age * 2.2)
        const x = cx + Math.cos(ang) * sp * k
        const y = cy + Math.sin(ang) * sp * k * 0.8 + age * age * 40
        g.globalAlpha = a * clamp(1 - age / 2.4)
        g.fillStyle = i % 4 === 0 ? '#ffffff' : i % 2 ? fromColor : p.logoGlow
        const sz = Math.max(2, Math.round(L * 0.35))
        g.fillRect(Math.round(x), Math.round(y), sz, sz)
      }
      g.globalCompositeOperation = 'source-over'
      g.globalAlpha = a
    }
    // Texel fliegen ins Logo
    const fromRgb = hex(fromColor)
    const maxDist = Math.hypot(8, 8)
    g.imageSmoothingEnabled = false
    for (let j = 0; j < 16; j++) {
      for (let i = 0; i < 16; i++) {
        const col = this.logoGrid[j * 16 + i]
        if (!col) continue
        const ex = x0 + i * L
        const ey = y0 + j * L
        if (m >= 1 || !origin) {
          g.fillStyle = col
          g.fillRect(ex, ey, L, L)
          continue
        }
        const dist = Math.hypot(i - 7.5, j - 7.5) / maxDist
        const delay = dist * 0.22 + hash2(i, j) * 0.08
        const k = easeInOut(range(m, delay, delay + 0.7))
        const st = origin(i, j)
        const arc = Math.sin(Math.PI * k) * (hash2(j, i) - 0.5) * L * 7
        const x = lerp(st.x, ex + L / 2, k) + arc
        const y = lerp(st.y, ey + L / 2, k) - Math.sin(Math.PI * k) * L * 2.5
        const sz = lerp(st.s, L, easeOutBack(k, 1.2))
        const c = mix(fromRgb, parseCss(col), smooth(range(k, 0.2, 1)))
        if (k > 0.02 && k < 0.98) {
          g.globalCompositeOperation = 'lighter'
          g.globalAlpha = a * 0.35 * Math.sin(Math.PI * k)
          const gs = sz * 3
          g.drawImage(this.glowSprite(fromColor), x - gs / 2, y - gs / 2, gs, gs)
          g.globalCompositeOperation = 'source-over'
          g.globalAlpha = a
        }
        g.fillStyle = css(c)
        g.fillRect(x - sz / 2, y - sz / 2, sz, sz)
      }
    }
    // Schriftzug
    const c = this.core
    const wordStart = reduced ? 0.25 : c.wordmark
    if (t >= wordStart) {
      const text = this.opts.wordmark
      const size = Math.max(12, Math.round(L * 2.1))
      g.font = `600 ${size}px ${this.opts.font}`
      g.textBaseline = 'alphabetic'
      const spacing = size * 0.18
      const widths = [...text].map((ch) => g.measureText(ch).width)
      const total = widths.reduce((s2, w) => s2 + w, 0) + spacing * (text.length - 1)
      let x = Math.round(this.W / 2 - total / 2)
      const y = Math.round(y0 + 16 * L + size * 1.9)
      ;[...text].forEach((ch, i) => {
        const k = reduced ? range(t, 0.25, 0.7) : easeOut(range(t, wordStart + i * 0.035, wordStart + i * 0.035 + 0.3))
        if (k > 0) {
          g.globalAlpha = a * k
          g.fillStyle = p.wordmark
          g.fillText(ch, x, y + Math.round((1 - k) * size * 0.5))
        }
        x += widths[i]! + spacing
      })
      // Leuchtlinie unter dem Schriftzug wächst von der Mitte
      const lk = reduced ? range(t, 0.3, 0.8) : easeOut(range(t, wordStart + 0.15, wordStart + 0.75))
      if (lk > 0) {
        const half = (total / 2) * lk
        const ly = y + Math.round(size * 0.55)
        const th = Math.max(2, Math.round(L * 0.25))
        g.globalAlpha = a
        g.fillStyle = p.wireOn
        g.fillRect(Math.round(this.W / 2 - half), ly, Math.round(half * 2), th)
        g.globalCompositeOperation = 'lighter'
        g.globalAlpha = a * 0.6
        const pulse = reduced ? 0.5 : (t * 0.8) % 1
        const px = this.W / 2 - half + half * 2 * pulse
        g.drawImage(this.glowSprite(p.wireLight), px - size, ly - size + th / 2, size * 2, size * 2)
        g.globalCompositeOperation = 'source-over'
      }
    }
    g.restore()
  }
}

function parseCss(c: string): [number, number, number] {
  if (c.startsWith('#')) return hex(c)
  const m = /rgba?\((\d+),(\d+),(\d+)/.exec(c)
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : [255, 255, 255]
}
