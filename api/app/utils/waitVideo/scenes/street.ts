// Szene „Draußen“ (Pixel, Strophe 2): drinnen feiern die Creator (Umhänge, Fan-Symbole, Konfetti), Lencraft steht
// nachts im Regen vor dem Fenster und tippt aufs Handy, an der Wand dreht sich die Uhr immer schneller.
import { at, blit, bubble, popOf, setCam, visible, type Frame } from '../core'
import { confetti, lightCone, rain } from '../fx'
import { clamp, ditherFill, easeIn, easeInOut, easeOut, glow, hash, layer, lerp, rgba, type Cv } from '../kit'
import { C, FAN, HEART, POPCORN, STAR, personFront, type PersonLook } from '../sprites'

const GROUND = 212
const WIN = { x: 252, y: 118, w: 92, h: 52 }
const DOOR = { x: 206, y: 160, w: 24, h: 52 }
const CLOCK = { x: 218, y: 128, r: 11 }
const LEN = { x: 158, y: GROUND - 34 }

let facade: Cv | null = null
const cache = new Map<string, Cv>()
const spr = (k: string, make: () => Cv) => {
  let c = cache.get(k)
  if (!c) cache.set(k, (c = make()))
  return c
}

const GUESTS: PersonLook[] = [
  { skin: '#e0b088', skinD: '#b88a62', shirt: '#2a6acb', shirtD: '#1d4a8f', pants: '#22264a', shoes: '#111', hair: '#d8b040', eye: '#222', cape: '#e0281e' },
  { skin: '#9a6a48', skinD: '#7a5034', shirt: '#2f9a5a', shirtD: '#1f6a3e', pants: '#2a2a2a', shoes: '#111', hair: '#1a1a1a', eye: '#111', cape: '#ffd27a' },
  { skin: '#f0c8a0', skinD: '#c8a078', shirt: '#e0a020', shirtD: '#a07010', pants: '#3a2a5a', shoes: '#111', hair: '#a03020', eye: '#222', cape: '#7c5cff' },
  { skin: '#d8a070', skinD: '#b07a48', shirt: '#c23a8a', shirtD: '#8a2a62', pants: '#20203a', shoes: '#111', hair: '#3b2416', eye: '#222', cape: '#2a6acb' },
  { skin: '#e8b98a', skinD: '#c09060', shirt: '#2a2a3a', shirtD: '#1a1a26', pants: '#4a2a2a', shoes: '#111', hair: '#e8e8f0', eye: '#222', cape: '#ff5a4d' },
]

