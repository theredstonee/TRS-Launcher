import type { Palette } from './palette'
import { DX, DY, type Cell, type Circuit, type Dir } from './sim'

// Zeichnet eine Schaltung von oben, 16 × 16 Texel je Block wie die
// Minecraft-Texturen. Gezeichnet wird in Texel-Auflösung; das Hochskalieren
// (pixelig) übernimmt CSS. Einzelteile werden als kleine Sprites gecacht, der
// Boden liegt als fertige Ebene vor – ein Bild pro Tick kostet so fast nichts.

export const TEX = 16

type Ctx = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D
type Surface = HTMLCanvasElement | OffscreenCanvas

/**
 * Manche WebViews (ältere WebKitGTK) kennen `OffscreenCanvas`, liefern aber
 * keinen 2D-Kontext dafür – dann ein normales, unsichtbares Canvas nehmen.
 */
let offscreen2d: boolean | undefined
function offscreenWorks(): boolean {
  if (offscreen2d === undefined) {
    try {
      offscreen2d = typeof OffscreenCanvas !== 'undefined' && !!new OffscreenCanvas(1, 1).getContext('2d')
    } catch {
      offscreen2d = false
    }
  }
  return offscreen2d
}

function surface(w: number, h: number): Surface {
  if (offscreenWorks()) return new OffscreenCanvas(w, h)
  const c = document.createElement('canvas')
  c.width = w
  c.height = h
  return c
}

function ctx2d(s: Surface): Ctx {
  return s.getContext('2d') as Ctx
}

/** Stabiles Rauschen je Texel. */
export function noise(x: number, y: number, seed = 0): number {
  let h = (x * 374761393 + y * 668265263 + seed * 2246822519) | 0
  h = Math.imul(h ^ (h >>> 13), 1274126177)
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296
}

/** Deepslate-Fliesen: 2 × 2 Fliesen je Block, Fugen, leichte Körnung. */
export function paintFloorBlock(g: Ctx, ox: number, oy: number, bx: number, by: number, p: Palette) {
  for (let y = 0; y < TEX; y++) {
    for (let x = 0; x < TEX; x++) {
      const gx = bx * TEX + x
      const gy = by * TEX + y
      const groutLine = x % 8 === 7 || y % 8 === 7
      let color: string
      if (groutLine) color = p.grout
      else {
        // Jede Fliese hat einen eigenen Grundton, darauf Körnung.
        const tile = noise((gx >> 3) + 11, gy >> 3, 3)
        const n = noise(gx, gy, 7)
        const shade = tile < 0.34 ? 0 : tile < 0.8 ? 1 : 2
        color = n < 0.12 ? p.floor[(shade + 1) % 3]! : n > 0.94 ? p.grout : p.floor[shade]!
      }
      g.fillStyle = color
      g.fillRect(ox + x, oy + y, 1, 1)
    }
  }
}

/** Deepslate-Ziegel als erhöhter Block. */
function paintBlock(g: Ctx, ox: number, oy: number, bx: number, by: number, p: Palette) {
  for (let y = 0; y < TEX; y++) {
    const row = y >> 2
    const shift = row % 2 === 0 ? 0 : 4
    for (let x = 0; x < TEX; x++) {
      const mortar = y % 4 === 3 || (x + shift) % 8 === 7
      const n = noise(bx * TEX + x, by * TEX + y, 19)
      g.fillStyle = mortar ? p.blockEdge : n < 0.2 ? p.block[1] : n > 0.85 ? p.block[2] : p.block[0]
      g.fillRect(ox + x, oy + y, 1, 1)
    }
  }
  g.fillStyle = p.blockHi
  g.fillRect(ox, oy, TEX, 1)
  g.fillRect(ox, oy, 1, TEX)
  g.fillStyle = p.blockEdge
  g.fillRect(ox, oy + TEX - 1, TEX, 1)
  g.fillRect(ox + TEX - 1, oy, 1, TEX)
}

/** Boden-Ebene einer ganzen Schaltung – einmal je Aufbau/Theme. Blöcke kommen als Sprites
 *  darüber, damit der Umbau sie jederzeit setzen und entfernen kann. */
