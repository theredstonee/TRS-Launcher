// Szenen-Set „Redstone-Dungeon“: isometrischer Pixel-Dungeon in einem Kunst-Puffer (ganze Kunst-Pixel, scharf),
// Lichtkarte (multipliziert), selbstleuchtende Ebene, Himmel dahinter; danach auf Bildschirmgröße hochskaliert
// (Subpixel-Versatz für weiche Kamerafahrten) mit Glühen, Vignette, Körnung, Blitz und dem Logo in voller Auflösung.
// Jedes Bild ist eine reine Funktion der Zeit t – Zurückspulen, Standbilder und Hängenbleiben am Logo gehen ohne Verlauf.

import type { IntroDesign } from './designs'
import {
  blip,
  canvas,
  clamp,
  css,
  ctx2d,
  decay,
  easeIn,
  easeInOut,
  easeOut,
  glow,
  hash,
  hash2,
  hash3,
  hex,
  lerp,
  mix,
  range,
  rgba,
  smooth,
  type Cv,
  type Rgb,
} from './kit'
import {
  boxSprite,
  bricks,
  BZ,
  charSprite,
  cobble,
  door as doorTex,
  HH,
  HW,
  lamp as lampTex,
  pillar as pillarTex,
  pistonFace,
  pistonSide,
  planks,
  pumpkinFace,
  pumpkinTop,
  repeaterTop,
  tiles,
  topDecal,
  type BoxSprite,
  type Faces,
  type Tex,
} from './textures'
import { IntroStage, type ArtView, type IntroStageOptions } from './stage'
import { WORLD, wireHead, type IntroScript } from './timeline'
import { buildWorld, type Mat, type WorldLayout } from './world'

/** Sichtbare Höhe in Kunst-Pixeln bei Zoom 1. */
const VIEW_H = 270

interface Cam {
  x: number
  y: number
  zoom: number
}

type View = ArtView

interface Item {
  k: number
  z: number
  draw: () => void
}

interface StaticBlock {
  k: number
  z: number
  sx: number
  sy: number
  sprite: BoxSprite
}

interface Light {
  x: number
  y: number
  r: number
  color: string
  a: number
  /** Auch als Glühen auf dem Bildschirm (Bloom). */
  bloom?: number
}

/** Projektion Welt → Kunst-Pixel. */
export const projX = (x: number, y: number) => HW * (x - y)
export const projY = (x: number, y: number, z: number) => HH * (x + y) - BZ * z

export class DungeonScene extends IntroStage {
  private readonly world: WorldLayout
  private readonly statics: StaticBlock[] = []
  private readonly atlas = new Map<string, BoxSprite>()
  private readonly decals = new Map<string, BoxSprite>()
  private light: Cv = canvas(4, 4)
  private lg: CanvasRenderingContext2D = ctx2d(this.light)
  private sky: Cv = canvas(4, 4)
  private sg: CanvasRenderingContext2D = ctx2d(this.sky)
  private readonly sprites: Record<string, Cv>

  constructor(
    cv: HTMLCanvasElement,
    readonly design: IntroDesign,
    readonly script: IntroScript,
    options: IntroStageOptions = {},
  ) {
    super(cv, design.palette, script.cues, options)
    this.world = buildWorld(design.props)
    for (const b of this.world.blocks) {
      const sprite = this.block(b.mat, b.v, !!b.snowy)
      this.statics.push({ k: b.x + b.y, z: b.z, sx: projX(b.x, b.y) + sprite.ox, sy: projY(b.x, b.y, b.z) + sprite.oy, sprite })
    }
    this.statics.sort((a, b) => a.k - b.k || a.z - b.z)
    this.sprites = this.makeSprites()
  }

  protected override onArtResized(w: number, h: number) {
    this.light = canvas(w, h)
    this.lg = ctx2d(this.light)
    this.sky = canvas(w, h)
    this.sg = ctx2d(this.sky)
  }

  // ───────────────────────── Sprites ─────────────────────────

  private faces(mat: Mat | 'pumpkinLit' | 'pistonHead' | 'repeater' | 'repeaterOn' | 'arm', v: number, snowy: boolean): Faces {
    const p = this.p
    const s = 1000 + v * 101
    let f: Faces
    switch (mat) {
      case 'tile':
        f = { top: tiles(p, s), left: bricks(p, s + 1), right: bricks(p, s + 2) }
        break
      case 'tileMoss':
        f = { top: tiles(p, s, 0.22), left: bricks(p, s + 1), right: bricks(p, s + 2) }
        break
      case 'cobble':
        f = { top: cobble(p, s), left: cobble(p, s + 1), right: cobble(p, s + 2) }
        break
      case 'brick':
        f = { top: tiles(p, s + 3), left: bricks(p, s + 4), right: bricks(p, s + 5) }
        break
      case 'brickMoss':
        f = { top: tiles(p, s + 3), left: bricks(p, s + 4, 0.3), right: bricks(p, s + 5, 0.3) }
        break
      case 'pillar':
        f = { top: tiles(p, s + 6), left: pillarTex(p, s + 7), right: pillarTex(p, s + 8) }
        break
      case 'piston':
        f = { top: pistonSide(p, s), left: pistonSide(p, s + 1), right: pistonFace(p, s + 2) }
        break
      case 'pistonHead':
      case 'arm':
        f = { top: planks(p, s), left: planks(p, s + 1), right: pistonFace(p, s + 2) }
        break
      case 'lampOff':
        f = { top: lampTex(p, false, s), left: lampTex(p, false, s + 1), right: lampTex(p, false, s + 2) }
        break
      case 'lampOn':
        f = { top: lampTex(p, true, s), left: lampTex(p, true, s + 1), right: lampTex(p, true, s + 2), emissive: true }
        break
      case 'door':
        f = { top: doorTex(p, s), left: doorTex(p, s + 1), right: doorTex(p, s + 2) }
        break
      case 'pumpkin':
        f = { top: pumpkinTop(), left: pumpkinFace(false), right: pumpkinFace(false) }
        break
      case 'pumpkinLit':
        f = { top: pumpkinTop(), left: pumpkinFace(true), right: pumpkinFace(true) }
        break
      case 'repeater':
      case 'repeaterOn': {
        const top = repeaterTop(p, s)
        if (mat === 'repeaterOn') {
          const on = hex(p.wireOn)
          const off = hex(p.wireOff)
          for (let i = 0; i < 256; i++) if (top[i]![0] === off[0] && top[i]![1] === off[1] && top[i]![2] === off[2]) top[i] = on
        }
        f = { top, left: tiles(p, s + 1), right: tiles(p, s + 2) }
        break
      }
    }
    if (snowy && p.frost) {
      const fr = hex(p.frost)
      const frostTop: Tex = f.top.map((c, i) => (hash(i * 7 + v * 13) > 0.45 ? mix(c, fr, 0.62) : mix(c, fr, 0.22)))
      const frostSide = (t: Tex): Tex => t.map((c, i) => (i < 32 || (i < 48 && hash(i + v) > 0.5) ? mix(c, fr, 0.8) : c))
      f = { ...f, top: frostTop, left: frostSide(f.left), right: frostSide(f.right) }
    }
    return f
  }

  private block(mat: Mat | 'pumpkinLit' | 'pistonHead' | 'repeater' | 'repeaterOn' | 'arm', v: number, snowy = false, w = 1, dy = 1, h = 1): BoxSprite {
    const key = `${mat}|${v}|${snowy ? 1 : 0}|${w}|${dy}|${h}`
    let s = this.atlas.get(key)
    if (!s) {
      s = boxSprite(this.faces(mat, v, snowy), w, dy, h)
      this.atlas.set(key, s)
    }
    return s
  }