function buildFacade(): Cv {
  return layer(760, 520, (g) => {
    g.translate(140, 120)
    // Nachbarhäuser (dunkel)
    g.fillStyle = '#0c0a1c'
    g.fillRect(-140, 60, 300, GROUND - 60)
    g.fillRect(400, 40, 300, GROUND - 40)
    for (let i = 0; i < 18; i++) {
      if (hash(i * 5) > 0.5) continue
      g.fillStyle = hash(i) > 0.5 ? '#3a2a10' : '#1a1430'
      g.fillRect(i < 9 ? -120 + (i % 3) * 30 : 420 + (i % 3) * 30, (i < 9 ? 80 : 60) + Math.floor((i % 9) / 3) * 34, 12, 14)
    }
    // Haus: Holzbretter
    ditherFill(g, 172, 96, 196, GROUND - 96, ['#2a1a12', '#3b2618', '#4a3020'])
    for (let y = 100; y < GROUND; y += 5) {
      g.fillStyle = 'rgba(15,8,5,0.6)'
      g.fillRect(172, y, 196, 1)
    }
    g.fillStyle = '#24160e'
    g.fillRect(172, 96, 4, GROUND - 96)
    g.fillRect(364, 96, 4, GROUND - 96)
    // Dach
    for (let i = 0; i < 24; i++) {
      g.fillStyle = i % 2 ? '#3a1410' : '#4a1a14'
      g.fillRect(164 + i, 96 - i, 212 - i * 2, 1)
    }
    // Fenster-Ausschnitt (Party dahinter, dynamisch)
    g.clearRect(WIN.x, WIN.y, WIN.w, WIN.h)
    g.fillStyle = '#6a4a2a'
    g.fillRect(WIN.x - 3, WIN.y - 3, WIN.w + 6, 3)
    g.fillRect(WIN.x - 4, WIN.y + WIN.h, WIN.w + 8, 4)
    g.fillRect(WIN.x - 3, WIN.y, 3, WIN.h)
    g.fillRect(WIN.x + WIN.w, WIN.y, 3, WIN.h)
    g.fillRect(WIN.x + WIN.w / 2 - 1, WIN.y, 2, WIN.h)
    // Tür (geschlossen)
    g.fillStyle = '#5a3a22'
    g.fillRect(DOOR.x, DOOR.y, DOOR.w, DOOR.h)
    g.fillStyle = '#3b2618'
    g.fillRect(DOOR.x + 2, DOOR.y + 3, 9, 20)
    g.fillRect(DOOR.x + 13, DOOR.y + 3, 9, 20)
    g.fillRect(DOOR.x + 2, DOOR.y + 27, 9, 22)
    g.fillRect(DOOR.x + 13, DOOR.y + 27, 9, 22)
    g.fillStyle = C.lamp2
    g.fillRect(DOOR.x + 19, DOOR.y + 26, 2, 2)
    // Straße: nasses Pflaster
    ditherFill(g, -140, GROUND, 760, 400 - GROUND + 120, ['#1a1726', '#121020', '#07060d'])
    for (let y = GROUND + 3; y < 330; y += 4) {
      for (let x = -140 + ((y >> 2) % 2) * 4; x < 620; x += 9) {
        g.fillStyle = 'rgba(0,0,0,0.35)'
        g.fillRect(x, y, 1, 4)
      }
      g.fillStyle = 'rgba(0,0,0,0.3)'
      g.fillRect(-140, y, 760, 1)
    }
    // Laterne
    g.fillStyle = '#16141c'
    g.fillRect(118, 112, 3, GROUND - 112)
    g.fillRect(112, 108, 15, 4)
    g.fillStyle = C.lamp2
    g.fillRect(114, 112, 11, 3)
  })
}

/** Party hinter dem Fenster (Fenster-Koordinaten werden geclippt). */
function party(f: Frame) {
  const g = f.g
  g.save()
  g.beginPath()
  g.rect(WIN.x, WIN.y, WIN.w, WIN.h)
  g.clip()
  const bg = g.createLinearGradient(0, WIN.y, 0, WIN.y + WIN.h)
  bg.addColorStop(0, '#4a1a3a')
  bg.addColorStop(1, '#8a3a2a')
  g.fillStyle = bg
  g.fillRect(WIN.x, WIN.y, WIN.w, WIN.h)
  // Disco-Lichtkegel
  const cols = ['#ff5a4d', '#b9a6ff', '#ffd27a', '#7aa8ff']
  for (let i = 0; i < 4; i++) {
    const a = Math.sin(f.t * (0.9 + i * 0.3) + i) * 0.6
    g.save()
    g.translate(WIN.x + WIN.w / 2, WIN.y + 2)
    g.rotate(a)
    lightCone(g, 0, 0, 2, 40, 70, cols[i]!, 0.25 + f.pulse * 0.2)
    g.restore()
  }
  // Discokugel
  g.fillStyle = '#d8d8e8'
  g.fillRect(WIN.x + WIN.w / 2 - 3, WIN.y + 3, 6, 6)
  g.fillStyle = '#ffffff'
  g.fillRect(WIN.x + WIN.w / 2 - 2 + (Math.floor(f.t * 6) % 3), WIN.y + 4, 1, 1)
  // Gäste tanzen (im Takt hüpfen, Arme hoch auf jedem 2. Schlag)
  GUESTS.forEach((look, i) => {
    const up = (f.beat.index + i) % 2 === 0
    const key = `g${i}:${up ? 1 : 0}`
    const s = spr(key, () => personFront(look, up ? 'up' : 'down'))
    const hop = Math.abs(Math.sin((f.beat.count + i * 0.25) * Math.PI)) * 2.5
    const x = WIN.x + 4 + i * 18 + Math.sin(f.t * 0.8 + i) * 2
    blit(g, s, Math.round(x), Math.round(WIN.y + WIN.h - 32 - hop))
  })
  // Fan-Symbole steigen auf
  for (let i = 0; i < 10; i++) {
    const age = (f.t * 0.35 + hash(i)) % 1
    const x = WIN.x + 6 + hash(i * 3) * (WIN.w - 12) + Math.sin(f.t * 2 + i) * 2
    const y = WIN.y + WIN.h - 10 - age * 50
    g.globalAlpha = Math.sin(age * Math.PI)
    blit(g, i % 2 ? HEART : STAR, Math.round(x), Math.round(y))
  }
  g.globalAlpha = 1
  confetti(f, { x: WIN.x, y: WIN.y - 10, w: WIN.w, h: WIN.h + 10 }, 40, 3)
  g.restore()
}

