// Pixel-Sprites des Musikvideos (prozedural, einmal gezeichnet, dann gecacht). 1 Kunst-Pixel = 1 Leinwand-Pixel;
// beim Zeichnen ohne Glättung hochskaliert → scharfe Pixel. Keine fremden Grafiken.
import { layer, sprite, type Cv } from './kit'

export const C = {
  ink: '#07060d', night0: '#0a0916', night1: '#121027', night2: '#1b1838', night3: '#272250', night4: '#363069',
  wallA: '#1b1830', wallB: '#221e3b', wallC: '#2b2649', wallD: '#14112a',
  woodA: '#3b2618', woodB: '#55361f', woodC: '#7a4e2b', woodD: '#94633a',
  stoneA: '#3a3a48', stoneB: '#4d4d5e', stoneC: '#62627a', stoneD: '#24242f',
  red0: '#3a0d09', red1: '#7a140e', red2: '#b31a12', red3: '#e0281e', red4: '#ff5a4d', red5: '#ff8f85',
  lamp: '#ffb347', lamp2: '#ffd27a', lamp3: '#fff1c9',
  cool: '#7aa8ff', cool2: '#bcd2ff',
  purple: '#7c5cff', purple2: '#b9a6ff', purpleD: '#4b2fb8', purpleDD: '#2e1d73',
  skin: '#d9a066', skinD: '#b07a48', skinL: '#e8b98a', hair: '#3b2416', hairD: '#26160d',
  white: '#f2efe6', grey: '#9a98a8', greyD: '#5f5d70', dirt: '#5b3d27', dirtD: '#3f2a1b', grass: '#4d8a3a', grassD: '#3a6b2c',
}

type G = CanvasRenderingContext2D
const R = (g: G, x: number, y: number, w: number, h: number, c: string) => {
  g.fillStyle = c
  g.fillRect(x, y, w, h)
}

export interface PersonLook {
  skin: string
  skinD: string
  shirt: string
  shirtD: string
  pants: string
  shoes: string
  hair: string
  /** Augen (Pupille). */
  eye: string
  /** Gesicht 8×8 aus dem Skin (ersetzt das gezeichnete Gesicht). */
  face?: Cv | null
  cape?: string | null
  headphones?: boolean
}

export const DEV: PersonLook = { skin: C.skin, skinD: C.skinD, shirt: C.red2, shirtD: C.red1, pants: '#2b2f48', shoes: '#16161f', hair: C.hair, eye: '#2a1d14' }
export const FAN: PersonLook = { skin: '#e2b48c', skinD: '#b98a62', shirt: C.purple, shirtD: C.purpleD, pants: '#24204a', shoes: '#141224', hair: '#2a1b4d', eye: '#2a1d4a' }

/** Kopf 8×8 von vorne. */
function headFront(g: G, x: number, y: number, o: PersonLook, eyes: 'open' | 'blink' | 'red' = 'open') {
  if (o.face) {
    g.drawImage(o.face, x, y)
    return
  }
  R(g, x, y, 8, 8, o.skin)
  R(g, x, y, 8, 2, o.hair)
  R(g, x, y + 2, 1, 2, o.hair)
  R(g, x + 7, y + 2, 1, 2, o.hair)
  R(g, x, y + 7, 8, 1, o.skinD)
  if (eyes === 'blink') {
    R(g, x + 1, y + 4, 2, 1, o.skinD)
    R(g, x + 5, y + 4, 2, 1, o.skinD)
  } else {
    R(g, x + 1, y + 4, 2, 1, eyes === 'red' ? '#ff9a9a' : C.white)
    R(g, x + 5, y + 4, 2, 1, eyes === 'red' ? '#ff9a9a' : C.white)
    R(g, x + 2, y + 4, 1, 1, eyes === 'red' ? '#e0281e' : o.eye)
    R(g, x + 5, y + 4, 1, 1, eyes === 'red' ? '#e0281e' : o.eye)
  }
  R(g, x + 3, y + 6, 2, 1, o.skinD)
}