  private decal(kind: 'off' | 'on' | 'hot', v: number): BoxSprite {
    const key = `${kind}|${v}`
    let s = this.decals.get(key)
    if (!s) {
      const p = this.p
      const base = kind === 'off' ? p.wireOff : kind === 'on' ? p.wireOn : p.wireHot
      const hi = kind === 'off' ? css(mix(hex(p.wireOff), hex(p.wireOn), 0.25)) : kind === 'on' ? css(mix(hex(p.wireOn), [255, 255, 255], 0.35)) : '#ffffff'
      s = topDecal((u, vv) => {
        const dx = Math.abs(u - 7.5)
        const n = hash3(u, vv, v + 3)
        if (dx < 1.6) return n > 0.75 ? hi : base
        if (dx < 3 && n > 0.72) return base
        return null
      })
      this.decals.set(key, s)
    }
    return s
  }

  private makeSprites(): Record<string, Cv> {
    const p = this.p
    const k = '#0b0a10'
    return {
      creeper: charSprite(
        [
          '..GGgGGGgG..',
          '..GgGGGgGG..',
          '..GkkGGkkG..',
          '..GkkgGkkG..',
          '..GGgkkGgG..',
          '..GGkkkkGG..',
          '..GGkGGkGG..',
          '..gGGGgGGG..',
          '...GgGGgG...',
          '...GGGGGG...',
          '...gGGgGG...',
          '...GGGGgG...',
          '...GgGGGG...',
          '...GGgGGG...',
          '...GGGGgG...',
          '..GgGG.GgG..',
          '..GGGg.GGG..',
          '..gGGG.gGG..',
        ],
        { G: '#3f7a34', g: '#2a5624', k },
      ),
      creeperWhite: charSprite(
        [
          '..WWWWWWWW..', '..WWWWWWWW..', '..WWWWWWWW..', '..WWWWWWWW..', '..WWWWWWWW..', '..WWWWWWWW..',
          '..WWWWWWWW..', '..WWWWWWWW..', '...WWWWWW...', '...WWWWWW...', '...WWWWWW...', '...WWWWWW...',
          '...WWWWWW...', '...WWWWWW...', '...WWWWWW...', '..WWWW.WWW..', '..WWWW.WWW..', '..WWWW.WWW..',
        ],
        { W: '#ffffff' },
      ),
      batUp: charSprite(['k.......k', 'kk.....kk', 'kkk.v.kkk', '.kkkkkkk.', '...kkk...'], { k: '#1a1426', v: '#c9b2ff' }),
      batDown: charSprite(['...k.k...', '..kvkvk..', '.kkkkkkk.', 'kk.kkk.kk', 'k.......k'], { k: '#1a1426', v: '#c9b2ff' }),
      penguin: charSprite(
        ['..RRW..', '.RRRR..', '.WWWWW.', '.KKKKK.', '.KeKeK.', '.KKOKK.', 'KKwwwKK', 'KKwwwKK', '.KwwwK.', '.KwwwK.', '.O...O.'],
        { R: '#d23a3a', W: '#f4f4f4', K: '#1c2230', e: '#f4f4f4', O: '#ffa230', w: '#f4f4f4' },
      ),
      penguinWave: charSprite(
        ['..RRW..', '.RRRR..', '.WWWWW.', '.KKKKKK', '.KeKeKK', '.KKOKK.', 'KKwwwK.', 'KKwwwK.', '.KwwwK.', '.KwwwK.', '.O...O.'],
        { R: '#d23a3a', W: '#f4f4f4', K: '#1c2230', e: '#f4f4f4', O: '#ffa230', w: '#f4f4f4' },
      ),
      banner: charSprite(
        [
          'wwwwwwwwww', '.RRRRRRRR.', '.RRRRRRRR.', '.RRrrrrRR.', '.RRrkkrRR.', '.RRrkkrRR.', '.RRrrrrRR.', '.RRRRRRRR.',
          '.RRRrrRRR.', '.RRrRRrRR.', '.RRRRRRRR.', '.RRRRRRRR.', '.RRR..RRR.', '.RR....RR.',
        ],
        { w: p.wood[1], R: '#8f1d16', r: '#d8261a', k: '#ff6a4d' },
      ),
      web: charSprite(
        ['w...w...w', '.w..w..w.', '..w.w.w..', 'wwwwwwwww', '..w.w.w..', '.w..w..w.', 'w...w...w'],
        { w: '#d8d4e6' },
      ),
    }
  }

  // ───────────────────────── Kamera ─────────────────────────

  private camera(t: number): { cam: Cam; shake: number } {
    const s = this.script
    const c = s.cues
    const base = s.length === 'short' ? 1.5 : s.length === 'medium' ? 1.42 : 1.35
    const lead = 3.6
    const follow = (tt: number) => {
      let acc = 0
      for (let i = 0; i < 8; i++) acc += wireHead(s.wire, tt - (i / 7) * 0.4)
      return acc / 8 + lead
    }
    let focus = follow(t)
    // Vor dem Impuls ein ruhiges Gleiten Richtung Startpunkt
    if (t < c.wireStart) focus = c.from + lead - (1 - easeOut(range(t, 0, c.wireStart))) * 2.2
    focus = Math.min(focus, WORLD.lamp - 0.5)
    // Kurz vor der Lampe: ganz auf die Lampe
    const settle = smooth(range(t, c.hush - 0.6, c.hush + 0.1))
    focus = lerp(focus, WORLD.lamp - 0.3, settle)
    let zoom = base
    if (s.length === 'medium') zoom = base * lerp(0.86, 1, easeOut(range(t, 0, 1.6)))
    if (s.length === 'short') zoom = base * lerp(0.92, 1, easeOut(range(t, 0, 0.9)))
    let lift = 0
    if (c.descent) {
      const [d0, d1] = c.descent
      const k = easeInOut(range(t, d0, d1))
      focus = lerp(26, focus, k)
      zoom = lerp(0.42, base, k * k * (3 - 2 * k))
      lift = (1 - easeOut(range(t, d0, d1))) * 120
    }
    // Vorstoß zur Lampe + Schlag
    zoom *= 1 + 0.22 * smooth(range(t, c.hush - 0.5, c.impact)) + 0.14 * decay(t - c.impact, 4) * (t >= c.impact ? 1 : 0)
    const tx = 1.6
    const tz = 0.8
    let x = projX(tx, -focus)
    let y = projY(tx, -focus, tz) - lift
    x += Math.sin(t * 0.7) * 2.5
    y += Math.cos(t * 0.53) * 1.5
    let shake = 0
    for (const d of s.doors) shake += 2.6 * decay(t - d.t, 9) * (t >= d.t ? 1 : 0)
    if (t >= c.impact) shake += 4.5 * decay(t - c.impact, 5)
    return { cam: { x, y, zoom }, shake }
  }

  private view(cam: Cam, shake: number, t: number): View {
    const sx = shake ? (Math.sin(t * 71.3) + Math.sin(t * 43.1) * 0.6) * shake * 0.6 : 0
    const sy = shake ? (Math.cos(t * 63.7) + Math.sin(t * 31.3) * 0.6) * shake * 0.6 : 0
    return this.beginArt(VIEW_H / cam.zoom, cam.x + sx, cam.y + sy)
  }

