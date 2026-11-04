// Redstone-Stapler: Bild, Effekte und Ton. Beeinflusst das Spiel nicht, darum darf hier Zufall und Kommazahlen stehen.

import type { GameAudio } from './audio'
import { ARE, CLEAR_T, HEIGHT, HIDDEN, T, WIDTH, type Cell, type Piece, type Stapler, type StaplerEvent } from './stapler'
import type { GameSkin, TextureMap } from './textures'

const TILE = 16
const A_COLS = 6
const IDX = { LAMP_ON: 13, TNT_HOT: 14, DROPPER: 15, DROP_ON: 16, FRAME: 17, WALL: 18, WELL: 19, DUST: 20, DUST_ON: 21, LEVER: 22 } as const
const COLORS: Record<number, string> = { 1: '#8d8d8f', 2: '#6f6f70', 3: '#a5533f', 4: '#c9794a', 5: '#a57d48', 6: '#3a2a55', 7: '#d9d3c8', 8: '#e0261c', 9: '#d8352a', 10: '#d9a43f', 11: '#a58a58', 12: '#b5b5b8' }
const MW = 14
const MH = 24
const OX = 2
const OY = 3

interface Particle { x: number, y: number, vx: number, vy: number, g: number, life: number, max: number, col: string, size: number }
interface Light { x: number, y: number, r: number, g: number, b: number, a: number }

function vis(y: number) {
  return OY + y - HIDDEN
}

function tileOf(c: Cell) {
  if (c.t === T.LAMP && c.lit > 0) return IDX.LAMP_ON
  if (c.t === T.TNT && c.fuse > 0 && (c.fuse >> 1) & 1) return IDX.TNT_HOT
  return c.t
}

export class StaplerView {
  readonly width = MW * TILE
  readonly height = MH * TILE
  private readonly g: CanvasRenderingContext2D
  private readonly atlas = document.createElement('canvas')
  private parts: Particle[] = []
  private timed: (Light & { t: number, max: number })[] = []
  private shake = 0
  private flash = 0
  private readonly wire = new Array<number>(WIDTH + 4).fill(0)
  private readonly drop = new Array<number>(WIDTH).fill(0)
  private viewX = 0
  private viewY = 0

  constructor(canvas: HTMLCanvasElement, images: TextureMap, private readonly skin: GameSkin, private readonly sound: GameAudio) {
    canvas.width = this.width
    canvas.height = this.height
    this.g = canvas.getContext('2d')!
    this.buildAtlas(images)
  }

  private buildAtlas(im: TextureMap) {
    this.atlas.width = A_COLS * TILE
    this.atlas.height = 4 * TILE
    const g = this.atlas.getContext('2d', { willReadFrequently: true })!
    g.imageSmoothingEnabled = false
    const at = (i: number): [number, number] => [(i % A_COLS) * TILE, Math.floor(i / A_COLS) * TILE]
    const put = (i: number, name: string, tint?: string) => {
      const [x, y] = at(i)
      g.drawImage(im[name]!, 0, 0, 16, 16, x, y, 16, 16)
      if (tint) {
        g.save()
        g.globalCompositeOperation = 'source-atop'
        g.fillStyle = tint
        g.fillRect(x, y, 16, 16)
        g.restore()
      }
    }
    const dust = (i: number, [r, gr, b]: [number, number, number]) => {
      const [x, y] = at(i)
      g.save()
      g.translate(x + 16, y)
      g.rotate(Math.PI / 2)
      g.drawImage(im.redstone_dust_line0!, 0, 0, 16, 16, 0, 0, 16, 16)
      g.restore()
      const d = g.getImageData(x, y, 16, 16)
      for (let p = 0; p < d.data.length; p += 4) {
        const v = d.data[p]!
        d.data[p] = v * r
        d.data[p + 1] = v * gr
        d.data[p + 2] = v * b
      }
      g.putImageData(d, x, y)
    }
    put(T.STONE, 'stone')
    put(T.COBBLE, 'cobblestone')
    put(T.BRICK, 'bricks')
    put(T.COPPER, 'copper_block')
    put(T.OAK, 'oak_planks')
    put(T.OBSID, 'obsidian')
    put(T.QUARTZ, 'quartz_block_side')
    put(T.RED, 'redstone_block')
    put(T.TNT, 'tnt_side')
    put(IDX.TNT_HOT, 'tnt_side', 'rgba(255,255,255,.62)')
    put(T.LAMP, 'redstone_lamp')
    put(IDX.LAMP_ON, 'redstone_lamp_on')
    put(T.PISTON, 'piston_top')
    put(T.HEAD, 'iron_block')
    put(IDX.DROPPER, 'dropper_front')
    put(IDX.DROP_ON, 'dropper_front', 'rgba(255,60,30,.42)')
    put(IDX.FRAME, this.skin.frame)
    put(IDX.WALL, this.skin.wall.name, this.skin.wall.tint)
    put(IDX.WELL, this.skin.well.name, this.skin.well.tint)
    dust(IDX.DUST, this.skin.wireOff)
    dust(IDX.DUST_ON, this.skin.wireOn)
    const [lx, ly] = at(IDX.LEVER)
    g.drawImage(im.cobblestone!, 0, 0, 16, 5, lx + 3, ly + 11, 10, 5)
    g.drawImage(im.lever!, 0, 0, 16, 16, lx, ly, 16, 16)
  }

