// Erzeugt die mitgelieferte TRS-Kosmetik als HD-Pixel-Art (scale 2) passend zu
// den Vorlagen in api/assets/cosmetics/templates.json. Animierte Teile sind
// senkrechte Bildstreifen (Frame 0 oben), genau wie bei den Umhängen.
//
//   node api/scripts/generate-cosmetics.mjs
//
// Ausgabe: api/assets/cosmetics/*.png + catalog.json,
//          Vorschauen (Frontansicht ×8, Flügel/Rucksack von hinten, Spur von oben)
//          in api/assets/cosmetic-previews/.
// Keine Abhängigkeiten: PNG wird mit node:zlib selbst geschrieben.
//
// Gemalt wird in Weltkoordinaten: für jedes Texel einer Würfelfläche berechnet
// `paintModel` den Punkt (x, y, z) im Anhängepunkt-Raum (API.md §11). So passen
// Vorder- und Rückseite, Kanten und Silhouetten automatisch zusammen – und die
// Vorschau liest die Textur über dieselbe Abbildung zurück (prüft das UV-Netz).

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { deflateSync } from 'node:zlib'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', 'assets')
const OUT = join(ROOT, 'cosmetics')
const PREVIEW = join(ROOT, 'cosmetic-previews')
const TEMPLATES = Object.fromEntries(
  JSON.parse(readFileSync(join(OUT, 'templates.json'), 'utf8')).templates.map((t) => [t.id, t]),
)

/** Auflösung der mitgelieferten Designs (Texel je Modell-Einheit). */
const SCALE = 2

// --- Farben ------------------------------------------------------------------

const hex = (h, a = 255) => {
  const n = Number.parseInt(h.replace('#', ''), 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255, a]
}
const mix = (a, b, t) => a.map((v, i) => Math.round(v + (b[i] - v) * Math.max(0, Math.min(1, t))))
const shade = (c, f) => [...c.slice(0, 3).map((v) => Math.max(0, Math.min(255, Math.round(v * f)))), c[3]]
const alpha = (c, a) => [c[0], c[1], c[2], Math.max(0, Math.min(255, Math.round(a)))]

function noise(x, y, seed = 1) {
  let h = (Math.floor(x) * 374761393 + Math.floor(y) * 668265263 + seed * 2147483647) | 0
  h = Math.imul(h ^ (h >>> 13), 1274126177)
  return ((h ^ (h >>> 16)) >>> 0) / 4294967295
}

// --- Leinwand ------------------------------------------------------------------

class Img {
  constructor(w, h, fill) {
    this.w = Math.ceil(w)
    this.h = Math.ceil(h)
    w = this.w
    h = this.h
    this.px = new Uint8Array(w * h * 4)
    if (fill) for (let i = 0; i < w * h; i++) this.px.set(fill, i * 4)
  }
  set(x, y, c) {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h || !c) return
    this.px.set(c, (y * this.w + x) * 4)
  }
  get(x, y) {
    const i = (y * this.w + x) * 4
    return [...this.px.slice(i, i + 4)]
  }
  /** Alpha-Mischung (für Vorschauen). */
  blend(x, y, c) {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h || !c || c[3] === 0) return
    const a = c[3] / 255
    const d = this.get(x, y)
    this.set(x, y, [0, 1, 2].map((i) => Math.round(c[i] * a + d[i] * (1 - a))).concat(255))
  }
  rect(x, y, w, h, c, blend = false) {
    for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) (blend ? this.blend : this.set).call(this, x + i, y + j, c)
  }
  blit(src, dx, dy) {
    for (let y = 0; y < src.h; y++) for (let x = 0; x < src.w; x++) this.set(dx + x, dy + y, src.get(x, y))
  }
}

// --- Vorlagen-Geometrie (identisch zu server/lib/templates.ts cubeFaces) -------

function cubeFaces(c) {
  const w = c.to[0] - c.from[0]
  const h = c.to[1] - c.from[1]
  const d = c.to[2] - c.from[2]
  const [u, v] = c.uv
  return {
    top: { x: u + d, y: v, w, h: d },
    bottom: { x: u + d + w, y: v, w, h: d },
    right: { x: u, y: v + d, w: d, h },
    front: { x: u + d, y: v + d, w, h },
    left: { x: u + d + w, y: v + d, w: d, h },
    back: { x: u + 2 * d + w, y: v + d, w, h },
  }
}

/**
 * Punkt (Mitte des Texels i, j) einer Würfelfläche im Anhängepunkt-Raum.
 * Orientierung wie Vanilla-Box-UV (API.md §11.3).
 */
function texelPos(c, face, i, j, k) {
  const [x0, y0, z0] = c.from
  const [x1, y1, z1] = c.to
  const a = (i + 0.5) / k
  const b = (j + 0.5) / k
  switch (face) {
    case 'front': return [x0 + a, y1 - b, z1]
    case 'back': return [x1 - a, y1 - b, z0]
    case 'right': return [x0, y1 - b, z0 + a]
    case 'left': return [x1, y1 - b, z1 - a]
    case 'top': return [x0 + a, y1, z0 + b]
    case 'bottom': return [x0 + a, y0, z0 + b]
  }
  throw new Error(face)
}

