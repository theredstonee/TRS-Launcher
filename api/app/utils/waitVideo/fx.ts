// Wiederverwendbare Effekte (alle rein zeitbasiert → Pause/Neustart/Standbild funktionieren ohne Verlauf).
import { at, bubble, visible, type Frame } from './core'
import { clamp, decay, glow, hash, hash2, rgba } from './kit'

interface Star {
  x: number
  y: number
  s: number
  tw: number
  c: string
}
const starCache = new Map<string, Star[]>()

/** Sternenfeld einer Parallax-Ebene (Kunst-Koordinaten, Feld −480…960 × −540…810). */
export function stars(f: Frame, depth: number, seed: number, count: number, bright = 1, pixel = false) {
  const key = `${seed}:${count}`
  let list = starCache.get(key)
  if (!list) {
    const cols = ['#ffffff', '#d8ccff', '#ffd9c8', '#c8d8ff', '#ffb3a8']
    list = Array.from({ length: count }, (_, i) => ({
      x: -480 + hash2(seed, i * 3) * 1440,
      y: -540 + hash2(seed, i * 3 + 1) * 1350,
      s: 0.35 + hash2(seed, i * 3 + 2) ** 3 * 1.6,
      tw: hash2(seed + 9, i) * 6.28,
      c: cols[i % cols.length]!,
    }))
    starCache.set(key, list)
  }
  at(f, depth)
  const v = visible(f, depth)
  const g = f.g
  for (const s of list) {
    if (s.x < v.x0 || s.x > v.x1 || s.y < v.y0 || s.y > v.y1) continue
    const tw = 0.65 + 0.35 * Math.sin(f.t * (1.3 + s.tw) + s.tw * 3)
    const a = clamp(tw * bright * (0.45 + s.s * 0.45) + f.pulse * 0.25 * s.s)
    g.globalAlpha = a
    g.fillStyle = s.c
    const sz = pixel ? Math.max(1, Math.round(s.s)) : s.s
    g.fillRect(s.x - sz / 2, s.y - sz / 2, sz, sz)
    if (s.s > 1.5) {
      g.globalAlpha = a * 0.5
      g.fillRect(s.x - sz * 2.5, s.y - 0.15, sz * 5, 0.3)
      g.fillRect(s.x - 0.15, s.y - sz * 2.5, 0.3, sz * 5)
    }
  }
  g.globalAlpha = 1
}

const FW_COLORS = ['#ff5a4d', '#ffd27a', '#b9a6ff', '#ffffff', '#ff8f85', '#7aa8ff']

/** Feuerwerk: Explosion bei `x,y` zur Zeit `t0` (Liedzeit). Größe in Kunst-Pixeln. */
export function firework(f: Frame, t0: number, x: number, y: number, size: number, seed: number) {
  const age = f.t - t0
  if (age < 0 || age > 2.6) return
  const g = f.g
  const col = FW_COLORS[seed % FW_COLORS.length]!
  const col2 = FW_COLORS[(seed + 2) % FW_COLORS.length]!
  const n = 72
  const fade = clamp(1 - age / 2.6)
  const op = g.globalCompositeOperation
  g.globalCompositeOperation = 'lighter'
  glow(g, x, y + age * 4, size * 2 * (1 - age / 3), col, 0.5 * decay(age, 2.5))
  glow(g, x, y, size * 0.6, '#ffffff', 0.8 * decay(age, 8))
  for (let i = 0; i < n; i++) {
    const a = (i / n) * Math.PI * 2 + hash2(seed, i) * 0.2
    const sp = size * (0.75 + hash2(seed, i + 99) * 0.35)
    const k = 1 - Math.exp(-age * 2.4)
    const px = x + Math.cos(a) * sp * k
    const py = y + Math.sin(a) * sp * k + age * age * 7
    const tail = 4 * Math.exp(-age * 1.5)
    const tw = (Math.sin(age * 30 + i) > 0.2 || age < 1.2) ? 1 : 0.2
    g.globalAlpha = fade * tw
    g.fillStyle = i % 3 === 0 ? col2 : col
    const ps = age < 0.5 ? 3 : 2.2
    g.fillRect(px - ps / 2, py - ps / 2, ps, ps)
    g.globalAlpha = fade * 0.4 * tw
    g.fillRect(px - Math.cos(a) * tail - 0.6, py - Math.sin(a) * tail - 0.6, 1.2, 1.2)
    g.globalAlpha = fade * 0.2 * tw
    g.fillRect(px - Math.cos(a) * tail * 2 - 0.5, py - Math.sin(a) * tail * 2 - 0.5, 1, 1)
  }
  g.globalAlpha = 1
  g.globalCompositeOperation = op
}

