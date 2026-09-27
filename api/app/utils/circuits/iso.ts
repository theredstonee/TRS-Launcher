// Isometrische Vorschau einer Schaltung auf einem Canvas (Website + Team-Editor). Einfache Formen je Block
// (shared/circuits.ts → `shape`), Malerreihenfolge nach x' + y + z' (reicht für Einheits-Würfel), Drehung in
// Vierteln um die Hochachse. Kein WebGL, keine Texturen – schnell und überall lesbar.

import { circuitBlock, type BlockSpec, type CircuitGrid } from '#shared/circuits'

export interface IsoOptions {
  /** Vierteldrehungen im Uhrzeigersinn (von oben gesehen). */
  rotation: number
  /** Nur Schichten bis einschließlich dieser zeigen, darüber blass (−1 = alle). */
  layer: number
  /** Markiertes Feld (Editor). */
  highlight?: { x: number, y: number, z: number } | null
  /** Anschlüsse (@A …) beschriften. */
  markers?: boolean
  /** Hintergrund (sonst transparent). */
  background?: string | null
  padding?: number
}

type V3 = [number, number, number]

function shade(hex: string, f: number): string {
  const n = Number.parseInt(hex.slice(1), 16)
  const ch = (v: number) => Math.max(0, Math.min(255, Math.round(v * f)))
  return `rgb(${ch((n >> 16) & 255)}, ${ch((n >> 8) & 255)}, ${ch(n & 255)})`
}

/** Welt-Richtung → gedrehte Richtung (dx, dz). */
function rotDir(dx: number, dz: number, r: number): [number, number] {
  switch (((r % 4) + 4) % 4) {
    case 1: return [-dz, dx]
    case 2: return [-dx, -dz]
    case 3: return [dz, -dx]
    default: return [dx, dz]
  }
}

const DIRS: Record<string, [number, number]> = { north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0] }

export class IsoPainter {
  private hw = 0
  private qw = 0
  private bh = 0
  private ox = 0
  private oy = 0
  private sx = 1
  private sz = 1

  constructor(
    private readonly ctx: CanvasRenderingContext2D,
    private readonly width: number,
    private readonly height: number,
  ) {}

  private project([x, y, z]: V3): [number, number] {
    return [this.ox + (x - z) * this.hw, this.oy + (x + z) * this.qw - y * this.bh]
  }

  /** Zell-lokale Box [0,1]³ in gedrehte Weltkoordinaten der Zelle (rx, ry, rz). */
  private box(cell: V3, a: V3, b: V3, r: number): [V3, V3] {
    const rot = (u: number, v: number): [number, number] => {
      switch (((r % 4) + 4) % 4) {
        case 1: return [1 - v, u]
        case 2: return [1 - u, 1 - v]
        case 3: return [v, 1 - u]
        default: return [u, v]
      }
    }
    const [ax, az] = rot(a[0], a[2])
    const [bx, bz] = rot(b[0], b[2])
    return [
      [cell[0] + Math.min(ax, bx), cell[1] + a[1], cell[2] + Math.min(az, bz)],
      [cell[0] + Math.max(ax, bx), cell[1] + b[1], cell[2] + Math.max(az, bz)],
    ]
  }

  private poly(points: V3[], fill: string, alpha: number, stroke?: string): void {
    const c = this.ctx
    c.beginPath()
    points.forEach((p, i) => {
      const [X, Y] = this.project(p)
      if (i === 0) c.moveTo(X, Y)
      else c.lineTo(X, Y)
    })
    c.closePath()
    c.globalAlpha = alpha
    c.fillStyle = fill
    c.fill()
    if (stroke) {
      c.strokeStyle = stroke
      c.lineWidth = 1
      c.stroke()
    }
    c.globalAlpha = 1
  }

  /** Quader mit sichtbaren Flächen (oben, +x', +z'). */
  private cuboid([x0, y0, z0]: V3, [x1, y1, z1]: V3, color: string, alpha = 1, outline = true): void {
    const stroke = outline ? 'rgba(0,0,0,0.35)' : undefined
    this.poly([[x1, y0, z0], [x1, y0, z1], [x1, y1, z1], [x1, y1, z0]], shade(color, 0.72), alpha, stroke)
    this.poly([[x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]], shade(color, 0.86), alpha, stroke)
    this.poly([[x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]], shade(color, 1.08), alpha, stroke)
  }