/** Ganze Figur von vorne, 16×32 (Arme: 'down' | 'up' | 'phone' | 'wave'). */
export function personFront(o: PersonLook, arms: 'down' | 'up' | 'phone' | 'wave' = 'down', eyes: 'open' | 'blink' = 'open'): Cv {
  return layer(16, 34, (g) => {
    if (o.cape) {
      R(g, 3, 9, 10, 17, o.cape)
      R(g, 3, 25, 10, 1, shade(o.cape))
    }
    headFront(g, 4, 0, o, eyes)
    if (o.headphones) {
      R(g, 3, 0, 10, 1, '#1b1b22')
      R(g, 2, 2, 2, 4, C.red3)
      R(g, 12, 2, 2, 4, C.red3)
    }
    R(g, 4, 8, 8, 12, o.shirt)
    R(g, 4, 18, 8, 2, o.shirtD)
    R(g, 7, 8, 2, 1, o.shirtD)
    const arm = (x: number, mode: 'down' | 'up' | 'fwd') => {
      if (mode === 'up') {
        R(g, x, 0, 4, 11, o.shirt)
        R(g, x, 0, 4, 3, o.skin)
      } else if (mode === 'fwd') {
        R(g, x, 8, 4, 7, o.shirt)
        R(g, x, 13, 4, 3, o.skin)
      } else {
        R(g, x, 8, 4, 12, o.shirt)
        R(g, x, 17, 4, 3, o.skin)
        R(g, x + (x < 8 ? 0 : 3), 8, 1, 12, o.shirtD)
      }
    }
    arm(0, arms === 'up' || arms === 'wave' ? 'up' : 'down')
    arm(12, arms === 'up' ? 'up' : arms === 'phone' ? 'fwd' : 'down')
    if (arms === 'phone') {
      R(g, 9, 12, 4, 6, '#111018')
      R(g, 10, 13, 2, 4, C.purple2)
    }
    R(g, 4, 20, 4, 12, o.pants)
    R(g, 8, 20, 4, 12, shade(o.pants))
    R(g, 4, 30, 4, 2, o.shoes)
    R(g, 8, 30, 4, 2, o.shoes)
  })
}

/** Oberkörper von hinten (sitzend am Schreibtisch), 18×22: Hinterkopf, Ohren, Nacken, Kapuzenpulli. */
export function personBack(o: PersonLook, headphones = false, soot = false): Cv {
  return layer(18, 22, (g) => {
    const x = 1
    const hair = soot ? '#2a2420' : o.hair
    const hairL = soot ? '#3a302a' : '#4a2e1c'
    // Kopf
    R(g, x + 4, 2, 8, 8, hair)
    R(g, x + 5, 2, 3, 1, hairL)
    R(g, x + 9, 3, 2, 1, hairL)
    R(g, x + 4, 9, 8, 1, C.hairD)
    // Ohren + Nacken
    R(g, x + 3, 5, 1, 2, o.skinD)
    R(g, x + 12, 5, 1, 2, o.skinD)
    R(g, x + 6, 9, 4, 1, o.skin)
    if (soot) {
      for (const [dx, dy] of [[2, 0], [3, -1], [5, -2], [8, -2], [11, -1], [12, 0], [13, 2], [2, 3], [13, 5], [3, 6], [12, 7]] as const) R(g, x + dx, dy + 2, 2, 2, '#2a2420')
      R(g, x + 6, 0, 4, 2, '#1d1915')
    }
    if (headphones) {
      R(g, x + 3, 1, 10, 1, '#1b1b22')
      R(g, x + 4, 0, 8, 1, '#2b2b35')
      R(g, x + 2, 4, 2, 5, C.red3)
      R(g, x + 12, 4, 2, 5, C.red3)
      R(g, x + 2, 4, 1, 5, C.red4)
    }
    // Pulli mit Kapuze
    R(g, x + 3, 10, 10, 12, o.shirt)
    R(g, x, 11, 4, 11, o.shirt)
    R(g, x + 12, 11, 4, 11, o.shirt)
    R(g, x + 4, 10, 8, 3, o.shirtD)
    R(g, x + 5, 13, 6, 1, o.shirtD)
    R(g, x + 7, 14, 2, 8, o.shirtD)
    R(g, x, 11, 1, 11, o.shirtD)
    R(g, x + 15, 11, 1, 11, o.shirtD)
    R(g, x + 1, 11, 2, 1, C.red4)
    R(g, x + 13, 11, 2, 1, C.red4)
  })
}

const shade = (hex: string) => {
  const n = Number.parseInt(hex.slice(1), 16)
  const f = (v: number) => Math.round(v * 0.7).toString(16).padStart(2, '0')
  return `#${f((n >> 16) & 255)}${f((n >> 8) & 255)}${f(n & 255)}`
}