export function paintFloor(circuit: Circuit, p: Palette): Surface {
  const s = surface(circuit.w * TEX, circuit.h * TEX)
  const g = ctx2d(s)
  for (let by = 0; by < circuit.h; by++) {
    for (let bx = 0; bx < circuit.w; bx++) paintFloorBlock(g, bx * TEX, by * TEX, bx, by, p)
  }
  return s
}

/** Koordinaten entlang einer Richtung: `a` von hinten (0) nach vorn (15), `c` quer dazu. */
function along(dir: Dir, a: number, c: number): [number, number] {
  switch (dir) {
    case 1:
      return [a, c]
    case 3:
      return [15 - a, 15 - c]
    case 2:
      return [15 - c, a]
    default:
      return [c, 15 - a]
  }
}

function rectAlong(g: Ctx, dir: Dir, a0: number, c0: number, la: number, lc: number) {
  for (let a = a0; a < a0 + la; a++) {
    for (let c = c0; c < c0 + lc; c++) {
      const [x, y] = along(dir, a, c)
      g.fillRect(x, y, 1, 1)
    }
  }
}

export class Sprites {
  private cache = new Map<string, Surface>()
  constructor(private p: Palette) {}

  private get(key: string, draw: (g: Ctx) => void): Surface {
    let s = this.cache.get(key)
    if (!s) {
      s = surface(TEX, TEX)
      draw(ctx2d(s))
      this.cache.set(key, s)
    }
    return s
  }

  /** Staub: Arme zu den verbundenen Seiten, gekörnt; ohne Verbindung ein Punkt. */
  dust(mask: number, power: number): Surface {
    return this.get(`d${mask}:${power}`, (g) => {
      const p = this.p
      let m = mask
      // Eine einzelne Verbindung wird wie in Minecraft zur durchgehenden Linie.
      for (let d = 0; d < 4; d++) if (mask === 1 << d) m |= 1 << ((d + 2) % 4)
      const on = (x: number, y: number) => {
        const n = noise(x, y, 31 + power)
        g.fillStyle = n < 0.2 ? p.dustHi[power]! : n > 0.9 ? p.dust[Math.max(0, power - 4)]! : p.dust[power]!
        g.fillRect(x, y, 1, 1)
      }
      const inCenter = (x: number, y: number) => x >= 6 && x <= 9 && y >= 6 && y <= 9
      for (let y = 0; y < TEX; y++) {
        for (let x = 0; x < TEX; x++) {
          const vertical = x >= 7 && x <= 8
          const horizontal = y >= 7 && y <= 8
          let lit = false
          if (m === 0) lit = x >= 5 && x <= 10 && y >= 5 && y <= 10 && !((x === 5 || x === 10) && (y === 5 || y === 10))
          else {
            // Ecken und Abzweige bekommen einen Knoten, gerade Linien nicht.
            lit = inCenter(x, y) && m !== 0b0101 && m !== 0b1010
            if (vertical && y < 8 && m & 1) lit = true
            if (vertical && y > 7 && m & 4) lit = true
            if (horizontal && x > 7 && m & 2) lit = true
            if (horizontal && x < 8 && m & 8) lit = true
          }
          if (lit) on(x, y)
        }
      }
    })
  }

  private slab(g: Ctx) {
    const p = this.p
    for (let y = 0; y < TEX; y++) {
      for (let x = 0; x < TEX; x++) {
        const edge = x === 0 || y === 0 || x === 15 || y === 15
        g.fillStyle = edge ? p.slabEdge : noise(x, y, 5) < 0.15 ? p.slabEdge : p.slab
        g.fillRect(x, y, 1, 1)
      }
    }
  }

  private torchHead(g: Ctx, x: number, y: number, on: boolean) {
    const p = this.p
    g.fillStyle = on ? p.torchOn : p.torchOff
    g.fillRect(x - 1, y - 1, 3, 3)
    if (on) {
      g.fillStyle = p.torchCore
      g.fillRect(x, y, 1, 1)
    }
  }