  // ───────────────────────── Bild ─────────────────────────

  protected renderScene(t: number) {
    const s = this.script
    const c = s.cues
    const { cam, shake } = this.camera(t)
    const v = this.view(cam, shake, t)
    const lights: Light[] = []

    // Szene nur bis kurz nach dem Schlag zeichnen – danach steht das Logo vor Schwarz.
    const sceneGone = t > c.logo + 0.3
    if (!sceneGone) {
      this.drawScene(t, v, lights)
      this.applyLight(v, lights, t)
      this.drawSky(t, v)
      this.drawEmissive(t, v)
      this.drawAtmosphere(t, v)
      this.blitArt(v)
      this.drawBloom(t, v, lights)
      this.drawScreenFx(t, v)
    }
    this.drawPost(this.p.cold, this.p.abyss)
    // Lampe → Logo: die Texel der Lampen-Oberseite fliegen ins Logo
    const lampTop = (i: number, j: number) => {
      const wx = WORLD.wireCol + (i + 0.5) / 16
      const wy = -WORLD.lamp + (j + 0.5) / 16
      const pt = this.toScreen(v, projX(wx, wy), projY(wx, wy, 1))
      return { x: pt.x, y: pt.y, s: v.S * 2.2 }
    }
    this.drawFinale(t, lampTop, this.p.lampOn[1], this.p.flash)
  }

  private inView(v: View, x: number, y: number, w: number, h: number) {
    return x + w >= v.ox && y + h >= v.oy && x <= v.ox + v.bw && y <= v.oy + v.bh
  }

  private blit(v: View, img: CanvasImageSource & { width: number; height: number }, x: number, y: number, alpha = 1) {
    const ag = this.ag
    if (alpha <= 0.01) return
    if (alpha < 1) ag.globalAlpha = alpha
    ag.drawImage(img, Math.round(x - v.ox), Math.round(y - v.oy))
    if (alpha < 1) ag.globalAlpha = 1
  }

