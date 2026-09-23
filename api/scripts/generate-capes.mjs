// Erzeugt die offiziellen TRS-Umhänge als HD-Pixel-Art (128×64 je Bild, also
// doppelte Vanilla-Auflösung). Animierte Umhänge sind senkrechte Bildstreifen.
//
//   node api/scripts/generate-capes.mjs
//
// Ausgabe: api/assets/capes/*.png + catalog.json, Vorschauen in api/assets/previews/.
// Keine Abhängigkeiten: PNG wird mit node:zlib selbst geschrieben.

import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { deflateSync } from 'node:zlib'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', 'assets')
const OUT = join(ROOT, 'capes')
const PREVIEW = join(ROOT, 'previews')

/** Vanilla-Umhang 64×32 × SCALE. */
const SCALE = 2
const W = 64 * SCALE
const H = 32 * SCALE

// Bereiche des Umhangs in Vanilla-Pixeln (x, y, w, h): Box 10×16×1 bei UV (0,0).
const REGION = {
  top: [1, 0, 10, 1],
  bottom: [11, 0, 10, 1],
  left: [0, 1, 1, 16],
  outer: [1, 1, 10, 16],
  right: [11, 1, 1, 16],
  inner: [12, 1, 10, 16],
  // Elytra nutzen denselben Bildbereich rechts daneben.
  elytra: [22, 0, 24, 22],
}

// --- Farben ------------------------------------------------------------------

const hex = (h) => {
  const n = Number.parseInt(h.replace('#', ''), 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255, 255]
}
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * t))
const shade = (c, f) => [...c.slice(0, 3).map((v) => Math.max(0, Math.min(255, Math.round(v * f)))), c[3]]

// Deterministisches Rauschen, damit jeder Lauf dieselben Bilder erzeugt.
function noise(x, y, seed = 1) {
  let h = (x * 374761393 + y * 668265263 + seed * 2147483647) | 0
  h = Math.imul(h ^ (h >>> 13), 1274126177)
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295
}

// --- Leinwand ------------------------------------------------------------------

class Img {
  constructor(w, h) {
    this.w = w
    this.h = h
    this.px = new Uint8Array(w * h * 4)
  }
  set(x, y, c) {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h) return
    this.px.set(c, (y * this.w + x) * 4)
  }
  get(x, y) {
    const i = (y * this.w + x) * 4
    return [...this.px.slice(i, i + 4)]
  }
  rect(x, y, w, h, c) {
    for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) this.set(x + i, y + j, c)
  }
  blit(src, dx, dy) {
    for (let y = 0; y < src.h; y++) for (let x = 0; x < src.w; x++) this.set(dx + x, dy + y, src.get(x, y))
  }
}

/** Ein Bereich des Umhangs in HD-Pixeln – Zeichnen in lokalen Koordinaten. */
function area(img, name) {
  const [x, y, w, h] = REGION[name].map((v) => v * SCALE)
  return {
    w,
    h,
    set: (px, py, c) => px >= 0 && py >= 0 && px < w && py < h && img.set(x + px, y + py, c),
    fill: (fn) => {
      for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) img.set(x + i, y + j, fn(i, j))
    },
  }
}

// --- Schrift (3×5) -------------------------------------------------------------

const FONT = {
  T: ['###', '.#.', '.#.', '.#.', '.#.'],
  E: ['###', '#..', '##.', '#..', '###'],
  A: ['.#.', '#.#', '###', '#.#', '#.#'],
  M: ['#.#', '###', '###', '#.#', '#.#'],
  S: ['.##', '#..', '.#.', '..#', '##.'],
  R: ['##.', '#.#', '##.', '#.#', '#.#'],
}

function text(a, str, cx, y, color, shadow) {
  const width = str.length * 4 - 1
  let x = Math.round(cx - width / 2)
  for (const ch of str) {
    FONT[ch].forEach((row, j) =>
      [...row].forEach((p, i) => {
        if (p !== '#') return
        if (shadow) a.set(x + i + 1, y + j + 1, shadow)
        a.set(x + i, y + j, color)
      }),
    )
    x += 4
  }
}

// --- Bausteine -------------------------------------------------------------------

/** Rand und Innenseite passend zur Grundfarbe (von hinten sieht man die Außenseite). */
function frame(img, base, edge) {
  for (const name of ['top', 'bottom', 'left', 'right']) {
    area(img, name).fill((i, j) => shade(edge, 0.9 + noise(i, j, 7) * 0.2))
  }
  area(img, 'inner').fill((i, j) => shade(base, 0.55 + noise(i, j, 3) * 0.08))
  area(img, 'elytra').fill((i, j) => shade(base, 0.85 + noise(i, j, 5) * 0.25))
}