  repeater(dir: Dir, delay: number, on: boolean): Surface {
    return this.get(`r${dir}:${delay}:${on ? 1 : 0}`, (g) => {
      this.slab(g)
      g.fillStyle = on ? this.p.dust[15]! : this.p.dust[0]!
      rectAlong(g, dir, 2, 7, 12, 2)
      // Vordere Fackel fest, hintere wandert mit der Verzögerung.
      const [fx, fy] = along(dir, 12, 7)
      const [bx, by] = along(dir, 2 + (delay - 1) * 2, 7)
      this.torchHead(g, fx, fy, on)
      this.torchHead(g, bx, by, on)
    })
  }

  comparator(dir: Dir, on: boolean): Surface {
    return this.get(`c${dir}:${on ? 1 : 0}`, (g) => {
      this.slab(g)
      g.fillStyle = on ? this.p.dust[15]! : this.p.dust[0]!
      rectAlong(g, dir, 3, 3, 1, 10)
      rectAlong(g, dir, 3, 7, 9, 2)
      const [ax, ay] = along(dir, 3, 3)
      const [bx, by] = along(dir, 3, 12)
      const [fx, fy] = along(dir, 12, 7)
      this.torchHead(g, ax, ay, on)
      this.torchHead(g, bx, by, on)
      this.torchHead(g, fx, fy, on)
    })
  }

  torch(on: boolean, flicker: boolean): Surface {
    return this.get(`t${on ? 1 : 0}${flicker ? 'f' : ''}`, (g) => {
      const p = this.p
      g.fillStyle = p.slabEdge
      g.fillRect(6, 6, 4, 4)
      g.fillStyle = on ? (flicker ? p.dust[11]! : p.torchOn) : p.torchOff
      g.fillRect(6, 6, 4, 4)
      g.fillRect(7, 5, 2, 6)
      g.fillRect(5, 7, 6, 2)
      if (on) {
        g.fillStyle = p.torchCore
        g.fillRect(7, 7, 2, 2)
      }
    })
  }

  /** Wandfackel: Stiel zum Block, Kopf etwas davor. */
  wallTorch(dir: Dir, on: boolean, flicker: boolean): Surface {
    return this.get(`w${dir}:${on ? 1 : 0}${flicker ? 'f' : ''}`, (g) => {
      const p = this.p
      g.fillStyle = p.pistonHead
      rectAlong(g, ((dir + 2) % 4) as Dir, 0, 7, 6, 2)
      g.fillStyle = on ? (flicker ? p.dust[11]! : p.torchOn) : p.torchOff
      rectAlong(g, ((dir + 2) % 4) as Dir, 5, 6, 4, 4)
      if (on) {
        g.fillStyle = p.torchCore
        rectAlong(g, ((dir + 2) % 4) as Dir, 6, 7, 2, 2)
      }
    })
  }

  /** Redstone-Lampe: Rahmen, Gitter aus Glasfeldern; an = Bernstein mit hellem Kern. */
  lamp(on: boolean): Surface {
    return this.get(`l${on ? 1 : 0}`, (g) => {
      const p = this.p
      for (let y = 0; y < TEX; y++) {
        for (let x = 0; x < TEX; x++) {
          const frame = x === 0 || y === 0 || x === 15 || y === 15
          const grid = x === 5 || x === 10 || y === 5 || y === 10
          const n = noise(x, y, on ? 41 : 43)
          let c: string
          if (frame) c = on ? p.lampOnLine : p.lampOffLine
          else if (grid) c = on ? p.lampOnLine : p.lampOffLine
          else if (on) c = n < 0.3 ? p.lampCore : p.lampOn
          else c = n < 0.18 ? p.lampOffLine : p.lampOff
          g.fillStyle = c
          g.fillRect(x, y, 1, 1)
        }
      }
    })
  }

  source(on: boolean): Surface {
    return this.get(`s${on ? 1 : 0}`, (g) => {
      const p = this.p
      for (let y = 0; y < TEX; y++) {
        for (let x = 0; x < TEX; x++) {
          const edge = x === 0 || y === 0 || x === 15 || y === 15
          const n = noise(x, y, 51)
          g.fillStyle = edge ? p.dust[on ? 9 : 0]! : n < 0.25 ? p.dustHi[on ? 15 : 0]! : p.dust[on ? 13 : 0]!
          g.fillRect(x, y, 1, 1)
        }
      }
    })
  }

