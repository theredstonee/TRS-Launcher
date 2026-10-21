// Szene „Brücke“ (Pixel, halbes Tempo): jedes „Wann?“ fliegt als Blase in die Mauer und wird ein „?“-Block,
// danach wird die Rakete Stück für Stück aus Redstone und Licht zusammengesetzt.
import { at, blit, bubble, setCam, visible, type Frame } from '../core'
import { debris, lightCone } from '../fx'
import { clamp, ditherFill, easeInOut, easeOut, easeOutBack, glow, hash, layer, lerp, type Cv } from '../kit'
import { C, DEV, FAN, personFront, qBlock, type PersonLook } from '../sprites'

export const GROUND = 210
export const WALL = { x: 136, cols: 7, rows: 5, size: 16 }
export const PAD = { x: 312, y: GROUND }
const LEN = { x: 92, y: GROUND - 34 }
/** Laternenpfosten (nicht vor der Mauer). */
const LAMPS = [30, 124, 400]

let land: Cv | null = null
const cache = new Map<string, Cv>()
const spr = (k: string, make: () => Cv) => {
  let c = cache.get(k)
  if (!c) cache.set(k, (c = make()))
  return c
}

function buildLand(): Cv {
  return layer(900, 520, (g) => {
    g.translate(200, 120)
    // ferne Hügel
    for (let i = 0; i < 3; i++) {
      g.fillStyle = ['#2a1530', '#22112a', '#1a0d22'][i]!
      for (let x = -200; x < 700; x += 4) {
        const h = 30 + Math.sin(x * 0.02 + i * 2) * 14 + Math.sin(x * 0.05 + i) * 6 + i * 10
        g.fillRect(x, GROUND - h + i * 6, 4, h)
      }
    }
    // Boden: Gras + Erde (Blöcke)
    for (let x = -200; x < 700; x += 16) {
      g.fillStyle = C.grass
      g.fillRect(x, GROUND, 16, 3)
      g.fillStyle = C.grassD
      g.fillRect(x, GROUND + 3, 16, 2)
      ditherFill(g, x, GROUND + 5, 16, 11, [C.dirt, C.dirtD])
      ditherFill(g, x, GROUND + 16, 16, 300, [C.dirtD, '#1d130c', C.ink])
    }
    // Startrampe
    g.fillStyle = C.stoneA
    g.fillRect(PAD.x - 26, GROUND - 4, 52, 4)
    g.fillStyle = C.stoneC
    g.fillRect(PAD.x - 26, GROUND - 4, 52, 1)
    for (const x of [PAD.x - 34, PAD.x + 30]) {
      g.fillStyle = '#2a2a36'
      g.fillRect(x, GROUND - 70, 4, 70)
      for (let y = GROUND - 70; y < GROUND; y += 8) g.fillRect(x - 2, y, 8, 1)
    }
    // Redstone-Lampen auf Pfosten
    for (const x of LAMPS) {
      g.fillStyle = C.woodB
      g.fillRect(x, GROUND - 24, 2, 24)
    }
  })
}

/** Zeitpunkt, an dem Block i erscheint (zwei Blöcke je Schlag, erste Hälfte der Brücke). */
function blockTime(f: Frame, i: number, n: number) {
  const half = (f.tl.final - f.tl.bridge) * 0.5
  return f.tl.bridge + 0.25 + (i / n) * (half - 0.2)
}

/** Mauer aus „?“-Blöcken (von unten nach oben). */
export function wall(f: Frame, lit: number) {
  const g = f.g
  const n = WALL.cols * WALL.rows
  const on = spr('q1', () => qBlock(true))
  const off = spr('q0', () => qBlock(false))
  for (let i = 0; i < n; i++) {
    const tb = blockTime(f, i, n)
    const age = f.t - tb
    if (age < 0) continue
    const col = i % WALL.cols
    const row = Math.floor(i / WALL.cols)
    const x = WALL.x + col * WALL.size
    const y = GROUND - (row + 1) * WALL.size
    const k = easeOutBack(clamp(age / 0.25), 2.4)
    const s = WALL.size * k
    blit(g, (age < 0.35 || lit > 0) ? on : off, x + (WALL.size - s) / 2, y + (WALL.size - s) / 2, s, s)
    if (age < 0.5) glow(g, x + 8, y + 8, 26, C.red3, 0.6 * (1 - age / 0.5))
  }
  if (lit > 0) glow(g, WALL.x + WALL.cols * 8, GROUND - WALL.rows * 8, 120, C.red3, 0.2 * lit + f.pulse * 0.1)
}

/** Rakete aus Teilen: Teil j landet auf Schlag; `done` 0…1 (nach dem Bau ganz). */
export function rocketBuilt(f: Frame, x: number, y: number, k = 1) {
  const g = f.g
  const rk = f.assets.rocket
  const start = f.tl.bridge + (f.tl.final - f.tl.bridge) * 0.5
  const beat = f.tl.beats ? 60 / f.tl.beats.bpm : 0.6
  const ox = x - 17 * k
  const oy = y - 60 * k
  rk.parts.forEach((part, j) => {
    const ta = start + 0.3 + j * beat * 1.5
    const age = f.t - ta
    const fly = clamp(1 + age / 0.5)
    if (fly <= 0) return
    const from = j % 2 ? -1 : 1
    const e = easeOut(fly)
    const dx = (1 - e) * 120 * from
    const dy = (1 - e) * -160
    g.globalAlpha = clamp(fly * 3)
    blit(g, sub(rk.full, part), ox + part.sx * k + dx, oy + part.sy * k + dy, part.sw * k, part.sh * k)
    g.globalAlpha = 1
    if (age >= 0 && age < 0.6) {
      glow(g, ox + (part.sx + part.sw / 2) * k, oy + (part.sy + part.sh / 2) * k, 30 * k, C.red4, 0.9 * (1 - age / 0.6))
      glow(g, ox + (part.sx + part.sw / 2) * k, oy + (part.sy + part.sh / 2) * k, 12 * k, '#ffffff', 0.6 * (1 - age / 0.6))
      debris(f, ox + (part.sx + part.sw / 2) * k, oy + (part.sy + part.sh / 2) * k, age, 14, j * 17 + 3, [C.red4, C.lamp2, '#ffffff'], 40, 30, 1.4)
    }
  })
}

