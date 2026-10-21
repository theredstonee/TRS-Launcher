// Szene „Start“ (letzter Refrain): die Rakete zündet (echtes Start-Foto als Rauchwolke), steigt, stoppt kurz vor der
// „?“-Mauer und bricht hindurch – dahinter das All mit Feuerwerk im Takt.
import { at, blit, setCam, stage, visible, type Frame } from '../core'
import { cometBubble, fireworksOnBeats, smoke } from '../fx'
import { clamp, easeIn, easeInOut, easeOut, glow, hash, hash2, lerp, range, rgba } from '../kit'
import { C, qBlock } from '../sprites'
import { GROUND, PAD, dusk, duskLights, wall } from './bridge'
import { astro, backdrop, planet } from './space'

/** Triebwerks-Flamme (Pixel-Stufen + Glühen) nach unten ab x,y. */
export function flame(f: Frame, x: number, y: number, len: number, w: number, k = 1) {
  const g = f.g
  const fl = 0.8 + 0.2 * Math.sin(f.t * 47) + 0.1 * Math.sin(f.t * 83)
  glow(g, x, y + len * 0.4, len * 1.4 * k, '#ff5a2a', 0.55 * k)
  glow(g, x, y + 4, w * 2.2 * k, '#fff1c9', 0.7 * k)
  const layers: [string, number, number][] = [['#e0281e', 1, 1], ['#ff8a2a', 0.78, 0.72], ['#ffd27a', 0.55, 0.48], ['#ffffff', 0.32, 0.25]]
  for (const [col, lw, ll] of layers) {
    const L = len * ll * fl * k
    const W = w * lw
    g.fillStyle = col
    for (let i = 0; i < 6; i++) {
      const yy = y + (L / 6) * i
      const ww = W * (1 - i / 7) + Math.sin(f.t * 60 + i * 3) * 0.6
      g.fillRect(x - ww / 2, yy, ww, L / 6 + 0.5)
    }
  }
}

/** Rakete (ganz) an x,y = Unterkante Mitte, Drehung `rot`, Größe `k`. */
export function rocketAt(f: Frame, x: number, y: number, k: number, rot: number, thrust: number) {
  const g = f.g
  const rk = f.assets.rocket.full
  g.save()
  g.translate(x, y)
  g.rotate(rot)
  if (thrust > 0) flame(f, 0, -2 * k, 30 * thrust, 9 * k, Math.min(1.4, k / 2 + 0.3))
  blit(g, rk, -17 * k, -60 * k, 34 * k, 64 * k)
  // Licht der Nase pulsiert im Takt
  glow(g, 0, -55 * k, 10 * k, C.lamp, 0.4 + f.pulse * 0.5)
  g.restore()
}

/** Rauch aus dem echten Start-Foto, am Boden ausgebreitet. */
function plume(f: Frame, x: number, y: number, size: number, a: number) {
  const img = f.assets.photos.plume
  const g = f.g
  if (img) {
    g.save()
    g.globalAlpha = clamp(a)
    g.globalCompositeOperation = 'screen'
    // untere Hälfte des Fotos = Wolke
    const sw = img.width
    const sh = img.height * 0.55
    const w = size * 2
    const h = w * (sh / sw)
    g.imageSmoothingEnabled = true
    g.drawImage(img, 0, img.height - sh, sw, sh, x - w / 2, y - h * 0.7, w, h)
    g.restore()
  }
  smoke(f, x, y - 4, (f.t % 3), 10, Math.floor(f.t / 3), '#d8c8c0', size * 0.6, size * 0.18)
}

function ignite(f: Frame) {
  const { shot } = f
  const p = shot.p
  const g = f.g
  const lift = easeIn(range(p, 0.35, 1))
  const ry = PAD.y - 4 - lift * 150
  setCam(f, lerp(270, 300, easeInOut(p)), lerp(170, 120, lift), lerp(2.3, 1.6, easeInOut(p)), Math.sin(f.t * 0.6) * 0.015)
  f.shake += 0.6 + f.pulse * 0.6 + (1 - lift) * 0.4
  dusk(f, 1)
  wall(f, 1)
  // Rauch + Feuer am Boden
  plume(f, PAD.x, GROUND + 6, 70 + p * 60, 0.35 + p * 0.5)
  rocketAt(f, PAD.x, ry, 1, 0, 0.6 + p * 0.8)
  g.save()
  g.globalCompositeOperation = 'multiply'
  const v = visible(f, 1)
  g.fillStyle = 'rgb(165,130,175)'
  g.fillRect(v.x0, v.y0, v.w, v.h)
  g.restore()
  duskLights(f)
  glow(g, PAD.x, GROUND - 10, 160, '#ff6a3a', 0.35 + f.pulse * 0.2)
  glow(g, PAD.x, ry, 60, '#fff1c9', 0.25)
  // Fragen fliegen mit dem Rauch hoch
  for (const m of f.chat) {
    if (m.section !== 'final' || m.from !== 'fan' || m.at > f.cue.finalStop || m.n % 3) continue
    const age = f.t - m.at
    if (age < 0 || age > 2.4) continue
    const side = hash(m.n) > 0.5 ? 1 : -1
    cometBubble(f, m, age, 2.4, PAD.x + side * (50 + hash(m.n + 2) * 60), GROUND, PAD.x + side * (90 + hash(m.n + 4) * 120), ry - 160, 4.5)
  }
}

