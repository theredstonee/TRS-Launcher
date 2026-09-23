// Kleine Redstone-Simulation für die Hintergrund-Szenen des Launchers.
//
// Kein exakter Nachbau von Minecraft, aber nah genug, dass die Schaltungen
// „richtig“ aussehen: Staub verliert pro Block eine Stufe Signalstärke (und das
// Signal läuft sichtbar die Leitung entlang), Verstärker frischen das Signal nach
// 1–4 Redstone-Ticks wieder auf 15 auf, Fackeln an Blöcken invertieren, Lampen
// gehen verzögert aus, Kolben fahren aus, Trichter-Uhren takten langsam,
// Beobachter geben kurze Impulse, Notenblöcke und Spender melden Ereignisse.
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
  | 'hopper'
  | 'observer'
  | 'note'
  | 'dispenser'
  | 'tnt'
  | 'sensor'

export interface Cell {
  kind: CellKind
  /** Verstärker/Komparator/Kolben/Beobachter/Spender: Ausgangsseite. Wandfackel: Seite des Blocks, an dem sie hängt. */
  dir: Dir
  /** Verzögerung eines Verstärkers in Redstone-Ticks (1–4). */
  delay: number
  /** Ausgang an (Verstärker, Komparator, Fackel, Quelle, Uhr), Lampe leuchtet, Kolben ausgefahren. */
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
  /** Trichter-Uhr: Periode und Impulslänge in Ticks, Versatz. */
  period: number
  pulse: number
  phase: number
  /** Klebekolben: schiebt einen Block vor sich her. */
  sticky: boolean
  /** Anzeige-Lampe: Muster (-1 = normale Lampe), Spalte/Zeile im Muster. */
  pattern: number
  col: number
  row: number
  /** Letzter Eingang (Beobachter: beobachteter Zustand, Notenblock/Spender: Strom). */
  last: number
  /** TNT: Ticks, die es noch „gezündet“ blinkt. */
  primed: number
  /** Notenblock: Tonhöhe 0–24. */
  pitch: number
}

/** Was eine Szene sichtbar machen soll, ohne dass es den Schaltzustand ändert. */
export interface CircuitEvent {
  type: 'note' | 'item' | 'spark'
  x: number
  y: number
  dir?: Dir
  /** Notenblock: Tonhöhe 0–24 (Farbe der Note). */
  pitch?: number
}

function cell(kind: CellKind, dir: Dir = 1): Cell {
  return {
    kind,
    dir,
    delay: 1,
    on: false,
    power: 0,
    hist: [],
    offTimer: 0,
    bus: false,
    external: false,
    mask: 0,
    period: 0,
    pulse: 0,
    phase: 0,
    sticky: false,
    pattern: -1,
    col: 0,
    row: 0,
    last: 0,
    primed: 0,
    pitch: 0,
  }
}

export function floorCell(): Cell {
  return cell('floor')
}

const DIR_CHARS: Record<string, Dir> = { '^': 0, '>': 1, v: 2, '<': 3 }

export interface StencilOptions {
  /** Trichter-Uhren: Periode und Impulslänge (Ticks) und Versatz. */
  period?: number
  clockPulse?: number
  phase?: number
  /** Anzeige-Lampen: Muster-Nummer (siehe `displayOn`). */
  pattern?: number
  /** Notenblöcke: Grundton. */
  pitch?: number
}

/**
 * Liest eine Schaltung aus einer Schablone: je Zelle zwei Zeichen, getrennt
 * durch Leerzeichen.
 *
 * `..` Boden · `##` Block · `--` Staub · `R>` Verstärker (Richtung `^ > v <`),
 * `r>` Verstärker mit anfänglichem Impuls · `C>` Komparator · `T.` Fackel ·
 * `I<` Wandfackel am Block in Richtung · `L.` Lampe · `l.` Anzeige-Lampe ·
 * `P>` Kolben · `Q>` Klebekolben mit Block · `S.` Redstone-Block ·
 * `H.` Trichter-Uhr · `O>` Beobachter (Ausgang in Richtung, schaut nach hinten) ·
 * `N.` Notenblock · `D>` Spender · `X.` TNT · `Y.` Tageslichtsensor.
 *
 * `delays` gilt der Reihe nach (zeilenweise) für alle Verstärker.
 */