const subCache = new WeakMap<Cv, Map<string, Cv>>()
/** Ausschnitt (Teil) der Raketen-Leinwand, gecacht je Quelle. */
export function sub(src: Cv, p: { name: string, sx: number, sy: number, sw: number, sh: number }): Cv {
  let m = subCache.get(src)
  if (!m) subCache.set(src, (m = new Map()))
  let c = m.get(p.name)
  if (!c) m.set(p.name, (c = layer(p.sw, p.sh, (g) => g.drawImage(src, p.sx, p.sy, p.sw, p.sh, 0, 0, p.sw, p.sh))))
  return c
}

/** Abendhimmel + Land (gemeinsam mit der Startszene). */
export function dusk(f: Frame, warm = 1) {
  const g = f.g
  land ??= buildLand()
  at(f, 0.2)
  const v = visible(f, 0.2)
  const sky = g.createLinearGradient(0, v.y0, 0, v.y1)
  sky.addColorStop(0, '#07051a')
  sky.addColorStop(0.55, '#2a1240')
  sky.addColorStop(0.8, `rgba(${Math.round(150 * warm)},40,60,1)`)
  sky.addColorStop(1, '#ff7a4a')
  g.fillStyle = sky
  g.fillRect(v.x0, v.y0, v.w, v.h)
  for (let i = 0; i < 60; i++) {
    g.fillStyle = `rgba(255,255,255,${0.15 + hash(i) * 0.5})`
    g.fillRect(v.x0 + hash(i * 3) * v.w, v.y0 + hash(i * 7) * v.h * 0.45, 1, 1)
  }
  at(f, 1)
  blit(g, land, -200, -120)
}

/** Laternen-Licht (warm) + Redstone-Glühen auf den Pfosten. */
export function duskLights(f: Frame) {
  const g = f.g
  for (const x of LAMPS.map((l) => l + 1)) {
    g.fillStyle = C.lamp
    g.fillRect(x - 3, GROUND - 30, 7, 7)
    glow(g, x, GROUND - 27, 46, C.lamp, 0.35 + f.pulse * 0.1)
    lightCone(g, x, GROUND - 24, 6, 50, 24, C.lamp2, 0.15)
  }
}

export function renderBridge(f: Frame) {
  const { shot } = f
  const g = f.g
  const p = shot.p
  const k = easeInOut(p)
  if (shot.shot === 'wall') setCam(f, lerp(170, 190, k), lerp(176, 168, k), lerp(2.5, 2.2, k))
  else setCam(f, lerp(250, 290, k), lerp(166, 162, k), lerp(2.2, 2.75, k))
  dusk(f)
  wall(f, 0)
  // Lencraft schickt die Fragen ab
  const look: PersonLook = { ...FAN, face: f.assets.face }
  blit(g, spr(`lenb:${f.assets.version}`, () => personFront(look, 'phone')), LEN.x, LEN.y)
  const n = WALL.cols * WALL.rows
  for (const m of f.chat) {
    if (m.section !== 'bridge') continue
    const age = f.t - m.at
    if (age < -0.2 || age > 1.2) continue
    // Ziel: der Block, der zu dieser Frage gehört
    const i = Math.min(n - 1, Math.round(((m.at + 0.6 - f.tl.bridge - 0.25) / ((f.tl.final - f.tl.bridge) * 0.5 - 0.2)) * n))
    const tx = WALL.x + (i % WALL.cols) * WALL.size + 8
    const ty = GROUND - (Math.floor(i / WALL.cols) + 1) * WALL.size + 8
    const e = easeInOut(clamp(age / 0.6))
    const x = lerp(LEN.x + 12, tx, e)
    const y = lerp(LEN.y + 8, ty, e) - Math.sin(e * Math.PI) * 30
    const sc = 1 - clamp((age - 0.45) / 0.15) * 0.8
    if (age < 0.6) bubble(f, m, x, y - 4, { pop: clamp((age + 0.2) / 0.25), alpha: sc, size: 4 * sc + 0.5, align: 'center' })
  }
  // Rakete auf der Rampe wird gebaut, Entwickler mit Kopfhörern hilft
  rocketBuilt(f, PAD.x, PAD.y - 4)
  const up = f.beat.index % 2 === 1
  blit(g, spr(`devb:${up}`, () => personFront({ ...DEV, headphones: true }, up ? 'up' : 'down')), PAD.x - 56, GROUND - 34)
  // Licht: Dämmerung, Laternen, Redstone
  g.save()
  g.globalCompositeOperation = 'multiply'
  const v = visible(f, 1)
  g.fillStyle = 'rgb(150,120,170)'
  g.fillRect(v.x0, v.y0, v.w, v.h)
  g.restore()
  duskLights(f)
  glow(g, PAD.x, PAD.y - 30, 70, C.red3, 0.15 + f.pulse * 0.12)
  glow(g, WALL.x + 56, GROUND - 40, 90, C.red2, 0.1)
}