/** Wanduhr mit (immer schneller) drehenden Zeigern; `k` = Größe. */
function clock(f: Frame, x: number, y: number, r: number, spin: number) {
  const g = f.g
  g.fillStyle = '#2a1d14'
  g.beginPath()
  g.arc(x, y, r + 2, 0, Math.PI * 2)
  g.fill()
  g.fillStyle = '#efe6d2'
  g.beginPath()
  g.arc(x, y, r, 0, Math.PI * 2)
  g.fill()
  for (let i = 0; i < 12; i++) {
    const a = (i / 12) * Math.PI * 2
    g.fillStyle = i % 3 ? '#8a7a6a' : '#2a1d14'
    const s = i % 3 ? r * 0.08 : r * 0.13
    g.fillRect(x + Math.cos(a) * r * 0.82 - s / 2, y + Math.sin(a) * r * 0.82 - s / 2, s, s)
  }
  const hour = spin / 12
  const min = spin
  // Bewegungsunschärfe des Minutenzeigers
  for (let k = 0; k < 5; k++) {
    const a = (min - k * 0.012 * Math.min(8, Math.abs(f.t))) * Math.PI * 2 - Math.PI / 2
    g.strokeStyle = rgba('#2a1d14', k === 0 ? 1 : 0.15)
    g.lineWidth = r * 0.09
    g.beginPath()
    g.moveTo(x, y)
    g.lineTo(x + Math.cos(a) * r * 0.78, y + Math.sin(a) * r * 0.78)
    g.stroke()
  }
  const ha = hour * Math.PI * 2 - Math.PI / 2
  g.strokeStyle = '#b31a12'
  g.lineWidth = r * 0.13
  g.beginPath()
  g.moveTo(x, y)
  g.lineTo(x + Math.cos(ha) * r * 0.5, y + Math.sin(ha) * r * 0.5)
  g.stroke()
  g.fillStyle = '#b31a12'
  g.beginPath()
  g.arc(x, y, r * 0.1, 0, Math.PI * 2)
  g.fill()
}

/** Lencraft vor dem Haus (vorne, Handy in der Hand). */
function lencraft(f: Frame, x: number, y: number, mode: 'phone' | 'down') {
  const look: PersonLook = { ...FAN, face: f.assets.face }
  const s = spr(`len:${mode}:${f.assets.version}`, () => personFront(look, mode))
  blit(f.g, s, x, y)
  if (mode === 'phone') glow(f.g, x + 11, y + 15, 12, C.purple2, 0.4)
}

/** Nachrichten über Lencrafts Handy. */
function phoneBubbles(f: Frame, x: number, y: number, size: number) {
  const list = f.chat.filter((m) => m.section === 'verse2' && m.at <= f.t && f.t - m.at < 3).slice(-2)
  list.forEach((m, i) => {
    const age = f.t - m.at
    bubble(f, m, x, y - (list.length - 1 - i) * size * 2.4 - easeOut(clamp(age / 3)) * size, { pop: popOf(f.t, m.at), alpha: clamp((3 - age) * 2), size, align: 'center' })
  })
}

