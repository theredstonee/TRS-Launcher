// Motor des Musikvideos: Leinwand (HiDPI, 16:9 mit Balken am Desktop, füllend im Hochformat), Zeitraster → Shot,
// Szenen zeichnen, danach Übergänge (Blitz, Wischschwenk, Blenden), Körnung und Vignette. Zeit kommt von außen
// (Audio-Uhr), dadurch ist jedes Bild nur eine Funktion der Liedzeit.
import { beatAt, type WaitTimeline } from '#shared/wait-song'
import { buildChat, type ChatEvent } from '../waitChat'
import { Assets } from './assets'
import { STAGE_H, STAGE_W, type Frame } from './core'
import { canvas, clamp, ctx2d, type Cv } from './kit'
import { cueTime, cutFx, cutList, shotAt, shotList, type Cut, type ShotSpan } from './plan'
import { renderBridge } from './scenes/bridge'
import { renderFinale } from './scenes/finale'
import { renderLaunch } from './scenes/launch'
import { renderRoom } from './scenes/room'
import { renderSpace } from './scenes/space'
import { renderStreet } from './scenes/street'

const SCENES = {
  room: renderRoom,
  space: renderSpace,
  street: renderStreet,
  bridge: renderBridge,
  launch: renderLaunch,
  finale: renderFinale,
} as const

/** Größte Leinwand in Gerätepixeln (Leistung auf 4K/Retina). */
const MAX_PIXELS = 2560 * 1440

export class WaitVideo {
  readonly assets = new Assets()
  private readonly g: CanvasRenderingContext2D
  private tl!: WaitTimeline
  private shots: ShotSpan[] = []
  private cuts: Cut[] = []
  private chat: ChatEvent[] = []
  private cue!: Frame['cue']
  private W = 1
  private H = 1
  private S = 1
  private zk = 1
  private box = { x: 0, y: 0, w: 1, h: 1 }
  private grain: CanvasPattern[] = []
  private scratch: Cv = canvas(4, 4)
  private frameNo = 0

