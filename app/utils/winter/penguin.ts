import type { Rgb } from '../redstone/palette'
import { CABINS } from './landscape'

// Der Weihnachts-Pinguin, der in der Hütte wohnt. Meist schaut er aus dem
// erleuchteten Fenster; alle ein bis zwei Minuten öffnet er die Tür, watschelt
// ein paar Schritte hinaus, winkt und geht wieder hinein. Am Tag ist er länger
// draußen. Bei reduzierter Bewegung steht er ruhig neben der Tür.
// Nur der Ablauf (Takte à 80 ms) – gemalt wird in paint.ts; testbar ohne Canvas.

export type PenguinPhase = 'window' | 'opening' | 'walkOut' | 'wave' | 'linger' | 'walkIn' | 'closing' | 'still'

export interface PenguinView {
  phase: PenguinPhase
  /** Linke Spalte der Figur relativ zur Hütte (draußen) bzw. im Fenster. */
  x: number
  /** Im Fenster: um wie viele Zeilen er gerade abtaucht (0 = ganz zu sehen). */
  dip: number
  /** Tür: 0 zu, 1 halb, 2 offen. */
  door: 0 | 1 | 2
  /** Schritt (Watscheln) 0/1. */
  step: 0 | 1
  /** Winkt gerade – Flosse oben (1) oder halb (0), sonst `null`. */
  wave: 0 | 1 | null
}

export const PENGUIN_W = 7
export const PENGUIN_H = 11

// Figur von vorn: Mütze mit Bommel (nach rechts hängend), Kopf, Schnabel, weißer Bauch.
// . leer, W Fell/Bommel, R Mütze, r Mützenschatten, K Gefieder, e Auge, O Schnabel/Füße, w Bauch
const BODY = [
  '...RRW.',
  '..RRR..',
  '.RRRRr.',
  '.WWWWW.',
  '.KKKKK.',
  '.KeKeK.',
  '.KKOKK.',
  'KKwwwKK',
  'KKwwwKK',
  '.KwwwK.',
]
const FEET = ['.OO.OO.', 'OO...OO']

export const PENGUIN_COLORS: Record<string, Rgb> = {
  W: [255, 255, 255],
  R: [216, 38, 58],
  r: [165, 26, 44],
  K: [27, 31, 43],
  e: [255, 255, 255],
  O: [255, 163, 26],
  w: [244, 248, 255],
}

/** Pixel der Figur: [x, y, Farbcode] (ohne leere). */
export function penguinPixels(v: Pick<PenguinView, 'step' | 'wave'>): [number, number, string][] {
  const rows = [...BODY, FEET[v.step]!]
  const out: [number, number, string][] = []
  rows.forEach((row, y) => {
    for (let x = 0; x < row.length; x++) {
      const c = row[x]!
      if (c !== '.') out.push([x, y, c])
    }
  })
  if (v.wave !== null) {
    // Rechte Flosse hoch: unten weg, oben neben dem Kopf.
    const keep = out.filter(([x, y]) => !(x === 6 && (y === 7 || y === 8)))
    const up = v.wave === 1 ? [4, 5] : [5, 6]
    for (const y of up) keep.push([6, y, 'K'])
    return keep
  }
  return out
}

/** Winzige Figur (3 × 5) für Banner. */
export const TINY_PENGUIN = ['RW.', 'KKK', 'KOK', 'KwK', 'O.O']

const SPEC = CABINS.large
/** Draußen: in der Tür (Start) und vor dem Fenster (Ziel). */
export const DOOR_X = SPEC.door.x0
export const TARGET_X = SPEC.windows[0]!.x0 + 3
/** Im Fenster: zwei Plätze hinter den Scheiben. */
const WINDOW_SPOTS = [SPEC.windows[0]!.x0, SPEC.windows[0]!.x1 - PENGUIN_W + 1]

const OPEN_TICKS = 6
const STEP_TICKS = 3
const WAVE_TICKS = 32

export class Penguin {
  phase: PenguinPhase = 'window'
  x = WINDOW_SPOTS[0]!
  private t = 0
  private next: number
  private spot = 0
  private dip = 0
  private stepFrame: 0 | 1 = 0

  constructor(
    private readonly rand: () => number,
    private readonly options: { day: boolean; still: boolean },
  ) {
    // Der erste Ausflug kommt bald, damit man ihn überhaupt kennenlernt.
    this.next = 250 + Math.floor(rand() * 200)
    if (options.still) this.makeStill()
  }

  private makeStill() {
    this.phase = 'still'
    this.x = SPEC.door.x1 + 2
  }

  /** Bewegung reduziert (an) bzw. wieder erlaubt (aus). */
  setStill(still: boolean) {
    this.options.still = still
    if (still) this.makeStill()
    else if (this.phase === 'still') this.toWindow()
  }

  setDay(day: boolean) {
    this.options.day = day
  }

  private toWindow() {
    this.phase = 'window'
    this.x = WINDOW_SPOTS[this.spot]!
    this.t = 0
    // Nachts alle 60–120 s, am Tag alle 30–60 s.
    const base = this.options.day ? 375 : 750
    this.next = base + Math.floor(this.rand() * base)
  }

  /** Sofort hinausgehen (Tastenkürzel, Entwicklung). */
  trigger() {
    if (this.phase === 'window') this.next = 0
  }

  step() {
    if (this.phase === 'still') return
    this.t++
    switch (this.phase) {
      case 'window':
        // Ab und zu taucht er kurz ab oder wechselt die Scheibe.
        if (this.t % 40 === 0) {
          const r = this.rand()
          if (r < 0.25) this.dip = 1
          else if (r < 0.4) {
            this.spot = 1 - this.spot
            this.x = WINDOW_SPOTS[this.spot]!
          }
        } else if (this.t % 40 === 6) this.dip = 0
        if (this.t >= this.next) this.go('opening')
        break
      case 'opening':
        if (this.t >= OPEN_TICKS) {
          this.x = DOOR_X
          this.go('walkOut')
        }
        break
      case 'walkOut':
        if (this.t % STEP_TICKS === 0) {
          this.x--
          this.stepFrame = this.stepFrame ? 0 : 1
        }
        if (this.x <= TARGET_X) this.go('wave')
        break
      case 'wave':
        if (this.t >= WAVE_TICKS) this.go('linger')
        break
      case 'linger':
        if (this.t >= (this.options.day ? 90 : 14)) this.go('walkIn')
        break
      case 'walkIn':
        if (this.t % STEP_TICKS === 0) {
          this.x++
          this.stepFrame = this.stepFrame ? 0 : 1
        }
        if (this.x >= DOOR_X) this.go('closing')
        break
      case 'closing':
        if (this.t >= OPEN_TICKS) this.toWindow()
        break
    }
  }

  private go(phase: PenguinPhase) {
    this.phase = phase
    this.t = 0
    this.stepFrame = 0
  }

  view(): PenguinView {
    const p = this.phase
    let door: PenguinView['door'] = 0
    if (p === 'opening') door = this.t < OPEN_TICKS / 2 ? 1 : 2
    else if (p === 'closing') door = this.t < OPEN_TICKS / 2 ? 2 : 1
    else if (p === 'walkOut' || p === 'wave' || p === 'linger' || p === 'walkIn') door = 2
    const wave = p === 'wave' ? (((this.t >> 2) & 1) as 0 | 1) : null
    return { phase: p, x: this.x, dip: p === 'window' ? this.dip : 0, door, step: this.stepFrame, wave }
  }
}