function drawStreet(f: Frame, spin: number, popcorn: number) {
  const g = f.g
  facade ??= buildFacade()
  at(f, 0.3)
  const v = visible(f, 0.3)
  const sky = g.createLinearGradient(0, v.y0, 0, v.y1)
  sky.addColorStop(0, '#05040c')
  sky.addColorStop(1, '#1c1638')
  g.fillStyle = sky
  g.fillRect(v.x0, v.y0, v.w, v.h)
  for (let i = 0; i < 70; i++) {
    g.fillStyle = `rgba(255,255,255,${0.2 + hash(i) * 0.4})`
    g.fillRect(v.x0 + hash(i * 3) * v.w, v.y0 + hash(i * 7) * v.h * 0.5, 1, 1)
  }
  at(f, 1)
  party(f)
  blit(g, facade, -140, -120)
  clock(f, CLOCK.x, CLOCK.y, CLOCK.r, spin)
  const len = popcorn > 0 ? 'down' : 'phone'
  lencraft(f, LEN.x, LEN.y, len)
  if (popcorn > 0) blit(g, POPCORN, LEN.x + 11, LEN.y + 12 - (1 - easeOut(popcorn)) * 30)
  // Licht: Nacht multiplizieren, dann Fenster/Tür/Laterne
  g.save()
  g.globalCompositeOperation = 'multiply'
  g.fillStyle = 'rgb(110,105,170)'
  g.fillRect(v.x0 - 400, v.y0 - 400, v.w + 800, v.h + 800)
  g.restore()
  const pulse = 0.3 + f.pulse * 0.25
  glow(g, WIN.x + WIN.w / 2, WIN.y + WIN.h / 2, 120, '#ff8a4a', pulse)
  glow(g, WIN.x + WIN.w / 2, WIN.y + WIN.h / 2, 50, '#ffd27a', 0.25)
  lightCone(g, WIN.x + WIN.w / 2, WIN.y + WIN.h, WIN.w, WIN.w * 1.8, 60, '#ff9a5a', 0.16)
  glow(g, DOOR.x + DOOR.w / 2, GROUND - 1, 30, '#ffb347', 0.35)
  g.fillStyle = rgba('#ffd27a', 0.8)
  g.fillRect(DOOR.x, GROUND - 1, DOOR.w, 1)
  lightCone(g, 120, 112, 10, 70, GROUND - 112, '#ffd27a', 0.2)
  glow(g, 120, 113, 24, '#ffd27a', 0.5)
  // Spiegelungen auf der nassen Straße
  for (let i = 0; i < 3; i++) {
    const x = [120, WIN.x + WIN.w / 2, DOOR.x + 12][i]!
    const c = ['#ffd27a', '#ff8a4a', '#ffb347'][i]!
    for (let k = 0; k < 7; k++) {
      g.fillStyle = rgba(c, 0.18 * (1 - k / 7))
      g.fillRect(x - 6 + Math.sin(f.t * 3 + k + i) * 2, GROUND + 3 + k * 4, 12 - k, 1)
    }
  }
  glow(g, LEN.x + 8, LEN.y + 8, 20, '#ff9a5a', 0.15)
}

