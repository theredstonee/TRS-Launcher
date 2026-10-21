// Szene „All“ (filmisch): Refrains 1 + 2 – Pixel-Astronaut über dem Server-Planeten, echte NASA-Fotos als
// Hintergrund (farblich angepasst), Parallax-Sterne, „Wann?“-Kometen, Licht-Lecks, Puls im Takt.
import { at, blit, bubble, photoCover, popOf, setCam, soft, stage, visible, type Frame } from '../core'
import { cometBubble, stars } from '../fx'
import { clamp, easeInOut, easeOut, glow, hash, lerp, rgba } from '../kit'
import type { PhotoId } from '../assets'

/** Hintergrund: Foto (oder Verlauf), Sterne in 3 Ebenen, Licht-Leck. */
export function backdrop(f: Frame, photo: PhotoId | null, drift = 0, bright = 1) {
  const g = f.g
  at(f, 0)
  const v = visible(f, 0)
  const grd = g.createLinearGradient(0, v.y0, 0, v.y1)
  grd.addColorStop(0, '#05040c')
  grd.addColorStop(0.6, '#140b2c')
  grd.addColorStop(1, '#2a0d24')
  g.fillStyle = grd
  g.fillRect(v.x0, v.y0, v.w, v.h)
  const img = photo ? f.assets.photos[photo] ?? null : null
  if (img) {
    g.globalAlpha = bright
    photoCover(f, img, 0.08, drift, 1.25)
    g.globalAlpha = 1
  } else {
    // Ersatz-Nebel aus weichen Kreisen
    at(f, 0.08)
    for (let i = 0; i < 7; i++) glow(g, 100 + hash(i) * 300, 60 + hash(i + 9) * 160, 90 + hash(i + 3) * 80, i % 2 ? '#7c2a8a' : '#b31a3a', 0.18)
  }
  stars(f, 0.15, 11, 380, 0.7)
  stars(f, 0.4, 12, 160, 0.9)
  stars(f, 0.75, 13, 60, 1)
  // Licht-Leck (warm/lila), wandert langsam
  stage(f)
  const lx = 240 + Math.sin(f.t * 0.13) * 260
  const leak = g.createRadialGradient(lx, -40, 0, lx, -40, 320)
  leak.addColorStop(0, `rgba(255,120,90,${0.12 + f.bar * 0.06})`)
  leak.addColorStop(1, 'rgba(255,120,90,0)')
  g.globalCompositeOperation = 'screen'
  g.fillStyle = leak
  g.fillRect(-200, -200, 880, 670)
  const leak2 = g.createRadialGradient(560, 300, 0, 560, 300, 300)
  leak2.addColorStop(0, 'rgba(140,90,255,0.14)')
  leak2.addColorStop(1, 'rgba(140,90,255,0)')
  g.fillStyle = leak2
  g.fillRect(-200, -200, 880, 670)
  g.globalCompositeOperation = 'source-over'
}

/** Server-Planet (Kugel aus der Nachtkarte) mit Atmosphäre und Satelliten-Ring. */
export function planet(f: Frame, x: number, y: number, r: number, rot: number, depth = 1) {
  const g = f.g
  at(f, depth)
  const lights = 0.8 + f.pulse * 0.5 + f.energy * 0.3
  // Satelliten hinten
  ring(f, x, y, r, rot, false)
  // Atmosphäre außen
  const atm = g.createRadialGradient(x, y, r * 0.92, x, y, r * 1.32)
  atm.addColorStop(0, `rgba(255,90,77,${0.4 + f.pulse * 0.2})`)
  atm.addColorStop(0.25, 'rgba(160,80,255,0.2)')
  atm.addColorStop(1, 'rgba(120,60,255,0)')
  g.globalCompositeOperation = 'lighter'
  g.fillStyle = atm
  g.fillRect(x - r * 1.4, y - r * 1.4, r * 2.8, r * 2.8)
  g.globalCompositeOperation = 'source-over'
  const ball = f.assets.sphere.render(f.assets.planet, rot, lights)
  soft(g, ball, x - r, y - r, r * 2, r * 2)
  // Sonnen-Sichel rechts außen
  g.save()
  g.globalCompositeOperation = 'lighter'
  g.strokeStyle = 'rgba(255,190,160,0.5)'
  g.lineWidth = Math.max(0.6, r * 0.012)
  g.beginPath()
  g.arc(x, y, r * 0.995, -1.25, 0.65)
  g.stroke()
  g.restore()
  ring(f, x, y, r, rot, true)
}