  private drawScene(t: number, v: View, lights: Light[]) {
    const s = this.script
    const c = s.cues
    const p = this.p
    const props = this.design.props
    const ag = this.ag
    const items: Item[] = []
    const head = t >= c.wireStart ? wireHead(s.wire, t) : -1e9
    const add = (k: number, z: number, draw: () => void) => items.push({ k, z, draw })

    // Leitung (Abziehbilder auf dem Boden)
    for (const d of this.world.wire) {
      const x = projX(WORLD.wireCol, -d)
      const y = projY(WORLD.wireCol, -d, 0)
      if (!this.inView(v, x - HW, y, HW * 2, HH * 2)) continue
      const lit = d >= c.from && d <= head
      const hot = lit && head - d < 1.2
      const vv = d % 3
      add(WORLD.wireCol - d, -0.5, () => this.blit(v, this.decal(hot ? 'hot' : lit ? 'on' : 'off', vv).cv, x - HW, y))
      if (lit) lights.push({ x: x, y: y + HH, r: hot ? 70 : 30, color: hot ? p.wireHot : p.wireLight, a: hot ? 0.95 : 0.42 })
    }
    // Startpunkt: kleine Redstone-Fackel
    {
      const d = c.from - 1
      const x = projX(WORLD.wireCol + 0.5, -d - 0.5)
      const y = projY(WORLD.wireCol + 0.5, -d - 0.5, 0)
      const on = t >= c.wireStart
      add(WORLD.wireCol - d + 0.6, 0.2, () => {
        ag.fillStyle = css(hex(p.wood[0]))
        ag.fillRect(Math.round(x - v.ox) - 1, Math.round(y - v.oy) - 9, 2, 9)
        ag.fillStyle = on ? p.wireOn : p.wireOff
        ag.fillRect(Math.round(x - v.ox) - 1, Math.round(y - v.oy) - 11, 2, 3)
      })
      if (on) lights.push({ x, y: y - 10, r: 40, color: p.wireLight, a: 0.7, bloom: 0.5 })
    }
    // Verstärker
    for (const r of s.repeaters.length ? WORLD.repeaters.map((d) => s.repeaters.find((q) => q.d === d) ?? { d, t: Infinity }) : WORLD.repeaters.map((d) => ({ d, t: Infinity }))) {
      const on = t >= r.t
      const spr = this.block(on ? 'repeaterOn' : 'repeater', 0, false, 1, 1, 0.125)
      const bx = projX(WORLD.wireCol, -r.d)
      const by = projY(WORLD.wireCol, -r.d, 0)
      if (!this.inView(v, bx - 40, by - 30, 80, 60)) continue
      add(WORLD.wireCol - r.d, -0.4, () => {
        this.blit(v, spr.cv, bx + spr.ox, by + spr.oy)
        // zwei Fackel-Stummel
        for (const off of [0.28, 0.72]) {
          const tx = projX(WORLD.wireCol + 0.5, -r.d + off) - v.ox
          const ty = projY(WORLD.wireCol + 0.5, -r.d + off, 0.125) - v.oy
          ag.fillStyle = css(hex(p.stone[3]))
          ag.fillRect(Math.round(tx) - 1, Math.round(ty) - 4, 2, 4)
          ag.fillStyle = on ? p.wireOn : p.wireOff
          ag.fillRect(Math.round(tx) - 1, Math.round(ty) - 6, 2, 2)
        }
      })
      if (on) lights.push({ x: bx, y: by + 2, r: 46, color: p.wireLight, a: 0.6 + (this.calm ? 0 : 0.5 * decay(t - r.t, 6)), bloom: 0.35 })
    }
    // Lichtquellen an der Wand
    for (const tq of s.torches) {
      const lit = tq.t < 0 || t >= tq.t
      const ign = tq.t < 0 ? 1 : easeOut(range(t, tq.t, tq.t + 0.18))
      const fl = this.flick(0.85 + 0.15 * Math.sin(t * 13 + tq.d) * Math.sin(t * 7.3 + tq.d * 2))
      if (props.light === 'torch') {
        const x = projX(0, -tq.d + 0.5)
        const y = projY(0, -tq.d + 0.5, 2.1)
        if (!this.inView(v, x - 60, y - 60, 120, 120)) continue
        add(-tq.d - 0.5, 2, () => this.drawTorch(v, x, y, lit ? ign : 0, t, tq.d))
        if (lit) lights.push({ x, y: y - 9, r: 104 * ign * fl, color: p.light, a: 0.95 * ign, bloom: 0.55 })
      } else if (props.light === 'iceLantern') {
        const x = projX(0.25, -tq.d + 0.5)
        const y = projY(0.25, -tq.d + 0.5, 2.6)
        if (!this.inView(v, x - 60, y - 60, 120, 120)) continue
        add(-tq.d - 0.3, 2, () => this.drawLantern(v, x, y, lit ? ign : 0, t, tq.d))
        if (lit) lights.push({ x, y: y + 6, r: 110 * ign * (0.95 + 0.05 * fl), color: p.light, a: 0.9 * ign, bloom: 0.5 })
      } else {
        const x = projX(1, -tq.d + 0.5)
        const y = projY(1, -tq.d + 0.5, 0.45)
        if (!this.inView(v, x - 60, y - 60, 120, 120)) continue
        if (lit) {
          const spr = this.block('pumpkinLit', 0)
          const bx = projX(0, -tq.d) + spr.ox
          const by = projY(0, -tq.d, 0) + spr.oy
          add(-tq.d, 0.01, () => this.blit(v, spr.cv, bx, by, ign))
          lights.push({ x, y, r: 110 * ign * fl, color: p.light, a: 0.95 * ign, bloom: 0.55 })
        }
      }
    }
    // Banner, Spinnweben, Fenster
    for (const d of this.world.banners) {
      const x = projX(0, -d + 0.8)
      const y = projY(0, -d + 0.8, 4.2)
      if (!this.inView(v, x - 30, y - 10, 60, 60)) continue
      add(-d - 0.45, 3, () => this.wallSprite(v, this.sprites.banner!, x, y, t, d))
    }
    for (const w of this.world.cobwebs) {
      const x = projX(0, -w.d + 0.9)
      const y = projY(0, -w.d + 0.9, w.z + 1)
      if (!this.inView(v, x - 20, y - 20, 40, 40)) continue
      add(-w.d - 0.45, 4, () => this.blit(v, this.sprites.web!, x, y, 0.55))
    }
    for (const d of this.world.windows) {
      add(-d - 0.3, 2, () => this.drawBars(v, d))
      const cold = this.design.props.sky === 'moon' ? 0.75 : 0.45
      lights.push({ x: projX(2.6, -d - 0.5), y: projY(2.6, -d - 0.5, 0), r: 75, color: p.cold, a: cold })
      lights.push({ x: projX(0.3, -d - 0.5), y: projY(0.3, -d - 0.5, 3), r: 45, color: p.cold, a: cold * 0.8 })
    }
    // Türen + Kolben
    for (const dr of s.doors.length ? WORLD.doors.map((d) => s.doors.find((q) => q.d === d) ?? { d, t: c.from >= d ? -10 : Infinity }) : WORLD.doors.map((d) => ({ d, t: c.from >= d ? -10 : Infinity }))) {
      const tau = t - dr.t
      for (let z = 0; z < 3; z++) {
        for (let col = 0; col < WORLD.width; col++) {
          let x = col
          let zz = z
          if (tau >= 0) {
            const push = easeOut(range(tau, 0, 0.07))
            const fly = Math.max(0, tau - 0.07)
            const vx = 9 + hash2(col, z + dr.d) * 7 + (WORLD.width - col) * 0.6
            const vz = 2 + hash2(col + 9, z) * 3
            x = col + push + vx * fly
            zz = z + vz * fly - 16 * fly * fly
            if (zz < -9 || x > 22) continue
          }
          const spr = this.block('door', (col + z) % 3)
          const bx = projX(x, -dr.d) + spr.ox
          const by = projY(x, -dr.d, zz) + spr.oy
          if (!this.inView(v, bx, by, 32, 32)) continue
          const fade = tau > 0.35 ? clamp(1 - (tau - 0.35) / 0.6) : 1
          add(x - dr.d, zz, () => this.blit(v, spr.cv, bx, by, fade))
        }
      }
      // Kolbenköpfe fahren aus
      if (tau >= 0) {
        const ext = easeOut(range(tau, 0, 0.06))
        for (let z = 0; z < 3; z++) {
          const hx = -0.25 + ext
          const spr = this.block('pistonHead', 0, false, 0.25, 1, 1)
          const bx = projX(hx, -dr.d) + spr.ox
          const by = projY(hx, -dr.d, z) + spr.oy
          if (!this.inView(v, bx, by, 40, 40)) continue
          const len = Math.max(1, Math.round(ext * 4)) / 4
          const arm = this.block('arm', 0, false, len, 0.25, 0.25)
          const ax = projX(0, -dr.d + 0.375) + arm.ox
          const ay = projY(0, -dr.d + 0.375, z + 0.375) + arm.oy
          add(hx - dr.d - 0.01, z, () => {
            if (ext > 0.3) this.blit(v, arm.cv, ax, ay)
            this.blit(v, spr.cv, bx, by)
          })
        }
        lights.push({ x: projX(2.5, -dr.d), y: projY(2.5, -dr.d, 1), r: 90, color: p.wireLight, a: this.calm ? 0 : 0.5 * decay(tau, 5) })
      }
    }
    // Auftritt in der Nische
    if (s.cameo !== null) this.cameo(t, v, s.cameo, add, lights)
    // Lampe
    {
      // Vor dem Schlag flackert die Lampe – ruhig: sie glimmt stetig an
      const flick = t >= c.hush && t < c.impact ? (this.calm ? 0 : Math.sin((t - c.hush) * 55) > 0.1 ? 1 : 0) : 0
      const glim = this.calm && t >= c.hush && t < c.impact ? smooth(range(t, c.hush, c.impact)) : 0
      const on = t >= c.impact
      const spr = this.block(on || flick ? 'lampOn' : 'lampOff', 0)
      const bx = projX(WORLD.wireCol, -WORLD.lamp) + spr.ox
      const by = projY(WORLD.wireCol, -WORLD.lamp, 0) + spr.oy
      add(WORLD.wireCol - WORLD.lamp, 0, () => this.blit(v, spr.cv, bx, by))
      const lx = projX(WORLD.wireCol + 0.5, -WORLD.lamp + 0.5)
      const ly = projY(WORLD.wireCol + 0.5, -WORLD.lamp + 0.5, 0.6)
      if (flick) lights.push({ x: lx, y: ly, r: 90, color: p.lampOn[0], a: 0.7, bloom: 0.5 })
      if (glim > 0) lights.push({ x: lx, y: ly, r: 90, color: p.lampOn[0], a: 0.6 * glim, bloom: 0.4 * glim })
      if (on) lights.push({ x: lx, y: ly, r: 140 + 600 * easeOut(range(t, c.impact, c.impact + (this.calm ? 1.1 : 0.5))), color: p.lampOn[1], a: this.calm ? 0.9 : 1.3, bloom: this.calm ? 0.7 : 1.2 })
    }

    // Abgrund-Leuchten von unten (Licht an der Vorderkante)
    const fd = -((v.cy + 0) / HH - 0) // grob: Gangabstand der Kamera
    void fd

    // Statische Blöcke (vorsortiert) und bewegte Teile zusammenführen
    items.sort((a, b) => a.k - b.k || a.z - b.z)
    let j = 0
    for (const b of this.statics) {
      if (b.sx + b.sprite.cv.width < v.ox || b.sx > v.ox + v.bw || b.sy + b.sprite.cv.height < v.oy || b.sy > v.oy + v.bh) continue
      while (j < items.length && (items[j]!.k < b.k || (items[j]!.k === b.k && items[j]!.z < b.z))) items[j++]!.draw()
      ag.drawImage(b.sprite.cv, b.sx - v.ox, b.sy - v.oy)
    }
    while (j < items.length) items[j++]!.draw()
    // Pfosten der Kürbisse/Blöcke sind statisch; Licht der Leere
    this.lightsAmbient(t, v, lights)
  }

  private lightsAmbient(t: number, v: View, lights: Light[]) {
    // Weiches Grundlicht entlang der Gangmitte, damit die Formen auch zwischen den Fackeln lesbar bleiben
    const p = this.p
    const midX = v.ox + v.bw / 2
    const midY = v.oy + v.bh / 2
    lights.push({ x: midX, y: midY, r: Math.max(v.bw, v.bh) * 0.55, color: p.cold, a: 0.12 + 0.03 * Math.sin(t) })
  }