export function parseStencil(rows: string[], delays: number[] = [], pulse = 1, opts: StencilOptions = {}): Cell[][] {
  let next = 0
  let notes = 0
  const grid = rows.map((row) => row.trim().split(/\s+/))
  return grid.map((tokens, y) =>
    tokens.map((token, x) => {
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
        case 'C':
          return cell('comparator', dir)
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
        case 'l': {
          const c = cell('lamp')
          c.pattern = opts.pattern ?? 0
          c.col = x
          c.row = y
          return c
        }
        case 'P':
          return cell('piston', dir)
        case 'Q': {
          const c = cell('piston', dir)
          c.sticky = true
          return c
        }
        case 'S': {
          const c = cell('source')
          c.on = true
          return c
        }
        case 'H': {
          const c = cell('hopper')
          c.period = Math.max(4, opts.period ?? 32)
          c.pulse = Math.min(c.period - 2, Math.max(2, opts.clockPulse ?? 8))
          c.phase = opts.phase ?? 0
          return c
        }
        case 'O':
          return cell('observer', dir)
        case 'N': {
          const c = cell('note')
          c.last = 0
          c.pitch = ((opts.pitch ?? 6) + notes++ * 4) % 25
          return c
        }
        case 'D':
          return cell('dispenser', dir)
        case 'X':
          return cell('tnt')
        case 'Y':
          return cell('sensor')
        default:
          return cell('floor')
      }
    }),
  )
}

/**
 * Anzeige-Lampen: an oder aus je nach Muster, Tick und Position.
 * 0 = Welle, 1 = Lauflicht, 2 = Schriftzug (blinkt kurz), 3 = Pfeil (läuft).
 */
export function displayOn(pattern: number, tick: number, col: number, row: number, cols: number): boolean {
  switch (pattern) {
    case 0: {
      // Ein Leuchtband wandert langsam über die Spalten.
      const band = Math.floor(tick / 5) % (cols + 6)
      return col >= band - 3 && col <= band
    }
    case 1:
      return col === Math.floor(tick / 4) % cols
    case 2:
      // Steht, geht alle 6 s kurz aus und wieder an.
      return tick % 60 >= 4
    case 3: {
      const head = Math.floor(tick / 3) % (cols + 4)
      return col <= head && col >= head - 2 - Math.abs(row - 1)
    }
    default:
      return false
  }
}