  /** Kolbenkörper; ausgefahren ohne Kopf (der sitzt im Feld davor). */
  piston(dir: Dir, extended: boolean): Surface {
    return this.get(`p${dir}:${extended ? 1 : 0}`, (g) => {
      const p = this.p
      for (let a = 0; a < TEX; a++) {
        for (let c = 0; c < TEX; c++) {
          const [x, y] = along(dir, a, c)
          const edge = c === 0 || c === 15 || a === 0
          const head = a >= 12
          let color = edge ? p.pistonEdge : noise(x, y, 61) < 0.2 ? p.pistonEdge : p.pistonBody
          if (head && !extended) color = a === 12 ? p.pistonEdge : p.pistonHead
          if (head && extended) color = c >= 6 && c <= 9 ? p.pistonArm : a === 12 || edge ? p.pistonEdge : p.pistonBody
          g.fillStyle = color
          g.fillRect(x, y, 1, 1)
        }
      }
    })
  }

  /** Ausgefahrener Kolbenkopf im Feld davor (Klebekolben mit grünem Schleim). */
  pistonHead(dir: Dir, sticky = false): Surface {
    return this.get(`h${dir}${sticky ? 's' : ''}`, (g) => {
      const p = this.p
      g.fillStyle = p.pistonArm
      rectAlong(g, dir, 0, 6, 12, 4)
      g.fillStyle = p.pistonHead
      rectAlong(g, dir, 12, 0, 4, 16)
      if (sticky) {
        g.fillStyle = SLIME
        rectAlong(g, dir, 13, 3, 3, 10)
      }
      g.fillStyle = p.pistonEdge
      rectAlong(g, dir, 12, 0, 1, 16)
    })
  }

  /** Klebekolben: Körper mit grünem Kopf. */
  stickyPiston(dir: Dir, extended: boolean): Surface {
    return this.get(`q${dir}:${extended ? 1 : 0}`, (g) => {
      g.drawImage(this.piston(dir, extended), 0, 0)
      if (!extended) {
        g.fillStyle = SLIME
        rectAlong(g, dir, 13, 3, 3, 10)
      }
    })
  }

  /** Deepslate-Ziegel als erhöhter Block (vier Varianten). */
  block(variant: number): Surface {
    return this.get(`b${variant}`, (g) => paintBlock(g, 0, 0, variant * 3 + 1, variant * 5 + 2, this.p))
  }

  /** Trichter von oben mit Komparator-Glühen: die Füllung zeigt den Takt. */
  hopper(on: boolean, fill: number): Surface {
    return this.get(`o${on ? 1 : 0}:${fill}`, (g) => {
      for (let y = 0; y < TEX; y++) {
        for (let x = 0; x < TEX; x++) {
          const rim = x <= 1 || y <= 1 || x >= 14 || y >= 14
          const inner = x >= 4 && x <= 11 && y >= 4 && y <= 11
          const n = noise(x, y, 71)
          g.fillStyle = rim ? (n < 0.3 ? HOPPER[2]! : HOPPER[1]!) : inner ? HOPPER[3]! : HOPPER[0]!
          g.fillRect(x, y, 1, 1)
        }
      }
      // Items im Trichter: je voller, desto mehr Pixel.
      const items = Math.round(fill * 12)
      for (let k = 0; k < items; k++) {
        g.fillStyle = ITEMS[k % ITEMS.length]!
        g.fillRect(5 + ((k * 3) % 6), 5 + ((k * 5) % 6), 2, 1)
      }
      g.fillStyle = on ? this.p.dust[15]! : this.p.dust[0]!
      g.fillRect(7, 14, 2, 2)
    })
  }

  /** Beobachter: „Gesicht“ zur beobachteten Seite, roter Punkt am Ausgang. */
  observer(dir: Dir, on: boolean): Surface {
    return this.get(`v${dir}:${on ? 1 : 0}`, (g) => {
      for (let a = 0; a < TEX; a++) {
        for (let c = 0; c < TEX; c++) {
          const [x, y] = along(dir, a, c)
          const edge = c === 0 || c === 15 || a === 0 || a === 15
          g.fillStyle = edge ? STONE[2]! : noise(x, y, 81) < 0.2 ? STONE[1]! : STONE[0]!
          g.fillRect(x, y, 1, 1)
        }
      }
      g.fillStyle = STONE[3]!
      rectAlong(g, dir, 1, 3, 3, 3)
      rectAlong(g, dir, 1, 10, 3, 3)
      g.fillStyle = on ? this.p.dust[15]! : this.p.torchOff
      rectAlong(g, dir, 12, 6, 3, 4)
    })
  }