/** Malt alle Würfel einer Modell-Vorlage: `fn(p)` liefert eine Farbe oder `null` (durchsichtig). */
function paintModel(tpl, fn) {
  const img = new Img(tpl.textureWidth * SCALE, tpl.textureHeight * SCALE)
  tpl.cubes.forEach((c, ci) => {
    for (const [face, r] of Object.entries(cubeFaces(c))) {
      const fw = r.w * SCALE
      const fh = r.h * SCALE
      for (let j = 0; j < fh; j++) {
        for (let i = 0; i < fw; i++) {
          const [x, y, z] = texelPos(c, face, i, j, SCALE)
          img.set(r.x * SCALE + i, r.y * SCALE + j, fn({ cube: ci, face, i, j, fw, fh, x, y, z }))
        }
      }
    }
  })
  return img
}

// --- Schrift (3×5) -------------------------------------------------------------

const FONT = {
  T: ['###', '.#.', '.#.', '.#.', '.#.'],
  R: ['##.', '#.#', '##.', '#.#', '#.#'],
  S: ['.##', '#..', '.#.', '..#', '##.'],
}
function textMask(str) {
  const set = new Set()
  ;[...str].forEach((ch, n) => FONT[ch].forEach((row, j) => [...row].forEach((p, i) => p === '#' && set.add(`${n * 4 + i},${j}`))))
  return { w: str.length * 4 - 1, h: 5, has: (x, y) => set.has(`${x},${y}`) }
}

// ================================================================== Designs

const GOLD = hex('#e3a93a')
const GOLD_HI = hex('#fff1b8')
const GOLD_LO = hex('#8a5a14')
const RED = hex('#d4231a')
const RED_HI = hex('#ff6a4d')
const RED_LO = hex('#5a0e0a')

/** Umlauf-Koordinate 0..40 um den Kronenreif (für Muster, die über Ecken laufen). */
function around(p) {
  if (p.face === 'front') return p.x + 5
  if (p.face === 'left') return 10 + (5 - p.z)
  if (p.face === 'back') return 20 + (5 - p.x)
  return 30 + (p.z + 5) // right
}

/** Kronen-Grundform: Reif (y 7..9) + drei Zacken je Seite (y 9..11). `null` außerhalb. */
function crownShape(p) {
  if (p.face === 'top' || p.face === 'bottom') return null
  if (p.y < 9) return 'band'
  // 0..10 entlang der Seite (vorne/hinten über x, links/rechts über z)
  const along = p.face === 'front' || p.face === 'back' ? p.x + 5 : p.z + 5
  const hy = (11 - p.y) / 2 // 0 oben, 1 unten
  for (const [c, w] of [[1.5, 1.2], [5, 1.6], [8.5, 1.2]]) {
    if (Math.abs(along - c) < w * hy + 0.3) return c === 5 && p.y > 10.25 ? 'tip' : 'spike'
  }
  return null
}

/** Redstone-Krone: Gold, rote Steine, Schimmer läuft einmal um die Krone (8 Frames). */
function redstoneCrown(f, frames) {
  return paintModel(TEMPLATES.crown, (p) => {
    const part = crownShape(p)
    if (!part) return null
    const u = around(p)
    const sweep = ((u * 2 + (11 - p.y) * 2 - (f / frames) * 96) % 96 + 96) % 96
    const shine = sweep < 6 ? 1 - sweep / 6 : 0
    const pulse = 0.8 + 0.2 * Math.sin((f / frames) * Math.PI * 2)
    if (part === 'tip') return mix(RED, RED_HI, pulse)
    let c = shade(GOLD, 0.85 + noise(p.i, p.j, p.cube * 7 + p.face.length) * 0.25)
    if (part === 'band') {
      if (p.y < 7.5 || (p.y > 8.5 && p.y < 9)) c = shade(GOLD_LO, 1.2)
      // Steine in der Mitte jeder Seite + an den Zacken
      const along = p.face === 'front' || p.face === 'back' ? p.x + 5 : p.z + 5
      const gy = Math.abs(p.y - 8)
      const big = Math.abs(along - 5) < 1.1 && gy < 0.6
      const small = (Math.abs(along - 1.5) < 0.6 || Math.abs(along - 8.5) < 0.6) && gy < 0.6
      if (big || small) {
        const hi = along < (big ? 5 : along < 5 ? 1.5 : 8.5) && p.y > 8
        return mix(shade(RED, hi ? 1.3 : 1), RED_HI, big ? pulse * 0.6 : pulse * 0.3)
      }
    } else if (p.y > 10.5) c = shade(GOLD, 1.1)
    return mix(c, GOLD_HI, shine * 0.85)
  })
}