  private blit(g: CanvasRenderingContext2D, idx: number, dx: number, dy: number, s = TILE) {
    g.drawImage(this.atlas, (idx % A_COLS) * TILE, Math.floor(idx / A_COLS) * TILE, TILE, TILE, dx, dy, s, s)
  }

  /** Mit dunkler Fuge, damit gleiche Steine im Stapel lesbar bleiben. */
  private block(idx: number, x: number, y: number) {
    this.blit(this.g, idx, x, y)
    this.g.strokeStyle = 'rgba(0,0,0,.34)'
    this.g.lineWidth = 1
    this.g.strokeRect(x + 0.5, y + 0.5, TILE - 1, TILE - 1)
  }

  private burst(x: number, y: number, col: string, n: number, speed: number, life: number, size: number) {
    for (let i = 0; i < n; i++) {
      const a = Math.random() * 6.283
      const v = speed * (0.3 + Math.random())
      this.parts.push({ x, y, vx: Math.cos(a) * v, vy: Math.sin(a) * v, g: 9, life, max: life, col, size: size * (0.6 + Math.random() * 0.7) })
    }
  }

  private dustAt(x: number, y: number, n: number) {
    for (let i = 0; i < n; i++) {
      this.parts.push({ x: x + Math.random(), y: y + Math.random(), vx: (Math.random() - 0.5) * 0.6, vy: -1.2 - Math.random() * 1.2, g: -0.2, life: 0.7, max: 0.7, col: '#ff3b24', size: 0.1 })
    }
  }

  reset() {
    this.parts.length = 0
    this.timed.length = 0
    this.wire.fill(0)
    this.drop.fill(0)
    this.shake = 0
    this.flash = 0
  }