  /** Pfeil auf einer waagerechten Fläche (Höhe h) in Richtung (dx, dz). */
  private arrow(cx: number, h: number, cz: number, dx: number, dz: number, color: string, alpha: number): void {
    const c = this.ctx
    const tip: V3 = [cx + dx * 0.32, h, cz + dz * 0.32]
    const back: V3 = [cx - dx * 0.25, h, cz - dz * 0.25]
    const l: V3 = [cx - dx * 0.05 - dz * 0.2, h, cz - dz * 0.05 + dx * 0.2]
    const rr: V3 = [cx - dx * 0.05 + dz * 0.2, h, cz - dz * 0.05 - dx * 0.2]
    c.globalAlpha = alpha
    c.strokeStyle = color
    c.lineWidth = Math.max(1.2, this.hw * 0.09)
    c.lineCap = 'round'
    c.beginPath()
    const [bx, by] = this.project(back)
    const [tx, ty] = this.project(tip)
    c.moveTo(bx, by)
    c.lineTo(tx, ty)
    const [lx, ly] = this.project(l)
    const [rx, ry] = this.project(rr)
    c.moveTo(lx, ly)
    c.lineTo(tx, ty)
    c.lineTo(rx, ry)
    c.stroke()
    c.globalAlpha = 1
  }

  private label(p: V3, text: string, alpha: number): void {
    const c = this.ctx
    const [X, Y] = this.project(p)
    c.globalAlpha = alpha
    c.font = `600 ${Math.max(9, Math.round(this.hw * 0.42))}px ui-monospace, monospace`
    c.textAlign = 'center'
    c.textBaseline = 'middle'
    const w = c.measureText(text).width + 6
    c.fillStyle = 'rgba(17,17,22,0.85)'
    c.fillRect(X - w / 2, Y - 8, w, 16)
    c.fillStyle = '#ffd48a'
    c.fillText(text, X, Y)
    c.globalAlpha = 1
  }