/** Redstone-Lampe (12×12), `on` 0..1. */
function lamp(a, x0, y0, on, size = 12) {
  const off = { base: hex('#3b2312'), line: hex('#6b4020'), hi: hex('#8a5426') }
  const lit = { base: hex('#e89a2c'), line: hex('#ffe08a'), hi: hex('#fff6cf') }
  for (let j = 0; j < size; j++) {
    for (let i = 0; i < size; i++) {
      const border = i === 0 || j === 0 || i === size - 1 || j === size - 1
      const grid = i % 4 === 1 || j % 4 === 1
      const spark = noise(i, j, 11) > 0.8
      const pick = (s) => (border ? shade(s.line, 0.75) : grid ? s.line : spark ? s.hi : s.base)
      a.set(x0 + i, y0 + j, mix(pick(off), pick(lit), on))
    }
  }
}

/** Weicher Schein um einen Punkt (addiert Farbe). */
function glow(a, cx, cy, radius, color, strength, img, region) {
  const [rx, ry] = REGION[region].map((v) => v * SCALE)
  for (let j = -radius; j <= radius; j++) {
    for (let i = -radius; i <= radius; i++) {
      const d = Math.hypot(i, j) / radius
      if (d > 1) continue
      const x = cx + i
      const y = cy + j
      if (x < 0 || y < 0 || x >= a.w || y >= a.h) continue
      const cur = img.get(rx + x, ry + y)
      a.set(x, y, mix(cur, color, (1 - d) ** 2 * strength))
    }
  }
}

/** Redstone-Staub-Leitung als Pfad aus Punkten. */
function wire(a, points, color) {
  for (let k = 0; k < points.length - 1; k++) {
    const [x1, y1] = points[k]
    const [x2, y2] = points[k + 1]
    const steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1))
    for (let s = 0; s <= steps; s++) {
      const x = Math.round(x1 + ((x2 - x1) * s) / steps)
      const y = Math.round(y1 + ((y2 - y1) * s) / steps)
      a.set(x, y, color)
    }
  }
}

// --- Designs ---------------------------------------------------------------------

function redstoneCape() {
  const img = new Img(W, H)
  const base = hex('#5a0e0a')
  frame(img, base, hex('#3a0806'))
  const o = area(img, 'outer')
  o.fill((i, j) => shade(base, 0.85 + noise(i, j, 21) * 0.3))
  const dust = hex('#ff3b24')
  wire(o, [[2, 0], [2, 8], [15, 8], [15, 18], [5, 18], [5, 27], [17, 27], [17, 32]], shade(dust, 0.75))
  for (const [x, y] of [[2, 8], [15, 18], [5, 27]]) {
    o.set(x, y, hex('#ffb199'))
  }
  // Redstone-Fackel unten
  o.set(10, 12, hex('#ffd24a'))
  o.set(10, 13, hex('#ff5a2a'))
  o.set(10, 14, hex('#6b4020'))
  o.set(10, 15, hex('#6b4020'))
  return img
}

function lampCape() {
  const img = new Img(W, H)
  const base = hex('#4a2a14')
  frame(img, base, hex('#2c190b'))
  const o = area(img, 'outer')
  o.fill((i, j) => {
    const lit = hex('#f0a431')
    const grid = i % 5 === 0 || j % 5 === 0
    return shade(grid ? hex('#ffe08a') : lit, 0.85 + noise(i, j, 31) * 0.3)
  })
  return img
}

function deepslateCape() {
  const img = new Img(W, H)
  const base = hex('#3a3a42')
  frame(img, base, hex('#232329'))
  const o = area(img, 'outer')
  o.fill((i, j) => {
    const tile = (i % 10 === 0 || j % 8 === 0) && noise(i, j, 41) > 0.2
    return shade(tile ? hex('#26262c') : base, 0.8 + noise(i, j, 43) * 0.35)
  })
  // Riss mit Glut
  wire(o, [[4, 3], [7, 9], [6, 14], [11, 20], [9, 26], [13, 31]], hex('#b3261a'))
  return img
}

