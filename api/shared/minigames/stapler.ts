// Redstone-Stapler: reine Spiellogik ohne Anzeige.
// Nur Ganzzahlen und fester 60-Hz-Takt, damit der Server und der TRS Client (Java) jede Partie exakt nachrechnen können.
// Änderungen an den Regeln müssen in api/shared/minigames und core/minigames (Client) mit, sonst passen die Testpartien nicht mehr.

export const WIDTH = 10
export const HEIGHT = 22
export const HIDDEN = 2
/** Eine Partie endet spätestens nach einer Stunde, damit der Server beim Nachrechnen eine feste Obergrenze hat. */
export const MAX_TICKS = 216_000

export const T = { STONE: 1, COBBLE: 2, BRICK: 3, COPPER: 4, OAK: 5, OBSID: 6, QUARTZ: 7, RED: 8, TNT: 9, LAMP: 10, PISTON: 11, HEAD: 12 } as const
export const K = { LEFT: 1, RIGHT: 2, DOWN: 4, CW: 8, CCW: 16, HARD: 32, HOLD: 64, PULSE: 128 } as const

const SHAPES: { n: number, m: number, b: number[][] }[] = [
  { n: 4, m: T.QUARTZ, b: [[0, 1], [1, 1], [2, 1], [3, 1]] },
  { n: 2, m: T.COPPER, b: [[0, 0], [1, 0], [0, 1], [1, 1]] },
  { n: 3, m: T.OBSID, b: [[1, 0], [0, 1], [1, 1], [2, 1]] },
  { n: 3, m: T.OAK, b: [[1, 0], [2, 0], [0, 1], [1, 1]] },
  { n: 3, m: T.BRICK, b: [[0, 0], [1, 0], [1, 1], [2, 1]] },
  { n: 3, m: T.COBBLE, b: [[0, 0], [0, 1], [1, 1], [2, 1]] },
  { n: 3, m: T.STONE, b: [[2, 0], [0, 1], [1, 1], [2, 1]] },
]
const GRAVITY = [48, 40, 32, 26, 20, 16, 12, 9, 7, 5, 4, 3, 2, 1]
const WALL_KICKS = [[0, 0], [-1, 0], [1, 0], [0, -1], [-2, 0], [2, 0], [-1, -1], [1, -1]]
const SPECIALS = [T.RED, T.TNT, T.LAMP, T.PISTON]
const LINE_POINTS = [0, 100, 300, 500, 800]

export const ARE = 14
export const CLEAR_T = 12
const LOCK_T = 30
const LOCK_MOVES = 15
const DAS = 10
const ARR = 2
const LAMP_T = 600

