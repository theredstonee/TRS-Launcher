// Szene „Finale“ (Outro): Countdown in der Raketen-Kapsel, Start ins All mit Feuerwerk, Klappuhr springt auf
// „FR 18:00“, zum Schluss geht die Tür zur Party auf.
import { at, bubble, popOf, setCam, visible, type Frame } from '../core'
import { fireworksOnBeats, smoke } from '../fx'
import { clamp, easeIn, easeInOut, glow, hash, lerp, range, rgba, roundRect } from '../kit'
import { C, pixelText, pixelTextWidth } from '../sprites'
import { flame, rocketAt } from './launch'
import { astro, backdrop, planet } from './space'
import { renderDoor } from './street'

/** Kapsel von innen: rundes Fenster ins All, Konsole mit 10 Lichtern + Anzeige, Countdown-Nachrichten. */
function cockpit(f: Frame) {
  const g = f.g
  const p = f.shot.p
  const lit = 10 - Math.floor(clamp(p) * 10)
  const shakeIn = range(p, 0.75, 1)
  f.shake += shakeIn * 1.2
  setCam(f, 240, lerp(150, 140, easeInOut(p)), lerp(1.25, 1.55, easeIn(p)))
  // Durchs Fenster: All mit Planet
  backdrop(f, 'stars', 0, 0.9)
  planet(f, 300, 150, 90, f.t * 0.01, 0.35)
  at(f, 1)
  const v = visible(f, 1)
  // Kapselwand mit rundem Fenster
  g.fillStyle = '#16141f'
  g.beginPath()
  g.rect(v.x0, v.y0, v.w, v.h)
  g.arc(240, 105, 78, 0, Math.PI * 2)
  g.fill('evenodd')
  g.strokeStyle = '#3a3848'
  g.lineWidth = 6
  g.beginPath()
  g.arc(240, 105, 80, 0, Math.PI * 2)
  g.stroke()
  for (let i = 0; i < 12; i++) {
    const a = (i / 12) * Math.PI * 2
    g.fillStyle = '#5a586a'
    g.fillRect(240 + Math.cos(a) * 80 - 1.5, 105 + Math.sin(a) * 80 - 1.5, 3, 3)
  }
  g.fillStyle = 'rgba(255,255,255,0.05)'
  g.beginPath()
  g.ellipse(210, 70, 40, 18, -0.5, 0, Math.PI * 2)
  g.fill()
  // Konsole mit 10 Lichtern (eines geht pro Schritt aus)
  g.fillStyle = '#1f1d2a'
  g.fillRect(v.x0, 190, v.w, v.y1 - 190)
  g.fillStyle = '#2b2938'
  g.fillRect(v.x0, 190, v.w, 3)
  for (let i = 0; i < 10; i++) {
    const x = 150 + i * 18
    const on = i < lit
    g.fillStyle = '#0d0c14'
    g.fillRect(x - 1, 197, 10, 10)
    g.fillStyle = on ? C.red3 : '#3a1410'
    g.fillRect(x, 198, 8, 8)
    if (on) {
      g.fillStyle = C.red5
      g.fillRect(x + 1, 199, 3, 2)
      glow(g, x + 4, 202, 12, C.red3, 0.45 + f.pulse * 0.2)
    }
  }
  // Ziffern-Anzeige
  g.fillStyle = '#0a0910'
  g.fillRect(222, 214, 36, 22)
  pixelText(g, String(Math.max(0, lit)).padStart(2, ' '), 240 - pixelTextWidth('00', 2) / 2, 218, 2, C.lamp)
  glow(g, 240, 225, 30, C.lamp, 0.25)
  // Hebel
  g.fillStyle = '#5a586a'
  g.fillRect(300, 214, 4, 14)
  g.fillStyle = C.red3
  g.fillRect(298, 210 + shakeIn * 8, 8, 5)
  // Funk-Bildschirm links mit den Countdown-Nachrichten
  g.fillStyle = '#0a0910'
  g.fillRect(84, 198, 56, 40)
  const msgs = f.chat.filter((m) => m.section === 'outro' && m.from === 'team' && m.at <= f.t).slice(-2)
  msgs.forEach((m, i) => bubble(f, m, 112, 202 + i * 15, { pop: popOf(f.t, m.at), size: 5, align: 'center' }))
  // Pilot (Hinterkopf mit Kopfhörern) im Vordergrund
  at(f, 1.3)
  g.fillStyle = '#2a1b4d'
  g.fillRect(222, 236, 36, 40)
  g.fillStyle = C.red3
  g.fillRect(216, 246, 8, 14)
  g.fillRect(256, 246, 8, 14)
  g.fillStyle = '#1b1b22'
  g.fillRect(220, 232, 40, 4)
  at(f, 1)
  glow(g, 240, 120, 120, '#6f86ff', 0.12)
}