  onEvent(e: StaplerEvent) {
    const s = this.sound
    switch (e.type) {
      case 'move':
        s.tone(320, 0.03, 'square', 0.012)
        break
      case 'rotate':
        s.tone(520, 0.05, 'triangle', 0.03)
        break
      case 'spawn':
        s.noise(0.05, 0.16, 2600)
        s.tone(190, 0.06, 'square', 0.035)
        this.viewX = e.x
        this.viewY = 0
        for (let i = 0; i < e.n; i++) {
          const x = e.x + i
          if (x >= 0 && x < WIDTH) {
            this.drop[x] = 1
            this.burst(x + 0.5, HIDDEN - 0.2, '#8d8d8f', 3, 1.4, 0.34, 0.12)
          }
        }
        break
      case 'lock':
        s.noise(0.09, 0.2, 650)
        s.tone(95, 0.1, 'sine', 0.14)
        for (const c of e.cells) this.burst(c[0]! + 0.5, c[1]! + 1, '#b9b1aa', 2, 1.2, 0.3, 0.09)
        break
      case 'hard':
        this.shake = Math.max(this.shake, 2.4)
        for (let i = 0; i < 6; i++) this.dustAt(Math.random() * WIDTH, e.to, 1)
        break
      case 'sweep':
        for (let i = 0; i < 3 + e.rows.length; i++) s.later(i * 30, () => s.tone(380 * 1.19 ** i, 0.09, 'square', 0.03))
        break
      case 'clear':
        s.noise(0.2, 0.14, 1700)
        for (let i = 0; i < e.rows.length + 1; i++) s.later(i * 50, () => s.tone(660 * 1.26 ** i, 0.14, 'triangle', 0.07))
        this.shake = Math.max(this.shake, 2.6 + e.rows.length)
        e.rows.forEach((y, ri) => e.cells[ri]!.forEach((t, x) => {
          if (t) this.burst(x + 0.5, y + 0.5, COLORS[t] ?? '#999', 5, 3.2, 0.62, 0.17)
          this.dustAt(x, y, 2)
        }))
        break
      case 'ignite':
        s.noise(0.34, 0.07, 5200)
        break
      case 'explode':
        s.noise(0.8, 0.75, 620)
        s.tone(80, 0.55, 'sine', 0.4, 26)
        this.shake = Math.max(this.shake, 9)
        this.flash = Math.max(this.flash, 0.85)
        this.burst(e.x + 0.5, e.y + 0.5, '#ffb35a', 26, 8, 0.68, 0.24)
        this.burst(e.x + 0.5, e.y + 0.5, '#4a403c', 22, 4, 1.1, 0.32)
        this.burst(e.x + 0.5, e.y + 0.5, '#e0261c', 12, 6, 0.78, 0.16)
        this.timed.push({ x: e.x + 0.5, y: e.y + 0.5, r: 1, g: 0.55, b: 0.2, a: 5.5, t: 0.34, max: 0.34 })
        break
      case 'piston':
        s.noise(0.13, 0.3, 900)
        s.tone(150, 0.13, 'sawtooth', 0.07, 70)
        this.shake = Math.max(this.shake, 2.2)
        this.burst(e.x + 0.5 + e.d, e.y + 0.5, '#b5b5b8', 8, 2.8, 0.4, 0.12)
        break
      case 'lamp':
        s.tone(1040, 0.24, 'sine', 0.08)
        this.burst(e.x + 0.5, e.y + 0.5, '#ffd27a', 8, 2, 0.44, 0.1)
        break
      case 'charge':
        s.tone(240, 0.12, 'sawtooth', 0.04, 480)
        this.dustAt(e.x, e.y, 6)
        break
      case 'pulse':
        s.tone(90, 0.7, 'sawtooth', 0.14, 620)
        s.noise(0.5, 0.24, 2200)
        this.shake = Math.max(this.shake, 5)
        this.flash = 0.58
        for (let x = 0; x < WIDTH; x++) this.dustAt(x, HEIGHT - 1, 8)
        break
      case 'level':
        ;[0, 4, 7, 12].forEach((n, i) => s.later(i * 70, () => s.tone(440 * 2 ** (n / 12), 0.15, 'square', 0.05)))
        break
      case 'over':
        ;[0, -3, -7, -12].forEach((n, i) => s.later(i * 120, () => s.tone(330 * 2 ** (n / 12), 0.24, 'square', 0.06)))
        break
    }
  }

  update(dt: number, game: Stapler | null) {
    this.shake *= 0.0009 ** dt
    if (this.shake < 0.1) this.shake = 0
    this.flash = Math.max(0, this.flash - dt * 3)
    for (let i = this.parts.length - 1; i >= 0; i--) {
      const p = this.parts[i]!
      p.life -= dt
      if (p.life <= 0) {
        this.parts.splice(i, 1)
        continue
      }
      p.vy += p.g * dt
      p.x += p.vx * dt
      p.y += p.vy * dt
    }
    if (this.parts.length > 900) this.parts.splice(0, this.parts.length - 900)
    for (let i = this.timed.length - 1; i >= 0; i--) {
      if ((this.timed[i]!.t -= dt) <= 0) this.timed.splice(i, 1)
    }
    for (let i = 0; i < this.wire.length; i++) this.wire[i] = Math.max(0, this.wire[i]! - dt * 1.9)
    for (let i = 0; i < WIDTH; i++) this.drop[i] = Math.max(0, this.drop[i]! - dt * 2.8)
    if (!game) return
    if (game.phase === 'are') {
      // Das Signal läuft die Leitung entlang bis zum Dropper, der den nächsten Stein ausgibt.
      const k = 1 - game.timer / ARE
      const next = game.queue[0]!
      const end = game.nextX + Math.max(...next.b.map(c => c[0]!)) + 1
      const pos = Math.floor(k * (end + 1))
      for (const c of next.b) {
        const x = game.nextX + c[0]!
        if (x >= 0 && x < WIDTH) this.drop[x] = Math.max(this.drop[x]!, 0.25 + k * 0.7)
      }
      for (let i = 0; i <= pos && i < WIDTH + 1; i++) this.wire[i] = Math.max(this.wire[i]!, 1 - (pos - i) * 0.07)
      if (Math.random() < 0.55 && pos <= WIDTH) this.dustAt(pos - 1, HIDDEN - 2.2, 1)
    }
    if (game.cur) {
      this.viewX += (game.cur.x - this.viewX) * Math.min(1, dt * 34)
      this.viewY += (game.cur.y - this.viewY) * Math.min(1, dt * 30)
      if (Math.abs(game.cur.x - this.viewX) > 4) this.viewX = game.cur.x
      if (Math.abs(game.cur.y - this.viewY) > 3) this.viewY = game.cur.y
    }
  }

