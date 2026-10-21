// Pinguin-Kolonie der Winternacht: Choreografie als reine Funktion der Zeit (testbar) plus Pixel-Sprites.
// Die Kolonie watschelt herein, rutscht in einer Reihe auf dem Bauch übers Eis, einer rutscht aus und die
// Nachbarn purzeln, einer rollt einen wachsenden Schneeball, alle hüpfen auf den Schlägen; vor dem Schlag schauen
// alle nach oben, beim Schlag jubeln sie mit erhobenen Flossen.

import { canvas, ctx2d, decay, easeInOut, easeOut, range, type Cv } from './kit'

export interface ColonyCues {
  /** Kolonie watschelt von links herein [von, bis] (null = steht schon da). */
  march: [number, number] | null
  /** Bauchrutscher: Startzeit je Pinguin (die ersten Pinguine rutschen nacheinander herein). */
  slides: number[]
  /** Ein Pinguin rutscht aus, die Nachbarn purzeln hinterher. */
  slip: number | null
  /** Ein Pinguin rollt einen wachsenden Schneeball [von, bis]. */
  snowball: [number, number] | null
  /** Alle hüpfen (auf den Schlägen). */
  hops: number[]
  hush: number
  impact: number
}

export type PenguinPose = 'stand' | 'walk' | 'slide' | 'tumble' | 'hop' | 'look' | 'cheer'

export interface PenguinState {
  id: number
  x: number
  /** Tiefe auf dem Eis (größer = näher, wird später gezeichnet). */
  y: number
  pose: PenguinPose
  frame: 0 | 1
  /** Drehung in Vierteln (Purzeln). */
  rot: number
  /** Sprunghöhe (Kunst-Pixel). */
  lift: number
  baby: boolean
  hat: number
  scarf: number
}

export interface ColonyFrame {
  penguins: PenguinState[]
  ball: { x: number; y: number; r: number; spin: number } | null
}

/** Plätze der acht Großen auf dem Eis (x, Tiefe). */
export const HOMES: readonly [number, number][] = [
  [252, 24], [270, 31], [288, 22], [306, 33], [324, 23], [342, 30], [360, 36], [380, 26],
]
/** Mützen-/Schalfarbe je Pinguin (Index in HAT_COLORS bzw. -1 = keine). */
const LOOKS: readonly [number, number][] = [[0, -1], [-1, 1], [2, 3], [-1, -1], [3, 0], [-1, 0], [1, 2], [0, 4]]
export const HAT_COLORS = ['#d23a3a', '#3aa65a', '#3a7ad2', '#e8c23a', '#f4f4f4']
const SLIPPERS = [2, 3, 4]
const PUSHER = 7
const PARENT = 5

export function colonyAt(c: ColonyCues, t: number): ColonyFrame {
  const out: PenguinState[] = []
  let ball: ColonyFrame['ball'] = null
  const walkFrame = (Math.floor(t * 8) % 2) as 0 | 1
  for (let i = 0; i < HOMES.length; i++) {
    const [hx, hy] = HOMES[i]!
    let x = hx
    let pose: PenguinPose = 'stand'
    let rot = 0
    let visible = true
    const slideAt = c.slides[i]
    if (i === PUSHER && c.snowball) {
      // Schneeball rollen
      const [s0, s1] = c.snowball
      if (t < s0) visible = false
      else {
        const k = easeInOut(range(t, s0, s1))
        x = hx - 150 + 150 * k - 20 * (1 - k)
        const r = 3 + 8 * k
        ball = { x: x + 10 + r, y: hy, r, spin: Math.floor((x + 10) / 3) }
        if (t < s1) pose = 'walk'
      }
    } else if (slideAt !== undefined) {
      if (t < slideAt) visible = false
      else if (t < slideAt + 0.75) {
        x = hx - 160 + 160 * easeOut(range(t, slideAt, slideAt + 0.75))
        pose = 'slide'
      }
    } else if (c.march) {
      const [m0, m1] = c.march
      const start = m0 + (i - c.slides.length) * 0.12
      if (t < start) visible = false
      else if (t < m1) {
        x = hx - 130 + 130 * easeOut(range(t, start, m1))
        pose = 'walk'
      }
    }
    if (!visible) continue
    // Ausrutschen + Purzeln (nur wer schon steht)
    const j = SLIPPERS.indexOf(i)
    if (c.slip !== null && j >= 0 && pose === 'stand') {
      const ts = c.slip + j * 0.12
      if (t >= ts) {
        const k = range(t, ts, ts + 0.55)
        // rollt ein Stück und rappelt sich wieder auf (zurück an den Platz)
        x += 9 * Math.sin(Math.PI * k) * (j + 1) * 0.6
        if (k < 1) {
          pose = 'tumble'
          rot = Math.floor(k * 6)
        }
      }
    }
    let lift = 0
    if (pose === 'stand') {
      for (const hb of c.hops) {
        const d = hb + i * 0.035
        if (t >= d && t < d + 0.32) {
          lift = 7 * Math.sin((Math.PI * (t - d)) / 0.32)
          pose = 'hop'
        }
      }
      if (t >= c.hush && t < c.impact) pose = 'look'
      if (t >= c.impact) {
        pose = 'cheer'
        lift = 9 * decay(t - c.impact - i * 0.03, 5) * (t - c.impact - i * 0.03 >= 0 ? 1 : 0)
        const second = t - c.impact - 0.42 - i * 0.03
        if (second >= 0 && second < 0.32) lift = Math.max(lift, 6 * Math.sin((Math.PI * second) / 0.32))
      }
    }
    const [hat, scarf] = LOOKS[i]!
    out.push({ id: i, x, y: hy, pose, frame: pose === 'walk' ? walkFrame : 0, rot, lift, baby: false, hat, scarf })
  }
  // Baby folgt dem Elternteil (etwas verzögert)
  const parent = out.find((p) => p.id === PARENT)
  if (parent) {
    const lag = parent.pose === 'walk' ? 12 : 0
    const pose: PenguinPose = parent.pose === 'tumble' || parent.pose === 'slide' ? 'stand' : parent.pose
    out.push({ id: 99, x: parent.x - 13 - lag, y: parent.y + 5, pose, frame: parent.frame, rot: 0, lift: parent.lift * 0.6, baby: true, hat: 5, scarf: -1 })
  }
  out.sort((a, b) => a.y - b.y)
  return { penguins: out, ball }
}

