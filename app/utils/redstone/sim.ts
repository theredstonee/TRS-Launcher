// Kleine Redstone-Simulation für den Hintergrund der Startseite.
//
// Kein exakter Nachbau von Minecraft, aber nah genug, dass die Schaltungen
// „richtig“ aussehen: Staub verliert pro Block eine Stufe Signalstärke,
// Verstärker frischen das Signal nach 1–4 Redstone-Ticks wieder auf 15 auf,
// Fackeln an Blöcken invertieren, Lampen gehen verzögert aus, Kolben fahren aus.
// Die Welt ist reine Datenstruktur – gezeichnet wird in `paint.ts`.

/** 0 = Norden (oben), 1 = Osten, 2 = Süden, 3 = Westen. */
export type Dir = 0 | 1 | 2 | 3
export const DX = [0, 1, 0, -1] as const
export const DY = [-1, 0, 1, 0] as const

export function opposite(d: Dir): Dir {
  return ((d + 2) % 4) as Dir
}

export type CellKind =
  | 'floor'
  | 'block'
  | 'dust'
  | 'repeater'
  | 'comparator'
  | 'torch'
  | 'wallTorch'
  | 'lamp'
  | 'piston'
  | 'source'

export interface Cell {
  kind: CellKind
  /** Verstärker/Komparator/Kolben: Ausgangsseite. Wandfackel: Seite des Blocks, an dem sie hängt. */
  dir: Dir
  /** Verzögerung eines Verstärkers in Redstone-Ticks (1–4). */
  delay: number
  /** Ausgang an (Verstärker, Komparator, Fackel, Quelle), Lampe leuchtet, Kolben ausgefahren. */
  on: boolean
  /** Signalstärke (Staub 0–15, Komparator-Ausgang). */
  power: number
  /** Verzögerungsleitung eines Verstärkers/Komparators. */
  hist: number[]
  /** Lampen gehen erst nach zwei Ticks ohne Strom aus. */
  offTimer: number
  /** Teil der Hauptleitung zur Spielen-Lampe. */
  bus: boolean
  /** Quelle, die von außen geschaltet wird (Spielstart). */
  external: boolean
  /** Staub: Verbindungen als Bitmaske (1 = N, 2 = O, 4 = S, 8 = W). */
  mask: number
}

function cell(kind: CellKind, dir: Dir = 1): Cell {
  return { kind, dir, delay: 1, on: false, power: 0, hist: [], offTimer: 0, bus: false, external: false, mask: 0 }
}

export function floorCell(): Cell {
  return cell('floor')
}

const DIR_CHARS: Record<string, Dir> = { '^': 0, '>': 1, v: 2, '<': 3 }

/**
 * Liest eine Schaltung aus einer Schablone: je Zelle zwei Zeichen, getrennt
 * durch Leerzeichen.
 *
 * `..` Boden · `##` Block · `--` Staub · `R>` Verstärker (Richtung `^ > v <`),
 * `r>` Verstärker mit anfänglichem Impuls · `C>` Komparator · `T.` Fackel ·
 * `I<` Wandfackel am Block in Richtung · `L.` Lampe · `P>` Kolben · `S.` Redstone-Block.
 *
 * `delays` gilt der Reihe nach (zeilenweise) für alle Verstärker.
 */
export function parseStencil(rows: string[], delays: number[] = [], pulse = 1): Cell[][] {
  let next = 0
  return rows.map((row) =>
    row
      .trim()
      .split(/\s+/)
      .map((token) => {
        const [k = '.', d = '.'] = token
        const dir = DIR_CHARS[d] ?? 1
        switch (k) {
          case '#':
            return cell('block')
          case '-':
            return cell('dust')
          case 'R':
          case 'r': {
            const c = cell('repeater', dir)
            c.delay = Math.min(4, Math.max(1, delays[next++] ?? 1))
            // Die Leitung hält `delay - 1` Werte; der Ausgang ist der älteste.
            c.hist = Array.from({ length: c.delay - 1 }, (_, i) => (k === 'r' && i < pulse - 1 ? 15 : 0))
            c.on = k === 'r'
            return c
          }
          case 'C': {
            const c = cell('comparator', dir)
            return c
          }
          case 'T': {
            const c = cell('torch')
            c.on = true
            return c
          }
          case 'I': {
            const c = cell('wallTorch', dir)
            c.on = true
            return c
          }
          case 'L':
            return cell('lamp')
          case 'P':
            return cell('piston', dir)
          case 'S': {
            const c = cell('source')
            c.on = true
            return c
          }
          default:
            return cell('floor')
        }
      }),
  )
}