function gemCape(baseHex, edgeHex, gemHex, fleckHex, seed) {
  const img = new Img(W, H)
  const base = hex(baseHex)
  frame(img, base, hex(edgeHex))
  const o = area(img, 'outer')
  o.fill((i, j) => {
    const fleck = noise(i, j, seed) > 0.93
    return fleck ? hex(fleckHex) : shade(base, 0.8 + noise(i, j, seed + 1) * 0.35)
  })
  // Edelstein in der Mitte (Raute)
  const gem = hex(gemHex)
  for (let j = -5; j <= 5; j++) {
    for (let i = -5; i <= 5; i++) {
      const d = Math.abs(i) + Math.abs(j)
      if (d > 5) continue
      const c = d === 5 ? shade(gem, 0.5) : i + j < -2 ? shade(gem, 1.35) : shade(gem, 0.9 + noise(i, j, seed + 2) * 0.2)
      o.set(10 + i, 13 + j, c)
    }
  }
  return img
}

function trsCape() {
  const img = new Img(W, H)
  const base = hex('#16161a')
  frame(img, base, hex('#0d0d10'))
  const o = area(img, 'outer')
  o.fill((i, j) => shade(base, 0.9 + noise(i, j, 61) * 0.2))
  // Redstone-Block als Logo
  for (let j = 0; j < 12; j++) {
    for (let i = 0; i < 12; i++) {
      const edge = i === 0 || j === 0 || i === 11 || j === 11
      o.set(4 + i, 5 + j, edge ? hex('#7a100b') : shade(hex('#d4231a'), 0.75 + noise(i, j, 63) * 0.5))
    }
  }
  glow(o, 10, 11, 9, hex('#ff3b24'), 0.25, img, 'outer')
  text(o, 'TRS', 10, 21, hex('#ff5a3c'), hex('#3a0806'))
  return img
}

/** Team: dunkelrot mit Goldrand, Redstone-Lampe mit „TEAM“, blinkt wie ein Takt. */
function teamFrame(on) {
  const img = new Img(W, H)
  const base = hex('#4a0907')
  const gold = hex('#d9a53b')
  frame(img, base, shade(gold, 0.7))
  const o = area(img, 'outer')
  o.fill((i, j) => {
    const edge = i === 0 || i === o.w - 1 || j === o.h - 1
    if (edge) return shade(gold, 0.85 + noise(i, j, 71) * 0.3)
    const inset = i === 1 || i === o.w - 2 || j === o.h - 2
    if (inset) return shade(gold, 0.45)
    return shade(base, 0.8 + noise(i, j, 73) * 0.3)
  })
  // Signal-Leitung von oben in die Lampe
  wire(o, [[10, 0], [10, 3]], mix(hex('#7a1a10'), hex('#ff3b24'), on))
  lamp(o, 4, 4, on)
  if (on > 0) glow(o, 10, 10, 10, hex('#ffb347'), 0.45 * on, img, 'outer')
  // Schriftzug unter der Lampe, leuchtet mit
  const letters = mix(gold, hex('#fff1b8'), on)
  text(o, 'TEAM', 10, 19, letters, hex('#1e0403'))
  // kleine Redstone-Fackeln links und rechts unten
  for (const x of [4, 15]) {
    o.set(x, 26, mix(hex('#6b1a10'), hex('#ffd24a'), on))
    o.set(x, 27, hex('#6b4020'))
    o.set(x, 28, hex('#6b4020'))
  }
  return img
}

function testerCape() {
  const img = new Img(W, H)
  const base = hex('#1f2226')
  const accent = hex('#f2a900')
  frame(img, base, hex('#121417'))
  const o = area(img, 'outer')
  o.fill((i, j) => shade(base, 0.85 + noise(i, j, 81) * 0.3))
  // Diagonale Warnstreifen oben
  for (let j = 0; j < 4; j++) for (let i = 0; i < o.w; i++) if ((i + j) % 6 < 3) o.set(i, j, shade(accent, 0.9))
  // Komparator: Steinplatte mit drei Fackeln
  o.rect = undefined
  for (let j = 0; j < 8; j++) {
    for (let i = 0; i < 14; i++) {
      const edge = i === 0 || j === 0 || i === 13 || j === 7
      o.set(3 + i, 8 + j, edge ? hex('#5b5f66') : shade(hex('#8b9098'), 0.85 + noise(i, j, 83) * 0.3))
    }
  }
  for (const [x, y] of [[6, 10], [13, 10], [10, 14]]) {
    o.set(x, y - 1, hex('#ffd24a'))
    o.set(x, y, hex('#ff3b24'))
  }
  wire(o, [[10, 16], [10, 21]], hex('#ff3b24'))
  text(o, 'TEST', 10, 23, accent, hex('#000000'))
  return img
}