  /** Ein Block in einer Zelle (Koordinaten schon gedreht). */
  private block(cell: V3, s: BlockSpec, r: number, alpha: number): void {
    const def = circuitBlock(s.key)
    if (!def) return
    const color = def.color
    const [x, y, z] = cell
    const facing = s.props.facing
    const fdir = facing && DIRS[facing] ? rotDir(DIRS[facing]![0], DIRS[facing]![1], r) : null
    const B = (a: V3, b: V3) => this.box(cell, a, b, r)
    switch (def.shape) {
      case 'cube':
      case 'piston': {
        this.cuboid([x, y, z], [x + 1, y + 1, z + 1], color, alpha)
        if (def.shape === 'piston' || s.key === 'observer' || s.key === 'dropper' || s.key === 'furnace' || s.key === 'chest') {
          if (fdir) this.arrow(x + 0.5, y + 1, z + 0.5, fdir[0], fdir[1], def.shape === 'piston' ? '#f3e2b8' : '#ffd48a', alpha)
          else if (facing === 'up' || facing === 'down') {
            const [cx, cy] = this.project([x + 0.5, y + 1, z + 0.5])
            this.ctx.globalAlpha = alpha
            this.ctx.fillStyle = facing === 'up' ? '#f3e2b8' : '#333'
            this.ctx.beginPath()
            this.ctx.arc(cx, cy, Math.max(2, this.hw * 0.16), 0, Math.PI * 2)
            this.ctx.fill()
            this.ctx.globalAlpha = 1
          }
        }
        if (s.key === 'redstone_lamp' || s.key === 'glowstone' || s.key === 'copper_bulb') {
          const [cx, cy] = this.project([x + 0.5, y + 1, z + 0.5])
          this.ctx.globalAlpha = 0.35 * alpha
          this.ctx.fillStyle = '#ffd48a'
          this.ctx.beginPath()
          this.ctx.ellipse(cx, cy, this.hw * 0.5, this.qw * 0.5, 0, 0, Math.PI * 2)
          this.ctx.fill()
          this.ctx.globalAlpha = 1
        }
        break
      }
      case 'glass':
        this.cuboid([x, y, z], [x + 1, y + 1, z + 1], color, 0.35 * alpha)
        break
      case 'water':
        this.cuboid([x, y, z], [x + 1, y + 0.88, z + 1], color, 0.6 * alpha, false)
        break
      case 'dust': {
        this.cuboid([x + 0.2, y, z + 0.2], [x + 0.8, y + 0.04, z + 0.8], color, alpha, false)
        const [cx, cy] = this.project([x + 0.5, y + 0.05, z + 0.5])
        this.ctx.globalAlpha = alpha
        this.ctx.fillStyle = '#ff5a4d'
        this.ctx.beginPath()
        this.ctx.arc(cx, cy, Math.max(1.5, this.hw * 0.1), 0, Math.PI * 2)
        this.ctx.fill()
        this.ctx.globalAlpha = 1
        break
      }
      case 'slab': {
        const [a, b] = B([0, 0, 0], [1, 0.14, 1])
        this.cuboid(a, b, color, alpha)
        if (fdir) this.arrow(x + 0.5, y + 0.15, z + 0.5, fdir[0], fdir[1], s.key === 'comparator' && s.props.mode === 'subtract' ? '#ff5a4d' : '#b31a12', alpha)
        break
      }
      case 'torch':
      case 'wall_torch': {
        let off: [number, number] = [0, 0]
        if (def.shape === 'wall_torch' && fdir) off = [-fdir[0] * 0.3, -fdir[1] * 0.3]
        const cx = x + 0.5 + off[0]
        const cz = z + 0.5 + off[1]
        const y0 = def.shape === 'wall_torch' ? y + 0.2 : y
        this.cuboid([cx - 0.07, y0, cz - 0.07], [cx + 0.07, y0 + 0.55, cz + 0.07], '#6b4f2c', alpha)
        this.cuboid([cx - 0.1, y0 + 0.55, cz - 0.1], [cx + 0.1, y0 + 0.72, cz + 0.1], color, alpha)
        break
      }
      case 'lever':
      case 'button': {
        const face = s.props.face ?? 'floor'
        const lever = def.shape === 'lever'
        if (face === 'wall' && fdir) {
          // An der Wand hinter dem Block (Gegenrichtung von facing).
          const bx = x + 0.5 - fdir[0] * 0.44
          const bz = z + 0.5 - fdir[1] * 0.44
          const w = lever ? 0.18 : 0.2
          this.cuboid([bx - (fdir[0] ? 0.06 : w), y + 0.3, bz - (fdir[1] ? 0.06 : w)], [bx + (fdir[0] ? 0.06 : w), y + (lever ? 0.7 : 0.55), bz + (fdir[1] ? 0.06 : w)], lever ? '#9d9d9d' : color, alpha)
          if (lever) this.cuboid([bx + fdir[0] * 0.05 - 0.04, y + 0.45, bz + fdir[1] * 0.05 - 0.04], [bx + fdir[0] * 0.3 + 0.04, y + 0.55, bz + fdir[1] * 0.3 + 0.04], color, alpha)
        } else {
          const top = face === 'ceiling'
          const y0 = top ? y + 0.9 : y
          this.cuboid([x + 0.3, y0, z + 0.3], [x + 0.7, y0 + 0.1, z + 0.7], lever ? '#9d9d9d' : color, alpha)
          if (lever) this.cuboid([x + 0.45, top ? y + 0.5 : y + 0.1, z + 0.45], [x + 0.55, top ? y + 0.9 : y + 0.5, z + 0.55], color, alpha)
        }
        break
      }
      case 'plate':
        this.cuboid([x + 0.07, y, z + 0.07], [x + 0.93, y + 0.07, z + 0.93], color, alpha)
        break
      case 'daylight':
        this.cuboid([x, y, z], [x + 1, y + 0.38, z + 1], s.props.inverted === 'true' ? '#6d86a8' : color, alpha)
        break
      case 'hopper':
        this.cuboid([x + 0.3, y, z + 0.3], [x + 0.7, y + 0.35, z + 0.7], color, alpha)
        this.cuboid([x, y + 0.35, z], [x + 1, y + 1, z + 1], color, alpha)
        if (fdir) this.arrow(x + 0.5, y + 1, z + 0.5, fdir[0], fdir[1], '#8b8ba2', alpha)
        break
      case 'cross':
        this.cuboid([x + 0.35, y, z + 0.35], [x + 0.5, y + 1, z + 0.5], color, alpha, false)
        this.cuboid([x + 0.55, y, z + 0.5], [x + 0.68, y + 0.9, z + 0.63], shade(color, 0.85), alpha, false)
        break
      case 'sign':
        this.cuboid([x + 0.46, y, z + 0.46], [x + 0.54, y + 0.55, z + 0.54], '#6b4f2c', alpha)
        this.cuboid([x + 0.1, y + 0.55, z + 0.45], [x + 0.9, y + 0.95, z + 0.55], color, alpha)
        break
    }
  }