// Team-Takt wie der Team-Umhang: aus, aus, aus, an, an, an, verglimmend, aus
const TEAM_ON = [0, 0, 0, 1, 1, 0.9, 0.45, 0.1]

/** Team-Krone (nur Admin): dunkelroter Samtreif, Goldkanten, Lampen-Steine blinken im Team-Takt. */
function teamCrown(f) {
  const on = TEAM_ON[f]
  const lampOff = hex('#6b4020')
  const lampOn = hex('#ffd24a')
  return paintModel(TEMPLATES.crown, (p) => {
    const part = crownShape(p)
    if (!part) return null
    if (part === 'tip') return mix(lampOff, lampOn, on)
    if (part === 'spike') return shade(GOLD, 0.8 + noise(p.i, p.j, 91) * 0.3 + (p.y > 10.5 ? 0.2 : 0))
    if (p.y < 7.5 || p.y > 8.5) return shade(GOLD, 0.9 + noise(p.i, p.j, 93) * 0.2)
    const along = p.face === 'front' || p.face === 'back' ? p.x + 5 : p.z + 5
    if ([1.5, 5, 8.5].some((c) => Math.abs(along - c) < (c === 5 ? 1.1 : 0.6))) return mix(lampOff, lampOn, on)
    return shade(RED_LO, 0.85 + noise(p.i, p.j, 95) * 0.3)
  })
}

/** TRS-Cap: schwarze Kappe, roter Schirm, „TRS“ vorne, roter Knopf oben. */
function trsCap() {
  const black = hex('#1b1b20')
  const logo = textMask('TRS')
  return paintModel(TEMPLATES.cap, (p) => {
    const n = noise(p.i, p.j, p.cube * 13 + p.face.length)
    if (p.cube === 1) {
      // Schirm
      if (p.face === 'bottom') return shade(hex('#3a3a40'), 0.9 + n * 0.2)
      return shade(p.face === 'top' ? RED : RED_LO, 0.85 + n * 0.25)
    }
    if (p.face === 'bottom') return null
    if (p.face === 'top') {
      if (Math.abs(p.x) < 0.6 && Math.abs(p.z) < 0.6) return RED
      const seam = Math.abs(p.x) < 0.3 || Math.abs(p.z) < 0.3 || Math.abs(Math.abs(p.x) - Math.abs(p.z)) < 0.3
      return shade(black, seam ? 0.7 : 0.95 + n * 0.2)
    }
    // Seiten: schwarz, roter Streifen unten
    if (p.y < 6.5) return shade(RED, 0.8)
    if (p.face === 'front') {
      const lx = p.i - Math.floor((p.fw - logo.w) / 2)
      const ly = p.j - 1
      if (logo.has(lx, ly)) return shade(RED_HI, 1)
      if (logo.has(lx - 1, ly - 1)) return hex('#000000')
    }
    return shade(black, 0.95 + n * 0.2)
  })
}

/** Redstone-Lampe (Rasterfläche) zwischen aus und an. */
function lampTexel(p, on, seed) {
  const off = { base: hex('#3b2312'), line: hex('#6b4020'), hi: hex('#8a5426') }
  const lit = { base: hex('#e89a2c'), line: hex('#ffe08a'), hi: hex('#fff6cf') }
  const border = p.i === 0 || p.j === 0 || p.i === p.fw - 1 || p.j === p.fh - 1
  const grid = p.i % 5 === 2 || p.j % 5 === 2
  const spark = noise(p.i, p.j, seed) > 0.82
  const pick = (s) => (border ? shade(s.line, 0.7) : grid ? s.line : spark ? s.hi : s.base)
  return mix(pick(off), pick(lit), on)
}

const HELMET_ON = [0, 0, 1, 1, 1, 0.6, 0.25, 0]

/** Redstone-Lampen-Helm: Helm aus einer Lampe mit Sichtfenster, Fackel oben; blinkt. */
function lampHelmet(f) {
  const on = HELMET_ON[f]
  return paintModel(TEMPLATES.lamp_helmet, (p) => {
    if (p.cube === 1) {
      // Redstone-Fackel
      if (p.face === 'bottom') return null
      if (p.face === 'top' || p.y > 13) return mix(hex('#5a1a10'), hex('#ff4a2a'), 0.35 + 0.65 * on)
      return shade(hex('#6b4020'), 0.9 + noise(p.i, p.j, 17) * 0.2)
    }
    if (p.face === 'bottom') return null
    if (p.face === 'front' && Math.abs(p.x) < 3 && p.y > 1.5 && p.y < 6) return null
    // Rahmen um das Sichtfenster
    if (p.face === 'front' && Math.abs(p.x) < 3.5 && p.y > 1 && p.y < 6.5) return shade(hex('#2a1a10'), 1)
    return lampTexel(p, on, 23 + p.face.length)
  })
}