export function renderStreet(f: Frame) {
  const { shot } = f
  const p = shot.p
  const vp = shot.sec.p
  // Uhr dreht immer schneller (Umdrehungen des Minutenzeigers)
  const spin = vp * vp * 18 + vp * 2
  const popMsg = f.chat.find((m) => m.section === 'verse2' && m.from === 'team')
  const popcorn = popMsg ? clamp((f.t - popMsg.at) / 0.6) : 0
  switch (shot.shot) {
    case 'street': {
      const k = easeInOut(p)
      setCam(f, lerp(200, 250, k), lerp(160, 166, k), lerp(1.9, 2.05, k))
      drawStreet(f, spin, popcorn)
      rain(f, 1, 220)
      rain(f, 1.6, 60, '#c8d4ff')
      at(f, 1)
      phoneBubbles(f, LEN.x + 8, LEN.y - 12, 5)
      return
    }
    case 'party': {
      const k = easeInOut(p)
      setCam(f, lerp(292, 300, k), lerp(145, 143, k), lerp(4.3, 4.8, k))
      drawStreet(f, spin, popcorn)
      rain(f, 1.4, 120)
      return
    }
    case 'outside': {
      const k = easeInOut(p)
      setCam(f, lerp(170, 174, k), lerp(186, 190, k), lerp(5.2, 5.8, k), -0.03)
      drawStreet(f, spin, popcorn)
      // Tropfen vom Kopf
      const g = f.g
      at(f, 1)
      for (let i = 0; i < 4; i++) {
        const age = (f.t * 1.2 + hash(i)) % 1
        g.fillStyle = rgba('#bcd2ff', 1 - age)
        g.fillRect(LEN.x + 3 + i * 3, LEN.y + 6 + age * 20, 0.6, 1.4)
      }
      rain(f, 1, 120)
      rain(f, 1.8, 50, '#c8d4ff')
      at(f, 1)
      phoneBubbles(f, LEN.x + 8, LEN.y - 3, 3.2)
      return
    }
    case 'clock': {
      // Halbtotale (Lencraft mit Popcorn unter der Uhr), dann schneller Zoom in die Uhr → Match-Cut zur Turbo-Anzeige
      const rush = easeIn(clamp((p - 0.55) / 0.45))
      setCam(f, lerp(190, CLOCK.x, rush), lerp(158, CLOCK.y, rush), lerp(3.1, 9.5, rush))
      drawStreet(f, spin, popcorn)
      rain(f, 1, 140)
      at(f, 1)
      glow(f.g, CLOCK.x, CLOCK.y, 30, '#ffd27a', 0.15 + f.pulse * 0.2)
      const team = f.chat.find((m) => m.section === 'verse2' && m.from === 'team')
      if (team && rush < 0.3) bubble(f, team, LEN.x + 8, LEN.y - 10, { pop: popOf(f.t, team.at), alpha: 1 - rush * 3, size: 4, align: 'center' })
      return
    }
    default:
      setCam(f, 240, 160, 2)
      drawStreet(f, spin, popcorn)
  }
}

/**
 * Schluss: Freitag, 18 Uhr – der Regen hört auf, die Tür geht auf, warmes Licht flutet heraus und Lencraft geht hinein.
 * `p` 0…1 über den Shot.
 */
export function renderDoor(f: Frame, p: number) {
  const g = f.g
  const open = easeOut(clamp((p - 0.08) / 0.35))
  const walk = easeInOut(clamp((p - 0.3) / 0.55))
  setCam(f, lerp(196, DOOR.x + 12, easeInOut(p)), lerp(170, 184, easeInOut(p)), lerp(3.4, 5.2, easeInOut(p)))
  facade ??= buildFacade()
  at(f, 0.3)
  const v = visible(f, 0.3)
  g.fillStyle = '#0c0a1e'
  g.fillRect(v.x0, v.y0, v.w, v.h)
  at(f, 1)
  party(f)
  blit(g, facade, -140, -120)
  clock(f, CLOCK.x, CLOCK.y, CLOCK.r, 18.5)
  // Offene Tür: warmes Licht innen, Türblatt klappt nach innen
  g.fillStyle = '#ffe2a8'
  g.fillRect(DOOR.x, DOOR.y, DOOR.w * open, DOOR.h)
  g.fillStyle = '#5a3a22'
  g.fillRect(DOOR.x + DOOR.w * open, DOOR.y, DOOR.w * (1 - open), DOOR.h)
  // Lencraft geht zur Tür und hinein
  const lx = lerp(LEN.x, DOOR.x + 4, walk)
  g.globalAlpha = 1 - clamp((walk - 0.75) / 0.25)
  lencraft(f, Math.round(lx), LEN.y, 'down')
  g.globalAlpha = 1
  g.save()
  g.globalCompositeOperation = 'multiply'
  g.fillStyle = `rgb(${Math.round(lerp(110, 200, open))},${Math.round(lerp(105, 170, open))},${Math.round(lerp(170, 190, open))})`
  g.fillRect(v.x0 - 400, v.y0 - 400, v.w + 800, v.h + 800)
  g.restore()
  glow(g, DOOR.x + DOOR.w / 2, DOOR.y + DOOR.h / 2, 60 + open * 140, '#ffd27a', 0.25 + open * 0.55)
  lightCone(g, DOOR.x + DOOR.w / 2, DOOR.y + DOOR.h - 2, DOOR.w, DOOR.w * 4, 40, '#ffd27a', 0.35 * open)
  glow(g, WIN.x + WIN.w / 2, WIN.y + WIN.h / 2, 120, '#ff8a4a', 0.35 + f.pulse * 0.2)
}