  /** Notenblock: Holz mit Notensymbol; beim Klingen hell. */
  note(on: boolean): Surface {
    return this.get(`n${on ? 1 : 0}`, (g) => {
      for (let y = 0; y < TEX; y++) {
        for (let x = 0; x < TEX; x++) {
          const edge = x === 0 || y === 0 || x === 15 || y === 15
          const grain = (y + (x >> 2)) % 5 === 0
          g.fillStyle = edge ? WOOD[2]! : grain ? WOOD[1]! : WOOD[0]!
          g.fillRect(x, y, 1, 1)
        }
      }
      g.fillStyle = on ? '#fff3c4' : WOOD[3]!
      g.fillRect(9, 3, 1, 8)
      g.fillRect(10, 3, 2, 1)
      g.fillRect(11, 4, 1, 2)
      g.fillRect(6, 10, 4, 3)
    })
  }

  /** Spender: Stein mit Auswurföffnung. */
  dispenser(dir: Dir, on: boolean): Surface {
    return this.get(`x${dir}:${on ? 1 : 0}`, (g) => {
      for (let y = 0; y < TEX; y++) {
        for (let x = 0; x < TEX; x++) {
          const edge = x === 0 || y === 0 || x === 15 || y === 15
          g.fillStyle = edge ? STONE[2]! : noise(x, y, 91) < 0.25 ? STONE[1]! : STONE[0]!
          g.fillRect(x, y, 1, 1)
        }
      }
      g.fillStyle = on ? this.p.dust[12]! : STONE[3]!
      rectAlong(g, dir, 11, 5, 5, 6)
      g.fillStyle = '#0d0d10'
      rectAlong(g, dir, 12, 6, 3, 4)
    })
  }

  /** TNT von oben: rot-weißes Band; gezündet blinkt es weiß. */
  tnt(flash: boolean): Surface {
    return this.get(`k${flash ? 1 : 0}`, (g) => {
      for (let y = 0; y < TEX; y++) {
        for (let x = 0; x < TEX; x++) {
          const band = y >= 6 && y <= 9
          const edge = x === 0 || y === 0 || x === 15 || y === 15
          let c = edge ? '#7a1712' : noise(x, y, 97) < 0.2 ? '#b8261b' : '#d8382a'
          if (band) c = (x + y) % 5 === 0 ? '#9a9a9a' : '#e8e4dc'
          if (flash) c = band ? '#ffffff' : '#fff0e6'
          g.fillStyle = c
          g.fillRect(x, y, 1, 1)
        }
      }
      g.fillStyle = flash ? '#ff5a2a' : '#3a3a3a'
      g.fillRect(7, 7, 2, 2)
    })
  }

  /** Tageslichtsensor: Holzplatte mit Glasfeldern; nachts bläulich (liefert dann Strom). */
  sensor(on: boolean): Surface {
    return this.get(`y${on ? 1 : 0}`, (g) => {
      for (let y = 0; y < TEX; y++) {
        for (let x = 0; x < TEX; x++) {
          const edge = x === 0 || y === 0 || x === 15 || y === 15
          const glass = x >= 3 && x <= 12 && y >= 3 && y <= 12 && (x - 3) % 3 !== 2 && (y - 3) % 3 !== 2
          let c = edge ? WOOD[2]! : WOOD[1]!
          if (glass) c = on ? (noise(x, y, 99) < 0.3 ? '#7fa8ff' : '#4d6fd6') : noise(x, y, 99) < 0.3 ? '#e9e6d6' : '#c9c3a5'
          g.fillStyle = c
          g.fillRect(x, y, 1, 1)
        }
      }
    })
  }
}