/** Start ins All: Rakete steigt aus dem Rauch (Foto), dann Warp-Linien und Feuerwerk auf jedem Schlag. */
function blastoff(f: Frame) {
  const g = f.g
  const t = f.shot.t
  const p = f.shot.p
  const climb = easeIn(clamp(t / 2.4))
  f.shake += 2.6 * Math.exp(-t * 0.9) + f.pulse * 0.5
  setCam(f, 240, 135, 1.1 + f.bar * 0.04, Math.sin(f.t * 0.7) * 0.03)
  backdrop(f, 'nebulaB', -p * 40, 1)
  planet(f, 240, lerp(380, 620, easeIn(clamp(t / 4))), 220, f.t * 0.02, 0.6)
  at(f, 1)
  const speed = clamp((t - 1.2) / 2)
  if (speed > 0) {
    g.globalCompositeOperation = 'lighter'
    for (let i = 0; i < 70; i++) {
      const x = 240 + (hash(i) - 0.5) * 520
      const y = ((hash(i * 3) * 400 + f.t * (300 + hash(i) * 300)) % 400) - 120
      g.fillStyle = rgba(i % 4 ? '#b9a6ff' : '#ffd27a', 0.35 * speed)
      g.fillRect(x, y, 0.8, 18 * speed)
    }
    g.globalCompositeOperation = 'source-over'
  }
  fireworksOnBeats(f, f.cue.launch, f.cue.fade, 1, { x: 50, y: 10, w: 380, h: 150 }, 66)
  const ry = lerp(300, 190, climb) + Math.sin(f.t * 3) * 2
  if (t < 3) {
    const img = f.assets.photos.plume
    if (img) {
      g.save()
      g.globalAlpha = clamp(1 - t / 3) * 0.85
      g.globalCompositeOperation = 'screen'
      g.imageSmoothingEnabled = true
      const w = 420
      g.drawImage(img, 240 - w / 2, ry - 40, w, w * (img.height / img.width))
      g.restore()
    }
    smoke(f, 240, ry + 40, t, 14, 3, '#d8c8c0', 120, 26)
  }
  flame(f, 240, ry + 4, 70, 18, 1)
  rocketAt(f, 240, ry, 2.2, Math.sin(f.t * 2) * 0.03, 2.4)
  astro(f, 110 + Math.sin(f.t) * 10, 90, 1.8, -0.3 + Math.sin(f.t * 2) * 0.2, 0.9, 1)
  at(f, 1)
  const m = f.chat.find((c) => c.section === 'outro' && c.from === 'team' && c.at >= f.cue.launch - 0.01)
  if (m && f.t - m.at < 4) bubble(f, m, 240, 30, { pop: popOf(f.t, m.at), alpha: clamp((4 - (f.t - m.at)) * 2), size: 7, align: 'center' })
}

/** Klappuhr im All: „FR 17:59“ → „FR 18:00“ (Objekt, kein Overlay-Text). */
function friday(f: Frame) {
  const g = f.g
  const { shot } = f
  const p = shot.p
  const t = shot.t
  setCam(f, 240, 135, lerp(1.0, 1.18, easeInOut(p)), Math.sin(f.t * 0.3) * 0.02)
  backdrop(f, 'stars', p * 20, 0.9)
  planet(f, 380, 330, 120, f.t * 0.01, 0.6)
  const flipAt = Math.min(2.2, (shot.end - shot.start) * 0.3)
  const before = 'FR 17:59'
  const after = 'FR 18:00'
  at(f, 1)
  const px = 3.2
  const cw = 7 * px
  const ch = 11 * px
  const total = before.length * (cw + 4) - 4
  const x0 = 240 - total / 2
  const y0 = 120 - ch / 2
  // Rakete kreist hinter der Uhr
  const a = f.t * 0.6
  const behind = Math.sin(a) < 0
  const rocket = () => rocketAt(f, 240 + Math.cos(a) * 170, 120 + Math.sin(a) * 50, 0.9, a + Math.PI, 0.7)
  if (behind) rocket()
  g.fillStyle = 'rgba(12,10,20,0.92)'
  roundRect(g, x0 - 14, y0 - 14, total + 28, ch + 28, 8)
  g.fill()
  g.strokeStyle = rgba('#ff5a4d', 0.5 + f.pulse * 0.3)
  g.lineWidth = 1.2
  g.stroke()
  for (let i = 0; i < before.length; i++) {
    const a0 = before[i]!
    const b0 = after[i]!
    if (a0 === ' ') continue
    const x = x0 + i * (cw + 4)
    const flipT = (t - flipAt - i * 0.05) / 0.4
    const changing = a0 !== b0
    const char = changing && flipT >= 0.5 ? b0 : a0
    const sy = changing && flipT > 0 && flipT < 1 ? Math.abs(Math.cos(flipT * Math.PI)) : 1
    g.fillStyle = '#1d1a2a'
    roundRect(g, x, y0, cw, ch, 2.5)
    g.fill()
    g.save()
    g.translate(x + cw / 2, y0 + ch / 2)
    g.scale(1, Math.max(0.02, sy))
    pixelText(g, char, -2.5 * px, -3.5 * px, px, char === ':' ? C.red4 : '#fff1c9')
    g.restore()
    g.fillStyle = 'rgba(0,0,0,0.5)'
    g.fillRect(x, y0 + ch / 2 - 0.4, cw, 0.8)
  }
  const flash = t > flipAt ? Math.exp(-(t - flipAt) * 2.5) : 0
  glow(g, 240, 120, 180, '#ffd27a', 0.15 + flash * 0.6)
  if (!behind) rocket()
  fireworksOnBeats(f, shot.start + flipAt, shot.end, 2, { x: 100, y: 20, w: 280, h: 50 }, 36)
  // Lencraft gibt auf (versprochen)
  for (const m of f.chat) {
    if (m.section !== 'outro' || m.from !== 'fan') continue
    const age = f.t - m.at
    if (age < 0 || age > 5) continue
    bubble(f, m, 240, 176 + (m.n % 2) * 16, { pop: popOf(f.t, m.at), alpha: clamp((5 - age) * 1.5), size: 6, align: 'center' })
  }
}

export function renderFinale(f: Frame) {
  switch (f.shot.shot) {
    case 'console':
      cockpit(f)
      return
    case 'launch':
      blastoff(f)
      return
    case 'friday':
      friday(f)
      return
    default:
      renderDoor(f, f.shot.p)
  }
}