/** Zylinder: schwarzer Seidenhut mit Redstone-Band und Stein vorne. */
function topHat() {
  const silk = hex('#16161b')
  return paintModel(TEMPLATES.tophat, (p) => {
    const n = noise(p.i, p.j, p.cube * 29 + p.face.length)
    if (p.cube === 0) {
      if (p.face === 'bottom') return shade(hex('#2a2a31'), 0.9 + n * 0.2)
      return shade(silk, p.face === 'top' ? 0.95 + n * 0.15 : 1.3)
    }
    if (p.face === 'bottom') return null
    if (p.face === 'top') return shade(silk, 1.1 + n * 0.15)
    // Band
    if (p.y < 11.5) {
      if (p.face === 'front' && Math.abs(p.x) < 1 && p.y > 10) return p.x < 0 && p.y > 10.75 ? RED_HI : RED
      return shade(RED_LO, 1.1 + n * 0.25)
    }
    // Glanzstreifen senkrecht
    const along = p.face === 'front' || p.face === 'back' ? p.x : p.z
    const sheen = Math.abs(along + 1.5) < 0.6 ? 1.6 : Math.abs(along - 2.5) < 0.3 ? 1.3 : 1
    return shade(silk, (0.95 + n * 0.12) * sheen)
  })
}

// --- Flügel ----------------------------------------------------------------------

/** Engel-/Federflügel: Silhouette in (s = Abstand von der Wurzel 0..15, y −13..5). */
function featherShape(s, y) {
  if (s < 0 || s > 15) return null
  const top = 2 + 3 * Math.sin((Math.PI / 2) * Math.min(s / 12, 1)) - (s > 12 ? (s - 12) * 0.6 : 0)
  const feather = 2.5
  const frac = ((s % feather) / feather) * 2 - 1
  const base = -5 - 7.5 * Math.pow(s / 15, 0.8)
  const bottom = base + 1.3 * Math.abs(frac)
  if (y > top || y < bottom) return null
  return { top, bottom, frac, featherIndex: Math.floor(s / feather), covert: y > top - 3.5 }
}

function wingCoords(p) {
  return { s: Math.abs(p.x) - 1, y: p.y }
}

/** Kanten (Dicke 1) nur dort, wo die Silhouette ist; Punkt dafür etwas nach innen schieben. */
function edgeInside(p, shapeFn) {
  const { s, y } = wingCoords(p)
  if (p.face === 'top') return shapeFn(s, y - 0.3)
  if (p.face === 'bottom') return shapeFn(s, y + 0.3)
  if (p.face === 'right' || p.face === 'left') {
    const inward = p.x < 0 ? (p.face === 'right' ? 0.3 : -0.3) : p.face === 'left' ? -0.3 : 0.3
    return shapeFn(Math.abs(p.x + inward) - 1, y)
  }
  return shapeFn(s, y)
}

/** Redstone-Flügel: dunkelrote Federn, Adern leuchten in einer Welle von der Wurzel zur Spitze. */
function redstoneWings(f, frames) {
  return paintModel(TEMPLATES.wings, (p) => {
    const shape = edgeInside(p, featherShape)
    if (!shape) return null
    const { s, y } = wingCoords(p)
    const n = noise(p.i, p.j, p.cube * 31 + p.face.length)
    const inner = p.face === 'front' // Seite zum Rücken
    if (p.face !== 'front' && p.face !== 'back') return shade(RED_LO, 0.8)
    const wave = Math.max(0, Math.cos(Math.PI * 2 * (s / 15 - f / frames))) ** 3
    let c
    if (shape.covert) {
      // kleine Deckfedern: Schuppenmuster
      const row = Math.floor((shape.top - y) * 2)
      const col = Math.floor((s + (row % 2) * 0.5) * 2)
      c = shade(hex('#7a1510'), (col + row) % 3 === 0 ? 0.75 : 0.95 + n * 0.2)
      if (y > shape.top - 0.6) c = shade(RED, 0.9)
    } else {
      const edge = Math.abs(shape.frac) > 0.8 || y < shape.bottom + 0.6
      c = edge ? shade(RED_LO, 0.7) : shade(hex('#8b1a12'), 0.85 + n * 0.25 - (shape.top - y) * 0.01)
      // Kiel = Redstone-Ader
      if (Math.abs(shape.frac) < 0.22) c = mix(shade(RED, 0.7), RED_HI, 0.25 + 0.75 * wave)
    }
    return inner ? shade(c, 0.7) : c
  })
}