/** Schaltung auf einem Raster; `step()` = ein Redstone-Tick (0,1 s). */
export class Circuit {
  readonly cells: Cell[]
  /** Hauptleitung: nur bis zu dieser Spalte leitet sie (Fortschritt beim Start). */
  busLimit = Number.POSITIVE_INFINITY
  /** Blöcke, die ein Signal je Tick durch Staub läuft (∞ = sofort, wie im Spiel). */
  dustSpeed = Number.POSITIVE_INFINITY
  /** Tageslichtsensoren liefern Strom („Nacht“-Ereignis). */
  night = false
  /** Kettenreaktion: Spalte der Lichtwelle (-1 = keine). */
  flashCol = -1
  /** Ereignisse seit dem letzten Abholen (Noten, Items, Funken). */
  events: CircuitEvent[] = []
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
    this.relink()
    this.updateDust(true)
    return this
  }

  /** Staub-Verbindungen neu berechnen (nach Umbauten). */
  relink() {
    for (let y = 0; y < this.h; y++) {
      for (let x = 0; x < this.w; x++) {
        const c = this.at(x, y)!
        if (c.kind === 'dust') c.mask = this.dustMask(x, y)
      }
    }
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
      case 'hopper':
      case 'sensor':
        return 15
      case 'wallTorch':
        return d === c.dir ? 0 : 15
      case 'repeater':
      case 'observer':
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
      if ((n.kind === 'source' || n.kind === 'hopper' || n.kind === 'sensor') && n.on) return true
      if ((n.kind === 'repeater' || n.kind === 'comparator' || n.kind === 'observer') && this.emits(nx, ny, opposite(d)) > 0)
        return true
    }
    return false
  }

  /** Lampen, Kolben, Notenblöcke, Spender: Strom von irgendeiner Seite (Kolben nicht von vorn). */
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
      case 'hopper':
      case 'sensor':
        return true
      case 'repeater':
      case 'comparator':
        // Nur an Ein- und Ausgang, nicht seitlich.
        return n.dir === d || n.dir === opposite(d)
      case 'observer':
        // Nur am Ausgang.
        return n.dir === opposite(d)
      default:
        return false
    }
  }

  private dustMask(x: number, y: number): number {
    let mask = 0
    for (let d = 0 as Dir; d < 4; d = (d + 1) as Dir) if (this.connects(x, y, d)) mask |= 1 << d
    return mask
  }

  /** Grundstärke eines Staubfelds aus seinen Nicht-Staub-Nachbarn. */
  private dustBase(x: number, y: number): number {
    let base = 0
    for (let d = 0 as Dir; d < 4; d = (d + 1) as Dir) {
      const nx = x + DX[d]
      const ny = y + DY[d]
      const n = this.at(nx, ny)
      if (!n || n.kind === 'dust' || n.kind === 'block') continue
      base = Math.max(base, this.emits(nx, ny, opposite(d)))
    }
    return base
  }

  /**
   * Signalstärken im Staub neu berechnen. Mit endlicher `dustSpeed` läuft das
   * Signal je Tick nur ein paar Blöcke weit (und verglimmt ebenso), sonst sofort.
   */
  updateDust(instant = false) {
    const { w, h } = this
    if (!instant && Number.isFinite(this.dustSpeed)) {
      const steps = Math.max(1, Math.round(this.dustSpeed))
      const next = new Array<number>(this.cells.length)
      for (let s = 0; s < steps; s++) {
        for (let i = 0; i < this.cells.length; i++) {
          const c = this.cells[i]!
          if (c.kind !== 'dust') continue
          const x = i % w
          const y = (i / w) | 0
          if (this.cut(x, c)) {
            next[i] = 0
            continue
          }
          let p = this.dustBase(x, y)
          for (let d = 0 as Dir; d < 4; d = (d + 1) as Dir) {
            const nx = x + DX[d]
            const ny = y + DY[d]
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
            const n = this.cells[ny * w + nx]!
            if (n.kind === 'dust' && !this.cut(nx, n)) p = Math.max(p, n.power - 1)
          }
          next[i] = p
        }
        for (let i = 0; i < this.cells.length; i++) if (this.cells[i]!.kind === 'dust') this.cells[i]!.power = next[i]!
      }
      return
    }
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
      c.power = this.dustBase(x, y)
      if (c.power > 1) queue.push(i)
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

  /** Sichtbarer Zustand einer Zelle für Beobachter. */
  private stateOf(x: number, y: number): number {
    const c = this.at(x, y)
    if (!c) return 0
    if (c.kind === 'dust') return c.power > 0 ? 1 : 0
    return c.on ? 1 : 0
  }

  /** TNT an (x, y) zünden (blinkt und sprüht Funken, explodiert aber nie). */
  prime(x: number, y: number, ticks = 40) {
    const c = this.at(x, y)
    if (c?.kind === 'tnt') c.primed = ticks
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
        case 'note':
          inputs[i] = this.powered(x, y) ? 1 : 0
          break
        case 'piston':
        case 'dispenser':
          inputs[i] = this.powered(x, y, c.dir) ? 1 : 0
          break
        case 'observer':
          // Schaut nach hinten (entgegen der Ausgangsseite).
          inputs[i] = this.stateOf(x - DX[c.dir], y - DY[c.dir])
          break
      }
    }
    // 2. … dann alle gleichzeitig schalten.
    for (let i = 0; i < this.cells.length; i++) {
      const c = this.cells[i]!
      const input = inputs[i]!
      const x = i % w
      const y = (i / w) | 0
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
        case 'lamp': {
          const flash = this.flashCol >= 0 && x <= this.flashCol && x >= this.flashCol - 4
          const shown = c.pattern >= 0 ? displayOn(c.pattern, this.ticks, c.col, c.row, 1 + this.patternWidth(i)) : false
          if (input || shown || flash) {
            c.on = true
            c.offTimer = 2
          } else if (c.on && --c.offTimer <= 0) {
            c.on = false
          }
          break
        }
        case 'piston':
          c.on = input > 0
          break
        case 'hopper':
          // Trichter-Uhr: langsamer, gleichmäßiger Takt mit eigenem Versatz.
          c.on = (this.ticks + c.phase) % c.period < c.pulse
          break
        case 'observer':
          c.on = input !== c.last
          c.last = input
          break
        case 'note':
          if (input && !c.last) this.events.push({ type: 'note', x, y, pitch: c.pitch })
          c.on = input > 0
          c.last = input
          break
        case 'dispenser':
          if (input && !c.last) this.events.push({ type: 'item', x, y, dir: c.dir })
          c.on = input > 0
          c.last = input
          break
        case 'tnt':
          if (c.primed > 0) {
            c.primed--
            c.on = c.primed % 6 < 3
            if (c.primed % 2 === 0) this.events.push({ type: 'spark', x, y })
          } else c.on = false
          break
        case 'sensor':
          c.on = this.night
          break
      }
    }
    if (this.flashCol >= 0) {
      this.flashCol += 3
      if (this.flashCol > w + 6) this.flashCol = -1
    }
    this.updateDust()
    this.ticks++
    return this.signature() !== before
  }

  /** Breite einer Anzeige (größte Spalte) – für Muster, die über die Breite laufen. */
  private patternCols = new Map<number, number>()
  private patternWidth(i: number): number {
    const c = this.cells[i]!
    return this.patternCols.get(c.pattern) ?? c.col
  }

  /** Nach dem Einsetzen einer Anzeige: ihre Breite merken. */
  notePattern(pattern: number, cols: number) {
    this.patternCols.set(pattern, Math.max(this.patternCols.get(pattern) ?? 0, cols - 1))
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
