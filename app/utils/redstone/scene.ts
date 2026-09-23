import { Circuit, parseStencil, type Cell } from './sim'

// Aufbau der Hintergrund-Szene: eine Hauptleitung zur Spielen-Lampe und
// darüber eine Reihe kleiner, in sich geschlossener Schaltungen (Takte,
// Lauflicht, Kolben, Inverter). Alles aus einem Seed – dieselbe Fenstergröße
// ergibt immer dieselbe Szene.

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

interface Module {
  name: string
  rows: string[]
  delays: number[]
  pulse?: number
}

// Takt = Ring aus Staub mit zwei Verstärkern, in dem ein Impuls kreist.
// Rechts daneben hängt, was der Takt schaltet.
const MODULES: Module[] = [
  {
    name: 'lamp-clock',
    rows: [
      '-- r> -- -- L.',
      '-- .. .. -- ..',
      '-- -- R< -- ..',
    ],
    delays: [4, 4],
    pulse: 2,
  },
  {
    name: 'piston-clock',
    rows: [
      '-- r> -- -- -- P> ..',
      '-- .. .. .. -- .. ..',
      '-- -- -- R< -- .. ..',
    ],
    delays: [4, 4],
    pulse: 3,
  },
  {
    name: 'inverter',
    rows: [
      '-- r> -- -- ## I< L.',
      '-- .. .. -- .. .. ..',
      '-- -- R< -- .. .. ..',
    ],
    delays: [3, 4],
    pulse: 2,
  },
  {
    name: 'chaser',
    rows: [
      '-- r> -- -- R> -- C> -- R> -- R> --',
      '-- .. .. -- .. L. .. L. .. L. .. L.',
      '-- -- R< -- .. .. .. .. .. .. .. ..',
    ],
    delays: [4, 1, 2, 2, 4],
    pulse: 2,
  },
  {
    name: 'torch-star',
    rows: [
      '.. -- ..',
      '-- T. --',
      '.. -- ..',
    ],
    delays: [],
  },
  {
    name: 'lamp-pair',
    rows: [
      'L. -- r> -- -- ..',
      '.. -- .. .. -- ..',
      '.. -- .. .. -- ..',
      '.. -- -- R< -- L.',
    ],
    delays: [4, 3],
    pulse: 2,
  },
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

function stencil(m: Module): Cell[][] {
  return parseStencil(m.rows, m.delays, m.pulse)
}

/** Abstand der Verstärker in der Hauptleitung. */
export const BUS_REPEATER_EVERY = 8

export function buildScene(spec: SceneSpec): Circuit {
  const { cols, rows } = spec
  const random = rng(spec.seed ?? 0x7e5)
  const circuit = new Circuit(Math.max(1, cols), Math.max(1, rows))
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

  // Oberhalb der Leitung (mit einer freien Zeile Abstand) die kleinen Schaltungen –
  // als Seiten-Hintergrund in mehreren Streifen übereinander.
  const lastRow = busRow >= 0 ? busRow - 2 : rows - 1
  if (spec.fill) {
    const step = MAX_MODULE_ROWS + 2 + Math.floor(random() * 2)
    for (let top = Math.floor(random() * 2); top + MIN_MODULE_ROWS - 1 <= lastRow; top += step) {
      fillBand(circuit, random, top, Math.min(lastRow, top + step - 2), cols)
    }
  } else {
    fillBand(circuit, random, 0, lastRow, cols)
  }

  // Ein paar Deepslate-Blöcke als Struktur auf leeren Feldern.
  for (let y = 0; y < rows; y++) {
    for (let cx = 0; cx < cols; cx++) {
      if (y === busRow || y === busRow - 1 || y === busRow + 1) continue
      const c = circuit.at(cx, y)
      if (!c || c.kind !== 'floor' || !isolated(circuit, cx, y)) continue
      if (random() < 0.05) circuit.set(cx, y, parseStencil(['##'])[0]![0]!)
    }
  }

  return circuit.finish()
}

const MAX_MODULE_ROWS = Math.max(...MODULES.map((m) => m.rows.length))
const MIN_MODULE_ROWS = Math.min(...MODULES.map((m) => m.rows.length))

/** Eine Reihe kleiner Schaltungen nebeneinander zwischen bandTop und bandBottom. */
function fillBand(circuit: Circuit, random: () => number, bandTop: number, bandBottom: number, cols: number) {
  const bandHeight = bandBottom - bandTop + 1
  let x = 1 + Math.floor(random() * 3)
  let last = ''
  let guard = 0
  while (x < cols && guard++ < 64) {
    const fitting = MODULES.filter((m) => m.rows.length <= bandHeight && m.name !== last)
    if (!fitting.length) break
    const m = fitting[Math.floor(random() * fitting.length)]!
    const cells = stencil(m)
    const w = cells[0]?.length ?? 0
    if (x + w > cols) {
      // Rechts passt vielleicht noch etwas Kleineres.
      const small = fitting.filter((f) => stencil(f)[0]!.length <= cols - x)
      if (!small.length) break
      const s = small[Math.floor(random() * small.length)]!
      const sc = stencil(s)
      const y = bandTop + Math.floor(random() * (bandHeight - sc.length + 1))
      circuit.place(x, y, sc)
      break
    }
    const y = bandTop + Math.floor(random() * (bandHeight - cells.length + 1))
    circuit.place(x, y, cells)
    last = m.name
    x += w + 1 + Math.floor(random() * 2)
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
