// Loren-Rausch: Stollen je Pixel, Effekte und Ton. Beeinflusst das Spiel nicht, darum darf hier Zufall und Kommazahlen stehen.

import type { GameAudio } from './audio'
import { BASE_SPEED, LANE_T, MAX_SPEED, O, ROW_GAP, UNIT, type Loren, type LorenEvent } from './loren'
import type { GameSkin, TextureMap } from './textures'

interface Tex { w: number, h: number, d: Uint32Array }
interface Zone { name: string, floor: string, wall: string, ores: string[], wood: string, roof: string, amb: [number, number, number], torch: [number, number, number] }
interface Spark { x: number, y: number, w: number, vx: number, vy: number, vw: number, life: number, c: number, s: number, g: number }
type Lit = [number, number, number]

const SW = 400
const SH = 225
const F = 225
const CX = 200
const CY = 82
const HW = 4
const HH = 4.4
const EYE = 2.65
const ZFAR = 64
const CAM_BACK = 4.3
const SUPPORT = 14
const ZONE_LEN = 420
const LANES = [-2, 0, 2]

const DEFAULT_ZONES: Zone[] = [
  { name: 'mine', floor: 'stone', wall: 'stone', ores: ['coal_ore', 'iron_ore', 'redstone_ore', 'coal_ore'], wood: 'oak_log', roof: 'oak_planks', amb: [46, 42, 40], torch: [300, 205, 110] },
  { name: 'deepslate', floor: 'deepslate', wall: 'deepslate', ores: ['deepslate_redstone_ore', 'deepslate_redstone_ore', 'diamond_ore', 'iron_ore'], wood: 'deepslate_bricks', roof: 'deepslate_bricks', amb: [34, 36, 46], torch: [250, 180, 130] },
  { name: 'nether', floor: 'netherrack', wall: 'netherrack', ores: ['nether_bricks', 'magma', 'lava_still', 'nether_bricks'], wood: 'nether_bricks', roof: 'nether_bricks', amb: [56, 26, 20], torch: [215, 125, 60] },
]

function shadePx(c: number, L: Lit): number {
  let r = ((c & 255) * L[0]) >> 7
  let g = (((c >>> 8) & 255) * L[1]) >> 7
  let b = (((c >>> 16) & 255) * L[2]) >> 7
  if (r > 255) r = 255
  if (g > 255) g = 255
  if (b > 255) b = 255
  return 0xFF000000 | (b << 16) | (g << 8) | r
}

export class LorenView {
  readonly width = SW
  readonly height = SH
  private readonly ctx: CanvasRenderingContext2D
  private readonly img: ImageData
  private readonly buf: Uint32Array
  private readonly tx: Record<string, Tex> = {}
  private readonly floorRail: Record<string, Tex> = {}
  private readonly floorPow: Record<string, Tex> = {}
  private readonly floorOn: Record<string, Tex> = {}
  private readonly zones: Zone[]
  private readonly powTab = [new Int8Array(90), new Int8Array(90), new Int8Array(90)]
  private powBase = 0
  private parts: Spark[] = []
  private shake = 0
  private flash = 0
  private red = 0
  private lx = 1
  private clack = 0
  private view = { camx: 0, bx: 0, by: 0, eye: EYE, shx: 0, shy: 0 }

  constructor(canvas: HTMLCanvasElement, images: TextureMap, skin: GameSkin, private readonly sound: GameAudio) {
    canvas.width = SW
    canvas.height = SH
    this.ctx = canvas.getContext('2d')!
    this.img = this.ctx.createImageData(SW, SH)
    this.buf = new Uint32Array(this.img.data.buffer)
    this.zones = skin.zone ? [skin.zone, DEFAULT_ZONES[1]!, DEFAULT_ZONES[2]!] : DEFAULT_ZONES
    this.prepare(images)
  }