/** Stillstand vor der Mauer: Mauer von vorn, Raketen-Nase glüht, ein Herzschlag. */
function stop(f: Frame) {
  const { shot } = f
  const p = shot.p
  const g = f.g
  setCam(f, 240, 135, lerp(1, 1.12, easeOut(p)))
  at(f, 1)
  const v = visible(f, 1)
  g.fillStyle = '#07060d'
  g.fillRect(v.x0, v.y0, v.w, v.h)
  const q = qBlock(true)
  const bs = 40
  const beat = f.tl.beats ? 60 / f.tl.beats.bpm : 0.6
  const heart = Math.exp(-((f.t - f.cue.finalStop) % beat) * 5) * (f.t - f.cue.finalStop < beat * 3.5 ? 1 : 0.3)
  for (let y = Math.floor(v.y0 / bs) * bs; y < v.y1; y += bs) {
    for (let x = Math.floor(v.x0 / bs) * bs; x < v.x1; x += bs) {
      blit(g, q, x, y, bs, bs)
    }
  }
  g.fillStyle = `rgba(7,6,13,${0.55 - heart * 0.3})`
  g.fillRect(v.x0, v.y0, v.w, v.h)
  glow(g, 240, 135, 200, C.red3, 0.15 + heart * 0.35)
  // Rakete von hinten-unten, Nase zur Mauer
  rocketAt(f, 240, 300 + (1 - easeOut(p)) * 40, 3.4, 0, 0.5)
  glow(g, 240, 110, 80, C.lamp, 0.3 + heart * 0.3)
}

/** Durchbruch: Blöcke fliegen auf die Kamera zu, dahinter das All; danach Rakete + Feuerwerk. */
function breakout(f: Frame) {
  const { shot } = f
  const g = f.g
  const t = shot.t
  const two = t < 1.6
  const wobble = Math.sin(f.t * 0.5)
  setCam(f, 240 + wobble * 10, 135, 1.08 + f.bar * 0.03 + (two ? (1.6 - t) * 0.15 : 0), wobble * 0.03)
  f.shake += two ? 2.4 * Math.exp(-t * 2) : 0.25 * f.pulse
  backdrop(f, 'nebulaA', -t * 6, 1)
  planet(f, 120, 380, 170, f.t * 0.025, 0.7)
  fireworksOnBeats(f, f.cue.finalHit, f.tl.outro, 1, { x: 60, y: 20, w: 360, h: 130 }, 62)
  // Rakete fliegt in einer weiten Kurve durchs Bild
  const k = clamp(t / Math.max(2, shot.end - shot.start))
  const ang = lerp(-0.3, 1.0, easeInOut(k))
  const rx = lerp(240, 420, easeInOut(k)) + Math.sin(f.t * 0.8) * 10
  const ry = lerp(330, 40, easeOut(k))
  at(f, 1)
  // Schweif
  for (let i = 0; i < 40; i++) {
    const a = i / 40
    const tx = rx - Math.sin(ang) * a * 140 + (hash(i) - 0.5) * 8 * a
    const ty = ry + Math.cos(ang) * a * 140 + (hash(i + 7) - 0.5) * 8 * a
    g.fillStyle = rgba(i % 3 ? '#ff8a4a' : '#ffd27a', (1 - a) * 0.5)
    g.fillRect(tx - 1.5 * (1 - a) - 0.5, ty - 1.5 * (1 - a) - 0.5, 3 * (1 - a) + 1, 3 * (1 - a) + 1)
  }
  rocketAt(f, rx, ry, 1.6, ang, 1.2)
  astro(f, 150 + Math.sin(f.t) * 8, 110 + Math.sin(f.t * 1.2) * 5, 2, -0.2, 1, 1)
  for (const m of f.chat) {
    if (m.section !== 'final' || m.from !== 'fan' || m.at < f.cue.finalHit) continue
    const age = f.t - m.at
    if (age < 0 || age > 2.4) continue
    cometBubble(f, m, age, 2.4, 240 + (hash(m.n) - 0.5) * 200, 300, 240 + (hash(m.n + 5) - 0.5) * 420, -40, 6)
  }
  // Mauer zerspringt (erste 1,6 s): Blöcke kommen auf die Kamera zu
  if (two) {
    stage(f)
    const q = qBlock(true)
    const n = 34
    for (let i = 0; i < n; i++) {
      const a = hash2(i, 5) * Math.PI * 2
      const d = 30 + hash2(i, 9) * 200
      const e = easeOut(clamp(t / 1.6))
      const z = 1 + e * (2 + hash2(i, 3) * 4)
      const x = 240 + Math.cos(a) * (d * 0.3 + e * d * 1.6)
      const y = 135 + Math.sin(a) * (d * 0.25 + e * d * 1.1)
      const s = 22 * z
      g.save()
      g.globalAlpha = clamp(1.4 - e * 1.4)
      g.translate(x, y)
      g.rotate(e * (hash2(i, 1) - 0.5) * 6)
      blit(g, q, -s / 2, -s / 2, s, s)
      g.restore()
    }
    g.globalAlpha = 1
    glow(g, 240, 135, 260 * (1 - t / 1.6), '#fff1c9', 0.8 * (1 - t / 1.6))
  }
}

export function renderLaunch(f: Frame) {
  switch (f.shot.shot) {
    case 'ignite':
      ignite(f)
      return
    case 'stop':
      stop(f)
      return
    default:
      breakout(f)
  }
}
