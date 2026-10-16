// Halloween 2026 (API.md §36): erzeugt aus dem freigegebenen Entwurf (halloween-2026-mockup) die Pixel-Assets.
//
//   node api/scripts/generate-halloween.mjs
//
// - 6 Umhänge „Mondnacht“ + „Kürbisjäger 2026“ in HD (Faktor 8 = 512×256 je Bild), animiert als senkrechter Streifen.
//   Die Motive sind 40×64 Pixel (Entwurf) und landen 2×2-fach auf der Außenseite (Vanilla-UV 1,1 · 10×16). Alle
//   Bewegungen hängen an Winkeln aus Bild/Bilder·2π (ganze Vielfache) – das letzte Bild geht nahtlos ins erste über.
//   Einträge kommen (mit unlock „reward“) in assets/capes/studio.json und catalog.json (vorhandene werden ersetzt).
// - 6 Chat-Sticker (12×12 ×8) nach assets/halloween/stickers/<id>.png
// - 2 Abzeichen (×8) nach assets/halloween/badges/<id>.png
// Deterministisch: jeder Lauf erzeugt dieselben Bytes.

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { PNG } from 'pngjs'
import { SPR, bay, hex, mix, rng, spriteSize } from '../shared/halloween-art.ts'
import { BADGES, HALLOWEEN_CAPES, HALLOWEEN_REWARD, HUNTER_CAPE, STICKERS } from '../shared/halloween.ts'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', 'assets')
const CAPES = join(ROOT, 'capes')
const ART = join(ROOT, 'halloween')

/** Umhang: Faktor 8, 24 Bilder à 250 ms = 6 s je Umlauf. */
const SCALE = 8
const FRAMES = 24
const FRAME_MS = 250

// ---------------------------------------------------------------- kleine RGBA-Leinwand (wie Canvas 2D, nur Rechtecke)

function parseColor(c) {
  if (c.startsWith('#')) return [...hex(c), 1]
  const m = /^rgba?\(([^)]+)\)$/.exec(c)
  if (!m) throw new Error(`bad color ${c}`)
  const p = m[1].split(',').map((x) => Number(x.trim()))
  return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1]
}

class Canvas {
  constructor(w, h) {
    this.w = w
    this.h = h
    this.d = new Float64Array(w * h * 4)
    this.fillStyle = '#000000'
    this.globalAlpha = 1
    this.erase = false
  }
  fillRect(x, y, w, h) {
    const [r, g, b, a0] = parseColor(this.fillStyle)
    const a = a0 * this.globalAlpha
    const x0 = Math.max(0, Math.round(x)), y0 = Math.max(0, Math.round(y))
    const x1 = Math.min(this.w, Math.round(x) + Math.round(w)), y1 = Math.min(this.h, Math.round(y) + Math.round(h))
    for (let yy = y0; yy < y1; yy++) for (let xx = x0; xx < x1; xx++) {
      const o = (yy * this.w + xx) * 4
      if (this.erase) {
        this.d[o + 3] *= 1 - a
        continue
      }
      const da = this.d[o + 3]
      const oa = a + da * (1 - a)
      if (oa <= 0) continue
      this.d[o] = (r * a + this.d[o] * da * (1 - a)) / oa
      this.d[o + 1] = (g * a + this.d[o + 1] * da * (1 - a)) / oa
      this.d[o + 2] = (b * a + this.d[o + 2] * da * (1 - a)) / oa
      this.d[o + 3] = oa
    }
  }
  drawImage(src) {
    for (let i = 0; i < this.w * this.h; i++) {
      const o = i * 4
      const a = src.d[o + 3] * this.globalAlpha
      if (a <= 0) continue
      const da = this.d[o + 3]
      const oa = a + da * (1 - a)
      for (let k = 0; k < 3; k++) this.d[o + k] = (src.d[o + k] * a + this.d[o + k] * da * (1 - a)) / oa
      this.d[o + 3] = oa
    }
  }
  clear() {
    this.d.fill(0)
  }
  get(x, y) {
    const o = (y * this.w + x) * 4
    return [this.d[o], this.d[o + 1], this.d[o + 2], this.d[o + 3]]
  }
}