// --- PNG ------------------------------------------------------------------------

const CRC = new Int32Array(256).map((_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c
})
function crc32(buf) {
  let c = -1
  for (const b of buf) c = CRC[(c ^ b) & 255] ^ (c >>> 8)
  return (c ^ -1) >>> 0
}
function chunk(type, data) {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length)
  const td = Buffer.concat([Buffer.from(type), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(td))
  return Buffer.concat([len, td, crc])
}
function png(img) {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(img.w, 0)
  ihdr.writeUInt32BE(img.h, 4)
  ihdr[8] = 8
  ihdr[9] = 6
  const raw = Buffer.alloc((img.w * 4 + 1) * img.h)
  for (let y = 0; y < img.h; y++) {
    raw[y * (img.w * 4 + 1)] = 0
    Buffer.from(img.px.buffer, y * img.w * 4, img.w * 4).copy(raw, y * (img.w * 4 + 1) + 1)
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

/** Senkrechter Streifen aus mehreren Bildern (animierter Umhang). */
function strip(frames) {
  const out = new Img(W, H * frames.length)
  frames.forEach((f, k) => out.blit(f, 0, k * H))
  return out
}

/** Vorschau: Außen- (von hinten sichtbar) und Innenseite nebeneinander, ×8 vergrößert. */
function preview(img) {
  const [ox, oy, ow, oh] = REGION.outer.map((v) => v * SCALE)
  const [ix, iy] = REGION.inner.map((v) => v * SCALE)
  const zoom = 8
  const gap = 4
  const out = new Img((ow * 2 + gap) * zoom, oh * zoom)
  for (let y = 0; y < oh; y++) {
    for (let x = 0; x < ow; x++) {
      out.rect(x * zoom, y * zoom, zoom, zoom, img.get(ox + x, oy + y))
      out.rect((ow + gap + x) * zoom, y * zoom, zoom, zoom, img.get(ix + x, iy + y))
    }
  }
  return out
}

// --- Ausgabe ----------------------------------------------------------------------

mkdirSync(OUT, { recursive: true })
mkdirSync(PREVIEW, { recursive: true })

// Team-Takt: aus, aus, aus, an (hell), an, an, verglimmend, aus
const TEAM_ON = [0, 0, 0, 1, 1, 0.9, 0.45, 0.1]

const capes = [
  { id: 'redstone', name: 'Redstone', unlock: 'free', img: redstoneCape() },
  { id: 'lamp', name: 'Redstone-Lampe', unlock: 'free', img: lampCape() },
  { id: 'deepslate', name: 'Deepslate', unlock: 'free', img: deepslateCape() },
  { id: 'lapis', name: 'Lapis', unlock: 'free', img: gemCape('#1d3f8f', '#10265a', '#3f7bff', '#d9b03b', 51) },
  { id: 'emerald', name: 'Smaragd', unlock: 'free', img: gemCape('#0f5a2c', '#083a1c', '#2fe07a', '#bff5d4', 53) },
  { id: 'amethyst', name: 'Amethyst', unlock: 'free', img: gemCape('#4a2475', '#2c1447', '#b77cff', '#f0d9ff', 55) },
  { id: 'trs', name: 'TRS', unlock: 'code', img: trsCape() },
  { id: 'team', name: 'Team', unlock: 'admin', frames: TEAM_ON.map(teamFrame), frameTimeMs: 150 },
  { id: 'tester', name: 'Tester', unlock: 'admin', img: testerCape() },
]

const catalog = capes.map((c) => {
  const frames = c.frames ?? [c.img]
  writeFileSync(join(OUT, `${c.id}.png`), png(frames.length > 1 ? strip(frames) : frames[0]))
  // Vorschau mit leuchtender Lampe (bei animierten das hellste Bild)
  writeFileSync(join(PREVIEW, `${c.id}.png`), png(preview(frames[Math.min(3, frames.length - 1)])))
  return {
    id: c.id,
    name: c.name,
    file: `${c.id}.png`,
    unlock: c.unlock,
    scale: SCALE,
    animated: frames.length > 1,
    frames: frames.length,
    ...(frames.length > 1 ? { frameTimeMs: c.frameTimeMs } : {}),
  }
})
writeFileSync(join(OUT, 'catalog.json'), `${JSON.stringify(catalog, null, 2)}\n`)
console.log(`${catalog.length} Umhänge → ${OUT}`)
