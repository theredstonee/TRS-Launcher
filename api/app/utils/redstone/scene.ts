import { Circuit, DX, DY, parseStencil, type Cell, type Dir, type StencilOptions } from './sim'

// Aufbau der Hintergrund-Szene: eine Hauptleitung zur Spielen-Lampe, dazu lange
// Redstone-Kabel quer über die Fläche (wie im TRS-Startbildschirm im Spiel) und
// kleine Schaltungen: Verstärker-Ringe mit kreisendem Impuls, Lauflicht,
// Fackel-Sterne, Inverter, Lampen und Kolben. Alles aus einem Seed – dieselbe
// Fenstergröße ergibt immer dieselbe Szene.
//
// Tempo wie im Spiel: Takt-Fackeln schalten mit eigener Periode (2,4–5,6 s) und
// eigenem Versatz, damit nie alles gleichzeitig blinkt.

export interface SceneSpec {
  cols: number
  rows: number
  /** Zeile der Hauptleitung (auf Höhe des Spielen-Knopfs); `-1` = keine. */
  busRow: number
  /** Spalte, in der die Leitung unter dem Spielen-Knopf endet (exklusiv). */
  busEnd: number
  seed?: number
  /** Ganze Fläche in mehreren Streifen füllen (Seiten-Hintergrund) statt nur eines Streifens. */
  fill?: boolean
}

export interface Module {
  name: string
  rows: string[]
  delays?: number[]
  pulse?: number
}

/** Wo eine Schaltung oder ein Kabel steht – für den langsamen Umbau. */
export interface Placement {
  x: number
  y: number
  w: number
  h: number
  name: string
  /** Kabel: die belegten Felder (Index im Raster). */
  cells?: number[]
}

// `K.` = Takt-Fackel mit eigenem, langsamem Takt.
export const MODULES: Module[] = [
  // Verstärker-Ring: ein Impuls kreist (6 × 4 Ticks ≈ 2,4 s) und lässt die Lampe aufleuchten.
  {
    name: 'lamp-ring',
    rows: ['-- r> R> R> -- -- L.', '-- .. .. .. .. -- ..', '-- -- R< R< R< -- ..'],
    delays: [4, 4, 4, 4, 4, 4],
    pulse: 4,
  },
  {
    name: 'piston-ring',
    rows: ['-- r> R> R> -- -- -- P> ..', '-- .. .. .. .. -- .. .. ..', '-- -- R< R< R< -- .. .. ..'],
    delays: [4, 4, 4, 4, 4, 4],
    pulse: 5,
  },
  {
    name: 'chaser',
    rows: ['K. -- R> -- R> -- R> -- R> --', '.. .. .. L. .. L. .. L. .. L.'],
    delays: [3, 3, 3, 3],
  },
  { name: 'inverter', rows: ['K. -- -- ## I< L.'] },
  { name: 'torch-star', rows: ['.. -- ..', '-- T. --', '.. -- ..'] },
  { name: 'lamp-pair', rows: ['L. -- K. -- L.'] },
  { name: 'lamp-row', rows: ['K. -- -- -- -- --', '.. L. .. L. .. L.'] },
  { name: 'torch-piston', rows: ['K. -- -- R> -- P> ..'], delays: [2] },
]