  private cameo(t: number, v: View, tc: number, add: (k: number, z: number, d: () => void) => void, lights: Light[]) {
    const kind = this.design.props.cameo
    const d = WORLD.alcove + 1
    const p = this.p
    if (kind === 'creeper') {
      const back = easeIn(range(t, tc + 0.55, tc + 1.1))
      const alpha = (1 - back) * smooth(range(t, tc - 0.9, tc - 0.3))
      if (alpha <= 0.02) return
      const spr = this.sprites.creeper!
      const x = projX(-0.5 - back * 0.4, -d) - spr.width / 2
      const y = projY(-0.5 - back * 0.4, -d, 0) - spr.height * 2 + 4
      const white = !this.calm && t > tc + 0.15 && t < tc + 0.55 && Math.sin((t - tc) * 40) > 0
      add(-0.5 - d + 0.3, 0.5, () => {
        const ag = this.ag
        const iw = spr.width * 2
        const ih = spr.height * 2
        ag.globalAlpha = alpha
        ag.drawImage(white ? this.sprites.creeperWhite! : spr, Math.round(x - v.ox - spr.width / 2), Math.round(y - v.oy), iw, ih)
        ag.globalAlpha = 1
      })
      lights.push({ x: projX(0.4, -d), y: projY(0.4, -d, 1), r: 60, color: p.wireLight, a: 0.65 * blip(t - tc + 0.35, 1.3) })
      if (white) lights.push({ x: projX(-0.3, -d), y: projY(-0.3, -d, 1), r: 50, color: '#ffffff', a: 0.6 })
    } else if (kind === 'penguin') {
      const out = easeInOut(range(t, tc - 0.35, tc + 0.45))
      const wave = t > tc + 0.45 && Math.floor((t - tc) * 6) % 2 === 0
      const step = Math.floor(t * 9) % 2
      const spr = wave ? this.sprites.penguinWave! : this.sprites.penguin!
      const wx = -0.5 + out * 1.3
      const x = projX(wx, -d + 0.2)
      const y = projY(wx, -d + 0.2, 0)
      add(wx - d + 0.4, 0.5, () => {
        const ag = this.ag
        ag.drawImage(spr, Math.round(x - v.ox - spr.width), Math.round(y - v.oy - spr.height * 2 - (out < 1 ? step : 0)), spr.width * 2, spr.height * 2)
      })
      lights.push({ x, y: y - 10, r: 55, color: p.light, a: 0.5 * out })
    } else {
      // Fledermäuse: Licht flackert in der Nische auf (Schwarm kommt im Bildschirm-Durchgang)
      lights.push({ x: projX(0, -d), y: projY(0, -d, 1), r: 60, color: p.wireLight, a: 0.5 * blip(t - tc + 0.2, 0.9) })
    }
  }

  private drawTorch(v: View, x: number, y: number, ign: number, t: number, seed: number) {
    const ag = this.ag
    const p = this.p
    const X = Math.round(x - v.ox)
    const Y = Math.round(y - v.oy)
    // Halter + Stiel (leicht schräg von der Wand)
    ag.fillStyle = css(hex(p.wood[0]))
    ag.fillRect(X - 1, Y - 6, 2, 9)
    ag.fillStyle = css(hex(p.wood[1]))
    ag.fillRect(X - 1, Y - 6, 1, 9)
    ag.fillStyle = css(hex(p.iron))
    ag.fillRect(X - 2, Y + 2, 4, 1)
    ag.fillStyle = '#2a1a10'
    ag.fillRect(X - 1, Y - 8, 2, 2)
    if (ign <= 0) return
    this.flame(X, Y - 8, ign, t, seed)
  }

  private flame(X: number, Y: number, ign: number, t: number, seed: number) {
    const ag = this.ag
    const p = this.p
    const f = Math.floor(t * (this.calm ? 3 : 12) + seed * 3) % 4
    const h = Math.round((5 + (f % 2)) * ign)
    const sway = f === 3 ? 1 : f === 1 ? -1 : 0
    ag.fillStyle = p.flame[2]
    ag.fillRect(X - 2, Y - h + 1, 4, h)
    ag.fillStyle = p.flame[1]
    ag.fillRect(X - 1 + (sway > 0 ? 1 : 0), Y - h, 2, h)
    ag.fillStyle = p.flame[0]
    ag.fillRect(X - 1, Y - Math.max(1, h - 3), 2, Math.max(1, h - 3))
    if (h > 4) {
      ag.fillStyle = p.flame[2]
      ag.fillRect(X + sway, Y - h - 1, 1, 1)
    }
  }

  private drawLantern(v: View, x: number, y: number, ign: number, t: number, seed: number) {
    const ag = this.ag
    const p = this.p
    const X = Math.round(x - v.ox)
    const Y = Math.round(y - v.oy)
    ag.fillStyle = css(hex(p.iron), 0.8)
    for (let i = 0; i < 6; i += 2) ag.fillRect(X, Y - 10 + i, 1, 1)
    ag.fillStyle = css(scale3(hex(p.iron), 0.55))
    ag.fillRect(X - 3, Y - 4, 7, 2)
    ag.fillRect(X - 3, Y + 7, 7, 2)
    ag.fillStyle = ign > 0 ? css(mix(hex(p.flame[2]), hex(p.flame[0]), 0.4 + (this.calm ? 0 : 0.2 * Math.sin(t * 9 + seed)))) : '#203048'
    ag.fillRect(X - 2, Y - 2, 5, 9)
    if (ign > 0) {
      ag.fillStyle = p.flame[0]
      ag.fillRect(X, Y + 1, 1, 3)
    }
    ag.fillStyle = css(hex(p.iron))
    ag.fillRect(X - 3, Y - 2, 1, 9)
    ag.fillRect(X + 3, Y - 2, 1, 9)
  }

  /** Bild schräg auf der Rückwand (Spalten treppenförmig wie die Wandfläche). */
  private wallSprite(v: View, img: Cv, x: number, y: number, t: number, seed: number) {
    const ag = this.ag
    const X = Math.round(x - v.ox)
    const Y = Math.round(y - v.oy)
    const sway = Math.sin(t * 1.6 + seed) * 0.6
    for (let i = 0; i < img.width; i++) {
      const dy = -Math.floor(i / 2)
      const wob = Math.round(sway * (i / img.width))
      ag.drawImage(img, i, 0, 1, img.height, X + i, Y + dy + wob, 1, img.height)
    }
  }

  private drawBars(v: View, d: number) {
    const ag = this.ag
    const iron = css(scale3(hex(this.p.iron), 0.5))
    const hi = css(scale3(hex(this.p.iron), 0.8))
    // Fensteröffnung: Rückwand-Ebene x = 0, d … d+2, z 2 … 4
    for (let i = 1; i < 8; i++) {
      const yy = -d - (i / 8) * 2 + 1
      const x0 = projX(0, yy) - v.ox
      const y0 = projY(0, yy, 4) - v.oy
      ag.fillStyle = i % 2 ? iron : hi
      ag.fillRect(Math.round(x0), Math.round(y0), 1, BZ * 2)
    }
    for (const z of [2.5, 3.0, 3.5]) {
      for (let i = 0; i < 32; i++) {
        const yy = -d + 1 - (i / 32) * 2
        ag.fillStyle = iron
        ag.fillRect(Math.round(projX(0, yy) - v.ox), Math.round(projY(0, yy, z) - v.oy), 1, 1)
      }
    }
  }

  // ───────────────────────── Licht ─────────────────────────