/** Großes Gesicht (24×24, „HD-Pixel“) für Nahaufnahmen. */
export function faceHD(mode: 'open' | 'blink' | 'red' | 'soot' | 'hot'): Cv {
  return layer(24, 24, (g) => {
    const skin = mode === 'hot' ? '#e8806a' : C.skin
    const skinD = mode === 'hot' ? '#c0503e' : C.skinD
    R(g, 2, 3, 20, 20, skin)
    R(g, 2, 3, 2, 20, skinD)
    R(g, 20, 3, 2, 20, skinD)
    R(g, 2, 21, 20, 2, skinD)
    // Haare
    const hair = mode === 'soot' ? '#2a2420' : C.hair
    R(g, 1, 0, 22, 6, hair)
    R(g, 1, 6, 3, 5, hair)
    R(g, 20, 6, 3, 5, hair)
    R(g, 6, 6, 3, 1, hair)
    R(g, 13, 6, 4, 1, hair)
    R(g, 2, 1, 4, 1, C.hairD)
    R(g, 15, 2, 5, 1, C.hairD)
    if (mode === 'soot') {
      for (const [x, y] of [[0, -0], [3, -1], [8, -2], [14, -2], [19, -1], [22, 1], [0, 3], [22, 5]] as const) R(g, x, Math.max(0, y), 3, 3, '#1d1915')
      for (const [x, y] of [[5, 16], [16, 10], [9, 19], [18, 17], [4, 9]] as const) R(g, x, y, 2, 1, '#3a2c22')
    }
    // Augenringe
    R(g, 4, 14, 6, 1, mode === 'red' ? '#9a5a4e' : skinD)
    R(g, 14, 14, 6, 1, mode === 'red' ? '#9a5a4e' : skinD)
    if (mode === 'blink') {
      R(g, 4, 12, 6, 1, C.hairD)
      R(g, 14, 12, 6, 1, C.hairD)
    } else {
      const white = mode === 'red' ? '#ffc2bc' : C.white
      R(g, 4, 10, 6, 4, white)
      R(g, 14, 10, 6, 4, white)
      const iris = mode === 'red' ? C.red3 : '#4a2e1c'
      R(g, 6, 11, 3, 3, iris)
      R(g, 15, 11, 3, 3, iris)
      R(g, 7, 12, 1, 1, '#0d0806')
      R(g, 16, 12, 1, 1, '#0d0806')
      R(g, 6, 11, 1, 1, '#ffffff')
      R(g, 15, 11, 1, 1, '#ffffff')
      if (mode === 'red') {
        for (const [x, y] of [[4, 10], [5, 13], [9, 11], [14, 13], [19, 10], [19, 12], [4, 12]] as const) R(g, x, y, 1, 1, '#e0281e')
      }
      R(g, 4, 9, 6, 1, C.hairD)
      R(g, 14, 9, 6, 1, C.hairD)
    }
    // Nase + Mund
    R(g, 11, 13, 2, 4, skinD)
    R(g, 8, 18, 8, 1, '#6b3a2a')
    R(g, 9, 19, 6, 1, mode === 'soot' ? '#2a1a14' : '#8a4a36')
  })
}

/** Astronaut mit Kopfhörern, 22×30. `visor` = Farbe der Spiegelung. */
export function astronaut(face?: Cv | null): Cv {
  return layer(26, 32, (g) => {
    const W = '#e9e6f2'
    const S = '#b9b4cc'
    const D = '#7d7894'
    // Rucksack
    R(g, 4, 10, 18, 13, D)
    // Helm
    R(g, 7, 1, 12, 11, W)
    R(g, 6, 3, 14, 7, W)
    R(g, 8, 3, 10, 7, '#1c1238')
    R(g, 9, 4, 8, 5, '#3a2470')
    R(g, 10, 4, 3, 2, '#d8c8ff')
    R(g, 14, 7, 3, 1, '#ff5a4d')
    if (face) g.drawImage(face, 1, 1, 6, 6, 12, 4, 4, 4)
    R(g, 6, 9, 14, 2, S)
    // Kopfhörer
    R(g, 7, 0, 12, 1, '#1b1b22')
    R(g, 4, 3, 3, 6, C.red3)
    R(g, 19, 3, 3, 6, C.red3)
    R(g, 4, 3, 1, 6, C.red4)
    // Körper
    R(g, 7, 11, 12, 11, W)
    R(g, 7, 19, 12, 3, S)
    R(g, 10, 13, 6, 4, '#2a2a36')
    R(g, 11, 14, 1, 1, C.red3)
    R(g, 13, 14, 1, 1, '#7aa8ff')
    R(g, 11, 16, 4, 1, C.red4)
    // Arme
    R(g, 2, 11, 5, 4, W)
    R(g, 0, 13, 3, 7, W)
    R(g, 0, 19, 3, 2, S)
    R(g, 19, 11, 5, 4, W)
    R(g, 23, 6, 3, 8, W)
    R(g, 23, 5, 3, 2, S)
    // Beine
    R(g, 8, 22, 4, 7, W)
    R(g, 14, 22, 4, 7, W)
    R(g, 8, 28, 4, 3, D)
    R(g, 14, 28, 4, 3, D)
    R(g, 11, 22, 1, 6, S)
  })
}