// ---------------------------------------------------------------- Umhang „Mondnacht“ (Port aus dem Entwurf)

const VARIANTS = {
  halloween_night_violet: { sky0: '#0d0718', sky1: '#3a2160', moon: '#eef1f8', shade: '#b9bfd0', halo: 'rgba(185,162,234,.35)', sil: '#07040e', sil2: '#1a1029', fog: '185,162,234', trim: '#cfd5e3', glow: '#ffb35c', star: '#eef1f8' },
  halloween_moon_silver: { sky0: '#232634', sky1: '#8e95ab', moon: '#ffffff', shade: '#d5d9e5', halo: 'rgba(255,255,255,.35)', sil: '#0e0f16', sil2: '#2a2c3a', fog: '238,241,248', trim: '#ffffff', glow: '#ffd27a', star: '#ffffff' },
  halloween_ghost_white: { sky0: '#8f93a6', sky1: '#e9ebf3', moon: '#ffffff', shade: '#c9ccd8', halo: 'rgba(255,255,255,.55)', sil: '#3b3851', sil2: '#5c5874', fog: '255,255,255', trim: '#6f6890', glow: '#b9a2ea', star: '#ffffff' },
  halloween_poison_green: { sky0: '#030c06', sky1: '#1f4a2b', moon: '#d6ffc4', shade: '#8fd67e', halo: 'rgba(127,209,139,.4)', sil: '#010503', sil2: '#0c2213', fog: '127,209,139', trim: '#b6f0a8', glow: '#c6ff6a', star: '#d6ffc4' },
  halloween_blood_moon: { sky0: '#0c0206', sky1: '#4a0e18', moon: '#e0553d', shade: '#a8382c', halo: 'rgba(201,66,74,.45)', sil: '#050103', sil2: '#1e060b', fog: '201,66,74', trim: '#f0c6a8', glow: '#ff6a3d', star: '#f0c6a8' },
  halloween_pumpkin_glow: { sky0: '#120603', sky1: '#6e3510', moon: '#ffc37a', shade: '#e08a3c', halo: 'rgba(255,179,92,.45)', sil: '#080302', sil2: '#241006', fog: '255,179,92', trim: '#ffd27a', glow: '#ffd27a', star: '#ffd27a' },
}
const CW = 40
const CH = 64

function tornEdge(seed) {
  const r = rng(seed)
  const cut = []
  for (let x = 0; x < CW; x++) cut.push(x % 7 === 3 ? 4 + Math.floor(r() * 3) : Math.floor(r() * 2.4))
  return cut
}

const rgbStr = (c) => `rgb(${c.map(Math.round).join(',')})`