  private lights(game: Stapler | null): Light[] {
    const out: Light[] = []
    for (let i = 0; i < WIDTH + 1; i++) {
      if (this.wire[i]! > 0.05) out.push({ x: i - 0.5, y: HIDDEN - 1.5, r: 1, g: 0.16, b: 0.08, a: this.wire[i]! * 1.2 })
    }
    for (let x = 0; x < WIDTH; x++) {
      if (this.drop[x]! > 0.05) out.push({ x: x + 0.5, y: HIDDEN - 0.4, r: 1, g: 0.3, b: 0.12, a: this.drop[x]! * 1.8 })
    }
    if (game) {
      for (let y = HIDDEN; y < HEIGHT; y++) {
        for (let x = 0; x < WIDTH; x++) {
          const c = game.grid[y]![x]
          if (!c) continue
          if (c.t === T.LAMP && c.lit > 0) out.push({ x: x + 0.5, y: y + 0.5, r: 1, g: 0.74, b: 0.34, a: 2.6 })
          else if (c.t === T.RED) out.push({ x: x + 0.5, y: y + 0.5, r: 1, g: 0.12, b: 0.06, a: 0.72 })
          else if (c.t === T.TNT && c.fuse > 0) out.push({ x: x + 0.5, y: y + 0.5, r: 1, g: 0.42, b: 0.16, a: 1.8 })
        }
      }
      if (game.sweep) {
        for (const y of game.sweep.rows) out.push({ x: game.sweep.t / CLEAR_T * WIDTH, y: y + 0.5, r: 1, g: 0.32, b: 0.14, a: 3.4 })
      }
      if (game.cur && game.phase === 'fall') {
        const p = game.cur
        p.mats.forEach((m, i) => {
          if (m === T.RED) {
            const c = p.b[i]!
            out.push({ x: p.x + c[0]! + 0.5, y: p.y + c[1]! + 0.5, r: 1, g: 0.12, b: 0.06, a: 0.9 })
          }
        })
      }
    }
    for (const l of this.timed) out.push({ x: l.x, y: l.y, r: l.r, g: l.g, b: l.b, a: l.a * (l.t / l.max) })
    out.sort((a, b) => b.a - a.a)
    return out.slice(0, 24)
  }

  private glow(x: number, y: number, r: number, col: string, a: number) {
    const g = this.g
    const gradient = g.createRadialGradient(x, y, 0, x, y, r)
    gradient.addColorStop(0, col)
    gradient.addColorStop(1, 'rgba(0,0,0,0)')
    g.globalAlpha = a
    g.fillStyle = gradient
    g.fillRect(x - r, y - r, r * 2, r * 2)
    g.globalAlpha = 1
  }

