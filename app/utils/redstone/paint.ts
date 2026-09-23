import type { Palette } from './palette'
import { DX, DY, type Cell, type Circuit, type Dir } from './sim'

// Zeichnet eine Schaltung von oben, 16 × 16 Texel je Block wie die
// Minecraft-Texturen. Gezeichnet wird in Texel-Auflösung; das Hochskalieren
// (pixelig) übernimmt CSS. Einzelteile werden als kleine Sprites gecacht, der
// Boden liegt als fertige Ebene vor – ein Bild pro Tick kostet so fast nichts.

export const TEX = 16

type Ctx = CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D
type Surface = HTMLCanvasElement | OffscreenCanvas

function surface(w: number, h: number): Surface {
  if (typeof OffscreenCanvas !== 'undefined') return new OffscreenCanvas(w, h)
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

/** Boden-Ebene einer ganzen Schaltung (inkl. Blöcken) – einmal je Aufbau/Theme. */
export function paintFloor(circuit: Circuit, p: Palette): Surface {
  const s = surface(circuit.w * TEX, circuit.h * TEX)
  const g = ctx2d(s)
  for (let by = 0; by < circuit.h; by++) {
    for (let bx = 0; bx < circuit.w; bx++) {
      const c = circuit.at(bx, by)!
      if (c.kind === 'block') paintBlock(g, bx * TEX, by * TEX, bx, by, p)
      else paintFloorBlock(g, bx * TEX, by * TEX, bx, by, p)
    }
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

  /** Ausgefahrener Kolbenkopf im Feld davor. */
  pistonHead(dir: Dir): Surface {
    return this.get(`h${dir}`, (g) => {
      const p = this.p
      g.fillStyle = p.pistonArm
      rectAlong(g, dir, 0, 6, 12, 4)
      g.fillStyle = p.pistonHead
      rectAlong(g, dir, 12, 0, 4, 16)
      g.fillStyle = p.pistonEdge
      rectAlong(g, dir, 12, 0, 1, 16)
    })
  }
}


export interface Particle {
  x: number
  y: number
  vx: number
  vy: number
  life: number
  max: number
  power: number
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
    if (c.kind === 'floor' || c.kind === 'block') continue
    const x = (i % w) * TEX
    const y = ((i / w) | 0) * TEX
    const s = spriteFor(c, sprites, flicker(i))
    if (s) g.drawImage(s, x, y)
    if (c.kind === 'piston' && c.on) {
      const hx = x + DX[c.dir] * TEX
      const hy = y + DY[c.dir] * TEX
      g.drawImage(sprites.pistonHead(c.dir), hx, hy)
    }
  }
  for (const part of particles) {
    const fade = part.life / part.max
    g.globalAlpha = Math.min(1, fade * 1.6)
    g.fillStyle = fade > 0.5 ? p.dustHi[part.power]! : p.dust[part.power]!
    g.fillRect(Math.round(part.x), Math.round(part.y), 1, 1)
  }
  g.globalAlpha = 1
}

function spriteFor(c: Cell, s: Sprites, flicker: boolean): Surface | null {
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
      return s.piston(c.dir, c.on)
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
      g.fillStyle = `rgb(${lamp} / 0.55)`
      g.fillRect(x - 2, y - 2, 8, 8)
      g.fillStyle = `rgb(${lamp} / 0.95)`
      g.fillRect(x, y, 4, 4)
    } else if ((c.kind === 'torch' || c.kind === 'wallTorch' || c.kind === 'source') && c.on) {
      g.fillStyle = `rgb(${glow} / 0.7)`
      g.fillRect(x + 1, y + 1, 2, 2)
    } else if ((c.kind === 'repeater' || c.kind === 'comparator') && c.on) {
      g.fillStyle = `rgb(${glow} / 0.4)`
      g.fillRect(x + 1, y + 1, 2, 2)
    }
  }
  target.clearRect(0, 0, W, H)
  target.filter = 'blur(2px)'
  target.drawImage(glowScratch, 0, 0)
  target.filter = 'none'
}