function capeBase(P, seed) {
  const g = new Canvas(CW, CH)
  const r = rng(seed)
  const s0 = hex(P.sky0), s1 = hex(P.sky1)
  for (let y = 0; y < CH; y++) for (let x = 0; x < CW; x++) {
    g.fillStyle = rgbStr(mix(s0, s1, Math.floor((y / CH) * 6 + bay(x, y)) / 6))
    g.fillRect(x, y, 1, 1)
  }
  const mx = 27, my = 15, mr = 7
  for (let y = -16; y <= 16; y++) for (let x = -16; x <= 16; x++) {
    const d = Math.hypot(x, y)
    if (d > mr && d < mr + 9 && bay(x + 32, y + 32) < (1 - (d - mr) / 9) ** 2 * 0.9) {
      g.fillStyle = P.halo
      g.fillRect(mx + x, my + y, 1, 1)
    }
  }
  const cr = [[-2, -1, 1.6], [2, 2, 1.3], [-1, 3, 1]]
  for (let y = -mr; y <= mr; y++) for (let x = -mr; x <= mr; x++) {
    if (x * x + y * y > mr * mr + 2) continue
    let col = (x * 0.6 + y * 0.45) / mr > 0.45 + bay(x + 9, y + 9) * 0.2 ? P.shade : P.moon
    if (cr.some(([a, b, q]) => (x - a) ** 2 + (y - b) ** 2 < q * q)) col = P.shade
    g.fillStyle = col
    g.fillRect(mx + x, my + y, 1, 1)
  }
  g.fillStyle = P.sil
  const gy = (x) => Math.round(50 + Math.sin(x * 0.12) * 2 + (x < 14 ? -2 : 0))
  for (let x = 0; x < CW; x++) g.fillRect(x, gy(x), 1, CH)
  const tr = (x, y, len, ang, d) => {
    const ex = x + Math.cos(ang) * len, ey = y - Math.sin(ang) * len
    for (let t = 0; t <= len; t++) g.fillRect(Math.round(x + (ex - x) * t / len), Math.round(y + (ey - y) * t / len), d > 3 ? 2 : 1, 1)
    if (d > 0) {
      tr(ex, ey, len * 0.68, ang + 0.45 + r() * 0.3, d - 1)
      tr(ex, ey, len * 0.64, ang - 0.4 - r() * 0.35, d - 1)
    }
  }
  tr(7, gy(7), 11, Math.PI / 2 + 0.12, 5)
  g.fillStyle = P.sil2
  for (const [x, h] of [[20, 6], [27, 7], [34, 5]]) {
    const b = gy(x) + 1
    g.fillRect(x, b - h, 4, h)
    g.fillRect(x + 1, b - h - 1, 2, 1)
    g.fillStyle = P.sil
    g.fillRect(x + 1, b - h + 2, 2, 1)
    g.fillRect((x + 1.5) | 0, b - h + 1, 1, 3)
    g.fillStyle = P.sil2
  }
  // Saum oben: Silberkante + Naht
  g.fillStyle = P.trim
  g.fillRect(0, 0, CW, 1)
  for (let x = 1; x < CW; x += 3) g.fillRect(x, 2, 2, 1)
  g.fillStyle = 'rgba(0,0,0,.35)'
  g.fillRect(0, 0, 1, CH)
  g.fillRect(CW - 1, 0, 1, CH)
  // zerrissener Saum unten
  const cut = tornEdge(seed + 7)
  g.erase = true
  g.fillStyle = '#000000'
  cut.forEach((h, x) => g.fillRect(x, CH - h, 1, h))
  g.erase = false
  return { c: g, mx, my, gy, cut }
}

/** Ein Bild bei Winkel `phi` (0 … 2π). Jede Bewegung nutzt ganze Vielfache von `phi` → nahtlose Schleife. */
function drawCape(g, P, base, phi) {
  g.clear()
  g.drawImage(base.c)
  // Sterne funkeln (2 Zyklen je Umlauf)
  for (let i = 0; i < 9; i++) {
    const x = (i * 13 + 3) % 36 + 2, y = (i * 7 + 4) % 26 + 2
    if (Math.hypot(x - base.mx, y - base.my) < 11) continue
    g.globalAlpha = 0.3 + 0.7 * (0.5 + 0.5 * Math.sin(phi * 2 + i * 1.7))
    g.fillStyle = P.star
    g.fillRect(x, y, 1, 1)
  }
  g.globalAlpha = 1
  // Drei Fledermäuse kreisen um den Mond (ein Umlauf je Schleife), Flügelschlag 11× je Schleife
  for (let i = 0; i < 3; i++) {
    const a = phi + i * 2.1
    const x = Math.round(base.mx + Math.cos(a) * (11 + i * 2)), y = Math.round(base.my + Math.sin(a) * 5 + i * 2)
    const up = Math.sin(phi * 11 + i) > 0
    g.fillStyle = P.sil
    g.fillRect(x, y, 1, 1)
    g.fillRect(x - 1, y - (up ? 1 : 0), 1, 1)
    g.fillRect(x + 1, y - (up ? 1 : 0), 1, 1)
    if (!up) {
      g.fillRect(x - 2, y, 1, 1)
      g.fillRect(x + 2, y, 1, 1)
    }
  }
  // Laterne auf dem mittleren Grab
  const fl = 0.6 + 0.4 * Math.sin(phi * 8) * Math.sin(phi * 3)
  g.fillStyle = P.glow
  g.globalAlpha = fl
  g.fillRect(28, base.gy(28) - 9, 2, 1)
  g.globalAlpha = fl * 0.3
  g.fillRect(26, base.gy(28) - 11, 6, 5)
  g.globalAlpha = 1
  // Nebel (zieht nach rechts: 2 Wellen je Umlauf; zweite Welle langsam gegenläufig)
  for (let y = 44; y < 58; y++) for (let x = 0; x < CW; x++) {
    const v = (0.5 + 0.5 * Math.sin(x * 0.35 + phi * 2) * Math.sin(x * 0.13 - phi + y)) * (1 - Math.abs(y - 51) / 7)
    if (bay(x, y) < v * 0.6 && !(y >= CH - base.cut[x])) {
      g.fillStyle = `rgba(${P.fog},.28)`
      g.fillRect(x, y, 1, 1)
    }
  }
}