  draw(game: Stapler | null) {
    const g = this.g
    g.imageSmoothingEnabled = false
    g.setTransform(1, 0, 0, 1, 0, 0)
    g.translate(Math.round((Math.random() - 0.5) * this.shake), Math.round((Math.random() - 0.5) * this.shake))
    for (let y = -1; y <= MH; y++) {
      for (let x = -1; x <= MW; x++) this.blit(g, IDX.WALL, x * TILE, y * TILE)
    }
    for (let y = OY; y < OY + 20; y++) {
      for (let x = OX; x < OX + WIDTH; x++) this.blit(g, IDX.WELL, x * TILE, y * TILE)
    }
    for (let x = OX - 1; x <= OX + WIDTH; x++) this.blit(g, IDX.FRAME, x * TILE, (OY + 20) * TILE)
    for (let y = OY - 2; y < OY + 20; y++) {
      this.blit(g, IDX.FRAME, (OX - 1) * TILE, y * TILE)
      this.blit(g, IDX.FRAME, (OX + WIDTH) * TILE, y * TILE)
    }
    this.blit(g, IDX.LEVER, (OX - 1) * TILE, (OY - 3) * TILE)
    for (let i = 0; i < WIDTH + 1; i++) this.blit(g, this.wire[i]! > 0.3 ? IDX.DUST_ON : IDX.DUST, (OX + i - 1) * TILE, (OY - 2) * TILE)
    for (let x = 0; x < WIDTH; x++) this.blit(g, this.drop[x]! > 0.2 ? IDX.DROP_ON : IDX.DROPPER, (OX + x) * TILE, (OY - 1) * TILE)
    if (game) {
      for (let y = HIDDEN; y < HEIGHT; y++) {
        for (let x = 0; x < WIDTH; x++) {
          const c = game.grid[y]![x]
          if (c) this.block(tileOf(c), (OX + x) * TILE, vis(y) * TILE)
        }
      }
      if (game.cur && game.phase === 'fall') {
        const p = game.cur
        const gy = game.ghostY()
        g.fillStyle = this.skin.ghost.fill
        g.strokeStyle = this.skin.ghost.stroke
        g.lineWidth = 1
        for (const c of p.b) {
          const y = gy + c[1]!
          if (y < HIDDEN) continue
          const px = (OX + p.x + c[0]!) * TILE
          const py = vis(y) * TILE
          g.fillRect(px, py, TILE, TILE)
          g.strokeRect(px + 0.5, py + 0.5, TILE - 1, TILE - 1)
        }
        g.save()
        g.beginPath()
        g.rect(OX * TILE, OY * TILE, WIDTH * TILE, 20 * TILE)
        g.clip()
        p.b.forEach((c, i) => this.block(p.mats[i]!, Math.round((OX + this.viewX + c[0]!) * TILE), Math.round((vis(this.viewY) + c[1]!) * TILE)))
        g.restore()
      }
      if (game.sweep) {
        const k = game.sweep.t / CLEAR_T
        for (const y of game.sweep.rows) {
          const h = vis(y) * TILE
          const end = k * WIDTH
          for (let x = 0; x < WIDTH; x++) {
            if (x < end) {
              g.fillStyle = `rgba(255,${Math.round(180 - 120 * k)},${Math.round(120 - 90 * k)},${0.26 + 0.62 * k})`
              g.fillRect((OX + x) * TILE, h, TILE, TILE)
            }
          }
          g.fillStyle = '#fff'
          g.fillRect((OX + end) * TILE - 2, h, 2, TILE)
        }
      }
    }
    for (const p of this.parts) {
      g.globalAlpha = Math.min(1, Math.max(0, p.life / p.max) * 1.5)
      g.fillStyle = p.col
      const s = Math.max(1, Math.round(p.size * TILE))
      g.fillRect(Math.round((OX + p.x) * TILE), Math.round(vis(p.y) * TILE), s, s)
    }
    g.globalAlpha = 1
    g.globalCompositeOperation = 'lighter'
    for (const l of this.lights(game)) this.glow((OX + l.x) * TILE, vis(l.y) * TILE, TILE * (1.6 + l.a * 0.95), `rgba(${Math.round(l.r * 255)},${Math.round(l.g * 255)},${Math.round(l.b * 255)},.92)`, Math.min(0.9, 0.22 + l.a * 0.16))
    if (this.flash > 0) {
      g.fillStyle = `${this.skin.glowTint}${this.flash * 0.55})`
      g.fillRect(-20, -20, this.width + 40, this.height + 40)
    }
    g.globalCompositeOperation = 'source-over'
    const v = g.createRadialGradient(this.width / 2, this.height / 2, this.height * 0.28, this.width / 2, this.height / 2, this.height * 0.72)
    v.addColorStop(0, 'rgba(0,0,0,0)')
    v.addColorStop(1, 'rgba(0,0,0,.56)')
    g.fillStyle = v
    g.fillRect(-20, -20, this.width + 40, this.height + 40)
  }

  /** Kleine Vorschau für „Halten“ und „Nächste“. */
  drawMini(g: CanvasRenderingContext2D, piece: Piece | null, ox: number, oy: number, s: number) {
    if (!piece) return
    const xs = piece.b.map(c => c[0]!)
    const ys = piece.b.map(c => c[1]!)
    const w = Math.max(...xs) - Math.min(...xs) + 1
    const h = Math.max(...ys) - Math.min(...ys) + 1
    const dx = ox + Math.floor((4 - w) * s / 2) - Math.min(...xs) * s
    const dy = oy + Math.floor((2 - h) * s / 2) - Math.min(...ys) * s
    g.imageSmoothingEnabled = false
    piece.b.forEach((c, i) => this.blit(g, piece.mats[i]!, dx + c[0]! * s, dy + c[1]! * s, s))
  }

  /** Einzelnes Feld für die Spielregeln. */
  drawTile(g: CanvasRenderingContext2D, tile: number, size: number) {
    g.imageSmoothingEnabled = false
    this.blit(g, tile, 0, 0, size)
  }
}