  private prepare(im: TextureMap) {
    const cv = document.createElement('canvas')
    const g = cv.getContext('2d', { willReadFrequently: true })!
    const grab = (image: HTMLImageElement, sx: number, sy: number, w: number, h: number): Tex => {
      cv.width = w
      cv.height = h
      g.clearRect(0, 0, w, h)
      g.drawImage(image, sx, sy, w, h, 0, 0, w, h)
      return { w, h, d: new Uint32Array(g.getImageData(0, 0, w, h).data.buffer.slice(0)) }
    }
    for (const name of Object.keys(im)) {
      if (name !== 'creeper' && name !== 'steve' && name !== 'minecart') this.tx[name] = grab(im[name]!, 0, 0, 16, 16)
    }
    this.tx.creeper_head = grab(im.creeper!, 8, 8, 8, 8)
    this.tx.creeper_body = grab(im.creeper!, 20, 20, 8, 12)
    this.tx.creeper_foot = grab(im.creeper!, 4, 20, 4, 6)
    this.tx.cart_side = grab(im.minecart!, 2, 2, 16, 8)
    this.tx.cart_floor = grab(im.minecart!, 2, 12, 20, 16)
    this.tx.steve_head = grab(im.steve!, 24, 8, 8, 8)
    this.tx.steve_body = grab(im.steve!, 32, 20, 8, 12)
    this.tx.steve_arm = grab(im.steve!, 52, 20, 4, 12)
    const over = (a: Tex, b: Tex): Tex => {
      const d = new Uint32Array(256)
      for (let i = 0; i < 256; i++) d[i] = (b.d[i]! >>> 24) > 127 ? b.d[i]! : a.d[i]!
      return { w: 16, h: 16, d }
    }
    for (const zone of this.zones) {
      const floor = this.tx[zone.floor]!
      this.floorRail[zone.floor] = over(floor, this.tx.rail!)
      this.floorPow[zone.floor] = over(floor, this.tx.powered_rail!)
      this.floorOn[zone.floor] = over(floor, this.tx.powered_rail_on!)
    }
  }

  private zoneAt(z: number): Zone {
    return this.zones[Math.floor(Math.max(0, z) / ZONE_LEN) % 3]!
  }

  /** Name der aktuellen Gegend für die Anzeige. */
  zoneName(game: Loren): string {
    return this.zoneAt(game.pos / UNIT).name
  }

  reset() {
    this.parts.length = 0
    this.lx = 0
    this.shake = 0
    this.flash = 0
    this.red = 0
  }

  private fillPow(g: Loren, zStart: number) {
    this.powBase = Math.floor(zStart)
    for (let i = 0; i < 90; i++) {
      const ri = Math.floor((this.powBase + i) / ROW_GAP)
      const r = ri >= 0 ? g.row(ri) : null
      for (let l = 0; l < 3; l++) this.powTab[l]![i] = r && r.pow[l] ? 1 : 0
    }
  }