/** Drachenflügel: Finger-Knochen strahlen von der Wurzel aus, Haut hängt dazwischen durch. */
const DRAGON_FINGERS = [
  { a: 8, r: 15.2 },
  { a: -22, r: 15.5 },
  { a: -52, r: 14.5 },
  { a: -80, r: 12.5 },
]
const DRAGON_ORIGIN = [0.5, 3.5]
function dragonShape(s, y) {
  if (s < 0 || s > 15.5) return null
  const dx = s - DRAGON_ORIGIN[0]
  const dy = y - DRAGON_ORIGIN[1]
  const dist = Math.hypot(dx, dy)
  const ang = (Math.atan2(dy, dx) * 180) / Math.PI
  if (dist < 1.6) return { bone: true, dist, ang }
  const F = DRAGON_FINGERS
  if (ang > F[0].a + 4 || ang < F[F.length - 1].a - 3) return null
  if (ang > F[0].a) return dist < F[0].r * 0.97 ? { bone: true, dist, ang } : null // Vorderkante
  for (let k = 0; k < F.length - 1; k++) {
    const a0 = F[k].a
    const a1 = F[k + 1].a
    if (ang <= a0 && ang >= a1) {
      const t = (a0 - ang) / (a0 - a1)
      const r = F[k].r + (F[k + 1].r - F[k].r) * t - 3.8 * Math.sin(Math.PI * t)
      if (dist > r) return null
      const nearBone = Math.min(Math.abs(ang - a0), Math.abs(ang - a1)) * (Math.PI / 180) * dist < 0.45
      return { bone: nearBone, dist, ang, t }
    }
  }
  return dist < F[F.length - 1].r ? { bone: true, dist, ang } : null
}

function dragonWings() {
  const skin = hex('#34183f')
  const bone = hex('#b7a58c')
  return paintModel(TEMPLATES.wings, (p) => {
    const shape = edgeInside(p, dragonShape)
    if (!shape) return null
    const n = noise(p.i, p.j, p.cube * 37 + p.face.length)
    if (p.face !== 'front' && p.face !== 'back') return shade(shape.bone ? bone : skin, 0.7)
    let c
    if (shape.bone) c = shade(bone, (0.8 + n * 0.25) * (p.face === 'back' ? 1 : 0.8))
    else {
      const vein = shape.t !== undefined && Math.abs(Math.sin(shape.dist * 1.3 + shape.t * 3)) < 0.12
      c = shade(vein ? hex('#5a2a66') : skin, 0.75 + n * 0.3 + shape.dist * 0.015)
      if (p.face === 'front') c = shade(mix(c, hex('#6b2f45'), 0.35), 0.85)
    }
    // Kralle an der Wurzel oben
    if (shape.dist < 1 && p.y > 3.5) return hex('#e8e0d0')
    return c
  })
}

// --- Rucksack --------------------------------------------------------------------

function backpack() {
  const leather = hex('#8a5429')
  const flapC = hex('#9b1d14')
  const stitchC = hex('#e3c49a')
  return paintModel(TEMPLATES.backpack, (p) => {
    const n = noise(p.i, p.j, p.cube * 41 + p.face.length)
    const border = p.i === 0 || p.j === 0 || p.i === p.fw - 1 || p.j === p.fh - 1
    if (p.cube === 1) {
      // Seitentasche (dunkler) mit Redstone-Staub-Zeichen
      if (p.face === 'back') {
        const cx = Math.abs(p.x)
        const cy = Math.abs(p.y + 7)
        if ((cx < 0.5 && cy < 1.5) || (cy < 0.5 && cx < 1.5)) return cx < 0.5 && cy < 0.5 ? RED_HI : RED
      }
      return shade(hex('#5e3719'), border ? 0.7 : 0.95 + n * 0.15)
    }
    if (p.face === 'bottom') return shade(leather, 0.55 + n * 0.1)
    if (p.face === 'front') return shade(leather, 0.65)
    // Goldschnalle am unteren Rand des Deckels
    if (p.face === 'back' && Math.abs(p.x) < 1 && p.y > -5.5 && p.y < -3.5) {
      const ring = Math.abs(p.x) > 0.5 || p.y < -5 || p.y > -4
      return ring ? GOLD : shade(flapC, 0.7)
    }
    const flap = p.face === 'top' || (p.y > -4.5 && (p.face === 'back' || p.face === 'left' || p.face === 'right'))
    if (flap) {
      if (p.face !== 'top' && p.y < -4) return shade(flapC, 0.65) // Kante des Deckels
      if (p.face === 'back' && p.y < -3.5 && p.y > -4 && p.i % 2 === 0) return stitchC // Naht
      return shade(flapC, (border ? 0.75 : 0.9) + n * 0.2)
    }
    // Riemen an den Seiten
    if ((p.face === 'left' || p.face === 'right') && Math.abs(p.z + 2) < 0.6) return shade(hex('#3b2312'), 1)
    return shade(leather, border ? 0.7 : 0.85 + n * 0.25)
  })
}

// --- Heiligenschein ----------------------------------------------------------------