/** Kleine Server-Blöcke auf einer Umlaufbahn (blinkende rote Lichter). */
function ring(f: Frame, x: number, y: number, r: number, rot: number, front: boolean) {
  const g = f.g
  const n = 12
  for (let i = 0; i < n; i++) {
    const a = (i / n) * Math.PI * 2 + rot * Math.PI * 2 * 1.6
    const s = Math.sin(a)
    if ((s > 0) !== front) continue
    const px = x + Math.cos(a) * r * 1.45
    const py = y + s * r * 0.32 - Math.cos(a) * r * 0.12
    const sz = Math.max(1.5, r * 0.05) * (front ? 1.15 : 0.8)
    g.globalAlpha = front ? 1 : 0.55
    g.fillStyle = '#2a2a36'
    g.fillRect(px - sz / 2, py - sz / 2, sz, sz)
    g.fillStyle = '#4d4d5e'
    g.fillRect(px - sz / 2, py - sz / 2, sz, sz * 0.3)
    const blink = Math.sin(f.t * 4 + i * 1.3) > 0.3
    if (blink) {
      g.fillStyle = '#ff5a4d'
      g.fillRect(px - 0.5, py - 0.5, 1, 1)
      glow(g, px, py, sz * 2, '#ff3a20', 0.4)
    }
    g.globalAlpha = 1
  }
}

/** Pixel-Astronaut (gedreht, schwebend), `k` = Pixelgröße. */
export function astro(f: Frame, x: number, y: number, k: number, rot: number, depth = 1, wave = 0) {
  const g = f.g
  at(f, depth)
  const spr = f.assets.astronaut
  glow(g, x, y, 40 * k / 2, '#8f7aff', 0.18)
  g.save()
  g.translate(x, y)
  g.rotate(rot)
  if (wave > 0) g.rotate(Math.sin(f.t * 6) * 0.05 * wave)
  blit(g, spr, -spr.width * k / 2, -spr.height * k / 2, spr.width * k, spr.height * k)
  // Visier-Spiegelung (Planetenlicht) + Puls
  g.globalCompositeOperation = 'lighter'
  g.fillStyle = rgba('#ff5a4d', 0.25 + f.pulse * 0.35)
  g.fillRect((-spr.width / 2 + 9) * k, (-spr.height / 2 + 4) * k, 8 * k, 5 * k)
  g.restore()
  // Kopfhörer-Leuchten im Takt
  glow(g, x - 8 * k + Math.cos(rot) * 0, y - 10 * k, 8 * k, '#ff3a20', 0.25 + f.pulse * 0.4)
}

/** „Wann?“-Kometen des aktuellen Teils: steigen vom Planeten auf und fliegen am Astronauten vorbei. */
function comets(f: Frame, fromX: number, fromY: number, spreadX: number, life: number, toY: number, size: number, swirl = 0) {
  const sec = f.shot.section
  for (const m of f.chat) {
    if (m.section !== sec || m.from !== 'fan') continue
    const age = f.t - m.at
    if (age < 0 || age > life) continue
    const sx = fromX + (hash(m.n * 7 + 1) - 0.5) * spreadX
    const ex = sx + (hash(m.n * 3 + 2) - 0.5) * 220 + swirl * Math.sin(m.n) * 120
    cometBubble(f, m, age, life, sx, fromY, ex, toY - hash(m.n) * 40, size)
  }
}

/** Antworten des Teams als Funkspruch neben dem Astronauten. */
function radio(f: Frame, x: number, y: number, size: number) {
  const list = f.chat.filter((m) => m.section === f.shot.section && m.from === 'team' && m.at <= f.t && f.t - m.at < 3.5)
  list.slice(-1).forEach((m) => {
    const age = f.t - m.at
    bubble(f, m, x, y - easeOut(clamp(age / 3.5)) * 6, { pop: popOf(f.t, m.at, 0.4), alpha: clamp((3.5 - age) * 2), size })
  })
}