// ---------------------------------------------------------------- Kürbisjäger 2026

const JACK2 = ['......gg.....', '.....g.......', '..ooOOoOOoo..', '.oOOOOoOOOOo.', 'oOOyyOoOyyOOo', 'oOyyyOoOyyyOo', 'oOOOOOoOOOOOo', 'oOyOyOyOyOyOo', 'oOOyyyyyyyOOo', '.oOOOyyyOOOo.', '..ooooooooo..']
const DIG = { 2: ['111', '001', '111', '100', '111'], 0: ['111', '101', '101', '101', '111'], 6: ['111', '100', '111', '101', '111'] }

function hunterBase() {
  const g = new Canvas(CW, CH)
  for (let y = 0; y < CH; y++) for (let x = 0; x < CW; x++) {
    g.fillStyle = rgbStr(mix(hex('#07040c'), hex('#2a0e0c'), Math.floor((y / CH) * 5 + bay(x, y)) / 5))
    g.fillRect(x, y, 1, 1)
  }
  for (let y = 0; y < CH; y++) for (let x = 0; x < CW; x++) {
    const d = Math.hypot(x - 20, y - 28) / 19
    if (d < 1 && bay(x, y) < (1 - d) ** 1.5 * 0.8) {
      g.fillStyle = 'rgba(224,138,60,.35)'
      g.fillRect(x, y, 1, 1)
    }
  }
  g.fillStyle = '#3f6b3a'
  for (let i = 0; i < 22; i++) {
    g.fillRect(Math.round(2 + i * 0.7 + Math.sin(i * 0.6) * 2), Math.round(3 + i * 0.8), 1, 1)
    g.fillRect(Math.round(37 - i * 0.7 - Math.sin(i * 0.6) * 2), Math.round(3 + i * 0.8), 1, 1)
  }
  g.fillStyle = '#5f9a52'
  for (const [x, y] of [[4, 8], [9, 14], [35, 8], [30, 14]]) g.fillRect(x, y, 2, 1)
  g.fillStyle = '#cfd5e3'
  g.fillRect(0, 0, CW, 1)
  g.fillRect(0, 0, 1, CH)
  g.fillRect(CW - 1, 0, 1, CH)
  g.fillStyle = 'rgba(207,213,227,.4)'
  g.fillRect(2, 2, CW - 4, 1)
  g.fillRect(2, 2, 1, CH - 8)
  g.fillRect(CW - 3, 2, 1, CH - 8)
  let dx = 13
  for (const d of ['2', '0', '2', '6']) {
    DIG[d].forEach((row, y) => [...row].forEach((ch, x) => {
      if (ch === '1') {
        g.fillStyle = '#cfd5e3'
        g.fillRect(dx + x, 50 + y, 1, 1)
      }
    }))
    dx += 4
  }
  const cut = tornEdge(99)
  g.erase = true
  g.fillStyle = '#000000'
  cut.forEach((h, x) => g.fillRect(x, CH - h, 1, h))
  g.erase = false
  return { c: g, cut }
}