  private applyLight(v: View, lights: Light[], t: number) {
    const lg = this.lg
    const p = this.p
    lg.setTransform(1, 0, 0, 1, 0, 0)
    lg.globalCompositeOperation = 'source-over'
    lg.globalAlpha = 1
    lg.fillStyle = p.ambient
    lg.fillRect(0, 0, v.bw, v.bh)
    // Kaltes Licht oben, warmes von unten (Abgrund)
    const grd = lg.createLinearGradient(0, 0, 0, v.bh)
    grd.addColorStop(0, rgba(p.cold, 0.1))
    grd.addColorStop(1, rgba(p.abyss, 0.12))
    lg.globalCompositeOperation = 'lighter'
    lg.fillStyle = grd
    lg.fillRect(0, 0, v.bw, v.bh)
    for (const l of lights) {
      if (l.a <= 0.01) continue
      const x = l.x - v.ox
      const y = l.y - v.oy
      if (x + l.r < 0 || y + l.r < 0 || x - l.r > v.bw || y - l.r > v.bh) continue
      // Mehrstufiger Abfall (gestuft wie Minecraft-Licht, aber weich genug)
      const c = hex(l.color)
      const g2 = lg.createRadialGradient(x, y, 0, x, y, l.r)
      g2.addColorStop(0, css(c, Math.min(1, l.a)))
      g2.addColorStop(0.25, css(c, Math.min(1, l.a * 0.7)))
      g2.addColorStop(0.6, css(c, l.a * 0.22))
      g2.addColorStop(1, css(c, 0))
      lg.fillStyle = g2
      lg.fillRect(x - l.r, y - l.r, l.r * 2, l.r * 2)
      if (l.a > 1) {
        lg.fillStyle = g2
        lg.fillRect(x - l.r, y - l.r, l.r * 2, l.r * 2)
      }
    }
    void t
    // Licht nur dort, wo Szene ist
    lg.globalCompositeOperation = 'destination-in'
    lg.drawImage(this.art, 0, 0)
    const ag = this.ag
    ag.globalCompositeOperation = 'multiply'
    ag.drawImage(this.light, 0, 0, v.bw, v.bh, 0, 0, v.bw, v.bh)
    ag.globalCompositeOperation = 'source-over'
  }

  // ───────────────────────── Himmel ─────────────────────────

  private drawSky(t: number, v: View) {
    const sg = this.sg
    const p = this.p
    const props = this.design.props
    sg.setTransform(1, 0, 0, 1, 0, 0)
    sg.globalCompositeOperation = 'source-over'
    sg.globalAlpha = 1
    const grd = sg.createLinearGradient(0, 0, 0, v.bh)
    grd.addColorStop(0, p.sky[0])
    grd.addColorStop(0.55, p.sky[1])
    grd.addColorStop(1, p.void)
    sg.fillStyle = grd
    sg.fillRect(0, 0, v.bw, v.bh)
    // Abgrund-Leuchten unter dem Diorama (vor der Vorderkante)
    const focusD = (v.cx / HW - (v.cy) / HH) / -2
    const ax = projX(WORLD.width + 4, -focusD) - v.ox
    const ay = projY(WORLD.width + 4, -focusD, -10) - v.oy
    sg.globalCompositeOperation = 'lighter'
    const ar = Math.max(v.bw, v.bh) * 0.7
    const ag2 = sg.createRadialGradient(ax, ay, 0, ax, ay, ar)
    ag2.addColorStop(0, rgba(p.abyss, 0.32 + 0.05 * Math.sin(t * 2)))
    ag2.addColorStop(0.5, rgba(p.abyss, 0.08))
    ag2.addColorStop(1, rgba(p.abyss, 0))
    sg.fillStyle = ag2
    sg.fillRect(0, 0, v.bw, v.bh)
    // Sterne (Nacht) bzw. ferne Glut (Höhle) mit Parallaxe
    const n = props.sky === 'cave' ? 26 : 90
    for (let i = 0; i < n; i++) {
      const px = ((hash2(i, 1) * 2000 - v.cx * 0.15) % v.bw + v.bw) % v.bw
      const py = ((hash2(i, 2) * 1200 - v.cy * 0.15) % v.bh + v.bh) % v.bh
      const tw = 0.5 + 0.5 * Math.sin(t * (1 + hash2(i, 3) * 3) + i)
      if (props.sky === 'cave') {
        sg.fillStyle = rgba(p.abyss, 0.25 * tw)
        sg.fillRect(Math.round(px), Math.round((py - t * 6 * (1 + hash2(i, 4))) % v.bh + v.bh) % v.bh, 1, 1)
      } else {
        sg.fillStyle = `rgba(235,240,255,${0.25 + 0.6 * tw * hash2(i, 5)})`
        sg.fillRect(Math.round(px), Math.round(py), 1, 1)
      }
    }
    if (props.sky === 'aurora') this.aurora(t, v)
    if (props.sky === 'moon') {
      for (const d of this.world.windows) {
        const mx = projX(-1.2, -d - 0.9) - v.ox
        const my = projY(-1.2, -d - 0.9, 3.2) - v.oy
        sg.globalCompositeOperation = 'lighter'
        glow(sg, mx, my, 34, p.cold, 0.5)
        sg.globalCompositeOperation = 'source-over'
        this.moonDisc(sg, mx, my, 9)
      }
    } else if (props.sky === 'aurora') {
      for (const d of this.world.windows) {
        const mx = projX(-1.2, -d - 0.9) - v.ox
        const my = projY(-1.2, -d - 0.9, 3.2) - v.oy
        sg.globalCompositeOperation = 'lighter'
        glow(sg, mx, my, 30, p.cold, 0.35)
      }
    }
    sg.globalCompositeOperation = 'source-over'
    const ag = this.ag
    ag.globalCompositeOperation = 'destination-over'
    ag.drawImage(this.sky, 0, 0, v.bw, v.bh, 0, 0, v.bw, v.bh)
    ag.globalCompositeOperation = 'source-over'
  }

  private moonDisc(g: CanvasRenderingContext2D, cx: number, cy: number, r: number) {
    const lite = hex('#eef1f8')
    const dark = hex('#a9afc2')
    for (let y = -r; y <= r; y++) {
      for (let x = -r; x <= r; x++) {
        const d = (x * x + y * y) / (r * r)
        if (d > 1) continue
        const crater = hash2(x + 50, y + 50) > 0.86 || (Math.abs(x + 3) < 2 && Math.abs(y - 2) < 2)
        g.fillStyle = css(crater ? dark : mix(lite, dark, d * 0.35))
        g.fillRect(Math.round(cx + x), Math.round(cy + y), 1, 1)
      }
    }
  }

  private aurora(t: number, v: View) {
    const sg = this.sg
    const p = this.p
    const c1 = hex(p.cold)
    const c2 = hex('#7aa8ff')
    sg.globalCompositeOperation = 'lighter'
    for (let x = 0; x < v.bw; x += 2) {
      const wx = x + v.ox * 0.3
      const wave = Math.sin(wx * 0.012 + t * 0.5) * 0.5 + Math.sin(wx * 0.031 - t * 0.8) * 0.3 + Math.sin(wx * 0.005 + t * 0.2) * 0.4
      const top = v.bh * 0.08 + wave * 22
      const len = 40 + 28 * (0.5 + 0.5 * Math.sin(wx * 0.02 + t))
      const a = 0.16 + 0.12 * Math.sin(wx * 0.05 + t * 1.3)
      const col = mix(c1, c2, 0.5 + 0.5 * Math.sin(wx * 0.008 + t * 0.3))
      for (let k = 0; k < 4; k++) {
        sg.fillStyle = css(col, a * (1 - k / 4) * (k === 0 ? 1.3 : 1))
        sg.fillRect(x, Math.round(top + k * len * 0.25), 2, Math.ceil(len * 0.25))
      }
    }
    sg.globalCompositeOperation = 'source-over'
  }

