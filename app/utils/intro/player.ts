// Abspieler der Start-Animation ohne Framework: wählt das Szenen-Set des Designs, Uhr (Audio-Uhr, sonst
// performance.now), Bildschleife, Ton, Fokus. Der Launcher (Vue-Komponente) und die eigenständige HTML-Vorschau
// benutzen genau diesen Code.

import { IntroAudio } from './audio'
import type { IntroDesign } from './designs'
import { FROST_CUES, FrostScene } from './frost'
import { FOXDEN_CUES, FoxdenScene } from './foxden'
import { GRAVEYARD_CUES, GraveyardScene } from './graveyard'
import { DungeonScene } from './renderer'
import { planFoxden, planDungeon, planFrost, planGraveyard, type SfxEvent } from './sfx'
import type { IntroScene, IntroStageOptions } from './stage'
import { buildScript, type IntroCoreCues, type IntroLength } from './timeline'

export interface IntroPlayOptions {
  canvas: HTMLCanvasElement
  design: IntroDesign
  length: IntroLength
  reduced?: boolean
  /** Ohne Flackern und Lichtblitze (gleiche Szenen und Musik). */
  calm?: boolean
  sound?: boolean
  volume?: number
  /** Jingle (URL oder Lader) oder null = nur Effekte. Sein Schlag (`design.audio.jingleHit`) liegt auf `impact`. */
  jingle?: string | (() => Promise<ArrayBuffer>) | null
  /** Logo-Bild (16×16-Raster, z. B. /icon.png). */
  logo?: string | null
  /** Handy: kleinere Leinwand. */
  mobile?: boolean
  /** Einmal, wenn die Übergabe beginnen darf (`fadeOut` erreicht). */
  onReady?: () => void
}

/** Szene + Schnittpunkte + Effekt-Fahrplan zum Design (Registry → Szenen-Set). */
export function createIntroScene(
  cv: HTMLCanvasElement,
  design: IntroDesign,
  length: IntroLength,
  opts: IntroStageOptions,
): { scene: IntroScene; cues: IntroCoreCues; plan: SfxEvent[] } {
  switch (design.scene) {
    case 'graveyard': {
      const cues = GRAVEYARD_CUES[length]
      return { scene: new GraveyardScene(cv, design, length, opts), cues, plan: planGraveyard(cues) }
    }
    case 'frost': {
      const cues = FROST_CUES[length]
      return { scene: new FrostScene(cv, design, length, opts), cues, plan: planFrost(cues) }
    }
    case 'foxden': {
      const cues = FOXDEN_CUES[length]
      return { scene: new FoxdenScene(cv, design, length, opts), cues, plan: planFoxden(cues) }
    }
    default: {
      const script = buildScript(length)
      return { scene: new DungeonScene(cv, design, script, opts), cues: script.cues, plan: planDungeon(script) }
    }
  }
}

export class IntroPlayer {
  readonly scene: IntroScene
  readonly audio: IntroAudio | null
  readonly duration: number
  readonly fadeOut: number
  private raf = 0
  private t0 = 0
  private stopped = false
  private readyFired = false
  /** Nach dem Überspringen läuft die Uhr ohne Ton weiter (ab dieser Zeit). */
  private jump: { at: number; t: number } | null = null
  private readonly onResize = () => this.scene.resize()
  private readonly onBlur = () => this.audio?.setFocused(false)
  private readonly onFocus = () => this.audio?.setFocused(true)

  constructor(private readonly o: IntroPlayOptions) {
    const made = createIntroScene(o.canvas, o.design, o.length, {
      reduced: !!o.reduced,
      calm: !!o.calm,
      maxPixels: o.mobile ? 1280 * 720 : 2560 * 1440,
      dprCap: o.mobile ? 1.5 : 2,
    })
    this.scene = made.scene
    this.duration = this.scene.duration
    this.fadeOut = this.scene.fadeOut
    this.audio = o.sound && !o.reduced ? new IntroAudio({ style: o.design.audio, cues: made.cues, plan: made.plan, volume: o.volume, jingle: o.jingle }) : null
    if (o.logo) {
      const img = new Image()
      img.onload = () => this.scene.setLogo(img)
      img.src = o.logo
    }
  }

  /** Aktuelle Animationszeit (s). */
  get time(): number {
    if (this.jump) return this.jump.t + (performance.now() - this.jump.at) / 1000
    const a = this.audio?.time()
    if (a !== null && a !== undefined) return a
    return (performance.now() - this.t0) / 1000
  }

  /** Startet sofort; der Jingle setzt ein, sobald er geladen ist (bleibt synchron). */
  start() {
    this.scene.resize()
    window.addEventListener('resize', this.onResize)
    window.addEventListener('blur', this.onBlur)
    window.addEventListener('focus', this.onFocus)
    if (this.audio) {
      void this.audio.load()
      if (typeof document !== 'undefined' && !document.hasFocus()) this.audio.setFocused(false)
      this.audio.start(0)
    }
    this.t0 = performance.now()
    const loop = () => {
      if (this.stopped) return
      const t = this.time
      this.scene.render(t)
      if (!this.readyFired && t >= this.fadeOut) {
        this.readyFired = true
        this.o.onReady?.()
      }
      this.raf = requestAnimationFrame(loop)
    }
    this.raf = requestAnimationFrame(loop)
  }

  /** Überspringen: Ton weich aus, Bild springt aufs fertige Logo. */
  skip() {
    if (this.stopped) return
    this.audio?.stop(0.25)
    const target = Math.max(this.time, this.fadeOut)
    this.jump = { at: performance.now(), t: target }
  }

  /** Bild + Ton weich beenden (Bildschleife hält an). */
  stop(fade = 0.35) {
    if (this.stopped) return
    this.stopped = true
    cancelAnimationFrame(this.raf)
    window.removeEventListener('resize', this.onResize)
    window.removeEventListener('blur', this.onBlur)
    window.removeEventListener('focus', this.onFocus)
    this.audio?.stop(fade)
  }

  /** Nur den Ton ausblenden (Bild läuft weiter). */
  fadeAudio(fade = 0.3) {
    this.audio?.stop(fade)
  }

  /** Standbild zur Zeit t (hält die Schleife an) – für Vorschau und Tests. */
  still(t: number) {
    this.stop(0.05)
    this.scene.resize()
    this.scene.render(t)
  }
}