  /** Zeichnet das ganze Raster; passt die Größe an die Zeichenfläche an. */
  draw(grid: CircuitGrid, opts: IsoOptions): void {
    const c = this.ctx
    const r = ((opts.rotation % 4) + 4) % 4
    c.clearRect(0, 0, this.width, this.height)
    if (opts.background) {
      c.fillStyle = opts.background
      c.fillRect(0, 0, this.width, this.height)
    }
    // Gedrehte Grundfläche
    this.sx = r % 2 === 0 ? grid.x : grid.z
    this.sz = r % 2 === 0 ? grid.z : grid.x
    const pad = opts.padding ?? 16
    // Ausmaß in Einheiten von hw: Breite (sx + sz), Höhe (sx + sz)/2 + y * bh/hw
    const bhRatio = 1.15
    const unitsW = this.sx + this.sz
    const unitsH = (this.sx + this.sz) / 2 + grid.y * bhRatio
    const hw = Math.max(3, Math.min((this.width - pad * 2) / unitsW, (this.height - pad * 2) / unitsH, 42))
    this.hw = hw
    this.qw = hw / 2
    this.bh = hw * bhRatio
    const drawW = unitsW * hw
    const drawH = unitsH * hw
    this.ox = (this.width - drawW) / 2 + this.sz * hw
    this.oy = (this.height - drawH) / 2 + grid.y * this.bh

    // Bodenraster
    c.strokeStyle = 'rgba(139,139,162,0.18)'
    c.lineWidth = 1
    for (let i = 0; i <= this.sx; i++) {
      const [ax, ay] = this.project([i, 0, 0])
      const [bx, by] = this.project([i, 0, this.sz])
      c.beginPath()
      c.moveTo(ax, ay)
      c.lineTo(bx, by)
      c.stroke()
    }
    for (let k = 0; k <= this.sz; k++) {
      const [ax, ay] = this.project([0, 0, k])
      const [bx, by] = this.project([this.sx, 0, k])
      c.beginPath()
      c.moveTo(ax, ay)
      c.lineTo(bx, by)
      c.stroke()
    }

    const cells: { key: number, cell: V3, s: BlockSpec, y: number }[] = []
    for (let y = 0; y < grid.y; y++) for (let z = 0; z < grid.z; z++) for (let x = 0; x < grid.x; x++) {
      const s = grid.cells[y]![z]![x]
      if (!s) continue
      const [rx, rz] = this.rotCell(x, z, grid, r)
      cells.push({ key: rx + y + rz, cell: [rx, y, rz], s, y })
    }
    cells.sort((a, b) => a.key - b.key || a.y - b.y)
    const hl = opts.highlight
    for (const { cell, s, y } of cells) {
      const faded = opts.layer >= 0 && y > opts.layer
      this.block(cell, s, r, faded ? 0.12 : 1)
    }
    if (hl) {
      const [rx, rz] = this.rotCell(hl.x, hl.z, grid, r)
      const p: V3[] = [[rx, hl.y, rz], [rx + 1, hl.y, rz], [rx + 1, hl.y, rz + 1], [rx, hl.y, rz + 1]]
      c.beginPath()
      p.forEach((q, i) => {
        const [X, Y] = this.project(q)
        if (i === 0) c.moveTo(X, Y)
        else c.lineTo(X, Y)
      })
      c.closePath()
      c.strokeStyle = '#ffb84d'
      c.lineWidth = 2
      c.stroke()
    }
    if (opts.markers) {
      for (const { cell, s, y } of cells) {
        if (!s.marker || (opts.layer >= 0 && y > opts.layer)) continue
        this.label([cell[0] + 0.5, cell[1] + 1.25, cell[2] + 0.5], s.marker, 0.95)
      }
    }
  }

  private rotCell(x: number, z: number, g: CircuitGrid, r: number): [number, number] {
    switch (r) {
      case 1: return [g.z - 1 - z, x]
      case 2: return [g.x - 1 - x, g.z - 1 - z]
      case 3: return [z, g.x - 1 - x]
      default: return [x, z]
    }
  }
}

/** Kompass-Richtung (Norden) nach der Drehung – für die Beschriftung im Editor. */
export function northAfter(rotation: number): [number, number] {
  return rotDir(0, -1, rotation)
}