  private drawTunnel(g: Loren, pz: number) {
    const { camx, bx, by, shx, shy, eye } = this.view
    const on = (g.tick >> 3) & 1
    const { buf, tx } = this
    for (let sy = 0; sy < SH; sy++) {
      const ny0 = (CY - sy + shy) / F
      for (let sx = 0; sx < SW; sx++) {
        const nx0 = (sx - CX + shx) / F
        let z = 14
        let dx = 0
        let dy = 0
        let tf = 0
        let tw = 0
        for (let it = 0; it < 2; it++) {
          dx = nx0 - bx * z
          dy = ny0 - by * z
          tf = dy < -1e-4 ? eye / -dy : dy > 1e-4 ? (HH - eye) / dy : 1e9
          tw = dx > 1e-4 ? (HW - camx) / dx : dx < -1e-4 ? (HW + camx) / -dx : 1e9
          z = tf < tw ? tf : tw
          if (z > ZFAR) z = ZFAR
        }
        const o = sy * SW + sx
        if (z >= ZFAR) {
          buf[o] = 0xFF040302
          continue
        }
        const wz = pz + z
        const zb = Math.floor(wz)
        const zone = this.zoneAt(wz)
        const fz = wz - zb
        let tex: Tex
        let u: number
        let v: number
        let emit = 0
        let lat = 1
        if (tf <= tw) {
          const wx = camx + dx * z
          if (dy < 0) {
            const q = Math.floor(wx + 0.5)
            u = ((wx + 0.5 - q) * 16) | 0
            v = 15 - ((fz * 16) | 0)
            if (q === -2 || q === 0 || q === 2) {
              const idx = zb - this.powBase
              const powered = idx >= 0 && idx < 90 && this.powTab[q / 2 + 1]![idx]
              tex = powered ? this.floorOn[zone.floor]! : this.floorRail[zone.floor]!
              if (powered) emit = 40
            }
            else {
              tex = tx[zone.floor]!
            }
          }
          else {
            const q = Math.floor(wx)
            u = ((wx - q) * 16) | 0
            v = 15 - ((fz * 16) | 0)
            tex = zb % SUPPORT < 1 ? tx[zone.roof]! : tx[zone.wall]!
          }
          lat = Math.abs(wx) / HW
        }
        else {
          const y = eye + dy * z
          const yi = y | 0
          u = (fz * 16) | 0
          v = 15 - (((y - yi) * 16) | 0)
          if (zb % SUPPORT < 1) {
            tex = tx[zone.wood]!
          }
          else {
            const h = (Math.imul(zb, 73856093) ^ Math.imul(yi + 3, 19349663) ^ (dx > 0 ? 83492791 : 0)) >>> 0
            if (h % 100 < 13) {
              const n = zone.ores[(h >>> 8) & 3]!
              tex = tx[n]!
              if (n === 'lava_still' || n === 'magma') emit = 170
              else if (n.includes('redstone')) emit = on ? 38 : 16
            }
            else {
              tex = tx[zone.wall]!
            }
          }
          lat = 1
        }
        const c = tex.d[(v & 15) * 16 + (u & 15)]!
        const m = ((wz % SUPPORT) + SUPPORT) % SUPPORT
        const dt = Math.abs(m - 7.5)
        const tl = dt < 7.5 ? 1 - dt / 7.5 : 0
        const tq = tl * tl * lat * 0.6
        const fog = 1 / (1 + z * z * 0.0048)
        const lr = (zone.amb[0] + zone.torch[0] * tq + emit + this.red * 110) * fog + this.flash * 200
        const lg = (zone.amb[1] + zone.torch[1] * tq + emit * 0.34) * fog + this.flash * 170
        const lb = (zone.amb[2] + zone.torch[2] * tq + emit * 0.16) * fog + this.flash * 130
        buf[o] = shadePx(c, [lr, lg, lb])
      }
    }
  }

  private light(z: number, wz: number): Lit {
    const zone = this.zoneAt(wz)
    const m = ((wz % SUPPORT) + SUPPORT) % SUPPORT
    const dt = Math.abs(m - 7.5)
    const tl = dt < 7.5 ? 1 - dt / 7.5 : 0
    const tq = tl * tl * 0.55
    const fog = 1 / (1 + z * z * 0.0048)
    return [(zone.amb[0] + 32 + zone.torch[0] * tq) * fog + this.flash * 200, (zone.amb[1] + 30 + zone.torch[1] * tq) * fog + this.flash * 170, (zone.amb[2] + 28 + zone.torch[2] * tq) * fog + this.flash * 130]
  }

  private px(x: number, z: number) {
    return CX - this.view.shx + F * ((x - this.view.camx) / z + this.view.bx * z)
  }

  private py(h: number, z: number) {
    return CY + this.view.shy - F * ((h - this.view.eye) / z + this.view.by * z)
  }

  private face(t: Tex, x0: number, x1: number, h0: number, h1: number, z: number, L: Lit | null) {
    if (z < 0.5) return
    const a = this.px(x0, z)
    const b = this.px(x1, z)
    const top = this.py(h1, z)
    const bot = this.py(h0, z)
    const xs = Math.max(0, Math.ceil(a))
    const xe = Math.min(SW, Math.ceil(b))
    const ys = Math.max(0, Math.ceil(top))
    const ye = Math.min(SH, Math.ceil(bot))
    const du = t.w / (b - a)
    const dv = t.h / (bot - top)
    for (let y = ys; y < ye; y++) {
      const v = Math.min(t.h - 1, ((y - top) * dv) | 0)
      for (let x = xs; x < xe; x++) {
        const c = t.d[v * t.w + Math.min(t.w - 1, ((x - a) * du) | 0)]!
        if ((c >>> 24) < 128) continue
        this.buf[y * SW + x] = L ? shadePx(c, L) : c | 0xFF000000
      }
    }
  }