/** Aufsteigende Rakete eines Feuerwerks (vor `t0`). */
export function fireworkTrail(f: Frame, t0: number, x: number, y: number, rise = 60) {
  const age = f.t - (t0 - 0.6)
  if (age < 0 || age > 0.6) return
  const k = age / 0.6
  const py = y + rise * (1 - k) ** 2
  glow(f.g, x, py, 6, '#ffd27a', 0.6)
  f.g.fillStyle = '#fff1c9'
  f.g.fillRect(x - 0.6, py - 0.6, 1.2, 3)
}

/** Feuerwerk auf jedem n-ten Schlag in einem Zeitfenster (deterministisch). */
export function fireworksOnBeats(f: Frame, from: number, to: number, every: number, area: { x: number, y: number, w: number, h: number }, size = 40) {
  if (!f.tl.beats) return
  const beat = 60 / f.tl.beats.bpm
  const first = Math.ceil((from - f.tl.beats.offset) / beat)
  const last = Math.floor((Math.min(to, f.t + 0.7) - f.tl.beats.offset) / beat)
  for (let b = first; b <= last; b++) {
    if (b % every !== 0) continue
    const tb = f.tl.beats.offset + b * beat
    if (f.t - tb > 2.6) continue
    const x = area.x + hash(b * 7) * area.w
    const y = area.y + hash(b * 13) * area.h
    fireworkTrail(f, tb, x, y)
    firework(f, tb, x, y, size * (0.7 + hash(b) * 0.6), b)
  }
}

/** Regen über den sichtbaren Bereich (Ebene `depth`). */
export function rain(f: Frame, depth: number, amount: number, color = '#9fb4ff') {
  at(f, depth)
  const v = visible(f, depth)
  const g = f.g
  const n = Math.round(amount)
  // In Nahaufnahmen dünner/kürzer, sonst wirken die Tropfen wie Balken
  const zz = Math.max(1, f.cam.z ** depth / 2.2)
  const len = 9 / zz
  g.strokeStyle = color
  g.lineWidth = 0.6 / zz
  g.globalAlpha = 0.35
  g.beginPath()
  for (let i = 0; i < n; i++) {
    const sp = 260 + hash(i * 5) * 140
    const x = v.x0 + ((hash(i) * v.w + f.t * 40) % v.w)
    const y = v.y0 + ((hash(i * 3 + 1) * v.h + f.t * sp) % v.h)
    g.moveTo(x, y)
    g.lineTo(x - len * 0.28, y + len)
  }
  g.stroke()
  g.globalAlpha = 1
}

/** Rauchwolken (weiche Kreise) um einen Punkt, wachsend mit `age`. */
export function smoke(f: Frame, x: number, y: number, age: number, n: number, seed: number, color = '#8a8496', spread = 40, size = 12) {
  if (age < 0) return
  const g = f.g
  for (let i = 0; i < n; i++) {
    const a = hash2(seed, i) * Math.PI * 2
    const sp = spread * (0.4 + hash2(seed, i + 50) * 0.8)
    const k = 1 - Math.exp(-age * 1.8)
    const px = x + Math.cos(a) * sp * k
    const py = y + Math.sin(a) * sp * k * 0.6 - age * 6
    const r = size * (0.6 + hash2(seed, i + 7) * 0.8) * (0.4 + k)
    const al = clamp(0.75 - age * 0.28)
    if (al <= 0) continue
    const grd = g.createRadialGradient(px, py, 0, px, py, r)
    grd.addColorStop(0, rgba(color, al))
    grd.addColorStop(0.6, rgba(color, al * 0.6))
    grd.addColorStop(1, rgba(color, 0))
    g.fillStyle = grd
    g.fillRect(px - r, py - r, r * 2, r * 2)
  }
}

/** Funken/Pixel-Trümmer: fliegen von x,y weg mit Schwerkraft. */
export function debris(f: Frame, x: number, y: number, age: number, n: number, seed: number, colors: readonly string[], speed = 60, grav = 40, sz = 2) {
  if (age < 0 || age > 3) return
  const g = f.g
  for (let i = 0; i < n; i++) {
    const a = hash2(seed, i) * Math.PI * 2
    const sp = speed * (0.3 + hash2(seed, i + 31) * 0.9)
    const px = x + Math.cos(a) * sp * age
    const py = y + Math.sin(a) * sp * age + grav * age * age
    g.globalAlpha = clamp(1 - age / 2.2)
    g.fillStyle = colors[i % colors.length]!
    const s = sz * (0.5 + hash2(seed, i + 3))
    g.fillRect(px - s / 2, py - s / 2, s, s)
  }
  g.globalAlpha = 1
}

