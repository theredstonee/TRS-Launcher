// Loren-Rausch: reine Spiellogik ohne Anzeige.
// Nur Ganzzahlen und fester 60-Hz-Takt, damit der Server und der TRS Client (Java) jede Fahrt exakt nachrechnen können.
// Änderungen an den Regeln müssen in api/shared/minigames und core/minigames (Client) mit, sonst passen die Testpartien nicht mehr.

import { MAX_TICKS, mulberry } from './stapler'

export const K = { LEFT: 1, RIGHT: 2, JUMP: 4, DUCK: 8, TURBO: 16 } as const
export const O = { NONE: 0, TNT: 1, CRATE: 2, BEAM: 3, CREEPER: 4 } as const

export const ROW_GAP = 7
export const UNIT = 256
export const LANE_T = 9
export const BASE_SPEED = 30
export const MAX_SPEED = 92
const JUMP_T = 40
const JUMP_H = 410
const CLEAR_H = 230
const DUCK_T = 28
const TURBO_T = 300
const STAGGER_T = 90
const ROW_UNITS = UNIT * ROW_GAP

export interface Row { o: number[], item: boolean[], pow: boolean[], used: boolean[], got: boolean[] }
export type LorenPhase = 'run' | 'over'

export type LorenEvent =
  | { type: 'lever' | 'land' | 'jump' | 'duck' | 'item' | 'dodge' | 'turbo' | 'over' }
  | { type: 'smash' | 'hit' | 'crash', o: number }

export class Loren {
  readonly seed: number
  private readonly emit: (event: LorenEvent) => void
  private readonly rows = new Map<number, Row>()
  pos = 0
  speed = BASE_SPEED
  lane = 1
  fromLane = 1
  laneT = 0
  jumpT = 0
  duckT = 0
  signal = 0
  turbo = 0
  score = 0
  tick = 0
  phase: LorenPhase = 'run'
  stagger = 0
  items = 0
  smashed = 0
  /** Eingaben der Fahrt: abwechselnd Abstand in Takten und Tastenmaske. */
  readonly inputs: number[] = []
  private prev = 0
  private lastMask = 0
  private lastInputTick = 0

  constructor(seed: number, emit?: (event: LorenEvent) => void) {
    this.seed = seed >>> 0
    this.emit = emit ?? (() => {})
  }

  get finished(): boolean {
    return this.phase === 'over'
  }

  /** Jede Reihe hängt nur vom Startwert und von der Vorreihe ab, die Reihenfolge der Abfragen ändert also nichts. */
  row(i: number): Row {
    const known = this.rows.get(i)
    if (known) return known
    const r: Row = { o: [0, 0, 0], item: [false, false, false], pow: [false, false, false], used: [false, false, false], got: [false, false, false] }
    this.rows.set(i, r)
    if (i < 5) return r
    const rnd = mulberry(Math.imul(this.seed ^ i, 2654435761))
    const hard = Math.min(100, Math.floor(i / 2))
    if (rnd() % 100 < 40 + Math.floor(hard / 2)) {
      const many = rnd() % 100 < 12 + Math.floor(hard / 2) ? 2 : 1
      const first = rnd() % 3
      for (let k = 0; k < many; k++) {
        const roll = rnd() % 100
        r.o[(first + k) % 3] = roll < 35 ? O.TNT : roll < 60 ? O.CRATE : roll < 82 ? O.BEAM : O.CREEPER
      }
      if (hard >= 30 && rnd() % 100 < hard - 20) {
        const lane = (first + many) % 3
        if (rnd() % 2) r.o[lane] = rnd() % 2 ? O.TNT : O.BEAM
      }
    }
    for (let l = 0; l < 3; l++) {
      if (!r.o[l] && rnd() % 100 < 26) r.item[l] = true
    }
    const before = i > 5 ? this.row(i - 1) : null
    for (let l = 0; l < 3; l++) r.pow[l] = rnd() % 100 < (before?.pow[l] ? 55 : 7)
    return r
  }