  private topFace(t: Tex, x0: number, x1: number, h: number, z0: number, z1: number, base: number) {
    if (this.view.eye <= h) return
    const near = Math.max(z0, 0.7)
    const ys = Math.max(0, Math.ceil(this.py(h, z1)))
    const ye = Math.min(SH, Math.ceil(this.py(h, near)))
    for (let y = ys; y < ye; y++) {
      const z = F * (this.view.eye - h) / Math.max(0.1, y - CY - this.view.shy)
      if (z < near || z > z1) continue
      const L = this.light(z, base + z)
      const lit: Lit = [L[0] * 1.12, L[1] * 1.12, L[2] * 1.12]
      const a = this.px(x0, z)
      const b = this.px(x1, z)
      const xs = Math.max(0, Math.ceil(a))
      const xe = Math.min(SW, Math.ceil(b))
      const du = t.w / (b - a)
      const v = Math.min(t.h - 1, Math.max(0, (((z - z0) / (z1 - z0)) * t.h) | 0))
      for (let x = xs; x < xe; x++) {
        const c = t.d[v * t.w + Math.min(t.w - 1, ((x - a) * du) | 0)]!
        if ((c >>> 24) > 127) this.buf[y * SW + x] = shadePx(c, lit)
      }
    }
  }

  private sideFace(t: Tex, x: number, h0: number, h1: number, z0: number, z1: number, base: number) {
    const near = Math.max(z0, 0.7)
    const a = this.px(x, near)
    const b = this.px(x, z1)
    const xs = Math.max(0, Math.ceil(Math.min(a, b)))
    const xe = Math.min(SW, Math.ceil(Math.max(a, b)))
    if (xe - xs < 1) return
    for (let sx = xs; sx < xe; sx++) {
      const k = (sx - a) / (b - a)
      const z = 1 / (1 / near + k * (1 / z1 - 1 / near))
      const L = this.light(z, base + z)
      const lit: Lit = [L[0] * 0.72, L[1] * 0.72, L[2] * 0.72]
      const top = this.py(h1, z)
      const bot = this.py(h0, z)
      const ys = Math.max(0, Math.ceil(top))
      const ye = Math.min(SH, Math.ceil(bot))
      const u = Math.min(t.w - 1, Math.max(0, (((z - z0) / (z1 - z0)) * t.w) | 0))
      const dv = t.h / (bot - top)
      for (let y = ys; y < ye; y++) {
        const c = t.d[Math.min(t.h - 1, ((y - top) * dv) | 0) * t.w + u]!
        if ((c >>> 24) > 127) this.buf[y * SW + sx] = shadePx(c, lit)
      }
    }
  }

  private box(front: Tex, top: Tex, x0: number, x1: number, h0: number, h1: number, z0: number, z1: number, pz: number) {
    const L = this.light(z0, pz + z0)
    if (this.view.camx < x0) this.sideFace(front, x0, h0, h1, z0, z1, pz)
    else if (this.view.camx > x1) this.sideFace(front, x1, h0, h1, z0, z1, pz)
    this.topFace(top, x0, x1, h1, z0, z1, pz)
    this.face(front, x0, x1, h0, h1, z0, L)
  }