export function mulberry(seed: number): () => number {
  let a = seed | 0
  return () => {
    a = (a + 0x6D2B79F5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return (t ^ (t >>> 14)) >>> 0
  }
}

export interface Cell { t: number, lit: number, fuse: number, pw: number }
export interface Piece { id: number, n: number, mats: number[], b: number[][], x: number, y: number }
export type StaplerPhase = 'are' | 'fall' | 'resolve' | 'over'

export type StaplerEvent =
  | { type: 'move' | 'rotate' | 'hold' | 'wire' | 'pulse' | 'level' | 'over' }
  | { type: 'spawn', x: number, n: number }
  | { type: 'lock', cells: number[][] }
  | { type: 'hard', from: number, to: number }
  | { type: 'sweep', rows: number[] }
  | { type: 'clear', rows: number[], cells: number[][] }
  | { type: 'ignite' | 'lamp' | 'charge', x: number, y: number }
  | { type: 'piston', x: number, y: number, d: number, n: number }
  | { type: 'explode', x: number, y: number, removed: number }

function cell(t: number): Cell {
  return { t, lit: 0, fuse: 0, pw: 0 }
}

export class Stapler {
  readonly seed: number
  private readonly rng: () => number
  private readonly emit: (event: StaplerEvent) => void
  grid: (Cell | null)[][] = []
  tick = 0
  score = 0
  lines = 0
  level = 1
  combo = 0
  signal = 0
  queue: Piece[] = []
  hold: Piece | null = null
  canHold = true
  cur: Piece | null = null
  phase: StaplerPhase = 'are'
  timer = ARE
  nextX = 0
  sweep: { rows: number[], t: number } | null = null
  /** Eingaben der Partie: abwechselnd Abstand in Takten und Tastenmaske. */
  readonly inputs: number[] = []
  private bag: number[] = []
  private prev = 0
  private dasDir = 0
  private dasT = 0
  private gravT = 0
  private lockT = 0
  private lockMoves = 0
  private blasts: { x: number, y: number, t: number }[] = []
  private power: Cell[] = []
  private clearedNow = false
  private forceBottom = false
  private lastMask = 0
  private lastInputTick = 0
  private resolveId = 0

  constructor(seed: number, emit?: (event: StaplerEvent) => void) {
    this.seed = seed >>> 0
    this.rng = mulberry(this.seed)
    this.emit = emit ?? (() => {})
    for (let y = 0; y < HEIGHT; y++) this.grid.push(new Array<Cell | null>(WIDTH).fill(null))
    while (this.queue.length < 4) this.queue.push(this.draw())
    this.nextX = this.pickX(this.queue[0]!)
  }

  get finished(): boolean {
    return this.phase === 'over'
  }

  private draw(): Piece {
    if (!this.bag.length) {
      this.bag = [0, 1, 2, 3, 4, 5, 6]
      for (let i = 6; i > 0; i--) {
        const j = this.rng() % (i + 1)
        const t = this.bag[i]!
        this.bag[i] = this.bag[j]!
        this.bag[j] = t
      }
    }
    const id = this.bag.pop()!
    const shape = SHAPES[id]!
    const mats = [shape.m, shape.m, shape.m, shape.m]
    if (this.rng() % 100 < 42) {
      const at = this.rng() % 4
      mats[at] = SPECIALS[this.rng() % 4]!
    }
    return { id, n: shape.n, mats, b: shape.b.map(p => p.slice()), x: 0, y: 0 }
  }

  private hits(b: number[][], x: number, y: number): boolean {
    for (const c of b) {
      const cx = x + c[0]!
      const cy = y + c[1]!
      if (cx < 0 || cx >= WIDTH || cy >= HEIGHT) return true
      if (cy >= 0 && this.grid[cy]![cx]) return true
    }
    return false
  }

  /** Zufälliger Dropper: Ein freier wird bevorzugt, Überlauf gibt es erst, wenn wirklich kein Platz mehr ist. */
  private pickX(p: Piece): number {
    let min = WIDTH
    let max = 0
    for (const c of p.b) {
      if (c[0]! < min) min = c[0]!
      if (c[0]! > max) max = c[0]!
    }
    const lo = -min
    const span = WIDTH - 1 - max - lo + 1
    const s = this.rng() % span
    for (let i = 0; i < span; i++) {
      const x = lo + (s + i) % span
      if (!this.hits(p.b, x, 0)) return x
    }
    return lo + s
  }

  private spawn(piece: Piece | null) {
    const p = piece ?? this.queue.shift()!
    while (this.queue.length < 4) this.queue.push(this.draw())
    p.x = this.phase === 'are' ? this.nextX : this.pickX(p)
    p.y = 0
    this.cur = p
    this.gravT = 0
    this.lockT = 0
    this.lockMoves = 0
    if (this.hits(p.b, p.x, p.y)) {
      this.end()
      return
    }
    this.phase = 'fall'
    this.emit({ type: 'spawn', x: p.x, n: p.n })
  }

  private end() {
    this.phase = 'over'
    this.emit({ type: 'over' })
  }

  private rotate(p: Piece, dir: number) {
    const next = p.b.map(c => (dir > 0 ? [p.n - 1 - c[1]!, c[0]!] : [c[1]!, p.n - 1 - c[0]!]))
    for (const k of WALL_KICKS) {
      if (!this.hits(next, p.x + k[0]!, p.y + k[1]!)) {
        p.b = next
        p.x += k[0]!
        p.y += k[1]!
        this.moved()
        this.emit({ type: 'rotate' })
        return
      }
    }
  }

  private moved() {
    if (this.lockT > 0 && this.lockMoves < LOCK_MOVES) {
      this.lockT = 0
      this.lockMoves++
    }
  }

  private shift(dir: number) {
    const p = this.cur!
    if (!this.hits(p.b, p.x + dir, p.y)) {
      p.x += dir
      this.moved()
      this.emit({ type: 'move' })
    }
  }

  ghostY(): number {
    const p = this.cur!
    let y = p.y
    while (!this.hits(p.b, p.x, y + 1)) y++
    return y
  }

  private lock() {
    const p = this.cur!
    let above = true
    p.b.forEach((c, i) => {
      const y = p.y + c[1]!
      const x = p.x + c[0]!
      if (y >= 0) this.grid[y]![x] = cell(p.mats[i]!)
      if (y >= HIDDEN) above = false
    })
    this.emit({ type: 'lock', cells: p.b.map(c => [p.x + c[0]!, p.y + c[1]!]) })
    this.cur = null
    this.canHold = true
    if (above) {
      this.end()
      return
    }
    this.beginResolve()
  }

  private beginResolve() {
    this.phase = 'resolve'
    this.clearedNow = false
    this.resolveId++
  }

  step(mask: number) {
    if (this.phase === 'over') return
    if (this.tick >= MAX_TICKS) {
      this.end()
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
    if (this.tick % 180 === 0 && this.signal > 0) this.signal--
    for (const row of this.grid) {
      for (const c of row) {
        if (c && c.lit > 0) c.lit--
      }
    }
    if (this.phase === 'are') {
      if (--this.timer <= 0) this.spawn(null)
      return
    }
    if (this.phase === 'resolve') {
      this.stepResolve()
      return
    }
    if (edge & K.PULSE && this.signal >= 15) {
      this.pulse()
      return
    }
    if (edge & K.HOLD && this.canHold) {
      const p = this.cur!
      const back = this.hold
      this.hold = { id: p.id, n: p.n, mats: p.mats, b: SHAPES[p.id]!.b.map(c => c.slice()), x: 0, y: 0 }
      this.canHold = false
      this.emit({ type: 'hold' })
      this.spawn(back)
      return
    }
    const cur = this.cur!
    if (edge & K.CW) this.rotate(cur, 1)
    if (edge & K.CCW) this.rotate(cur, -1)
    const dir = (mask & K.LEFT ? -1 : 0) + (mask & K.RIGHT ? 1 : 0)
    if (dir !== this.dasDir) {
      this.dasDir = dir
      this.dasT = 0
      if (dir) this.shift(dir)
    }
    else if (dir && ++this.dasT >= DAS && (this.dasT - DAS) % ARR === 0) {
      this.shift(dir)
    }
    if (edge & K.HARD) {
      const y = this.ghostY()
      this.score += (y - cur.y) * 2
      this.emit({ type: 'hard', from: cur.y, to: y })
      cur.y = y
      this.lock()
      return
    }
    if (this.hits(cur.b, cur.x, cur.y + 1)) {
      if (++this.lockT >= LOCK_T || this.lockMoves >= LOCK_MOVES) this.lock()
    }
    else {
      this.lockT = 0
      const every = mask & K.DOWN ? 2 : GRAVITY[Math.min(this.level - 1, GRAVITY.length - 1)]!
      if (++this.gravT >= every) {
        this.gravT = 0
        cur.y++
        if (mask & K.DOWN) this.score += 1
      }
    }
  }

  private pulse() {
    this.signal = 0
    this.forceBottom = true
    this.beginResolve()
    for (let y = 0; y < HEIGHT; y++) {
      for (let x = 0; x < WIDTH; x++) {
        const c = this.grid[y]![x]
        if (c && (c.t === T.TNT || c.t === T.LAMP || c.t === T.PISTON)) this.power.push(c)
      }
    }
    this.emit({ type: 'pulse' })
  }

  private find(target: Cell): [number, number] | null {
    for (let y = 0; y < HEIGHT; y++) {
      const x = this.grid[y]!.indexOf(target)
      if (x >= 0) return [x, y]
    }
    return null
  }

  private stepResolve() {
    if (this.sweep) {
      if (++this.sweep.t >= CLEAR_T) this.finishClear()
      return
    }
    let busy = false
    for (let y = 0; y < HEIGHT; y++) {
      for (let x = 0; x < WIDTH; x++) {
        const c = this.grid[y]![x]
        if (c && c.fuse > 0) {
          busy = true
          if (--c.fuse === 0) this.explode(x, y, c)
        }
      }
    }
    for (let i = this.blasts.length - 1; i >= 0; i--) {
      busy = true
      const blast = this.blasts[i]!
      if (--blast.t <= 0) {
        this.blasts.splice(i, 1)
        this.explode(blast.x, blast.y, null)
      }
    }
    if (this.power.length) {
      busy = true
      const c = this.power.shift()!
      const at = this.find(c)
      if (at) this.act(c, at[0], at[1])
    }
    if (busy) return
    const rows: number[] = []
    for (let y = 0; y < HEIGHT; y++) {
      if (this.grid[y]!.every(Boolean)) rows.push(y)
    }
    if (this.forceBottom) {
      this.forceBottom = false
      for (let y = HEIGHT - 1; y >= 0; y--) {
        if (this.grid[y]!.some(Boolean)) {
          if (!rows.includes(y)) rows.push(y)
          break
        }
      }
      rows.sort((a, b) => a - b)
    }
    if (rows.length) {
      this.sweep = { rows, t: 0 }
      this.emit({ type: 'sweep', rows })
      return
    }
    if (!this.clearedNow) this.combo = 0
    if (this.cur) {
      while (this.hits(this.cur.b, this.cur.x, this.cur.y) && this.cur.y > -2) this.cur.y--
      this.phase = 'fall'
    }
    else {
      this.phase = 'are'
      this.timer = ARE
      this.nextX = this.pickX(this.queue[0]!)
      this.emit({ type: 'wire' })
    }
  }

  private neighbours(x: number, y: number, skip: number[], up: number) {
    for (let dy = -up; dy <= 1; dy++) {
      for (let dx = -1; dx <= 1; dx++) {
        const yy = y + dy
        const xx = x + dx
        if (yy < 0 || yy >= HEIGHT || xx < 0 || xx >= WIDTH || skip.includes(yy)) continue
        const c = this.grid[yy]![xx]
        if (c && c.t >= T.RED && c.t <= T.PISTON && c.pw !== this.resolveId) {
          c.pw = this.resolveId
          this.power.push(c)
        }
      }
    }
  }

  private finishClear() {
    const rows = this.sweep!.rows
    const n = rows.length
    let lampBonus = 0
    for (const y of rows) {
      for (let x = 0; x < WIDTH; x++) {
        const c = this.grid[y]![x]
        if (!c) continue
        if (c.t === T.RED) {
          this.neighbours(x, y, rows, 4)
        }
        else if (c.t === T.TNT) {
          let below = 0
          for (const r of rows) {
            if (r > y) below++
          }
          this.blasts.push({ x, y: y + below, t: 4 })
        }
        else if (c.t === T.LAMP) {
          lampBonus += 2
        }
      }
    }
    this.emit({ type: 'clear', rows, cells: rows.map(y => this.grid[y]!.map(c => (c ? c.t : 0))) })
    this.grid = this.grid.filter((_, y) => !rows.includes(y))
    while (this.grid.length < HEIGHT) this.grid.unshift(new Array<Cell | null>(WIDTH).fill(null))
    this.combo++
    this.clearedNow = true
    this.lines += n
    this.award(LINE_POINTS[Math.min(n, 4)]! * this.level + 50 * (this.combo - 1) * this.level)
    this.signal = Math.min(15, this.signal + 3 * n + (this.combo - 1) + lampBonus)
    const level = 1 + Math.floor(this.lines / 10)
    if (level !== this.level) {
      this.level = level
      this.emit({ type: 'level' })
    }
    this.sweep = null
  }

  litLamps(): number {
    let n = 0
    for (const row of this.grid) {
      for (const c of row) {
        if (c && c.t === T.LAMP && c.lit > 0) n++
      }
    }
    return n
  }

  private award(points: number) {
    this.score += Math.floor(points * (2 + this.litLamps()) / 2)
  }

  private act(c: Cell, x: number, y: number) {
    if (c.t === T.TNT) {
      if (!c.fuse) {
        c.fuse = 10
        this.emit({ type: 'ignite', x, y })
      }
    }
    else if (c.t === T.LAMP) {
      c.lit = LAMP_T
      this.emit({ type: 'lamp', x, y })
    }
    else if (c.t === T.RED) {
      this.neighbours(x, y, [], 1)
      this.emit({ type: 'charge', x, y })
    }
    else if (c.t === T.PISTON) {
      this.piston(c, x, y)
    }
  }

  private piston(c: Cell, x: number, y: number) {
    const row = this.grid[y]!
    let best = 0
    let dist = 99
    for (const d of [-1, 1]) {
      for (let i = x + d, s = 1; i >= 0 && i < WIDTH; i += d, s++) {
        if (!row[i]) {
          if (s < dist) {
            dist = s
            best = d
          }
          break
        }
      }
    }
    c.t = T.STONE
    if (!best) return
    for (let i = x + best * dist; i !== x + best; i -= best) row[i] = row[i - best]!
    row[x + best] = cell(T.HEAD)
    this.emit({ type: 'piston', x, y, d: best, n: dist })
  }

  private explode(cx: number, cy: number, self: Cell | null) {
    let removed = 0
    let columns = 0
    for (let y = cy - 1; y <= cy + 1; y++) {
      for (let x = cx - 1; x <= cx + 1; x++) {
        if (y < 0 || y >= HEIGHT || x < 0 || x >= WIDTH) continue
        const c = this.grid[y]![x]
        if (!c) continue
        if (c !== self && c.t === T.TNT) {
          if (!c.fuse) c.fuse = 6
          continue
        }
        this.grid[y]![x] = null
        removed++
        columns |= 1 << x
      }
    }
    this.award(25 * removed * this.level)
    for (let x = 0; x < WIDTH; x++) {
      if (!(columns & (1 << x))) continue
      let write = Math.min(HEIGHT - 1, cy + 1)
      for (let y = write; y >= 0; y--) {
        const c = this.grid[y]![x]
        if (c) {
          this.grid[y]![x] = null
          this.grid[write]![x] = c
          write--
        }
      }
    }
    this.emit({ type: 'explode', x: cx, y: cy, removed })
  }

  hash(): number {
    let h = 2166136261 ^ this.score
    for (const row of this.grid) {
      for (const c of row) h = Math.imul(h ^ (c ? c.t + 1 : 0), 16777619)
    }
    return (Math.imul(h ^ this.tick, 16777619) ^ this.lines) >>> 0
  }
}