  // ───────────────────────── Leuchten, Partikel ─────────────────────────

  private drawEmissive(t: number, v: View) {
    const s = this.script
    const c = s.cues
    const p = this.p
    const ag = this.ag
    ag.globalCompositeOperation = 'lighter'
    const head = t >= c.wireStart ? wireHead(s.wire, t) : -1e9
    // Leitung glüht nach
    for (const d of this.world.wire) {
      if (d < c.from || d > head) continue
      const x = projX(WORLD.wireCol, -d)
      const y = projY(WORLD.wireCol, -d, 0)
      if (!this.inView(v, x - HW, y, HW * 2, HH * 2)) continue
      const hot = head - d < 1.5
      this.blit(v, this.decal(hot ? 'hot' : 'on', d % 3).cv, x - HW, y, hot ? 0.9 : 0.45)
    }
    // Funken am Impulskopf
    if (head > c.from && head < WORLD.lamp) {
      const hx = projX(WORLD.wireCol + 0.5, -head)
      const hy = projY(WORLD.wireCol + 0.5, -head, 0)
      for (let i = 0; i < 9; i++) {
        const ph = (t * 3 + hash(i)) % 1
        const sx = hx + (hash2(i, Math.floor(t * 3 + hash(i))) - 0.5) * 14 * ph
        const sy = hy - ph * (10 + hash(i + 3) * 12) + ph * ph * 10
        ag.fillStyle = i % 3 ? p.wireOn : p.wireHot
        ag.globalAlpha = 1 - ph
        ag.fillRect(Math.round(sx - v.ox), Math.round(sy - v.oy), 1, 1)
      }
      ag.globalAlpha = 1
      ag.drawImage(this.glowSprite(p.wireLight), Math.round(hx - v.ox) - 16, Math.round(hy - v.oy) - 16, 32, 32)
    }
    // Lampe glüht
    if (t >= c.impact) {
      const spr = this.block('lampOn', 0)
      this.blit(v, spr.cv, projX(WORLD.wireCol, -WORLD.lamp) + spr.ox, projY(WORLD.wireCol, -WORLD.lamp, 0) + spr.oy, 0.8)
    }
    // Glut über Fackeln/Laternen
    for (const tq of s.torches) {
      if (tq.t >= 0 && t < tq.t) continue
      const base = this.design.props.light === 'pumpkin' ? { x: projX(1, -tq.d + 0.5), y: projY(1, -tq.d + 0.5, 0.9) } : { x: projX(0, -tq.d + 0.5), y: projY(0, -tq.d + 0.5, 2.6) }
      if (!this.inView(v, base.x - 30, base.y - 60, 60, 80)) continue
      for (let i = 0; i < 5; i++) {
        const ph = (t * (0.45 + hash2(i, tq.d) * 0.4) + hash2(tq.d, i)) % 1
        const ex = base.x + Math.sin(ph * 6 + i) * 3 + (hash2(i + 7, tq.d) - 0.5) * 6
        const ey = base.y - ph * 34
        ag.globalAlpha = (1 - ph) * 0.9
        ag.fillStyle = i % 2 ? p.flame[1] : p.flame[2]
        ag.fillRect(Math.round(ex - v.ox), Math.round(ey - v.oy), 1, 1)
      }
      ag.globalAlpha = 1
      // Zünd-Funkenregen
      if (tq.t >= 0 && t - tq.t < 0.6) {
        const age = t - tq.t
        for (let i = 0; i < 10; i++) {
          const a = hash2(i, tq.d + 5) * Math.PI * 2
          const sp = 10 + hash2(i + 3, tq.d) * 18
          const ex = base.x + Math.cos(a) * sp * age * 2
          const ey = base.y - 4 + Math.sin(a) * sp * age * 1.2 + age * age * 40
          ag.globalAlpha = 1 - age / 0.6
          ag.fillStyle = p.flame[0]
          ag.fillRect(Math.round(ex - v.ox), Math.round(ey - v.oy), 1, 1)
        }
        ag.globalAlpha = 1
      }
    }
    // Mondstrahl durchs Gitter / Kälte durch die Fenster
    for (const d of this.world.windows) {
      const a = this.design.props.sky === 'moon' ? 0.16 : 0.08
      const x0 = projX(0, -d + 1) - v.ox
      const y0 = projY(0, -d + 1, 4) - v.oy
      ag.fillStyle = rgba(p.cold, a)
      ag.beginPath()
      ag.moveTo(x0, y0)
      ag.lineTo(x0 + HW * 2, y0 - HH * 2)
      ag.lineTo(x0 + HW * 2 + HW * 3.2, y0 - HH * 2 + HH * 3.2 + BZ * 4)
      ag.lineTo(x0 + HW * 3.2, y0 + HH * 3.2 + BZ * 4)
      ag.closePath()
      ag.fill()
    }
    ag.globalCompositeOperation = 'source-over'
  }

  private drawAtmosphere(t: number, v: View) {
    const s = this.script
    const p = this.p
    const props = this.design.props
    const ag = this.ag
    // Nische liegt im Schatten – nur wenn der Impuls vorbeirast, sieht man kurz, was darin lauert
    {
      const reveal = s.cameo === null ? 0 : blip(t - s.cameo + 0.45, 1.5)
      const a = 0.82 - 0.62 * reveal
      const d0 = WORLD.alcove
      ag.fillStyle = `rgba(2,2,4,${a})`
      ag.beginPath()
      ag.moveTo(projX(0, -d0 + 1) - v.ox, projY(0, -d0 + 1, 0) - v.oy)
      ag.lineTo(projX(0, -d0 - 1) - v.ox, projY(0, -d0 - 1, 0) - v.oy)
      ag.lineTo(projX(0, -d0 - 1) - v.ox, projY(0, -d0 - 1, 3) - v.oy)
      ag.lineTo(projX(0, -d0 + 1) - v.ox, projY(0, -d0 + 1, 3) - v.oy)
      ag.closePath()
      ag.fill()
    }
    // Staub der Türen
    for (const dr of s.doors) {
      const age = t - dr.t
      if (age < 0 || age > 1.6) continue
      for (let i = 0; i < 26; i++) {
        const col = hash2(i, dr.d) * WORLD.width
        const z = hash2(i + 9, dr.d) * 3
        const x = projX(col + age * (3 + hash(i) * 6), -dr.d + (hash2(i, 3) - 0.5) * 2)
        const y = projY(col, -dr.d, z) - age * 10 + age * age * 6
        const sz = 1 + Math.floor(age * 4 * hash(i + 1))
        ag.globalAlpha = clamp(0.55 * (1 - age / 1.6))
        ag.fillStyle = i % 3 ? css(hex(p.stone[3])) : css(hex(p.fog))
        ag.fillRect(Math.round(x - v.ox), Math.round(y - v.oy), sz, sz)
      }
    }
    ag.globalAlpha = 1
    // Schwebende Staubkörner im Licht
    for (let i = 0; i < 70; i++) {
      const wx = (hash2(i, 11) * v.bw + t * (3 + hash(i) * 5)) % v.bw
      const wy = (hash2(i, 12) * v.bh - t * (2 + hash(i + 1) * 4) + v.bh * 4) % v.bh
      const tw = 0.5 + 0.5 * Math.sin(t * 2 + i)
      ag.fillStyle = rgba(props.snow ? '#ffffff' : p.flame[1], 0.12 + 0.18 * tw)
      ag.fillRect(Math.round(wx), Math.round(wy), 1, 1)
    }
    // Schnee in zwei Tiefen
    if (props.snow) {
      for (let i = 0; i < 160; i++) {
        const depth = i % 3 === 0 ? 1.3 : 0.8
        const fall = 22 * depth
        const wx = hash2(i, 21) * 3000 + Math.sin(t * 0.9 + i) * 6 + t * 8
        const wy = hash2(i, 22) * 3000 + t * fall
        const x = ((wx - v.ox * depth) % v.bw + v.bw) % v.bw
        const y = ((wy - v.oy * depth) % v.bh + v.bh) % v.bh
        ag.fillStyle = `rgba(240,248,255,${depth > 1 ? 0.95 : 0.55})`
        const sz = depth > 1 ? 2 : 1
        ag.fillRect(Math.round(x), Math.round(y), sz, sz)
      }
    }
    // Bodennebel
    if (props.fog > 0) {
      ag.globalCompositeOperation = 'screen'
      for (let i = 0; i < 9; i++) {
        const d = ((hash(i + 40) * 60 + t * (0.6 + hash(i) * 0.6)) % 64) - 6
        const col = hash(i + 41) * (WORLD.width + 3) - 1
        const x = projX(col, -d) - v.ox
        const y = projY(col, -d, 0.3) - v.oy
        const r = 50 + hash(i + 42) * 40
        if (x + r < 0 || x - r > v.bw || y + r < 0 || y - r > v.bh) continue
        const grd = ag.createRadialGradient(x, y, 0, x, y, r)
        grd.addColorStop(0, rgba(p.fog, 0.16 * props.fog))
        grd.addColorStop(1, rgba(p.fog, 0))
        ag.fillStyle = grd
        ag.fillRect(x - r, y - r * 0.5, r * 2, r)
      }
      ag.globalCompositeOperation = 'source-over'
    }
  }