  private drawObject(o: number, lane: number, z: number, pz: number, tick: number) {
    const { tx } = this
    const x = LANES[lane]!
    const L = this.light(z, pz + z)
    if (o === O.TNT) {
      this.box(tx.tnt_side!, tx.tnt_top!, x - 0.5, x + 0.5, 0, 1, z, z + 1, pz)
    }
    else if (o === O.CRATE) {
      this.box(tx.oak_planks!, tx.oak_planks!, x - 0.8, x + 0.8, 0, 1.2, z, z + 1.4, pz)
      this.box(tx.oak_planks!, tx.oak_planks!, x - 0.55, x + 0.55, 1.2, 2.3, z + 0.1, z + 1.2, pz)
    }
    else if (o === O.BEAM) {
      this.face(tx.oak_log!, x - 1, x - 0.8, 0, 1.05, z, L)
      this.face(tx.oak_log!, x + 0.8, x + 1, 0, 1.05, z, L)
      this.box(tx.oak_log!, tx.oak_planks!, x - 1, x + 1, 1.05, 1.75, z, z + 0.8, pz)
    }
    else if (o === O.CREEPER) {
      const near = z < 16
      const hot = near && (tick >> 2) & 1
      const lit: Lit = hot ? [L[0] * 3 + 120, L[1] * 3 + 120, L[2] * 3 + 120] : L
      const s = near ? 1.06 : 1
      this.face(tx.creeper_foot!, x - 0.4, x - 0.02, 0, 0.36, z, lit)
      this.face(tx.creeper_foot!, x + 0.02, x + 0.4, 0, 0.36, z, lit)
      this.face(tx.creeper_body!, x - 0.36 * s, x + 0.36 * s, 0.36, 1.38, z, lit)
      this.face(tx.creeper_head!, x - 0.42 * s, x + 0.42 * s, 1.38, 2.22 * s, z, lit)
    }
  }

  private drawPlayer(g: Loren, pz: number, x: number, hop: number) {
    const { tx } = this
    const z = CAM_BACK
    const duck = g.duckT > 0
    const top = 0.68
    const L = this.light(z, pz + z)
    this.box(tx.cart_side!, tx.cart_floor!, x - 0.56, x + 0.56, hop + 0.1, hop + top, z - 0.15, z + 1.05, pz)
    const lit: Lit = duck ? [L[0] * 0.9, L[1] * 0.9, L[2] * 0.9] : L
    const shift = duck ? -0.5 : 0
    const base = hop + top + shift
    this.face(tx.steve_arm!, x - 0.44, x - 0.25, base + 0.02, base + 0.68, z + 0.28, lit)
    this.face(tx.steve_arm!, x + 0.25, x + 0.44, base + 0.02, base + 0.68, z + 0.28, lit)
    this.face(tx.steve_body!, x - 0.25, x + 0.25, base, base + 0.72, z + 0.3, lit)
    this.face(tx.steve_head!, x - 0.27, x + 0.27, base + 0.72, base + 1.25, z + 0.3, lit)
  }

  private drawSparks(pz: number) {
    for (const p of this.parts) {
      const z = p.w - pz
      if (z < 0.6 || z > ZFAR) continue
      const a = this.px(p.x, z) | 0
      const b = this.py(p.y, z) | 0
      const s = Math.max(1, Math.round(p.s * F / z))
      const ye = Math.min(SH, b + s)
      const xe = Math.min(SW, a + s)
      for (let y = Math.max(0, b); y < ye; y++) {
        for (let x = Math.max(0, a); x < xe; x++) this.buf[y * SW + x] = p.c
      }
    }
  }

  private speedLines(speedK: number, turbo: boolean) {
    if (speedK <= 0.5 && !turbo) return
    const n = 6 + Math.floor((speedK - 0.5) * 40) + (turbo ? 26 : 0)
    for (let i = 0; i < n; i++) {
      const a = Math.random() * 6.283
      const r0 = 90 + Math.random() * 150
      const len = 8 + 34 * speedK
      for (let k = 0; k < len; k++) {
        const r = r0 + k
        const x = (CX + Math.cos(a) * r) | 0
        const y = (CY + Math.sin(a) * r * 0.75) | 0
        if (x < 0 || x >= SW || y < 0 || y >= SH) continue
        const o = y * SW + x
        const c = this.buf[o]!
        this.buf[o] = 0xFF000000 | (Math.min(255, ((c >>> 16) & 255) + (turbo ? 26 : 34)) << 16) | (Math.min(255, ((c >>> 8) & 255) + (turbo ? 40 : 34)) << 8) | Math.min(255, (c & 255) + (turbo ? 110 : 34))
      }
    }
  }