/** Kleiner, stabiler Zufallsgenerator (Mulberry32). */
export function rng(seed: number) {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export const moduleWidth = (m: Module) => m.rows[0]!.trim().split(/\s+/).length
export const moduleHeight = (m: Module) => m.rows.length

/** Takt wie im Spiel-Startbildschirm: Periode 2,4–5,6 s, Impuls 0,6–1,9 s, zufälliger Versatz. */
function clockOptions(random: () => number): StencilOptions {
  const period = 24 + Math.floor(random() * 33)
  return {
    period,
    clockPulse: Math.min(period - 5, 6 + Math.floor(random() * 14)),
    phase: Math.floor(random() * period),
  }
}

/** Schablone mit eigenem Takt. */
export function stencilFor(m: Module, random: () => number): Cell[][] {
  return parseStencil(m.rows, m.delays, m.pulse, clockOptions(random))
}

/** Eine zufällige Schaltung, die in w × h passt. */
export function pickModule(random: () => number, maxW: number, maxH: number, avoid = ''): Module | null {
  const fitting = MODULES.filter((m) => moduleWidth(m) <= maxW && moduleHeight(m) <= maxH && m.name !== avoid)
  if (!fitting.length) return null
  return fitting[Math.floor(random() * fitting.length)]!
}

/** Schaltungen und Kabel je Szene – für den Umbau im laufenden Betrieb. */
export const placements = new WeakMap<Circuit, Placement[]>()

function remember(circuit: Circuit, p: Placement) {
  const list = placements.get(circuit) ?? []
  list.push(p)
  placements.set(circuit, list)
}

/** Ist das Rechteck (mit einem Feld Rand) noch leer? */
function rectFree(circuit: Circuit, x: number, y: number, w: number, h: number): boolean {
  for (let yy = y - 1; yy <= y + h; yy++)
    for (let xx = x - 1; xx <= x + w; xx++) {
      const c = circuit.at(xx, yy)
      if (c && c.kind !== 'floor') return false
    }
  return true
}

/** Setzt eine Schaltung ein und merkt sich ihren Platz. */
export function placeModule(circuit: Circuit, m: Module, x: number, y: number, random: () => number) {
  circuit.place(x, y, stencilFor(m, random))
  remember(circuit, { x, y, w: moduleWidth(m), h: moduleHeight(m), name: m.name })
}

const DIR_CHAR = ['^', '>', 'v', '<'] as const

/** Ein Feld für ein Kabel: frei, im Raster und ohne fremde Nachbarn (sonst verbinden sich Leitungen). */
function cableFree(circuit: Circuit, taken: Set<number>, x: number, y: number, from: number): boolean {
  if (x < 1 || y < 1 || x >= circuit.w - 1 || y >= circuit.h - 1) return false
  const i = y * circuit.w + x
  if (taken.has(i) || circuit.at(x, y)!.kind !== 'floor') return false
  for (let d = 0; d < 4; d++) {
    const nx = x + DX[d]!
    const ny = y + DY[d]!
    const ni = ny * circuit.w + nx
    if (ni === from) continue
    const n = circuit.at(nx, ny)
    if ((n && n.kind !== 'floor') || taken.has(ni)) return false
  }
  return true
}

/**
 * Plant ein langes Kabel wie im Spiel-Startbildschirm: Takt-Fackel, Staub mit
 * Knicken, alle paar Blöcke ein Verstärker, am Ende eine Lampe oder ein Kolben.
 * Setzt noch nichts – liefert die Felder (für Aufbau und Umbau).
 */
export function planCable(circuit: Circuit, random: () => number): { i: number; cell: Cell }[] | null {
  for (let attempt = 0; attempt < 12; attempt++) {
    const sx = 1 + Math.floor(random() * Math.max(1, circuit.w - 2))
    const sy = 1 + Math.floor(random() * Math.max(1, circuit.h - 2))
    const taken = new Set<number>()
    if (!cableFree(circuit, taken, sx, sy, -1)) continue
    const out: { i: number; cell: Cell }[] = []
    const start = sy * circuit.w + sx
    taken.add(start)
    out.push({ i: start, cell: parseStencil(['K.'], [], 1, clockOptions(random))[0]![0]! })

    let x = sx
    let y = sy
    let dir = Math.floor(random() * 4) as Dir
    let run = 0
    let runTarget = 3 + Math.floor(random() * 6)
    const length = 9 + Math.floor(random() * 24)
    let sinceRepeater = 0
    let nextRepeater = 4 + Math.floor(random() * 5)
    for (let step = 0; step < length; step++) {
      if (run >= runTarget) {
        // Knick nach links oder rechts.
        const turn = ((dir + (random() < 0.5 ? 1 : 3)) % 4) as Dir
        dir = turn
        run = 0
        runTarget = 3 + Math.floor(random() * 7)
      }
      const from = y * circuit.w + x
      const nx = x + DX[dir]
      const ny = y + DY[dir]
      if (!cableFree(circuit, taken, nx, ny, from)) break
      x = nx
      y = ny
      const i = y * circuit.w + x
      taken.add(i)
      run++
      sinceRepeater++
      // Verstärker nur mitten in geraden Stücken.
      const repeater = sinceRepeater >= nextRepeater && run >= 1 && run < runTarget
      const token = repeater ? `R${DIR_CHAR[dir]}` : '--'
      const cell = parseStencil([token], [1 + Math.floor(random() * 4)])[0]![0]!
      out.push({ i, cell })
      if (repeater) {
        sinceRepeater = 0
        nextRepeater = 5 + Math.floor(random() * 5)
      }
    }
    if (out.length < 6) continue
    // Ende: Lampe, oder ein Kolben, wenn davor Platz für den Kopf ist.
    const last = out[out.length - 1]!
    const lx = last.i % circuit.w
    const ly = (last.i / circuit.w) | 0
    const hx = lx + DX[dir]
    const hy = ly + DY[dir]
    const headFree = cableFree(circuit, taken, hx, hy, last.i)
    last.cell =
      random() < 0.3 && headFree
        ? parseStencil([`P${DIR_CHAR[dir]}`])[0]![0]!
        : parseStencil(['L.'])[0]![0]!
    return out
  }
  return null
}

/** Setzt ein geplantes Kabel sofort und merkt es sich. */
function placeCable(circuit: Circuit, cells: { i: number; cell: Cell }[]) {
  for (const { i, cell } of cells) circuit.set(i % circuit.w, (i / circuit.w) | 0, cell)
  remember(circuit, { ...cableBox(circuit, cells.map((c) => c.i)), name: 'cable', cells: cells.map((c) => c.i) })
}

/** Umrisse eines Kabels. */
export function cableBox(circuit: Circuit, cells: number[]) {
  const xs = cells.map((i) => i % circuit.w)
  const ys = cells.map((i) => (i / circuit.w) | 0)
  const x = Math.min(...xs)
  const y = Math.min(...ys)
  return { x, y, w: Math.max(...xs) - x + 1, h: Math.max(...ys) - y + 1 }
}

/** Abstand der Verstärker in der Hauptleitung. */
export const BUS_REPEATER_EVERY = 8

export function buildScene(spec: SceneSpec): Circuit {
  const { cols, rows } = spec
  const random = rng(spec.seed ?? 0x7e5)
  const circuit = new Circuit(Math.max(1, cols), Math.max(1, rows))
  placements.set(circuit, [])
  const busRow = spec.busRow >= 0 && spec.busRow < rows ? spec.busRow : -1
  const busEnd = Math.min(cols, Math.max(0, spec.busEnd))

  // Hauptleitung: Redstone-Block links (von außen geschaltet), Staub mit
  // Verstärkern bis unter den Spielen-Knopf, darunter Lampen als Abzweige.
  if (busRow >= 0 && busEnd > 2) {
    const [[source]] = parseStencil(['S.']) as [[Cell]]
    source.external = true
    source.on = false
    source.bus = true
    circuit.set(0, busRow, source)
    for (let x = 1; x < busEnd; x++) {
      const token = x % BUS_REPEATER_EVERY === 0 && x < busEnd - 1 ? 'R>' : '--'
      const [[c]] = parseStencil([token], [2]) as [[Cell]]
      c.bus = true
      circuit.set(x, busRow, c)
      // Abzweig-Lampen nur in der rechten Hälfte – links steht der Text.
      if (token === '--' && x % 6 === 3 && x >= busEnd / 2 && x < busEnd - 2 && busRow + 1 < rows) {
        const [[lamp]] = parseStencil(['L.']) as [[Cell]]
        lamp.bus = true
        circuit.set(x, busRow + 1, lamp)
      }
    }
  }

  // Kleine Schaltungen – als Seiten-Hintergrund in mehreren Streifen, locker verteilt.
  const lastRow = busRow >= 0 ? busRow - 2 : rows - 1
  if (spec.fill) {
    const step = 6 + Math.floor(random() * 3)
    for (let top = Math.floor(random() * 3); top + MIN_MODULE_ROWS - 1 <= lastRow; top += step) {
      fillBand(circuit, random, top, Math.min(lastRow, top + step - 2), cols)
    }
  } else {
    fillBand(circuit, random, 0, lastRow, cols)
  }

  // Dazwischen die langen Kabel – der größte Teil der Szene.
  const area = cols * Math.max(0, lastRow + 1)
  const cables = Math.min(60, Math.round(area / (spec.fill ? 55 : 80)))
  for (let k = 0; k < cables; k++) {
    // Die Hauptleitung und ihre Nachbarzeilen bleiben frei.
    const planned = planCable(circuit, random)
    if (planned && planned.every(({ i }) => Math.abs(((i / cols) | 0) - busRow) > 1 || busRow < 0)) placeCable(circuit, planned)
  }

  // Ein paar Deepslate-Blöcke als Struktur auf leeren Feldern.
  for (let y = 0; y < rows; y++) {
    for (let cx = 0; cx < cols; cx++) {
      if (y === busRow || y === busRow - 1 || y === busRow + 1) continue
      const c = circuit.at(cx, y)
      if (!c || c.kind !== 'floor' || !isolated(circuit, cx, y)) continue
      if (random() < 0.04) circuit.set(cx, y, parseStencil(['##'])[0]![0]!)
    }
  }

  // Signale laufen sichtbar durch den Staub (etwa 20 Blöcke pro Sekunde).
  circuit.dustSpeed = 2
  return circuit.finish()
}

const MIN_MODULE_ROWS = Math.min(...MODULES.map(moduleHeight))

/** Eine Reihe kleiner Schaltungen mit Luft dazwischen (für die Kabel). */
function fillBand(circuit: Circuit, random: () => number, bandTop: number, bandBottom: number, cols: number) {
  const bandHeight = bandBottom - bandTop + 1
  let x = 1 + Math.floor(random() * 4)
  let last = ''
  let guard = 0
  while (x < cols && guard++ < 64) {
    const m = pickModule(random, cols - x, bandHeight, last)
    if (!m) break
    const y = bandTop + Math.floor(random() * (bandHeight - moduleHeight(m) + 1))
    if (rectFree(circuit, x, y, moduleWidth(m), moduleHeight(m))) {
      placeModule(circuit, m, x, y, random)
      last = m.name
    }
    // Viel Platz lassen – dort laufen die Kabel.
    x += moduleWidth(m) + 7 + Math.floor(random() * 12)
  }
}

/** Keine Schaltung rundherum – dort darf ein Block stehen, ohne etwas zu verbinden. */
function isolated(c: Circuit, x: number, y: number): boolean {
  for (let dy = -1; dy <= 1; dy++) {
    for (let dx = -1; dx <= 1; dx++) {
      const n = c.at(x + dx, y + dy)
      if (n && n.kind !== 'floor' && n.kind !== 'block') return false
    }
  }
  return true
}