/** Goldener Achteck-Ring; ein heller Funke läuft einmal herum. */
function halo(f, frames) {
  const spark = (f / frames) * Math.PI * 2
  return paintModel(TEMPLATES.halo, (p) => {
    const ang = Math.atan2(p.x, p.z)
    let d = Math.abs(ang - spark) % (Math.PI * 2)
    if (d > Math.PI) d = Math.PI * 2 - d
    const n = noise(p.i, p.j, p.cube * 43 + p.face.length)
    const base = mix(hex('#ffcf4a'), hex('#fff3b0'), p.face === 'top' ? 0.5 : p.face === 'bottom' ? 0 : 0.25)
    const glow = d < 0.7 ? (1 - d / 0.7) ** 2 : 0
    return mix(shade(base, 0.92 + n * 0.12), hex('#ffffff'), glow)
  })
}

// --- Partikel ------------------------------------------------------------------------

/** Sprite-Bereich (HD-Pixel) mit lokaler Malfunktion fn(u, v) → Farbe; u, v ∈ [-1, 1]. */
function paintSprites(tpl, fn) {
  const img = new Img(tpl.textureWidth * SCALE, tpl.textureHeight * SCALE)
  tpl.particles.sprites.forEach((sp, k) => {
    const w = sp.size[0] * SCALE
    const h = sp.size[1] * SCALE
    for (let j = 0; j < h; j++) {
      for (let i = 0; i < w; i++) {
        const u = ((i + 0.5) / w) * 2 - 1
        const v = ((j + 0.5) / h) * 2 - 1
        img.set(sp.uv[0] * SCALE + i, sp.uv[1] * SCALE + j, fn(k, u, v, i, j))
      }
    }
  })
  return img
}

/** Redstone-Staub und Funke; flackern über 4 Frames. */
function redstoneAura(f) {
  const tw = [1, 0.75, 0.9, 0.6][f]
  return paintSprites(TEMPLATES.orbit, (k, u, v, i, j) => {
    const r = Math.hypot(u, v)
    if (k === 0) {
      // Staubkorn: Raute mit weichem Schein
      const d = Math.abs(u) + Math.abs(v)
      if (d < 0.45) return mix(RED, RED_HI, (0.45 - d) * 2 * tw)
      if (d < 0.75 && noise(i, j, 51 + f) > 0.35) return alpha(RED, 200 * tw)
      if (r < 1) return alpha(hex('#ff3b24'), 70 * (1 - r) * tw)
      return null
    }
    // Funke: Stern mit gelbem Kern
    const star = Math.min(Math.abs(u), Math.abs(v)) < 0.12 * (1 - r) + 0.02 && r < 0.95 * tw + 0.05
    if (r < 0.2) return mix(hex('#ffd24a'), hex('#ffffff'), tw)
    if (star) return alpha(mix(RED_HI, hex('#ffd24a'), 1 - r), 255 * (1 - r * 0.6))
    if (r < 0.6) return alpha(RED, 90 * (0.6 - r) * 2 * tw)
    return null
  })
}

/** Fußabdrücke (links/rechts), Spitze zeigt nach oben (= Laufrichtung). */
function footprints() {
  // Sohle in 16×16 Texeln: Ballen (breit) + Ferse (schmaler), abgerundete Ecken.
  const inside = (x, y) => {
    const ball = x >= 3 && x <= 12 && y >= 0 && y <= 8 && !((x === 3 || x === 12) && (y === 0 || y === 8))
    const heel = x >= 4 && x <= 11 && y >= 10 && y <= 15 && !((x === 4 || x === 11) && y === 15)
    return ball || heel
  }
  return paintSprites(TEMPLATES.trail, (k, u, v, i, j) => {
    const x = k === 0 ? i : 15 - i // rechter Fuß gespiegelt
    if (!inside(x, j)) return null
    const edge = !inside(x - 1, j) || !inside(x + 1, j) || !inside(x, j - 1) || !inside(x, j + 1)
    return edge ? alpha(RED_LO, 230) : alpha(mix(hex('#b3261a'), RED, noise(i, j, 61 + k) * 0.6), 235)
  })
}

// ================================================================== Vorschau

const PX = 16 // Bildschirm-Pixel je Modell-Einheit (HD-Texel = 8×8)
const BG = hex('#353b45')
const ANCHOR = { head: [0, 24, 0], body: [0, 24, 0], back: [0, 24, -2], root: [0, 0, 0] }

/** Spieler-Silhouette (grau): Kopf, Körper, Arme, Beine in Weltkoordinaten (Füße bei y = 0). */
const PLAYER = [
  { from: [-4, 24, -4], to: [4, 32, 4], color: hex('#a9afb8') },
  { from: [-4, 12, -2], to: [4, 24, 2], color: hex('#8f959e') },
  { from: [-8, 12, -2], to: [-4, 24, 2], color: hex('#9ba1aa') },
  { from: [4, 12, -2], to: [8, 24, 2], color: hex('#9ba1aa') },
  { from: [-4, 0, -2], to: [0, 12, 2], color: hex('#7f858e') },
  { from: [0, 0, -2], to: [4, 12, 2], color: hex('#7a8089') },
]