export function renderSpace(f: Frame) {
  const { shot } = f
  const p = shot.p
  const t = shot.t
  const two = shot.section === 'chorus2' || shot.section === 'final'
  const rotSpeed = two ? 0.022 : 0.008
  const rot = f.t * rotSpeed
  const bob = Math.sin(f.t * 1.1) * 4
  switch (shot.shot) {
    case 'breach':
    case 'limb': {
      // Aufstieg über die Atmosphäre: Foto vom Erdrand, Astronaut steigt ins Bild
      const k = easeInOut(p)
      setCam(f, 240, lerp(150, 120, k), lerp(1.25, 1.05, k), lerp(0.05, 0, k))
      const g = f.g
      at(f, 0)
      const v = visible(f, 0)
      g.fillStyle = '#05040c'
      g.fillRect(v.x0, v.y0, v.w, v.h)
      const img = f.assets.photos.limb
      if (img) {
        // Erdrand (Foto): Horizont wandert nach unten = wir steigen auf
        at(f, 0.3)
        const vv = visible(f, 0.3)
        const w = Math.max(vv.w * 1.2, vv.h * 1.4 * img.width / img.height)
        const h = w * img.height / img.width
        const horizon = shot.shot === 'limb' ? lerp(215, 250, k) : lerp(190, 300, k)
        soft(g, img, 240 - w / 2 + (shot.shot === 'limb' ? -k * 40 : 0), horizon - h * 0.87, w, h)
      } else {
        backdrop(f, null)
        planet(f, 240, 420, 260, rot, 0.5)
      }
      stars(f, 0.5, 21, 120, 0.8)
      if (shot.shot === 'limb') astro(f, lerp(120, 330, k), 140 + bob, 3.2, lerp(-0.4, 0.2, k), 1.3, 1)
      else astro(f, 240 + Math.sin(f.t * 0.6) * 10, lerp(330, 150, easeOut(p)) + bob, 2.4, lerp(0.5, -0.12, k), 1)
      // Sonnen-Blendenfleck
      at(f, 0)
      glow(g, 150, 70, 120, '#fff1c9', 0.25 + f.bar * 0.1)
      return
    }
    case 'float':
    case 'comets': {
      const comet = shot.shot === 'comets'
      const k = easeInOut(clamp(shot.sec.p))
      setCam(f, lerp(230, 250, k), lerp(140, 120, k) - (comet ? p * 10 : 0), lerp(1, 1.12, k), Math.sin(f.t * 0.2) * 0.02)
      backdrop(f, 'nebulaA', -k * 30)
      planet(f, 330, 400, 190, rot, 0.85)
      if (comet) comets(f, 330, 230, 300, 2.8, -80, 8.5)
      astro(f, 200 + Math.sin(f.t * 0.5) * 12, 115 + bob, 2.4, Math.sin(f.t * 0.4) * 0.25 - 0.1, 1, comet ? 1 : 0)
      at(f, 1)
      radio(f, 228, 74, 6)
      return
    }
    case 'orbit': {
      // Kamera kreist: Astronaut auf einer Ellipse um den Planeten, Hintergrund schwenkt mit
      const a = t * 0.55 + 2.2
      const front = Math.sin(a) > 0
      setCam(f, 240 + Math.cos(a) * 20, 140, lerp(1.05, 1.2, p), Math.cos(a) * 0.06)
      backdrop(f, 'nebulaB', Math.cos(a) * 80)
      const ax = 240 + Math.cos(a) * 165
      const ay = 140 + Math.sin(a) * 38 + bob
      const sc = 1.6 + Math.sin(a) * 0.7
      if (!front) astro(f, ax, ay, sc, Math.cos(a) * 0.4, 1)
      planet(f, 240, 140, 82, rot)
      if (front) astro(f, ax, ay, sc, Math.cos(a) * 0.4, 1)
      comets(f, 240, 140, 140, 2.4, -120, 8)
      at(f, 1)
      radio(f, 300, 60, 6)
      return
    }
    case 'swarm': {
      const g = f.g
      setCam(f, 240, 140, lerp(1.2, 1.4, p) + f.bar * 0.03, Math.sin(f.t * 0.5) * 0.04)
      backdrop(f, 'nebulaB', -p * 60, 1)
      planet(f, 240, 210, 120, rot)
      // Blasen-Strudel um die Mitte
      for (const m of f.chat) {
        if (m.section !== f.shot.section || m.from !== 'fan') continue
        const age = f.t - m.at
        if (age < 0 || age > 3.2) continue
        const ang = m.n * 2.4 + age * 1.6
        const rad = 40 + age * 45
        cometBubble(f, m, age, 3.2, 240 + Math.cos(ang) * 30, 160 + Math.sin(ang) * 15, 240 + Math.cos(ang + 1.2) * rad * 2, 120 + Math.sin(ang + 1.2) * rad, 7.5)
      }
      astro(f, 240 + Math.sin(f.t * 0.9) * 30, 95 + bob, 2.6, Math.sin(f.t * 0.7) * 0.3, 1, 1)
      at(f, 1)
      radio(f, 270, 40, 6)
      // Takt-Blitze im Takt
      stage(f)
      if (!f.reduced) {
        g.globalCompositeOperation = 'lighter'
        g.fillStyle = `rgba(255,90,77,${f.bar * 0.12})`
        g.fillRect(-300, -300, 1080, 870)
        g.globalCompositeOperation = 'source-over'
      }
      return
    }
    default:
      backdrop(f, 'nebulaA')
      planet(f, 330, 400, 190, rot)
      astro(f, 200, 120, 2.4, 0)
  }
}