  private drawBloom(t: number, v: View, lights: Light[]) {
    const g = this.g
    g.globalCompositeOperation = 'lighter'
    for (const l of lights) {
      if (!l.bloom || l.a < 0.05) continue
      const x = (l.x - v.cx) * v.S + this.W / 2
      const y = (l.y - v.cy) * v.S + this.H / 2
      const r = Math.min(l.r, 160) * v.S * 0.5
      if (x + r < 0 || y + r < 0 || x - r > this.W || y - r > this.H) continue
      g.globalAlpha = clamp(l.bloom * Math.min(1, l.a) * 0.55)
      g.drawImage(this.glowSprite(l.color), x - r, y - r, r * 2, r * 2)
    }
    g.globalAlpha = 1
    g.globalCompositeOperation = 'source-over'
    void t
  }

  /** Bildschirmeffekte über der Szene: unscharfe Teilchen ganz vorn, Sturzflug-Nebel, Fledermausschwärme. */
  private drawScreenFx(t: number, v: View) {
    const g = this.g
    const s = this.script
    const c = s.cues
    const p = this.p
    // Unscharfe Teilchen dicht vor der Kamera (Tiefe): bewegen sich schneller als die Szene
    g.globalCompositeOperation = 'lighter'
    const span = this.W + 400
    for (let i = 0; i < 9; i++) {
      const par = 1.8 + hash(i + 60) * 1.2
      const x = ((hash(i + 61) * 5000 - v.cx * v.S * (par - 1) * 0.35 + t * 20) % span + span) % span - 200
      const y = ((hash(i + 62) * 3000 - v.cy * v.S * (par - 1) * 0.35 - t * 14) % (this.H + 400) + this.H + 400) % (this.H + 400) - 200
      const r = (18 + hash(i + 63) * 40) * (this.H / 720) * par
      g.globalAlpha = 0.05 + 0.05 * Math.sin(t * 1.3 + i)
      g.drawImage(this.glowSprite(this.design.props.snow ? '#dff2ff' : p.flame[1]), x - r, y - r, r * 2, r * 2)
    }
    g.globalAlpha = 1
    g.globalCompositeOperation = 'source-over'
    // Sturzflug durch Wolken/Nebel
    if (c.descent) {
      const [d0, d1] = c.descent
      const k = range(t, d0, d1)
      if (k < 1) {
        for (let i = 0; i < 14; i++) {
          const ph = (k * 1.6 + hash(i)) % 1
          const a = Math.sin(Math.PI * ph) * (1 - k) * 0.32
          if (a < 0.01) continue
          const ang = hash(i + 7) * Math.PI * 2
          const dist = ph * ph * Math.max(this.W, this.H) * 0.9
          const x = this.W / 2 + Math.cos(ang) * dist
          const y = this.H / 2 + Math.sin(ang) * dist * 0.7
          const r = (0.15 + ph * 0.9) * Math.max(this.W, this.H) * 0.45
          const grd = g.createRadialGradient(x, y, 0, x, y, r)
          grd.addColorStop(0, rgba(p.fog, a))
          grd.addColorStop(1, rgba(p.fog, 0))
          g.fillStyle = grd
          g.fillRect(x - r, y - r, r * 2, r * 2)
        }
      }
    }
    // Fledermäuse (Halloween): bei jeder Tür ein paar, im Auftritt ein ganzer Schwarm aufs Bild zu
    if (this.design.props.bats) {
      const swarms: { t0: number; n: number; d: number; big: boolean }[] = s.doors.map((d) => ({ t0: d.t, n: 5, d: d.d, big: false }))
      if (s.cameo !== null) swarms.push({ t0: s.cameo - 0.1, n: 18, d: WORLD.alcove + 1, big: true })
      for (const sw of swarms) {
        const age0 = t - sw.t0
        if (age0 < 0 || age0 > 2) continue
        const ox = (projX(sw.big ? -0.5 : 1, -sw.d) - v.cx) * v.S + this.W / 2
        const oy = (projY(sw.big ? -0.5 : 1, -sw.d, 1.2) - v.cy) * v.S + this.H / 2
        for (let i = 0; i < sw.n; i++) {
          const age = age0 - hash2(i, sw.d) * 0.35
          if (age < 0) continue
          const ang = -Math.PI * (0.55 + hash2(i + 3, sw.d) * 0.6)
          const sp = (0.25 + hash2(i + 5, sw.d) * 0.35) * this.H
          const grow = sw.big ? 1 + age * age * 3 : 1 + age
          const x = ox + Math.cos(ang) * sp * age * 1.3 - age * this.W * 0.12 + Math.sin(age * 9 + i) * 8
          const y = oy + Math.sin(ang) * sp * age + Math.cos(age * 11 + i) * 6
          const px = Math.max(1, Math.round(v.S * 1.2 * grow))
          const spr = Math.floor(t * 16 + i) % 2 ? this.sprites.batUp! : this.sprites.batDown!
          g.globalAlpha = clamp(1.6 - age)
          g.drawImage(spr, Math.round(x - (spr.width * px) / 2), Math.round(y), spr.width * px, spr.height * px)
        }
        g.globalAlpha = 1
      }
    }
  }

}

function scale3(c: Rgb, k: number): Rgb {
  return [c[0] * k, c[1] * k, c[2] * k]
}