  draw(g: Loren) {
    const { view, ctx, tx } = this
    const pz = g.pos / UNIT - CAM_BACK
    const k = (LANE_T - g.laneT) / LANE_T
    const e = g.laneT > 0 ? k * k * (3 - 2 * k) : 1
    const xp = LANES[g.fromLane]! + (LANES[g.lane]! - LANES[g.fromLane]!) * e
    this.lx += (xp * 0.72 - this.lx) * 0.22
    view.camx = this.lx
    const t = pz * 0.0085 + (g.seed % 1000)
    view.bx = (Math.sin(t) * 0.55 + Math.sin(t * 2.3 + 1) * 0.35) * 0.0016
    view.by = Math.sin(t * 1.3 + 2) * 0.4 * 0.0009
    const hop = g.height() / UNIT
    const speedK = Math.min(1, (g.speed - BASE_SPEED) / (MAX_SPEED + 34 - BASE_SPEED))
    const wobble = Math.sin(g.pos / UNIT * 3.1) * 0.008 * (1 + speedK * 2)
    view.eye = EYE + hop * 0.32 - (g.duckT > 0 ? 0.28 : 0) + wobble
    view.shx = (Math.random() - 0.5) * this.shake
    view.shy = (Math.random() - 0.5) * this.shake
    this.fillPow(g, pz)
    this.drawTunnel(g, pz)
    const list: { z: number, f: () => void }[] = []
    const near = Math.floor(pz / ROW_GAP)
    for (let i = near + 9; i >= Math.max(0, near); i--) {
      const r = g.row(i)
      const z = i * ROW_GAP - pz
      if (z < 0.7 || z > ZFAR) continue
      list.push({ z, f: () => {
        for (let l = 0; l < 3; l++) {
          if (r.o[l] && !r.used[l]) this.drawObject(r.o[l]!, l, z, pz, g.tick)
          if (r.item[l] && !r.got[l]) {
            const L = this.light(z, pz + z)
            const bob = 0.75 + Math.sin(g.tick * 0.1 + i) * 0.12
            this.face(tx.redstone!, LANES[l]! - 0.42, LANES[l]! + 0.42, bob, bob + 0.84, z, [L[0] + 90, L[1] + 40, L[2] + 30])
          }
        }
      } })
    }
    const support = Math.floor(pz / SUPPORT)
    for (let i = support + 4; i >= Math.max(0, support); i--) {
      const z = i * SUPPORT + 7.5 - pz
      if (z < 1 || z > ZFAR) continue
      list.push({ z, f: () => {
        this.face(tx.torch!, -HW + 0.01, -HW + 0.7, 1.7, 2.4, z, null)
        this.face(tx.torch!, HW - 0.7, HW - 0.01, 1.7, 2.4, z, null)
      } })
    }
    list.push({ z: CAM_BACK, f: () => this.drawPlayer(g, pz, xp, hop) })
    list.sort((a, b) => b.z - a.z)
    for (const item of list) item.f()
    this.drawSparks(pz)
    this.speedLines(speedK, g.turbo > 0)
    ctx.putImageData(this.img, 0, 0)
    if (g.stagger > 0) {
      ctx.fillStyle = `rgba(255,30,10,${g.stagger / 90 * 0.2})`
      ctx.fillRect(0, 0, SW, SH)
    }
    if (g.turbo > 0) {
      const a = Math.min(1, g.turbo / 60) * 0.44
      const gradient = ctx.createRadialGradient(CX, CY, 60, CX, CY, 260)
      gradient.addColorStop(0, 'rgba(255,40,10,0)')
      gradient.addColorStop(1, `rgba(255,40,10,${a})`)
      ctx.fillStyle = gradient
      ctx.fillRect(0, 0, SW, SH)
    }
  }

  private spark(g: Loren, x: number, y: number, c: number, n: number, sp: number, life: number, s: number) {
    const w = g.pos / UNIT
    for (let i = 0; i < n; i++) {
      this.parts.push({ x: x + (Math.random() - 0.5) * 0.5, y: y + Math.random() * 0.3, w: w + Math.random() * 0.6, vx: (Math.random() - 0.5) * sp, vy: Math.random() * sp * 0.8, vw: Math.random() * sp, life, c, s: s * (0.6 + Math.random()), g: 12 })
    }
  }