  height(): number {
    if (this.jumpT <= 0) return 0
    const e = JUMP_T - this.jumpT
    return Math.floor(4 * JUMP_H * e * (JUMP_T - e) / (JUMP_T * JUMP_T))
  }

  step(mask: number) {
    if (this.phase !== 'run') return
    if (this.tick >= MAX_TICKS) {
      this.phase = 'over'
      this.emit({ type: 'over' })
      return
    }
    if (mask !== this.lastMask) {
      this.inputs.push(this.tick - this.lastInputTick, mask)
      this.lastInputTick = this.tick
      this.lastMask = mask
    }
    this.tick++
    const edge = mask & ~this.prev
    this.prev = mask
    if (this.laneT > 0) this.laneT--
    if (edge & K.LEFT && this.lane > 0) {
      this.fromLane = this.lane
      this.lane--
      this.laneT = LANE_T
      this.emit({ type: 'lever' })
    }
    if (edge & K.RIGHT && this.lane < 2) {
      this.fromLane = this.lane
      this.lane++
      this.laneT = LANE_T
      this.emit({ type: 'lever' })
    }
    if (this.jumpT > 0) {
      this.jumpT--
      if (this.jumpT === 0) this.emit({ type: 'land' })
    }
    if (edge & K.JUMP && this.jumpT === 0) {
      this.jumpT = JUMP_T
      this.duckT = 0
      this.emit({ type: 'jump' })
    }
    if (edge & K.DUCK && this.jumpT === 0) {
      this.duckT = DUCK_T
      this.emit({ type: 'duck' })
    }
    if (this.duckT > 0) this.duckT--
    if (edge & K.TURBO && this.signal >= 15) {
      this.signal = 0
      this.turbo = TURBO_T
      this.emit({ type: 'turbo' })
    }
    if (this.turbo > 0) this.turbo--
    if (this.stagger > 0) this.stagger--
    const base = Math.min(MAX_SPEED, BASE_SPEED + Math.floor(this.pos / (UNIT * 24)))
    const powered = this.row(Math.floor(this.pos / ROW_UNITS)).pow[this.lane]! && this.jumpT === 0
    let want = base + (powered ? 12 : 0) + (this.turbo > 0 ? 34 : 0)
    if (this.stagger > 0) want = Math.floor(want / 2)
    this.speed += want > this.speed ? 1 : want < this.speed ? -1 : 0
    const before = this.pos
    const after = before + this.speed
    this.pos = after
    const from = Math.floor(before / ROW_UNITS)
    const to = Math.floor(after / ROW_UNITS)
    for (let i = from + 1; i <= to; i++) this.cross(this.row(i))
    this.score += Math.floor(after / UNIT) - Math.floor(before / UNIT)
    if (powered && this.tick % 4 === 0) this.score += 1
  }

  private cross(r: Row) {
    if (this.phase !== 'run') return
    const l = this.lane
    const o = r.o[l]!
    const h = this.height()
    if (r.item[l] && !r.got[l] && h < JUMP_H - 60) {
      r.got[l] = true
      this.items++
      this.signal = Math.min(15, this.signal + 1)
      this.score += 50
      this.emit({ type: 'item' })
    }
    if (!o || r.used[l]) return
    r.used[l] = true
    const clear = o === O.CRATE ? false : o === O.BEAM ? this.duckT > 0 : h >= CLEAR_H
    if (clear) {
      this.score += 25
      this.emit({ type: 'dodge' })
      return
    }
    if (this.turbo > 0) {
      this.smashed++
      this.score += 150
      this.emit({ type: 'smash', o })
      return
    }
    if (this.signal >= 5) {
      this.signal -= 5
      this.stagger = STAGGER_T
      this.emit({ type: 'hit', o })
      return
    }
    this.phase = 'over'
    this.emit({ type: 'crash', o })
  }

  hash(): number {
    let h = 2166136261
    for (const v of [this.pos, this.score, this.lane, this.signal, this.tick, this.items, this.turbo, this.jumpT]) h = Math.imul(h ^ v, 16777619)
    return h >>> 0
  }
}