function drawHunter(g, base, phi) {
  g.clear()
  g.drawImage(base.c)
  const fl = 0.75 + 0.25 * Math.sin(phi * 9) * Math.sin(phi * 4)
  const P = { o: '#7a3512', O: '#c4651f', g: '#4d7a45', y: `rgba(${255},${Math.round(190 + 40 * fl)},${Math.round(90 + 50 * fl)},1)` }
  JACK2.forEach((row, y) => [...row].forEach((ch, x) => {
    const col = P[ch]
    if (col) {
      g.fillStyle = col
      g.fillRect(7 + x * 2, 17 + y * 2, 2, 2)
    }
  }))
  // Glut steigt auf (zwei Durchgänge je Umlauf)
  for (let i = 0; i < 7; i++) {
    const ph = (phi / (2 * Math.PI) * 2 + i / 7) % 1
    const x = Math.round(12 + ((i * 37) % 17) + Math.sin(phi * 2 + i) * 2), y = Math.round(16 - ph * 14)
    g.fillStyle = `rgba(255,${150 + i * 10},60,${(1 - ph).toFixed(2)})`
    g.fillRect(x, y, 1, 1)
  }
}

// ---------------------------------------------------------------- HD-Umhang-Layout (Vanilla 64×32 × SCALE)

/**
 * Ein Bild 512×256: Außenseite (UV 1,1 · 10×16) = Motiv 2×2-fach; Innenseite (UV 12,1) = dunkles Futter mit
 * gespiegeltem Riss; Kanten links/rechts/unten in der Silhouettenfarbe, oben die Saumfarbe. Ausgerissene Pixel sind
 * durchsichtig, tragen aber die dunkle Silhouettenfarbe (Clients, die Alpha ignorieren, zeigen dort einen dunklen Saum).
 */
function layoutFrame(out, frameY, art, cut, colors) {
  const W = 64 * SCALE
  const put = (x, y, rgba) => {
    const o = ((frameY + y) * W + x) * 4
    out[o] = Math.round(rgba[0])
    out[o + 1] = Math.round(rgba[1])
    out[o + 2] = Math.round(rgba[2])
    out[o + 3] = Math.round(rgba[3] * 255)
  }
  const k = SCALE / 4 // Motiv-Pixel → HD-Pixel (40 → 80)
  const lining = hex(colors.lining)
  const edge = hex(colors.edge)
  const trim = hex(colors.trim)
  const hole = [...hex(colors.edge), 0]
  // Außenseite
  for (let y = 0; y < CH; y++) for (let x = 0; x < CW; x++) {
    const p = art.get(x, y)
    const px = p[3] > 0.004 ? [p[0], p[1], p[2], p[3] >= 0.999 ? 1 : p[3]] : hole
    for (let dy = 0; dy < k; dy++) for (let dx = 0; dx < k; dx++) put(SCALE + x * k + dx, SCALE + y * k + dy, px)
  }
  // Innenseite (Futter, leicht gemustert), Riss gespiegelt
  for (let y = 0; y < 16 * SCALE; y++) for (let x = 0; x < 10 * SCALE; x++) {
    const col = Math.floor(x / k)
    const ax = CW - 1 - col
    const torn = Math.floor(y / k) >= CH - cut[ax]
    const shade = bay(x >> 1, y >> 1) < 0.25 ? 0.82 : 1
    put(12 * SCALE + x, SCALE + y, torn ? hole : [lining[0] * shade, lining[1] * shade, lining[2] * shade, 1])
  }
  // Kanten
  for (let y = 0; y < 16 * SCALE; y++) for (let x = 0; x < SCALE; x++) {
    put(x, SCALE + y, [...edge, 1])
    put(11 * SCALE + x, SCALE + y, [...edge, 1])
  }
  for (let y = 0; y < SCALE; y++) for (let x = 0; x < 10 * SCALE; x++) {
    put(SCALE + x, y, [...trim, 1])
    put(11 * SCALE + x, y, [...edge, 1])
  }
}