  update(dt: number, g: Loren | null) {
    this.shake *= 0.002 ** dt
    if (this.shake < 0.2) this.shake = 0
    this.flash = Math.max(0, this.flash - dt * 2.6)
    this.red = Math.max(0, this.red - dt * 2)
    for (let i = this.parts.length - 1; i >= 0; i--) {
      const p = this.parts[i]!
      if ((p.life -= dt) <= 0) {
        this.parts.splice(i, 1)
        continue
      }
      p.vy -= p.g * dt
      p.x += p.vx * dt
      p.y += p.vy * dt
      p.w += p.vw * dt
      if (p.y < 0) {
        p.y = 0
        p.vy *= -0.4
      }
    }
    if (this.parts.length > 500) this.parts.splice(0, this.parts.length - 500)
    if (!g || g.phase !== 'run') return
    const x = LANES[g.lane]!
    const row = g.row(Math.floor(g.pos / UNIT / ROW_GAP))
    const side = () => x + (Math.random() < 0.5 ? -0.45 : 0.45)
    if ((row.pow[g.lane] || g.turbo > 0) && g.jumpT === 0 && Math.random() < 0.75) this.spark(g, side(), 0.05, 0xFF2A50FF, 1, 3, 0.3, 0.05)
    else if (g.speed > 60 && g.jumpT === 0 && Math.random() < 0.25) this.spark(g, side(), 0.05, 0xFF50C0FF, 1, 2.4, 0.24, 0.04)
  }

  /** Rollgeräusch und Schienenstöße; `active` ist falsch bei Pause oder im Sprung. */
  updateSound(g: Loren | null, active: boolean) {
    const rolling = !!g && active && g.phase === 'run' && g.jumpT === 0
    this.sound.roll(rolling, g?.speed ?? 0)
    if (rolling && g) {
      const beat = Math.floor(g.pos / UNIT / 1.5)
      if (beat !== this.clack) {
        this.clack = beat
        this.sound.tone(95, 0.03, 'square', 0.018)
      }
    }
  }

  private boom(g: Loren, k: number) {
    this.sound.noise(0.7 * k, 0.55, 620)
    this.sound.tone(80, 0.5 * k, 'sine', 0.34, 26)
    this.shake = Math.max(this.shake, 12 * k)
    this.flash = Math.max(this.flash, 0.5 * k)
    const x = LANES[g.lane]!
    this.spark(g, x, 0.8, 0xFF4AB3FF, 26, 7, 0.6, 0.1)
    this.spark(g, x, 0.8, 0xFF3A3A4A, 24, 4, 1, 0.15)
    this.spark(g, x, 0.8, 0xFF1C26E0, 12, 6, 0.7, 0.07)
  }

  onEvent(e: LorenEvent, g: Loren) {
    const s = this.sound
    switch (e.type) {
      case 'lever':
        s.noise(0.04, 0.1, 3000)
        s.tone(420, 0.05, 'square', 0.03)
        break
      case 'jump':
        s.noise(0.08, 0.12, 1800)
        s.tone(180, 0.16, 'triangle', 0.1, 360)
        break
      case 'land':
        s.noise(0.1, 0.2, 700)
        s.tone(90, 0.1, 'sine', 0.14)
        this.shake = Math.max(this.shake, 3)
        this.spark(g, LANES[g.lane]!, 0.05, 0xFF60C0FF, 8, 3, 0.3, 0.05)
        break
      case 'duck':
        s.tone(220, 0.1, 'triangle', 0.06, 120)
        break
      case 'item':
        s.tone(880, 0.09, 'square', 0.05)
        s.later(55, () => s.tone(1320, 0.1, 'square', 0.05))
        this.spark(g, LANES[g.lane]!, 0.9, 0xFF2020FF, 12, 3, 0.4, 0.05)
        break
      case 'dodge':
        s.tone(660, 0.06, 'triangle', 0.04)
        break
      case 'turbo':
        s.noise(0.5, 0.24, 2400)
        s.tone(110, 0.8, 'sawtooth', 0.16, 900)
        this.flash = 0.5
        this.shake = Math.max(this.shake, 7)
        break
      case 'smash':
        this.boom(g, 0.7)
        break
      case 'hit':
        this.boom(g, 1)
        this.red = 1
        break
      case 'crash':
        this.boom(g, 1.4)
        this.red = 1
        break
    }
  }
}