/** Schaltung auf einem Raster; `step()` = ein Redstone-Tick (0,1 s). */
export class Circuit {
  readonly cells: Cell[]
  /** Hauptleitung: nur bis zu dieser Spalte leitet sie (Fortschritt beim Start). */
  busLimit = Number.POSITIVE_INFINITY
  ticks = 0

  constructor(
    readonly w: number,
    readonly h: number,
  ) {
    this.cells = Array.from({ length: w * h }, floorCell)
  }

  at(x: number, y: number): Cell | null {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h) return null
    return this.cells[y * this.w + x] ?? null
  }

  set(x: number, y: number, c: Cell) {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h) return
    this.cells[y * this.w + x] = c
  }

  /** Schablone an (x, y) einsetzen. */
  place(x: number, y: number, stencil: Cell[][]) {
    stencil.forEach((row, dy) => row.forEach((c, dx) => this.set(x + dx, y + dy, c)))
  }

  /** Einmal nach dem Aufbau: Staub-Verbindungen und den Startzustand berechnen. */
  finish() {
    for (let y = 0; y < this.h; y++) {
      for (let x = 0; x < this.w; x++) {
        const c = this.at(x, y)!
        if (c.kind === 'dust') c.mask = this.dustMask(x, y)
      }
    }
    this.updateDust()
    return this
  }

  /** Von außen geschaltete Quellen (Hauptleitung beim Spielstart). */
  setExternal(on: boolean) {
    for (const c of this.cells) if (c.external) c.on = on
  }

  private cut(x: number, c: Cell) {
    return c.bus && x > this.busLimit
  }

  /** Stärke, die Zelle (x, y) in Richtung `d` abgibt (ohne Staub). */
  emits(x: number, y: number, d: Dir): number {
    const c = this.at(x, y)
    if (!c || !c.on || this.cut(x, c)) return 0
    switch (c.kind) {
      case 'source':
      case 'torch':
        return 15
      case 'wallTorch':
        return d === c.dir ? 0 : 15
      case 'repeater':
        return d === c.dir ? 15 : 0
      case 'comparator':
        return d === c.dir ? c.power : 0
      default:
        return 0
    }
  }

  /** Was an Zelle (x, y) von der Nachbarzelle in Richtung `d` ankommt. */
  private incoming(x: number, y: number, d: Dir): number {
    const nx = x + DX[d]
    const ny = y + DY[d]
    const n = this.at(nx, ny)
    if (!n || this.cut(nx, n)) return 0
    if (n.kind === 'dust') return n.power
    if (n.kind === 'block') return this.blockPowered(nx, ny) ? 15 : 0
    return this.emits(nx, ny, opposite(d))
  }

  /** Ein Block ist „unter Strom“, wenn Staub, eine Quelle oder ein Verstärker ihn speist. */
  blockPowered(x: number, y: number): boolean {
    for (let d = 0 as Dir; d < 4; d = (d + 1) as Dir) {
      const nx = x + DX[d]
      const ny = y + DY[d]
      const n = this.at(nx, ny)
      if (!n || this.cut(nx, n)) continue
      if (n.kind === 'dust' && n.power > 0) return true
      if (n.kind === 'source' && n.on) return true
      if ((n.kind === 'repeater' || n.kind === 'comparator') && this.emits(nx, ny, opposite(d)) > 0) return true
    }
    return false
  }

  /** Lampen und Kolben: Strom von irgendeiner Seite (Kolben nicht von vorn). */
  private powered(x: number, y: number, except: Dir | -1 = -1): boolean {
    for (let d = 0 as Dir; d < 4; d = (d + 1) as Dir) {
      if (d === except) continue
      const n = this.at(x + DX[d], y + DY[d])
      if (!n) continue
      if (n.kind === 'block') continue
      if (this.incoming(x, y, d) > 0) return true
    }
    return false
  }

  /** Verbindet sich Staub bei (x, y) in Richtung `d`? */
  private connects(x: number, y: number, d: Dir): boolean {
    const n = this.at(x + DX[d], y + DY[d])
    if (!n) return false
    switch (n.kind) {
      case 'dust':
      case 'torch':
      case 'wallTorch':
      case 'source':
        return true
      case 'repeater':
      case 'comparator':
        // Nur an Ein- und Ausgang, nicht seitlich.
        return n.dir === d || n.dir === opposite(d)
      default:
        return false
    }
  }

  private dustMask(x: number, y: number): number {
    let mask = 0
    for (let d = 0 as Dir; d < 4; d = (d + 1) as Dir) if (this.connects(x, y, d)) mask |= 1 << d
    return mask
  }

  /** Signalstärken im Staub neu berechnen (Quellen → Staub, je Block −1). */
  updateDust() {
    const { w, h } = this
    const queue: number[] = []
    for (let i = 0; i < this.cells.length; i++) {
      const c = this.cells[i]!
      if (c.kind !== 'dust') continue
      const x = i % w
      const y = (i / w) | 0
      if (this.cut(x, c)) {
        c.power = 0
        continue
      }
      let base = 0
      for (let d = 0 as Dir; d < 4; d = (d + 1) as Dir) {
        const nx = x + DX[d]
        const ny = y + DY[d]
        const n = this.at(nx, ny)
        if (!n || n.kind === 'dust' || n.kind === 'block') continue
        base = Math.max(base, this.emits(nx, ny, opposite(d)))
      }
      c.power = base
      if (base > 1) queue.push(i)
    }
    // Stärke fällt entlang der Leitung – Breitensuche von den stärksten Punkten.
    queue.sort((a, b) => this.cells[b]!.power - this.cells[a]!.power)
    while (queue.length) {
      const i = queue.shift()!
      const c = this.cells[i]!
      const x = i % w
      const y = (i / w) | 0
      for (let d = 0 as Dir; d < 4; d = (d + 1) as Dir) {
        const nx = x + DX[d]
        const ny = y + DY[d]
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
        const n = this.cells[ny * w + nx]!
        if (n.kind !== 'dust' || this.cut(nx, n)) continue
        if (n.power < c.power - 1) {
          n.power = c.power - 1
          if (n.power > 1) queue.push(ny * w + nx)
        }
      }
    }
  }

  /** Ein Redstone-Tick. Liefert `true`, wenn sich sichtbar etwas geändert hat. */
  step(): boolean {
    const { w } = this
    const before = this.signature()
    // 1. Eingänge aus dem aktuellen Zustand lesen …
    const inputs = new Array<number>(this.cells.length).fill(0)
    for (let i = 0; i < this.cells.length; i++) {
      const c = this.cells[i]!
      const x = i % w
      const y = (i / w) | 0
      if (this.cut(x, c)) continue
      switch (c.kind) {
        case 'repeater':
        case 'comparator':
          inputs[i] = this.incoming(x, y, opposite(c.dir))
          break
        case 'wallTorch':
          inputs[i] = this.blockPowered(x + DX[c.dir], y + DY[c.dir]) ? 1 : 0
          break
        case 'lamp':
          inputs[i] = this.powered(x, y) ? 1 : 0
          break
        case 'piston':
          inputs[i] = this.powered(x, y, c.dir) ? 1 : 0
          break
      }
    }
    // 2. … dann alle gleichzeitig schalten.
    for (let i = 0; i < this.cells.length; i++) {
      const c = this.cells[i]!
      const input = inputs[i]!
      switch (c.kind) {
        case 'repeater': {
          c.hist.push(input > 0 ? 15 : 0)
          c.on = (c.hist.shift() ?? 0) > 0
          break
        }
        case 'comparator': {
          c.hist.push(input)
          c.power = c.hist.shift() ?? 0
          c.on = c.power > 0
          break
        }
        case 'wallTorch':
          c.on = input === 0
          break
        case 'lamp':
          if (input) {
            c.on = true
            c.offTimer = 2
          } else if (c.on && --c.offTimer <= 0) {
            c.on = false
          }
          break
        case 'piston':
          c.on = input > 0
          break
      }
    }
    this.updateDust()
    this.ticks++
    return this.signature() !== before
  }

  /** Kurzer Fingerabdruck des sichtbaren Zustands. */
  private signature(): string {
    let s = ''
    for (const c of this.cells) {
      if (c.kind === 'dust') s += String.fromCharCode(65 + c.power)
      else if (c.kind !== 'floor' && c.kind !== 'block') s += c.on ? '1' : '0'
    }
    return s
  }
}