/**
 * Orthografische Ansicht von vorne (view = 'front') oder hinten ('back'):
 * alle bildparallelen Flächen nach Tiefe sortiert, Texturen über `texelPos`
 * zurückgelesen – dieselbe Abbildung, mit der gemalt wurde.
 */
function renderView(tpl, tex, frame, view, win) {
  const [xa, xb, ya, yb] = win
  const img = new Img((xb - xa) * PX, (yb - ya) * PX, BG)
  const sx = (x) => Math.round((view === 'front' ? x - xa : xb - x) * PX)
  const sy = (y) => Math.round((yb - y) * PX)
  const depth = (z) => (view === 'front' ? z : -z)
  const quads = []
  for (const b of PLAYER) {
    quads.push({ d: depth(view === 'front' ? b.to[2] : b.from[2]), draw: () => {
      const x0 = Math.min(sx(b.from[0]), sx(b.to[0]))
      const x1 = Math.max(sx(b.from[0]), sx(b.to[0]))
      img.rect(x0, sy(b.to[1]), x1 - x0, sy(b.from[1]) - sy(b.to[1]), b.color)
    } })
  }
  if (tpl.kind === 'model') {
    const frameH = tpl.textureHeight * SCALE
    for (const c of tpl.cubes) {
      const off = ANCHOR[c.attach]
      const faces = cubeFaces(c)
      for (const face of ['front', 'back']) {
        const r = faces[face]
        const z = face === 'front' ? c.to[2] : c.from[2]
        quads.push({ d: depth(z + off[2]) + (face === (view === 'front' ? 'front' : 'back') ? 0.001 : -0.001), draw: () => {
          for (let j = 0; j < r.h * SCALE; j++) {
            for (let i = 0; i < r.w * SCALE; i++) {
              const col = tex.get(r.x * SCALE + i, frame * frameH + r.y * SCALE + j)
              if (col[3] < 128) continue
              const [x, y] = texelPos(c, face, i, j, SCALE)
              const px = sx(x + off[0]) - PX / 4
              const py = sy(y + off[1]) - PX / 4
              img.rect(px, py, PX / 2, PX / 2, col)
            }
          }
        } })
      }
    }
  } else {
    const pd = tpl.particles
    const frameH = tpl.textureHeight * SCALE
    const off = ANCHOR[pd.attach]
    for (let n = 0; n < pd.count; n++) {
      const phi = (Math.PI * 2 * n) / pd.count + 0.3
      const yy = pd.pattern === 'orbit' ? pd.height * Math.sin((Math.PI * 2 * n) / pd.count) : 0
      const pos = [pd.center[0] + pd.radius * Math.sin(phi), pd.center[1] + yy, pd.center[2] + pd.radius * Math.cos(phi)]
      const sp = pd.sprites[n % pd.sprites.length]
      quads.push({ d: depth(pos[2] + off[2]), draw: () => drawSprite(img, tex, sp, frame * frameH, sx(pos[0] + off[0]), sy(pos[1] + off[1]), pd.size * PX) })
    }
  }
  quads.sort((a, b) => a.d - b.d).forEach((q) => q.draw())
  return img
}

/** Sprite als Quad der Kantenlänge `sizePx` (längere Seite) um (cx, cy); Alpha wird gemischt. */
function drawSprite(img, tex, sp, frameOffset, cx, cy, sizePx, fade = 1) {
  const w = sp.size[0] * SCALE
  const h = sp.size[1] * SCALE
  const k = sizePx / Math.max(w, h)
  for (let j = 0; j < h; j++) {
    for (let i = 0; i < w; i++) {
      const col = tex.get(sp.uv[0] * SCALE + i, frameOffset + sp.uv[1] * SCALE + j)
      if (col[3] === 0) continue
      img.rect(
        Math.round(cx - (w * k) / 2 + i * k),
        Math.round(cy - (h * k) / 2 + j * k),
        Math.ceil(k),
        Math.ceil(k),
        alpha(col, col[3] * fade),
        true,
      )
    }
  }
}

/** Spur von oben: Spieler-Grundriss + die letzten Abdrücke, ältere blasser. */
function renderTrailTop(tpl, tex) {
  const pd = tpl.particles
  const step = pd.spacingBlocks * 16
  const n = 6
  const win = [-10, 10, -(n + 0.5) * step - 4, 6] // x, z
  const img = new Img((win[1] - win[0]) * PX, (win[3] - win[2]) * PX, BG)
  const sx = (x) => Math.round((win[1] - x) * PX) // echte Draufsicht: Spieler-links (+x) = Bild-links
  const sz = (z) => Math.round((win[3] - z) * PX)
  img.rect(sx(4), sz(2), 8 * PX, 4 * PX, hex('#8f959e'))
  for (let k = 0; k < n; k++) {
    const side = k % 2 === 0 ? 1 : -1
    const sp = pd.sprites[k % pd.sprites.length]
    drawSprite(img, tex, sp, 0, sx(side * pd.sideOffset), sz(-(k + 1) * step), pd.size * PX, 1 - k / n)
  }
  return img
}

