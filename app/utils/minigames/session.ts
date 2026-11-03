// Eine laufende Partie: fester 60-Hz-Takt, Tasten, Pause und Bildschleife. Die Regeln stecken in Stapler und Loren.

import { GameAudio } from './audio'
import { K as LK, Loren, type LorenEvent, UNIT } from './loren'
import { LorenView } from './lorenView'
import type { GameId } from './replay'
import { K as SK, Stapler, type StaplerEvent } from './stapler'
import { StaplerView } from './staplerView'
import type { GameSkin, TextureMap } from './textures'

const STEP = 1 / 60
const MAX_STEPS_PER_FRAME = 8
const HUD_EVERY = 0.1

export const STAPLER_KEYS: Record<string, number> = { ArrowLeft: SK.LEFT, ArrowRight: SK.RIGHT, ArrowDown: SK.DOWN, ArrowUp: SK.CW, KeyX: SK.CW, KeyZ: SK.CCW, KeyY: SK.CCW, Space: SK.HARD, KeyC: SK.HOLD, ShiftLeft: SK.HOLD, KeyE: SK.PULSE }
export const LOREN_KEYS: Record<string, number> = { ArrowLeft: LK.LEFT, KeyA: LK.LEFT, ArrowRight: LK.RIGHT, KeyD: LK.RIGHT, ArrowUp: LK.JUMP, KeyW: LK.JUMP, Space: LK.JUMP, ArrowDown: LK.DUCK, KeyS: LK.DUCK, KeyE: LK.TURBO }

export type SessionState = 'ready' | 'playing' | 'paused' | 'over'

export interface StaplerHud { kind: 'stapler', score: number, lines: number, level: number, combo: number, signal: number, lamps: number }
export interface LorenHud { kind: 'loren', score: number, blocks: number, speed: number, zone: string, signal: number, turbo: boolean }
export type GameHud = StaplerHud | LorenHud

export interface FinishedRun { game: GameId, seed: number, inputs: number[], score: number, ticks: number }

export interface SessionOptions {
  game: GameId
  canvas: HTMLCanvasElement
  images: TextureMap
  skin: GameSkin
  onHud: (hud: GameHud) => void
  onState: (state: SessionState) => void
  onFinish: (run: FinishedRun) => void
}

export class GameSession {
  readonly audio = new GameAudio()
  readonly stapler: StaplerView | null
  readonly loren: LorenView | null
  state: SessionState = 'ready'
  private sim: Stapler | Loren | null = null
  private readonly idle = new Loren(4242)
  private mask = 0
  private latch = 0
  private acc = 0
  private last = 0
  private hudT = 0
  private frameId = 0
  private disposed = false
  private readonly keys: Record<string, number>

  constructor(private readonly options: SessionOptions) {
    const { game, canvas, images, skin } = options
    this.keys = game === 'stapler' ? STAPLER_KEYS : LOREN_KEYS
    this.stapler = game === 'stapler' ? new StaplerView(canvas, images, skin, this.audio) : null
    this.loren = game === 'loren' ? new LorenView(canvas, images, skin, this.audio) : null
    this.frameId = requestAnimationFrame(this.frame)
  }

  get current(): Stapler | Loren | null {
    return this.sim
  }

  private setState(state: SessionState) {
    if (this.state === state) return
    this.state = state
    this.options.onState(state)
  }

  start(seed: number) {
    this.audio.unlock()
    if (this.options.game === 'stapler') {
      this.sim = new Stapler(seed, e => this.onStapler(e))
      this.stapler!.reset()
    }
    else {
      this.sim = new Loren(seed, e => this.onLoren(e))
      this.loren!.reset()
    }
    this.mask = 0
    this.latch = 0
    this.acc = 0
    this.setState('playing')
  }

  private onStapler(e: StaplerEvent) {
    this.stapler!.onEvent(e)
    if (e.type === 'over') this.finish()
  }

  private onLoren(e: LorenEvent) {
    this.loren!.onEvent(e, this.sim as Loren)
    if (e.type === 'crash' || e.type === 'over') this.finish()
  }

  private finish() {
    const sim = this.sim!
    this.setState('over')
    this.pushHud()
    this.options.onFinish({ game: this.options.game, seed: sim.seed, inputs: [...sim.inputs], score: sim.score, ticks: sim.tick })
  }

  pause(on: boolean) {
    if (on && this.state === 'playing') {
      this.mask = 0
      this.latch = 0
      this.setState('paused')
    }
    else if (!on && this.state === 'paused') {
      this.audio.unlock()
      this.acc = 0
      this.setState('playing')
    }
  }

  /** Gibt `true` zurück, wenn die Taste zum Spiel gehört und der Browser nichts damit tun soll. */
  keyDown(code: string, repeat: boolean): boolean {
    const bit = this.keys[code]
    if (this.state !== 'playing' || !bit) return !!bit
    this.mask |= bit
    if (!repeat) this.latch |= bit
    return true
  }

  keyUp(code: string) {
    const bit = this.keys[code]
    if (bit) this.mask &= ~bit
  }

  /** Beim Verlassen des Fensters: Sonst liefe das Spiel mit gedrückter Taste weiter. */
  blur() {
    this.mask = 0
    this.latch = 0
    this.pause(true)
  }

  private pushHud() {
    const sim = this.sim
    if (!sim) return
    if (sim instanceof Stapler) {
      this.options.onHud({ kind: 'stapler', score: sim.score, lines: sim.lines, level: sim.level, combo: sim.combo, signal: sim.signal, lamps: sim.litLamps() })
    }
    else {
      this.options.onHud({ kind: 'loren', score: sim.score, blocks: Math.floor(sim.pos / UNIT), speed: Math.round(sim.speed * 600 / UNIT) / 10, zone: this.loren!.zoneName(sim), signal: sim.signal, turbo: sim.turbo > 0 })
    }
  }

  private frame = (now: number) => {
    if (this.disposed) return
    const dt = Math.min(0.1, (now - this.last) / 1000 || 0)
    this.last = now
    if (this.state === 'playing' && this.sim) {
      this.acc += dt
      let steps = 0
      while (this.acc >= STEP && steps++ < MAX_STEPS_PER_FRAME) {
        this.acc -= STEP
        this.sim.step(this.mask | this.latch)
        this.latch = 0
        if ((this.state as SessionState) !== 'playing') break
      }
      if (steps >= MAX_STEPS_PER_FRAME) this.acc = 0
    }
    const live = this.state === 'paused' ? 0 : dt
    if (this.stapler) {
      const sim = this.sim as Stapler | null
      this.stapler.update(live, sim)
      this.stapler.draw(sim)
    }
    else if (this.loren) {
      const sim = this.sim as Loren | null
      this.loren.update(live, sim)
      if (!sim) {
        // Vor der ersten Fahrt rollt der Stollen im Hintergrund.
        this.idle.pos += 18
        this.idle.tick++
      }
      this.loren.draw(sim ?? this.idle)
      this.loren.updateSound(sim, this.state === 'playing')
    }
    this.hudT += dt
    if (this.hudT >= HUD_EVERY) {
      this.hudT = 0
      this.pushHud()
    }
    this.frameId = requestAnimationFrame(this.frame)
  }

  dispose() {
    this.disposed = true
    cancelAnimationFrame(this.frameId)
    this.audio.dispose()
  }
}