// ───────────────────────── Sprites ─────────────────────────

const BASE = {
  K: '#1c2230',
  w: '#f4f4f4',
  e: '#f4f4f4',
  O: '#ffa230',
  W: '#ffffff',
  g: '#8a93a6',
  G: '#c3cad8',
  P: '#ff8fb8',
}

function rows(pose: PenguinPose, frame: 0 | 1, hat: boolean, scarf: boolean): string[] {
  const wingsUp = pose === 'hop' || pose === 'cheer'
  const lookUp = pose === 'look' || pose === 'cheer'
  const top = hat ? ['....W....', '...HHH...', '..HhHhH..'] : ['.........', '.........', '...KKK...']
  const head = lookUp ? ['..KeKeK..', '..KKOKK..', '..KKKKK..'] : ['..KKKKK..', '..KeKeK..', '..KKOKK..']
  if (wingsUp) {
    head[1] = 'K' + head[1]!.slice(1, 8) + 'K'
    head[2] = 'K' + head[2]!.slice(1, 8) + 'K'
  }
  const neck = scarf ? '.KSSSSSK.' : '.KKwwwKK.'
  const body = wingsUp ? ['..KwwwK..', '..KwwwK..'] : ['.KKwwwKK.', '.KKwwwKK.']
  const feet = pose === 'walk' && frame === 1 ? '...O.O...' : '..O...O..'
  return [...top, ...head, scarf && wingsUp ? '.KSSSSSK.' : neck, ...body, '..KwwwK..', '..KwwwK..', feet]
}

const SLIDE = ['.........HHW.', '........KKKK.', '.KKKKKKKKKeK.', 'KKwwwwwwwwKKO', '.KKKKKKKSSKK.', 'OO...........']
const SLIDE_NOHAT = ['.............', '........KKKK.', '.KKKKKKKKKeK.', 'KKwwwwwwwwKKO', '.KKKKKKKSSKK.', 'OO...........']

function babyRows(pose: PenguinPose): string[] {
  const look = pose === 'look' || pose === 'cheer'
  const up = pose === 'hop' || pose === 'cheer'
  return [
    '..PPP..',
    up ? 'g.GGG.g' : '..GGG..',
    look ? '.GeGeG.' : '.GGGGG.',
    look ? '.GGOGG.' : '.GeGeG.',
    look ? '.gwwwg.' : '.GGOGG.',
    up ? '..www..' : '.gwwwg.',
    '..ggg..',
    pose === 'walk' ? '...O.O.' : '..O.O..',
  ]
}

export class PenguinSprites {
  private readonly cache = new Map<string, Cv>()

  private paint(rws: string[], pal: Record<string, string>): Cv {
    const w = Math.max(...rws.map((r) => r.length))
    const c = canvas(w, rws.length)
    const g = ctx2d(c)
    rws.forEach((row, y) => {
      for (let x = 0; x < row.length; x++) {
        const col = pal[row[x]!]
        if (!col) continue
        g.fillStyle = col
        g.fillRect(x, y, 1, 1)
      }
    })
    return c
  }

  get(p: PenguinState): Cv {
    const pose: PenguinPose = p.pose === 'tumble' ? 'slide' : p.pose
    const key = `${p.baby ? 'b' : 'a'}|${pose}|${p.frame}|${p.hat}|${p.scarf}`
    let c = this.cache.get(key)
    if (!c) {
      const hatCol = p.hat >= 0 ? (HAT_COLORS[p.hat] ?? BASE.P) : BASE.K
      const pal: Record<string, string> = {
        ...BASE,
        H: hatCol,
        h: shade(hatCol),
        S: p.scarf >= 0 ? HAT_COLORS[p.scarf]! : BASE.K,
      }
      if (p.baby) c = this.paint(babyRows(pose), pal)
      else if (pose === 'slide') c = this.paint(p.hat >= 0 ? SLIDE : SLIDE_NOHAT, pal)
      else c = this.paint(rows(pose, p.frame, p.hat >= 0, p.scarf >= 0), pal)
      this.cache.set(key, c)
    }
    return c
  }
}

function shade(hexColor: string): string {
  const n = Number.parseInt(hexColor.slice(1), 16)
  const k = 0.7
  return `rgb(${Math.round(((n >> 16) & 255) * k)},${Math.round(((n >> 8) & 255) * k)},${Math.round((n & 255) * k)})`
}