const SLIME = '#6fbf4a'
const HOPPER = ['#4a4a52', '#3a3a41', '#5a5a64', '#26262c']
const STONE = ['#76767e', '#65656c', '#55555c', '#3f3f46']
const WOOD = ['#6b4a2e', '#5a3d25', '#46301d', '#2e1f12']
const ITEMS = ['#e2c24a', '#5fd3e8', '#3fcf6e', '#c87cf0', '#e8e4dc']
/** Farben für Items, die Spender auswerfen. */
export const ITEM_COLORS = ITEMS

/** Notenfarben wie im Spiel: Farbton je Tonhöhe. */
export function noteColor(pitch: number): string {
  const hue = Math.round((pitch / 24) * 330)
  return `hsl(${hue} 90% 60%)`
}


export interface Particle {
  x: number
  y: number
  vx: number
  vy: number
  life: number
  max: number
  power: number
  /** Eigene Farbe (Noten, Items, Funken, Bruchstücke) statt Staubfarbe. */
  color?: string
  /** Note (kleines Symbol) oder 2×2-Item; sonst ein Pixel. */
  shape?: 'note' | 'item'
  /** Schwerkraft je Tick (Items fallen, Noten steigen). */
  gravity?: number
}

/** Ein Bild der Schaltung: Boden-Ebene, dann alle Teile, dann Funken. */
export function paintFrame(
  g: CanvasRenderingContext2D,
  floor: Surface,
  circuit: Circuit,
  sprites: Sprites,
  p: Palette,
  particles: Particle[],
  flicker: (i: number) => boolean,
) {
  g.drawImage(floor, 0, 0)
  const { w } = circuit
  for (let i = 0; i < circuit.cells.length; i++) {
    const c = circuit.cells[i]!
    if (c.kind === 'floor') continue
    const bx = i % w
    const by = (i / w) | 0
    const x = bx * TEX
    const y = by * TEX
    if (c.kind === 'block') {
      g.drawImage(sprites.block((bx * 7 + by * 3) & 3), x, y)
      continue
    }
    const s = spriteFor(c, sprites, flicker(i), circuit.ticks)
    if (s) g.drawImage(s, x, y)
    if (c.kind === 'piston') {
      const hx = x + DX[c.dir] * TEX
      const hy = y + DY[c.dir] * TEX
      if (c.on) g.drawImage(sprites.pistonHead(c.dir, c.sticky), hx, hy)
      // Der Klebekolben trägt einen Block: eingefahren direkt davor, ausgefahren ein Feld weiter.
      if (c.sticky) {
        const k = c.on ? 2 : 1
        g.drawImage(sprites.block(2), x + DX[c.dir] * TEX * k, y + DY[c.dir] * TEX * k)
      }
    }
  }
  for (const part of particles) {
    const fade = part.life / part.max
    g.globalAlpha = Math.min(1, fade * 1.6)
    g.fillStyle = part.color ?? (fade > 0.5 ? p.dustHi[part.power]! : p.dust[part.power]!)
    const px = Math.round(part.x)
    const py = Math.round(part.y)
    if (part.shape === 'note') {
      g.fillRect(px + 2, py, 1, 4)
      g.fillRect(px + 3, py, 1, 1)
      g.fillRect(px, py + 3, 3, 2)
    } else if (part.shape === 'item') {
      g.fillRect(px, py, 2, 2)
    } else {
      g.fillRect(px, py, 1, 1)
    }
  }
  g.globalAlpha = 1
  // Nacht (Tageslichtsensor-Ereignis): alles etwas dunkler, die Lampen strahlen.
  if (circuit.night) {
    g.fillStyle = 'rgb(8 10 30 / 0.35)'
    g.fillRect(0, 0, g.canvas.width, g.canvas.height)
  }
}

