import { Circuit, parseStencil, type Cell, type StencilOptions } from './sim'

// Aufbau der Hintergrund-Szene: eine Hauptleitung zur Spielen-Lampe und
// darüber kleine, in sich geschlossene Schaltungen – Trichter-Uhren schalten
// Lampen, Kolben-Türen, Notenblöcke und Spender, dazu Lampen-Anzeigen,
// TNT und Tageslichtsensoren für die Ereignisse. Alles aus einem Seed –
// dieselbe Fenstergröße ergibt immer dieselbe Szene.
//
// Tempo wie der TRS-Startbildschirm im Spiel: jede Uhr taktet mit eigener
// Periode (2,4–5,6 s) und eigenem Versatz, damit nie alles gleichzeitig blinkt.

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
  /** Anzeige-Muster für `l.`-Lampen. */
  pattern?: number
  /** Nur selten wählen (große Anzeigen). */
  rare?: boolean
}

/** Wo eine Schaltung steht – für den langsamen Umbau. */
export interface Placement {
  x: number
  y: number
  w: number
  h: number
  name: string
}

// `H.` = Trichter-Uhr (Trichter + Komparator), der langsame Takt der Szene.
export const MODULES: Module[] = [
  { name: 'hopper-lamp', rows: ['H. -- -- R> -- L.'], delays: [2] },
  { name: 'hopper-piston', rows: ['H. -- R> -- -- P> ..'], delays: [3] },
  { name: 'inverter', rows: ['H. -- -- ## I< L.'] },
  {
    name: 'chaser',
    rows: ['H. -- R> -- C> -- R> -- R> --', '.. .. .. L. .. L. .. L. .. L.'],
    delays: [3, 3, 3],
  },
  // Lampe schaltet, der Beobachter sieht es und spielt den Notenblock an.
  { name: 'note-observer', rows: ['H. -- L. O> N.', '.. .. .. .. ..'] },
  { name: 'note-pair', rows: ['H. -- -- N.', '.. .. -- N.'] },
  { name: 'dispenser', rows: ['H. -- -- D> .. ..'] },
  // Kolben-Tür: zwei Klebekolben schieben ihre Blöcke in die Öffnung.
  { name: 'piston-door', rows: ['Q> .. .. .. .. Q<', '-- -- -- -- -- --', '.. .. H. .. .. ..'] },
  {
    name: 'ring-clock',
    rows: ['-- r> R> -- -- L.', '-- .. .. .. -- ..', '-- -- R< R< -- ..'],
    delays: [4, 4, 4, 4],
    pulse: 3,
  },
  { name: 'torch-star', rows: ['.. -- ..', '-- T. --', '.. -- ..'] },
  { name: 'sensor-lamps', rows: ['Y. -- L. -- L.'] },
  { name: 'tnt', rows: ['## X. ##', '.. X. ..'] },
  { name: 'lamp-wave', rows: ['l. l. l. l. l. l. l. l.', 'l. l. l. l. l. l. l. l.'], pattern: 0 },
  { name: 'lamp-chase', rows: ['l. l. l. l. l. l. l.'], pattern: 1 },
  {
    name: 'lamp-arrow',
    rows: ['l. l. l. l. l. l.', 'l. l. l. l. l. l.', 'l. l. l. l. l. l.'],
    pattern: 3,
    rare: true,
  },
  {
    name: 'trs-sign',
    rows: [
      'l. l. l. .. l. l. .. .. l. l. l.',
      '.. l. .. .. l. .. l. .. l. .. ..',
      '.. l. .. .. l. l. .. .. l. l. l.',
      '.. l. .. .. l. .. l. .. .. .. l.',
      '.. l. .. .. l. .. l. .. l. l. l.',
    ],
    pattern: 2,
    rare: true,
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

export const moduleWidth = (m: Module) => m.rows[0]!.trim().split(/\s+/).length
export const moduleHeight = (m: Module) => m.rows.length

/** Schablone mit eigenem Takt: Periode 2,4–5,6 s, Impuls 0,6–1,9 s, zufälliger Versatz. */
export function stencilFor(m: Module, random: () => number): Cell[][] {
  const period = 24 + Math.floor(random() * 33)
  const opts: StencilOptions = {
    period,
    clockPulse: Math.min(period - 5, 6 + Math.floor(random() * 14)),
    phase: Math.floor(random() * period),
    pattern: m.pattern,
    pitch: Math.floor(random() * 25),
  }
  return parseStencil(m.rows, m.delays, m.pulse, opts)
}

/** Eine zufällige Schaltung, die in w × h passt (seltene nur manchmal). */
export function pickModule(
  random: () => number,
  maxW: number,
  maxH: number,
  avoid = '',
  used: ReadonlySet<string> = new Set(),
): Module | null {
  // Große Anzeigen (TRS-Schriftzug, Pfeil) höchstens einmal je Szene.
  const fitting = MODULES.filter(
    (m) =>
      moduleWidth(m) <= maxW &&
      moduleHeight(m) <= maxH &&
      m.name !== avoid &&
      (!m.rare || (!used.has(m.name) && random() < 0.35)),
  )
  if (!fitting.length) return null
  return fitting[Math.floor(random() * fitting.length)]!
}

/** Schaltungen je Szene – für den Umbau im laufenden Betrieb. */
export const placements = new WeakMap<Circuit, Placement[]>()

/** Setzt eine Schaltung ein und merkt sich ihren Platz. */
export function placeModule(circuit: Circuit, m: Module, x: number, y: number, random: () => number) {
  circuit.place(x, y, stencilFor(m, random))
  if (m.pattern !== undefined) circuit.notePattern(m.pattern, moduleWidth(m))
  const list = placements.get(circuit) ?? []
  list.push({ x, y, w: moduleWidth(m), h: moduleHeight(m), name: m.name })
  placements.set(circuit, list)
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

  // Oberhalb der Leitung (mit einer freien Zeile Abstand) die kleinen Schaltungen –
  // als Seiten-Hintergrund in mehreren Streifen übereinander.
  const lastRow = busRow >= 0 ? busRow - 2 : rows - 1
  if (spec.fill) {
    const step = 5 + Math.floor(random() * 2)
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

  // Signale laufen sichtbar durch den Staub (etwa 20 Blöcke pro Sekunde).
  circuit.dustSpeed = 2
  return circuit.finish()
}

const MIN_MODULE_ROWS = Math.min(...MODULES.map(moduleHeight))

/** Eine Reihe kleiner Schaltungen nebeneinander zwischen bandTop und bandBottom. */
function fillBand(circuit: Circuit, random: () => number, bandTop: number, bandBottom: number, cols: number) {
  const bandHeight = bandBottom - bandTop + 1
  let x = 1 + Math.floor(random() * 3)
  let last = ''
  let guard = 0
  while (x < cols && guard++ < 64) {
    const used = new Set((placements.get(circuit) ?? []).map((p) => p.name))
    const m = pickModule(random, cols - x, bandHeight, last, used)
    if (!m) break
    const y = bandTop + Math.floor(random() * (bandHeight - moduleHeight(m) + 1))
    placeModule(circuit, m, x, y, random)
    last = m.name
    x += moduleWidth(m) + 1 + Math.floor(random() * 3)
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