function capeStrip(drawFrame, cut, colors) {
  const W = 64 * SCALE, H = 32 * SCALE
  const out = Buffer.alloc(W * H * FRAMES * 4)
  const g = new Canvas(CW, CH)
  for (let f = 0; f < FRAMES; f++) {
    drawFrame(g, (f / FRAMES) * 2 * Math.PI)
    layoutFrame(out, f * H, g, cut, colors)
  }
  return encode(W, H * FRAMES, out)
}

function encode(w, h, data) {
  const png = new PNG({ width: w, height: h, colorType: 6 })
  data.copy(png.data)
  return PNG.sync.write(png, { colorType: 6, deflateLevel: 9 })
}

// ---------------------------------------------------------------- Sprites ×8

function spritePng(name, scale = 8) {
  const { w, h } = spriteSize(name)
  const out = Buffer.alloc(w * scale * h * scale * 4)
  SPR[name].m.forEach((row, y) => [...row].forEach((ch, x) => {
    const c = SPR[name].pal[ch]
    if (!c) return
    const [r, g, b] = hex(c)
    for (let dy = 0; dy < scale; dy++) for (let dx = 0; dx < scale; dx++) {
      const o = ((y * scale + dy) * w * scale + x * scale + dx) * 4
      out[o] = r
      out[o + 1] = g
      out[o + 2] = b
      out[o + 3] = 255
    }
  }))
  return encode(w * scale, h * scale, out)
}

// ---------------------------------------------------------------- los

mkdirSync(join(ART, 'stickers'), { recursive: true })
mkdirSync(join(ART, 'badges'), { recursive: true })
for (const s of STICKERS) writeFileSync(join(ART, 'stickers', `${s.id}.png`), spritePng(s.sprite))
for (const [id, b] of Object.entries(BADGES)) writeFileSync(join(ART, 'badges', `${id}.png`), spritePng(b.icon))

const entries = []
HALLOWEEN_CAPES.forEach((c, i) => {
  const P = VARIANTS[c.id]
  const base = capeBase(P, 11 + i * 5)
  const png = capeStrip((g, phi) => drawCape(g, P, base, phi), base.cut, { lining: P.sil2, edge: P.sil, trim: P.trim })
  writeFileSync(join(CAPES, `${c.id}.png`), png)
  entries.push({ id: c.id, name: c.name.de, png })
})
{
  const base = hunterBase()
  const png = capeStrip((g, phi) => drawHunter(g, base, phi), base.cut, { lining: '#120808', edge: '#2a0e0c', trim: '#cfd5e3' })
  writeFileSync(join(CAPES, `${HUNTER_CAPE.id}.png`), png)
  entries.push({ id: HUNTER_CAPE.id, name: HUNTER_CAPE.name.de, png })
}

/** Katalog-Einträge ersetzen bzw. anhängen (Reihenfolge der übrigen bleibt). */
function upsert(file) {
  const list = JSON.parse(readFileSync(file, 'utf8'))
  for (const e of entries) {
    const entry = {
      id: e.id,
      name: e.name,
      file: `${e.id}.png`,
      unlock: { type: 'reward', reward: HALLOWEEN_REWARD },
      scale: SCALE,
      animated: true,
      frames: FRAMES,
      frameTimeMs: FRAME_MS,
    }
    const at = list.findIndex((x) => x.id === e.id)
    if (at >= 0) list[at] = entry
    else list.push(entry)
  }
  writeFileSync(file, `${JSON.stringify(list, null, 2)}\n`)
}
upsert(join(CAPES, 'studio.json'))
upsert(join(CAPES, 'catalog.json'))

console.log(`${entries.length} capes (${FRAMES} frames, ${entries.map((e) => `${e.id} ${(e.png.length / 1024).toFixed(0)} KB`).join(', ')}), ${STICKERS.length} stickers, ${Object.keys(BADGES).length} badges`)