function spriteFor(c: Cell, s: Sprites, flicker: boolean, tick: number): Surface | null {
  switch (c.kind) {
    case 'dust':
      return s.dust(c.mask, c.power)
    case 'repeater':
      return s.repeater(c.dir, c.delay, c.on)
    case 'comparator':
      return s.comparator(c.dir, c.on)
    case 'torch':
      return s.torch(c.on, flicker)
    case 'wallTorch':
      return s.wallTorch(c.dir, c.on, flicker)
    case 'lamp':
      return s.lamp(c.on)
    case 'source':
      return s.source(c.on)
    case 'piston':
      return c.sticky ? s.stickyPiston(c.dir, c.on) : s.piston(c.dir, c.on)
    case 'hopper': {
      // Füllstand in 4 Stufen: läuft über die Periode voll und leert sich im Impuls.
      const t = (tick + c.phase) % Math.max(1, c.period)
      const fill = c.on ? 1 - t / Math.max(1, c.pulse) : (t - c.pulse) / Math.max(1, c.period - c.pulse)
      return s.hopper(c.on, Math.max(0, Math.min(4, Math.round(fill * 4))) / 4)
    }
    case 'observer':
      return s.observer(c.dir, c.on)
    case 'note':
      return s.note(c.on)
    case 'dispenser':
      return s.dispenser(c.dir, c.on)
    case 'tnt':
      return s.tnt(c.on)
    case 'sensor':
      return s.sensor(c.on)
    default:
      return null
  }
}

/**
 * Weiches Licht: eine sehr grobe Ebene (4 Pixel je Block), hier auf der
 * kleinen Fläche weichgezeichnet und von CSS nur noch glatt hochskaliert –
 * leuchtende Lampen, Fackeln und geladener Staub strahlen so auf den Boden ab.
 * (Ein CSS-Blur auf der großen Ebene würde bei jedem Bild die GPU fordern.)
 */
let glowScratch: Surface | null = null
export function paintGlow(target: CanvasRenderingContext2D, circuit: Circuit, p: Palette) {
  const { w } = circuit
  const W = target.canvas.width
  const H = target.canvas.height
  if (!glowScratch || glowScratch.width !== W || glowScratch.height !== H) glowScratch = surface(W, H)
  const g = ctx2d(glowScratch)
  g.clearRect(0, 0, W, H)
  const glow = `${p.glow[0] | 0} ${p.glow[1] | 0} ${p.glow[2] | 0}`
  const lamp = `${p.lampGlow[0] | 0} ${p.lampGlow[1] | 0} ${p.lampGlow[2] | 0}`
  for (let i = 0; i < circuit.cells.length; i++) {
    const c = circuit.cells[i]!
    const x = (i % w) * 4
    const y = ((i / w) | 0) * 4
    if (c.kind === 'dust' && c.power > 0) {
      // Die Hauptleitung strahlt kräftiger – sie zeigt den Spielstart.
      const strength = (c.bus ? 0.3 : 0.12) + (c.power / 15) * (c.bus ? 0.55 : 0.4)
      g.fillStyle = `rgb(${glow} / ${strength.toFixed(3)})`
      if (c.bus) g.fillRect(x, y + 1, 4, 2)
      else g.fillRect(x + 1, y + 1, 2, 2)
    } else if (c.kind === 'lamp' && c.on) {
      // Anzeige-Lampen stehen dicht an dicht – schwächer, sonst überstrahlen sie alles.
      const display = c.pattern >= 0
      g.fillStyle = `rgb(${lamp} / ${display ? 0.22 : 0.55})`
      g.fillRect(x - 2, y - 2, 8, 8)
      g.fillStyle = `rgb(${lamp} / ${display ? 0.5 : 0.95})`
      g.fillRect(x, y, 4, 4)
    } else if ((c.kind === 'torch' || c.kind === 'wallTorch' || c.kind === 'source') && c.on) {
      g.fillStyle = `rgb(${glow} / 0.7)`
      g.fillRect(x + 1, y + 1, 2, 2)
    } else if ((c.kind === 'repeater' || c.kind === 'comparator' || c.kind === 'observer' || c.kind === 'hopper') && c.on) {
      g.fillStyle = `rgb(${glow} / 0.4)`
      g.fillRect(x + 1, y + 1, 2, 2)
    } else if (c.kind === 'tnt' && c.on) {
      g.fillStyle = 'rgb(255 244 230 / 0.85)'
      g.fillRect(x - 2, y - 2, 8, 8)
    } else if (c.kind === 'sensor' && c.on) {
      g.fillStyle = 'rgb(120 160 255 / 0.5)'
      g.fillRect(x, y, 4, 4)
    }
  }
  target.clearRect(0, 0, W, H)
  target.filter = 'blur(2px)'
  target.drawImage(glowScratch, 0, 0)
  target.filter = 'none'
}