/** Redstone-Rakete 34×64 + Teile für den Zusammenbau. */
export interface Rocket {
  full: Cv
  parts: { name: string, sx: number, sy: number, sw: number, sh: number }[]
}

export function rocket(face?: Cv | null): Rocket {
  const full = layer(34, 64, (g) => {
    const block = (x: number, y: number, w: number, h: number) => {
      R(g, x, y, w, h, C.red2)
      for (let yy = y; yy < y + h; yy++) {
        for (let xx = x; xx < x + w; xx++) {
          const v = ((xx * 7 + yy * 13) ^ (xx * yy)) % 9
          if (v === 0) R(g, xx, yy, 1, 1, C.red3)
          else if (v === 4) R(g, xx, yy, 1, 1, C.red1)
          else if (v === 7) R(g, xx, yy, 1, 1, C.red4)
        }
      }
    }
    // Nase (Redstone-Lampe)
    const nose = [[15, 0, 4], [13, 2, 8], [12, 4, 10], [11, 6, 12], [10, 9, 14]] as const
    for (const [x, y, w] of nose) R(g, x, y, w, y === 9 ? 5 : 2, C.lamp)
    R(g, 14, 2, 2, 8, C.lamp3)
    R(g, 12, 6, 2, 6, C.lamp2)
    R(g, 21, 6, 2, 8, '#c7782a')
    // Rumpf
    block(9, 14, 16, 36)
    R(g, 9, 14, 2, 36, C.red1)
    R(g, 23, 14, 2, 36, C.red1)
    R(g, 9, 14, 16, 1, C.stoneB)
    R(g, 9, 31, 16, 1, C.stoneD)
    R(g, 9, 32, 16, 1, C.stoneB)
    // Fenster
    R(g, 12, 17, 10, 10, C.stoneC)
    R(g, 13, 18, 8, 8, '#1a1236')
    if (face) g.drawImage(face, 13, 18, 8, 8)
    else {
      R(g, 14, 19, 6, 6, C.purple)
      R(g, 15, 21, 1, 1, C.white)
      R(g, 18, 21, 1, 1, C.white)
    }
    R(g, 13, 18, 2, 1, 'rgba(255,255,255,0.55)')
    // Lichter
    R(g, 11, 36, 2, 2, C.lamp2)
    R(g, 21, 36, 2, 2, C.lamp2)
    R(g, 16, 40, 2, 2, '#7aa8ff')
    // Flossen
    for (let i = 0; i < 12; i++) {
      R(g, 9 - Math.min(8, Math.floor(i * 0.75)), 40 + i, Math.min(8, Math.floor(i * 0.75)), 1, i % 3 ? C.red1 : C.red2)
      R(g, 25, 40 + i, Math.min(8, Math.floor(i * 0.75)), 1, i % 3 ? C.red1 : C.red2)
    }
    R(g, 1, 50, 8, 2, C.stoneB)
    R(g, 25, 50, 8, 2, C.stoneB)
    // Triebwerk
    R(g, 11, 50, 12, 3, C.stoneB)
    R(g, 12, 53, 10, 5, C.stoneA)
    R(g, 13, 58, 8, 2, C.stoneD)
    R(g, 13, 53, 2, 5, C.stoneC)
  })
  return {
    full,
    parts: [
      { name: 'engine', sx: 11, sy: 50, sw: 12, sh: 10 },
      { name: 'body', sx: 9, sy: 32, sw: 16, sh: 18 },
      { name: 'finL', sx: 0, sy: 40, sw: 9, sh: 12 },
      { name: 'finR', sx: 25, sy: 40, sw: 9, sh: 12 },
      { name: 'cabin', sx: 9, sy: 14, sw: 16, sh: 18 },
      { name: 'nose', sx: 10, sy: 0, sw: 14, sh: 14 },
    ],
  }
}