  constructor(private readonly cv: HTMLCanvasElement, private readonly name: string, private readonly reduced: boolean) {
    const g = cv.getContext('2d', { alpha: false })
    if (!g) throw new Error('canvas 2d unavailable')
    this.g = g
    for (let k = 0; k < 3; k++) {
      const n = canvas(200, 200)
      const ng = ctx2d(n)
      const img = ng.createImageData(200, 200)
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

  setTimeline(tl: WaitTimeline) {
    this.tl = tl
    this.shots = shotList(tl)
    this.cuts = cutList(tl)
    this.cue = {
      finalStop: cueTime(tl, 'finalStop'),
      finalHit: cueTime(tl, 'finalHit'),
      launch: cueTime(tl, 'launch'),
      fade: cueTime(tl, 'fade'),
      swell: cueTime(tl, 'swell'),
    }
    this.chat = buildChat(tl, this.cue.launch, this.cue.fade)
  }

  /** Leinwandgröße an die CSS-Größe anpassen (HiDPI). */
  resize() {
    const r = this.cv.getBoundingClientRect()
    const dpr = Math.min(2, window.devicePixelRatio || 1)
    let w = Math.max(1, Math.round(r.width * dpr))
    let h = Math.max(1, Math.round(r.height * dpr))
    const over = (w * h) / MAX_PIXELS
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
    const portrait = w / h < 1
    // Querformat: 16:9 einpassen (Balken). Hochformat: füllen, mindestens 230 Kunst-Pixel breit sichtbar.
    this.S = portrait ? w / 230 : Math.min(w / STAGE_W, h / STAGE_H)
    // Hochformat: Nahaufnahmen etwas weiter, damit Figuren nicht aus dem Bild fallen
    this.zk = portrait ? 0.8 : 1
    const bw = portrait ? w : STAGE_W * this.S
    const bh = portrait ? h : STAGE_H * this.S
    this.box = { x: (w - bw) / 2, y: (h - bh) / 2, w: bw, h: bh }
    this.scratch = canvas(Math.ceil(w / 4), Math.ceil(h / 4))
  }

  /** Ein Bild zur Liedzeit `t`. `energy` = Lautstärke 0…1 (Analyser) oder 0. */
  render(t: number, energy = 0) {
    const g = this.g
    const tl = this.tl
    this.frameNo++
    const beat = beatAt(tl, t)
    const started = t >= (tl.beats?.offset ?? tl.verse1) - 0.05
    const red = this.reduced ? 0.4 : 1
    const pulse = started ? Math.exp(-beat.phase * 5) * red : 0
    const barPhase = beat.count - Math.floor(beat.count / 4) * 4
    const bar = started ? Math.exp(-barPhase * 3) * red : 0
    const shot = shotAt(this.shots, tl, t)
    const f: Frame = {
      g,
      t,
      tl,
      shot,
      beat,
      pulse,
      bar,
      energy,
      reduced: this.reduced,
      assets: this.assets,
      chat: this.chat,
      name: this.name,
      S: this.S,
      W: this.W,
      H: this.H,
      cx: this.box.x + this.box.w / 2,
      cy: this.box.y + this.box.h / 2,
      zk: this.zk,
      shake: 0,
      cam: { x: STAGE_W / 2, y: STAGE_H / 2, z: 1, r: 0 },
      cue: this.cue,
    }
    g.setTransform(1, 0, 0, 1, 0, 0)
    g.globalAlpha = 1
    g.globalCompositeOperation = 'source-over'
    g.imageSmoothingEnabled = false
    g.fillStyle = '#05040a'
    g.fillRect(0, 0, this.W, this.H)
    g.save()
    try {
      SCENES[shot.scene](f)
    } catch (err) {
      // Ein Fehler in einer Szene darf das Video nicht anhalten
      if (import.meta.dev) console.error(err)
    }
    g.restore()
    this.post(f)
  }

  private post(f: Frame) {
    const g = this.g
    const { W, H } = this
    g.setTransform(1, 0, 0, 1, 0, 0)
    g.globalAlpha = 1
    g.globalCompositeOperation = 'source-over'
    const fx = cutFx(this.cuts, f.t, this.tl.end)
    const red = this.reduced ? 0.3 : 1
    // Wischschwenk: Bewegungsunschärfe senkrecht
    if (Math.abs(fx.whip) > 0.03 && !this.reduced) {
      const sg = ctx2d(this.scratch)
      sg.imageSmoothingEnabled = true
      sg.drawImage(this.cv, 0, 0, this.scratch.width, this.scratch.height)
      g.imageSmoothingEnabled = true
      for (let k = 1; k <= 6; k++) {
        g.globalAlpha = 0.22
        g.drawImage(this.scratch, 0, fx.whip * k * H * 0.035, W, H)
      }
      g.globalAlpha = 1
      g.imageSmoothingEnabled = false
    }
    // Vignette
    const vg = g.createRadialGradient(W / 2, H / 2, Math.min(W, H) * 0.35, W / 2, H / 2, Math.hypot(W, H) * 0.6)
    vg.addColorStop(0, 'rgba(0,0,0,0)')
    vg.addColorStop(1, 'rgba(0,0,0,0.55)')
    g.fillStyle = vg
    g.fillRect(0, 0, W, H)
    // Filmkorn (dezent)
    const pat = this.grain[this.frameNo % this.grain.length]
    if (pat) {
      g.save()
      g.globalAlpha = 0.045
      g.globalCompositeOperation = 'overlay'
      g.translate((this.frameNo * 37) % 200, (this.frameNo * 71) % 200)
      g.fillStyle = pat
      g.fillRect(-200, -200, W + 400, H + 400)
      g.restore()
    }
    // Blenden
    if (fx.flash > 0.01) {
      g.globalCompositeOperation = 'lighter'
      g.fillStyle = `rgba(255,244,230,${clamp(fx.flash * 0.85 * red)})`
      g.fillRect(0, 0, W, H)
      g.globalCompositeOperation = 'source-over'
    }
    if (fx.white > 0.01) {
      g.fillStyle = `rgba(255,236,200,${clamp(fx.white)})`
      g.fillRect(0, 0, W, H)
    }
    if (fx.black > 0.01) {
      g.fillStyle = `rgba(3,2,8,${clamp(fx.black)})`
      g.fillRect(0, 0, W, H)
    }
    // Balken (16:9)
    const b = this.box
    g.fillStyle = '#000'
    if (b.y > 0) {
      g.fillRect(0, 0, W, Math.ceil(b.y))
      g.fillRect(0, Math.floor(b.y + b.h), W, H)
    }
    if (b.x > 0) {
      g.fillRect(0, 0, Math.ceil(b.x), H)
      g.fillRect(Math.floor(b.x + b.w), 0, W, H)
    }
  }

  /** Bilder + Kopf laden (im Hintergrund, blockiert nie). */
  preload(skin: string | null) {
    this.assets.load(skin)
  }
}
