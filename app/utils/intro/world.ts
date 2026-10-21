// Der Dungeon als Blockliste (rein, ohne DOM). Koordinaten: x = Spalte quer zum Gang (0 … width−1, Rückwand bei −1),
// y = −Abstand entlang des Gangs (der Gang läuft nach −y, auf dem Bildschirm nach rechts oben), z = Höhe (Boden-Oberkante 0).

import type { IntroProps } from './designs'
import { hash2 } from './kit'
import { WORLD } from './timeline'

export type Mat =
  | 'tile'
  | 'tileMoss'
  | 'cobble'
  | 'brick'
  | 'brickMoss'
  | 'pillar'
  | 'piston'
  | 'lampOff'
  | 'lampOn'
  | 'door'
  | 'pumpkin'

export interface Block {
  x: number
  y: number
  z: number
  mat: Mat
  /** Texturvariante 0…2. */
  v: number
  /** Oberseite mit Raureif/Schnee. */
  snowy?: boolean
}

export interface WorldLayout {
  blocks: Block[]
  /** Leitung liegt auf diesen Abständen (Spalte `wireCol`). */
  wire: number[]
  /** Lichtquellen an der Wand (Abstand). */
  lights: number[]
  /** Banner an der Rückwand (Abstand). */
  banners: number[]
  /** Fenster in der Rückwand (Abstand der linken Hälfte, 2 breit, z 2…4). */
  windows: number[]
  /** Spinnweben (Abstand, Höhe). */
  cobwebs: { d: number; z: number }[]
}

const WALL_H = 5
const START = -7

export function buildWorld(props: IntroProps): WorldLayout {
  const blocks: Block[] = []
  const v = (a: number, b: number) => Math.floor(hash2(a * 31 + 7, b * 17 + 3) * 3)
  const windows = props.sky === 'cave' ? [] : [...WORLD.windows]
  const isWindow = (d: number, z: number) => z >= 2 && z <= 3 && windows.some((w) => d === w || d === w + 1)
  const isAlcove = (d: number, z: number) => z <= 2 && (d === WORLD.alcove || d === WORLD.alcove + 1)
  const isDoor = (d: number) => (WORLD.doors as readonly number[]).includes(d)
  const snow = !!props.snow

  // Boden: zwei Lagen an der Vorderkante (Diorama-Rand), sonst eine.
  for (let d = START; d <= WORLD.end; d++) {
    for (let c = 0; c < WORLD.width; c++) {
      const h = hash2(c * 13 + 1, d * 7 + 5)
      const mat: Mat = h < 0.16 ? 'cobble' : h > 0.9 ? 'tileMoss' : 'tile'
      blocks.push({ x: c, y: -d, z: -1, mat, v: v(c, d), snowy: snow && h > 0.86 })
      if (c === WORLD.width - 1 || d === START) {
        if (hash2(d, 99) > 0.18) blocks.push({ x: c, y: -d, z: -2, mat: 'brick', v: v(c + 5, d) })
        if (hash2(d, 98) > 0.6) blocks.push({ x: c, y: -d, z: -3, mat: 'brick', v: v(c + 9, d) })
      }
    }
    // Rückwand
    for (let z = 0; z < WALL_H; z++) {
      if (isWindow(d, z) || isAlcove(d, z)) continue
      let mat: Mat = d % 6 === 0 ? 'pillar' : hash2(d * 3, z * 5) > 0.84 ? 'brickMoss' : 'brick'
      if (isDoor(d) && z <= 2) mat = 'piston'
      blocks.push({ x: -1, y: -d, z, mat, v: v(d, z), snowy: snow && z === WALL_H - 1 })
    }
    // Wandfuß/Fundament der Rückwand
    blocks.push({ x: -1, y: -d, z: -1, mat: 'brick', v: v(d, 9) })
  }
  // Nische: Boden, Rückseite
  for (const d of [WORLD.alcove, WORLD.alcove + 1]) {
    for (let z = 0; z < 3; z++) blocks.push({ x: -2, y: -d, z, mat: 'brick', v: v(d, z + 20) })
  }
  // Abschlusswand hinter der Lampe
  for (let c = -1; c < WORLD.width; c++) {
    for (let z = -1; z < WALL_H; z++) blocks.push({ x: c, y: -(WORLD.end + 1), z, mat: z >= 0 && (c === -1 || c === WORLD.width - 1) ? 'pillar' : 'brick', v: v(c, z + 40), snowy: snow && z === WALL_H - 1 })
  }
  // Türrahmen-Säulen am Gangrand (vorne), niedrig – geben Tiefe, verdecken den Gang nicht
  for (const d of WORLD.doors) {
    for (const dd of [d - 1, d + 1]) for (const z of [-1, 0]) blocks.push({ x: WORLD.width, y: -dd, z, mat: 'pillar', v: z + 1 })
  }
  // Kürbislaternen stehen auf dem Boden an der Wand
  if (props.light === 'pumpkin') {
    for (const d of WORLD.torches) blocks.push({ x: 0, y: -d, z: 0, mat: 'pumpkin', v: 0 })
  }

  const wire: number[] = []
  for (let d = 0; d < WORLD.lamp; d++) {
    if ((WORLD.repeaters as readonly number[]).includes(d)) continue
    wire.push(d)
  }
  const banners = props.banners ? WORLD.torches.slice(0, -1).map((d) => d + 3).filter((d) => !isDoor(d) && d % 6 !== 0 && d !== WORLD.alcove && d !== WORLD.alcove + 1) : []
  const cobwebs = props.cobwebs ? [6, 18, 30, 47].map((d, i) => ({ d, z: i % 2 ? 4 : 3 })) : []
  return { blocks, wire, lights: [...WORLD.torches], banners, windows, cobwebs }
}

/** Sortierschlüssel für den Maleralgorithmus (hinten zuerst). */
export const depthKey = (x: number, y: number) => x + y