// ================================================================== PNG

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

/** Senkrechter Streifen aus mehreren Frames. */
function strip(frames) {
  const out = new Img(frames[0].w, frames[0].h * frames.length)
  frames.forEach((f, k) => out.blit(f, 0, k * f.h))
  return out
}

// ================================================================== Ausgabe

const range = (n) => Array.from({ length: n }, (_, i) => i)

// view: Ausschnitt [xMin, xMax, yMin, yMax] in Welt-Einheiten (Füße bei y = 0)
const HEAD_WIN = [-9, 9, 20, 43]
const cosmetics = [
  { id: 'redstone_crown', name: 'Redstone-Krone', template: 'crown', unlock: 'code', emissive: true,
    frames: range(8).map((f) => redstoneCrown(f, 8)), frameTimeMs: 120, view: 'front', win: HEAD_WIN, previewFrame: 1 },
  { id: 'team_crown', name: 'Team-Krone', template: 'crown', unlock: 'admin', emissive: true,
    frames: range(8).map(teamCrown), frameTimeMs: 150, view: 'front', win: HEAD_WIN, previewFrame: 3 },
  { id: 'trs_cap', name: 'TRS-Cap', template: 'cap', unlock: 'free', frames: [trsCap()], view: 'front', win: HEAD_WIN },
  { id: 'lamp_helmet', name: 'Redstone-Lampen-Helm', template: 'lamp_helmet', unlock: 'free', emissive: true,
    frames: range(8).map(lampHelmet), frameTimeMs: 150, view: 'front', win: HEAD_WIN, previewFrame: 3 },
  { id: 'top_hat', name: 'Zylinder', template: 'tophat', unlock: 'free', frames: [topHat()], view: 'front', win: HEAD_WIN },
  { id: 'redstone_wings', name: 'Redstone-Flügel', template: 'wings', unlock: 'code', emissive: true,
    frames: range(8).map((f) => redstoneWings(f, 8)), frameTimeMs: 120, view: 'back', win: [-19, 19, 6, 34], previewFrame: 2 },
  { id: 'dragon_wings', name: 'Drachenflügel', template: 'wings', unlock: 'free', frames: [dragonWings()], view: 'back', win: [-19, 19, 6, 34] },
  { id: 'backpack', name: 'Rucksack', template: 'backpack', unlock: 'free', frames: [backpack()], view: 'back', win: [-10, 10, 8, 34] },
  { id: 'halo', name: 'Heiligenschein', template: 'halo', unlock: 'code', emissive: true,
    frames: range(8).map((f) => halo(f, 8)), frameTimeMs: 125, view: 'front', win: HEAD_WIN },
  { id: 'redstone_aura', name: 'Redstone-Partikel-Aura', template: 'orbit', unlock: 'free', emissive: true,
    frames: range(4).map(redstoneAura), frameTimeMs: 150, view: 'front', win: [-17, 17, -2, 36] },
  { id: 'footprints', name: 'Fußspuren', template: 'trail', unlock: 'free', frames: [footprints()], view: 'top' },
]

mkdirSync(OUT, { recursive: true })
mkdirSync(PREVIEW, { recursive: true })

const catalog = cosmetics.map((c) => {
  const tpl = TEMPLATES[c.template]
  if (!tpl) throw new Error(`unknown template ${c.template}`)
  for (const f of c.frames) {
    if (f.w !== tpl.textureWidth * SCALE || f.h !== tpl.textureHeight * SCALE) throw new Error(`${c.id}: frame size mismatch`)
  }
  const tex = c.frames.length > 1 ? strip(c.frames) : c.frames[0]
  writeFileSync(join(OUT, `${c.id}.png`), png(tex))
  const frame = Math.min(c.previewFrame ?? 0, c.frames.length - 1)
  const view = c.view === 'top' ? renderTrailTop(tpl, tex) : renderView(tpl, tex, frame, c.view, c.win)
  writeFileSync(join(PREVIEW, `${c.id}.png`), png(view))
  return {
    id: c.id,
    name: c.name,
    template: c.template,
    file: `${c.id}.png`,
    unlock: c.unlock,
    scale: SCALE,
    animated: c.frames.length > 1,
    frames: c.frames.length,
    ...(c.frames.length > 1 ? { frameTimeMs: c.frameTimeMs } : {}),
    emissive: c.emissive === true,
  }
})
// Im TRS Studio gestaltete Teile (studio.json + PNGs) bleiben erhalten und kommen ans Ende.
const studioFile = join(OUT, 'studio.json')
if (existsSync(studioFile)) catalog.push(...JSON.parse(readFileSync(studioFile, 'utf8')))
writeFileSync(join(OUT, 'catalog.json'), `${JSON.stringify(catalog, null, 2)}\n`)
console.log(`${catalog.length} Kosmetik-Teile → ${OUT}, Vorschauen → ${PREVIEW}`)