/** Konfetti (fällt, flattert) über einem Bereich. */
export function confetti(f: Frame, area: { x: number, y: number, w: number, h: number }, n: number, seed: number) {
  const g = f.g
  const cols = ['#ff5a4d', '#ffd27a', '#b9a6ff', '#7aa8ff', '#ffffff', '#7ee0a0']
  for (let i = 0; i < n; i++) {
    const sp = 14 + hash2(seed, i) * 16
    const y = area.y + ((hash2(seed, i + 1) * area.h + f.t * sp) % area.h)
    const x = area.x + hash2(seed, i + 2) * area.w + Math.sin(f.t * 3 + i) * 3
    const flip = Math.abs(Math.sin(f.t * 6 + i * 1.7))
    g.fillStyle = cols[i % cols.length]!
    g.fillRect(x, y, 1.6, Math.max(0.3, 1.6 * flip))
  }
}

/** Chat-Blase als Komet: fliegt von (x0,y0) nach (x1,y1) mit leuchtendem Schweif. */
export function cometBubble(f: Frame, ev: { from: 'fan' | 'team' | 'system', text: string }, age: number, life: number, x0: number, y0: number, x1: number, y1: number, size = 7) {
  if (age < 0 || age > life) return
  const k = age / life
  const e = k * k * (3 - 2 * k)
  const x = x0 + (x1 - x0) * e
  const y = y0 + (y1 - y0) * e
  const g = f.g
  // Schweif
  const dx = x1 - x0
  const dy = y1 - y0
  const len = Math.hypot(dx, dy) || 1
  const tl = 40 * (0.4 + Math.sin(Math.PI * k))
  const grd = g.createLinearGradient(x, y, x - dx / len * tl, y - dy / len * tl)
  const col = ev.from === 'team' ? '#ff5a4d' : '#b9a6ff'
  grd.addColorStop(0, rgba(col, 0.55))
  grd.addColorStop(1, rgba(col, 0))
  g.strokeStyle = grd
  g.lineWidth = size * 0.9
  g.lineCap = 'round'
  g.globalCompositeOperation = 'lighter'
  g.beginPath()
  g.moveTo(x, y)
  g.lineTo(x - dx / len * tl, y - dy / len * tl)
  g.stroke()
  g.globalCompositeOperation = 'source-over'
  glow(g, x, y, size * 3, col, 0.35)
  const alpha = Math.min(1, k * 8, (1 - k) * 5)
  bubble(f, ev, x, y - size, { pop: Math.min(1, k * 6), alpha, size, align: 'center' })
}

/** Weicher Lichtstrahl (Kegel) von oben. */
export function lightCone(g: CanvasRenderingContext2D, x: number, y: number, w0: number, w1: number, h: number, color: string, a: number) {
  const grd = g.createLinearGradient(x, y, x, y + h)
  grd.addColorStop(0, rgba(color, a))
  grd.addColorStop(1, rgba(color, 0))
  g.save()
  g.globalCompositeOperation = 'lighter'
  g.fillStyle = grd
  g.beginPath()
  g.moveTo(x - w0 / 2, y)
  g.lineTo(x + w0 / 2, y)
  g.lineTo(x + w1 / 2, y + h)
  g.lineTo(x - w1 / 2, y + h)
  g.closePath()
  g.fill()
  g.restore()
}

/** Pixel-Linie (Bresenham) in ganzen Kunst-Pixeln. */
export function pixLine(g: CanvasRenderingContext2D, x0: number, y0: number, x1: number, y1: number, w = 1) {
  x0 = Math.round(x0)
  y0 = Math.round(y0)
  x1 = Math.round(x1)
  y1 = Math.round(y1)
  const dx = Math.abs(x1 - x0)
  const dy = -Math.abs(y1 - y0)
  const sx = x0 < x1 ? 1 : -1
  const sy = y0 < y1 ? 1 : -1
  let err = dx + dy
  for (let i = 0; i < 2000; i++) {
    g.fillRect(x0 - (w >> 1), y0 - (w >> 1), w, w)
    if (x0 === x1 && y0 === y1) break
    const e2 = 2 * err
    if (e2 >= dy) {
      err += dy
      x0 += sx
    }
    if (e2 <= dx) {
      err += dx
      y0 += sy
    }
  }
}