/** Stein-Block mit leuchtendem „?“ (16×16). `lit` = rot glühend. */
export function qBlock(lit: boolean): Cv {
  return layer(16, 16, (g) => {
    R(g, 0, 0, 16, 16, C.stoneA)
    for (let y = 0; y < 16; y++) {
      for (let x = 0; x < 16; x++) {
        const v = (x * 31 + y * 17 + x * y) % 11
        if (v === 0) R(g, x, y, 1, 1, C.stoneB)
        else if (v === 5) R(g, x, y, 1, 1, C.stoneD)
      }
    }
    R(g, 0, 0, 16, 1, C.stoneC)
    R(g, 0, 0, 1, 16, C.stoneC)
    R(g, 15, 0, 1, 16, C.stoneD)
    R(g, 0, 15, 16, 1, C.stoneD)
    const q = ['.####.', '##..##', '....##', '...##.', '..##..', '..##..', '......', '..##..']
    const col = lit ? C.red4 : C.red2
    q.forEach((row, y) => [...row].forEach((ch, x) => ch === '#' && R(g, 5 + x, 4 + y, 1, 1, col)))
    if (lit) R(g, 6, 4, 4, 1, C.red5)
  })
}

/** Kleine Sprites aus Zeichen-Karten. */
export const MUG = sprite([
  '.wwwww.',
  'wcccccw',
  'wcbbbcwww',
  'wcccccw.w',
  'wcccccwww',
  'wcccccw',
  '.wwwww.',
], { w: '#d8d4cc', c: '#bfb9ae', b: '#3a2416' })

export const PHONE = sprite([
  'kkkkkkk',
  'ksssssk',
  'ksssssk',
  'ksssssk',
  'ksssssk',
  'ksssssk',
  'ksssssk',
  'ksssssk',
  'ksssssk',
  'kkkkkkk',
], { k: '#111018', s: '#1d1b2b' })

export const HEART = sprite([
  '.rr.rr.',
  'rRRrrrr',
  'rRrrrrr',
  'rrrrrrr',
  '.rrrrr.',
  '..rrr..',
  '...r...',
], { r: C.red3, R: C.red5 })

export const STAR = sprite([
  '...y...',
  '...y...',
  '.yyYyy.',
  'yyYYYyy',
  '.yyYyy.',
  '.yy.yy.',
  'y.....y',
], { y: C.lamp, Y: C.lamp3 })

export const POPCORN = sprite([
  '.wyw.w.',
  'wwywwyw',
  'ywwwyww',
  'rwrwrwr',
  'rwrwrwr',
  '.rwrwr.',
  '.rwrwr.',
], { w: '#fff6dc', y: '#ffd27a', r: C.red3 })

/** Ziffern + Buchstaben im 5×7-Pixelraster (für Uhr/Konsole). */
const GLYPH: Record<string, string[]> = {
  0: ['.###.', '#...#', '#..##', '#.#.#', '##..#', '#...#', '.###.'],
  1: ['..#..', '.##..', '..#..', '..#..', '..#..', '..#..', '.###.'],
  2: ['.###.', '#...#', '....#', '...#.', '..#..', '.#...', '#####'],
  3: ['####.', '....#', '....#', '.###.', '....#', '....#', '####.'],
  4: ['...#.', '..##.', '.#.#.', '#..#.', '#####', '...#.', '...#.'],
  5: ['#####', '#....', '####.', '....#', '....#', '#...#', '.###.'],
  6: ['..##.', '.#...', '#....', '####.', '#...#', '#...#', '.###.'],
  7: ['#####', '....#', '...#.', '..#..', '.#...', '.#...', '.#...'],
  8: ['.###.', '#...#', '#...#', '.###.', '#...#', '#...#', '.###.'],
  9: ['.###.', '#...#', '#...#', '.####', '....#', '...#.', '.##..'],
  ':': ['.....', '..#..', '..#..', '.....', '..#..', '..#..', '.....'],
  F: ['#####', '#....', '#....', '####.', '#....', '#....', '#....'],
  R: ['####.', '#...#', '#...#', '####.', '#.#..', '#..#.', '#...#'],
  ' ': ['.....', '.....', '.....', '.....', '.....', '.....', '.....'],
}

/** Text im Pixelraster (nur Zeichen aus GLYPH), in aktueller Füllfarbe. */
export function pixelText(g: G, text: string, x: number, y: number, px = 1, color = '#fff') {
  g.fillStyle = color
  let cx = x
  for (const ch of text) {
    const gl = GLYPH[ch]
    if (gl) {
      gl.forEach((row, yy) => {
        for (let xx = 0; xx < row.length; xx++) if (row[xx] === '#') g.fillRect(cx + xx * px, y + yy * px, px, px)
      })
    }
    cx += 6 * px
  }
}
export const pixelTextWidth = (text: string, px = 1) => text.length * 6 * px - px

/** Gezeichneter Kopf 8×8 (Ersatz, wenn kein Skin geladen ist). */
export function headFace(o: PersonLook): Cv {
  return layer(8, 8, (g) => headFront(g, 0, 0, { ...o, face: null }))
}
