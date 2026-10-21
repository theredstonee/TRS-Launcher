// Szene „Zimmer“ (Pixel): Intro, Strophe 1 (Entwickler am Schreibtisch, Creeper-Explosion) und beide Pre-Chorusse
// (Kopfhörer, Turbo-Anzeige, das Zimmer hebt ab).
import { at, blit, bubble, popOf, setCam, soft, visible, type Frame } from '../core'
import { debris, lightCone, pixLine, smoke, stars } from '../fx'
import { clamp, decay, ditherFill, easeIn, easeInOut, easeOut, easeOutBack, glow, hash, hash2, layer, lerp, range, rgba, type Cv } from '../kit'
import { C, DEV, MUG, PHONE, faceHD, personBack } from '../sprites'

// Welt-Koordinaten des Zimmers (Bühne 480×270, Mitte 240/135)
const X0 = -140
const Y0 = -120
const WW = 760
const WH = 520
const FLOOR = 200
const WIN = { x: 148, y: 116, w: 40, h: 40 }
const MON = { x: 218, y: 138, w: 44, h: 31 }
const SCR = { x: 220, y: 140, w: 40, h: 26 }
const DEV_X = 231
const DEV_Y = 157
/** Wand-Fackeln (Redstone). */
const TORCHES = [138, 344]
const DESK_LAMP = { x: 280, y: 180 }
const LAMP = { x: 334, y: 190 }
/** Redstone-Leitung (Kunst-Koordinaten) – Signale laufen von vorn nach hinten. */
const WIRE: [number, number][] = [[290, 185], [290, 203], [310, 206], [334, 206], [338, 200], [338, 199]]
const WIRE2: [number, number][] = [[205, 204], [180, 207], [150, 207], [140, 204]]

let base: Cv | null = null
const cache = new Map<string, Cv>()
const spr = (k: string, make: () => Cv) => {
  let c = cache.get(k)
  if (!c) cache.set(k, (c = make()))
  return c
}

/** Statische Ebene: Wand, Boden, Fensterrahmen (Scheibe durchsichtig), Regal, Schreibtisch, Stuhl-Fuß. */
function buildBase(): Cv {
  return layer(WW, WH, (g) => {
    g.translate(-X0, -Y0)
    // Wand: Bretter mit Fugen, oben dunkler (Dithering)
    ditherFill(g, X0, Y0, WW, FLOOR - Y0, [C.ink, C.wallD, C.wallA, C.wallB])
    for (let y = 92; y < FLOOR; y += 6) {
      g.fillStyle = C.wallD
      g.fillRect(X0, y, WW, 1)
      for (let x = X0 + ((y / 6) % 2) * 9; x < X0 + WW; x += 18) g.fillRect(x, y, 1, 6)
    }
    for (let i = 0; i < 260; i++) {
      g.fillStyle = hash(i) > 0.5 ? C.wallC : C.wallD
      g.fillRect(X0 + Math.floor(hash(i * 3) * WW), 92 + Math.floor(hash(i * 7) * (FLOOR - 92)), 2, 1)
    }
    // Ecken des Zimmers abdunkeln
    g.fillStyle = 'rgba(5,4,12,0.55)'
    g.fillRect(X0, Y0, 130 - X0, WH)
    g.fillRect(352, Y0, X0 + WW - 352, WH)
    // Fußleiste + Boden (Holz, Perspektive durch Abdunkeln)
    g.fillStyle = C.woodA
    g.fillRect(X0, FLOOR - 3, WW, 3)
    ditherFill(g, X0, FLOOR, WW, WH + Y0 - FLOOR, [C.woodB, C.woodA, '#24170e', C.ink])
    for (let y = FLOOR + 4; y < FLOOR + 80; y += 5 + Math.floor((y - FLOOR) / 12)) {
      g.fillStyle = 'rgba(10,6,4,0.45)'
      g.fillRect(X0, y, WW, 1)
    }
    // Fenster: Rahmen (Scheibe bleibt durchsichtig, Himmel kommt dynamisch dahinter)
    g.clearRect(WIN.x, WIN.y, WIN.w, WIN.h)
    g.fillStyle = C.woodC
    g.fillRect(WIN.x - 3, WIN.y - 3, WIN.w + 6, 3)
    g.fillRect(WIN.x - 3, WIN.y + WIN.h, WIN.w + 6, 4)
    g.fillRect(WIN.x - 3, WIN.y, 3, WIN.h)
    g.fillRect(WIN.x + WIN.w, WIN.y, 3, WIN.h)
    g.fillRect(WIN.x + WIN.w / 2 - 1, WIN.y, 2, WIN.h)
    g.fillRect(WIN.x, WIN.y + WIN.h / 2 - 1, WIN.w, 2)
    g.fillStyle = C.woodD
    g.fillRect(WIN.x - 3, WIN.y - 3, WIN.w + 6, 1)
    // Regal mit Büchern + Poster
    g.fillStyle = C.woodB
    g.fillRect(290, 146, 46, 3)
    const books = ['#7a2a8a', '#2a5a9a', '#b31a12', '#c99a3a', '#3a7a4a', '#5a3a8a', '#9a3a3a']
    let bx = 292
    for (let i = 0; i < 9; i++) {
      const w = 3 + (i % 3)
      const h = 9 + ((i * 5) % 5)
      g.fillStyle = books[i % books.length]!
      g.fillRect(bx, 146 - h, w, h)
      g.fillStyle = 'rgba(255,255,255,0.15)'
      g.fillRect(bx, 146 - h + 2, w, 1)
      bx += w + (i === 5 ? 4 : 0)
    }
    g.fillStyle = '#2a1d14'
    g.fillRect(286, 104, 30, 24)
    g.fillStyle = '#16121e'
    g.fillRect(288, 106, 26, 20)
    g.fillStyle = C.red1
    g.fillRect(291, 116, 20, 1)
    g.fillRect(300, 109, 1, 14)
    g.fillStyle = C.red3
    g.fillRect(299, 115, 3, 3)
    g.fillRect(291, 115, 2, 2)
    g.fillRect(309, 115, 2, 2)
    // Schreibtisch
    g.fillStyle = C.woodC
    g.fillRect(194, 180, 98, 4)
    g.fillStyle = C.woodD
    g.fillRect(194, 180, 98, 1)
    g.fillStyle = C.woodA
    g.fillRect(195, 184, 3, 16)
    g.fillRect(288, 184, 3, 16)
    g.fillStyle = C.woodB
    g.fillRect(262, 184, 26, 12)
    g.fillStyle = C.woodA
    g.fillRect(262, 189, 26, 1)
    g.fillStyle = C.lamp2
    g.fillRect(273, 187, 4, 1)
    // Redstone-Leitung (aus)
    g.fillStyle = C.red0
    for (const path of [WIRE, WIRE2]) for (let i = 0; i + 1 < path.length; i++) pixLine(g, path[i]![0], path[i]![1], path[i + 1]![0], path[i + 1]![1], 1)
    // Hebel links, Redstone-Lampe rechts (Gehäuse)
    g.fillStyle = C.stoneB
    g.fillRect(136, 201, 6, 3)
    g.fillStyle = C.woodC
    g.fillRect(138, 196, 1, 5)
    g.fillStyle = '#3a2416'
    g.fillRect(LAMP.x - 1, LAMP.y - 11, 12, 12)
    // Redstone-Fackeln an der Wand (Stiel)
    for (const x of TORCHES) {
      g.fillStyle = C.woodB
      g.fillRect(x, 128, 1, 6)
    }
    // Schreibtischlampe (Fuß + Arm)
    g.fillStyle = '#2a2a36'
    g.fillRect(DESK_LAMP.x - 3, DESK_LAMP.y - 1, 7, 1)
    g.fillRect(DESK_LAMP.x, DESK_LAMP.y - 12, 1, 11)
    g.fillRect(DESK_LAMP.x - 5, DESK_LAMP.y - 13, 6, 1)
    g.fillStyle = '#3a3a48'
    g.fillRect(DESK_LAMP.x - 8, DESK_LAMP.y - 14, 5, 3)
  })
}

/** Nachthimmel hinter dem Fenster (mit Mond) – `lift` 0…1 = das Zimmer steigt (Himmel wird All). */
function sky(f: Frame, lift: number) {
  const g = f.g
  g.save()
  g.beginPath()
  g.rect(WIN.x, WIN.y, WIN.w, WIN.h)
  g.clip()
  const grd = g.createLinearGradient(0, WIN.y, 0, WIN.y + WIN.h)
  grd.addColorStop(0, lift > 0.5 ? '#05040c' : '#0d0b26')
  grd.addColorStop(1, lift > 0.5 ? '#1a0f3a' : '#2a1f5a')
  g.fillStyle = grd
  g.fillRect(WIN.x, WIN.y, WIN.w, WIN.h)
  for (let i = 0; i < 14; i++) {
    const tw = 0.5 + 0.5 * Math.sin(f.t * 2 + i)
    g.fillStyle = `rgba(255,255,255,${0.3 + tw * 0.5})`
    g.fillRect(WIN.x + Math.floor(hash(i) * WIN.w), WIN.y + Math.floor(hash(i + 40) * WIN.h * 0.8 - lift * 30) % WIN.h, 1, 1)
  }
  const my = WIN.y + 10 + lift * 40
  glow(g, WIN.x + 28, my, 16, '#cdd6ff', 0.5)
  g.fillStyle = '#e8ecff'
  g.fillRect(WIN.x + 24, my - 4, 8, 8)
  g.fillStyle = '#c5cbea'
  g.fillRect(WIN.x + 26, my - 2, 2, 2)
  g.fillRect(WIN.x + 29, my + 1, 2, 1)
  // Silhouette der Stadt
  if (lift < 0.6) {
    g.fillStyle = '#07061a'
    for (let i = 0; i < 8; i++) {
      const h = 6 + Math.floor(hash(i * 9) * 14)
      g.fillRect(WIN.x + i * 5, WIN.y + WIN.h - h + lift * 40, 5, h)
      if (hash(i * 3) > 0.4) {
        g.fillStyle = C.lamp2
        g.fillRect(WIN.x + i * 5 + 2, WIN.y + WIN.h - h + 3 + lift * 40, 1, 1)
        g.fillStyle = '#07061a'
      }
    }
  }
  g.restore()
}

/** Signal-Lichter auf der Redstone-Leitung: Pulse wandern im Takt. */
function wireLight(f: Frame, path: [number, number][], strength: number, speed = 1) {
  const g = f.g
  const segs: { x0: number, y0: number, x1: number, y1: number, len: number }[] = []
  let total = 0
  for (let i = 0; i + 1 < path.length; i++) {
    const [x0, y0] = path[i]!
    const [x1, y1] = path[i + 1]!
    const len = Math.hypot(x1 - x0, y1 - y0)
    segs.push({ x0, y0, x1, y1, len })
    total += len
  }
  g.fillStyle = rgba(C.red3, 0.35 + strength * 0.65)
  for (const s of segs) pixLine(g, s.x0, s.y0, s.x1, s.y1, 1)
  // Pulse
  const phase = (f.beat.count * speed) % 1
  for (let k = 0; k < 2; k++) {
    let d = ((phase + k * 0.5) % 1) * total
    for (const s of segs) {
      if (d <= s.len) {
        const x = s.x0 + (s.x1 - s.x0) * (d / s.len)
        const y = s.y0 + (s.y1 - s.y0) * (d / s.len)
        glow(g, x, y, 7, C.red4, 0.55 * strength)
        g.fillStyle = C.red5
        g.fillRect(Math.round(x), Math.round(y), 1, 1)
        break
      }
      d -= s.len
    }
  }
}

/** Monitor-Inhalt: Chat (Kanal mit Nachrichten) oder Launcher mit Turbo-Anzeige. */
function screenContent(f: Frame, mode: 'chat' | 'gauge', gauge: number, flash: number) {
  const g = f.g
  g.save()
  g.beginPath()
  g.rect(SCR.x, SCR.y, SCR.w, SCR.h)
  g.clip()
  if (mode === 'chat') {
    g.fillStyle = '#16151c'
    g.fillRect(SCR.x, SCR.y, SCR.w, SCR.h)
    g.fillStyle = '#101015'
    g.fillRect(SCR.x, SCR.y, 6, SCR.h)
    for (let i = 0; i < 4; i++) {
      g.fillStyle = i === 2 ? '#3a3b45' : '#24252c'
      g.fillRect(SCR.x + 1, SCR.y + 2 + i * 3, 4, 1.4)
    }
    // Nachrichten des aktuellen Teils (unten neu)
    const msgs = f.chat.filter((m) => m.at <= f.t && m.at > f.t - 40 && m.from !== 'system').slice(-6)
    msgs.forEach((m, i) => {
      const y = SCR.y + SCR.h - 3.2 * (msgs.length - i) - 1
      const fresh = clamp(1 - (f.t - m.at) * 2)
      g.fillStyle = m.from === 'team' ? C.red3 : C.purple
      g.fillRect(SCR.x + 8, y, 2, 2)
      g.fillStyle = m.from === 'team' ? '#ffb3aa' : '#c9bcff'
      g.fillRect(SCR.x + 11, y, 6 + Math.min(12, m.text.length * 0.8), 0.8)
      g.fillStyle = '#9a9aa8'
      g.fillRect(SCR.x + 11, y + 1.3, 4 + Math.min(10, m.text.length * 0.5), 0.6)
      if (fresh > 0) {
        g.fillStyle = rgba('#ffffff', fresh * 0.25)
        g.fillRect(SCR.x + 7, y - 0.6, SCR.w - 8, 3)
      }
    })
    // Benachrichtigungs-Punkt blinkt jede Sekunde
    if (Math.floor(f.t * 2) % 2 === 0) {
      g.fillStyle = C.red4
      g.fillRect(SCR.x + 4, SCR.y + 8, 2, 2)
    }
  } else {
    // Launcher-Fenster mit Turbo-Anzeige
    g.fillStyle = '#121117'
    g.fillRect(SCR.x, SCR.y, SCR.w, SCR.h)
    g.fillStyle = '#1d1c24'
    g.fillRect(SCR.x, SCR.y, SCR.w, 2.2)
    g.fillStyle = C.red3
    g.fillRect(SCR.x + 1, SCR.y + 0.6, 1, 1)
    const cx = SCR.x + SCR.w / 2
    const cy = SCR.y + SCR.h * 0.42
    const r = SCR.h * 0.32
    const a0 = Math.PI * 0.8
    const a1 = Math.PI * 2.2
    g.lineCap = 'butt'
    g.lineWidth = 1.6
    g.strokeStyle = '#2b2a34'
    g.beginPath()
    g.arc(cx, cy, r, a0, a1)
    g.stroke()
    const red = range(gauge, 0.55, 1)
    const arcG = g.createLinearGradient(cx - r, 0, cx + r, 0)
    arcG.addColorStop(0, '#7aa8ff')
    arcG.addColorStop(0.6, '#ffb347')
    arcG.addColorStop(1, '#ff2a1a')
    g.strokeStyle = arcG
    g.beginPath()
    g.arc(cx, cy, r, a0, a0 + (a1 - a0) * gauge)
    g.stroke()
    for (let i = 0; i <= 10; i++) {
      const a = a0 + (a1 - a0) * (i / 10)
      g.strokeStyle = i >= 8 ? C.red4 : '#8a8996'
      g.lineWidth = 0.4
      g.beginPath()
      g.moveTo(cx + Math.cos(a) * (r - 2.6), cy + Math.sin(a) * (r - 2.6))
      g.lineTo(cx + Math.cos(a) * (r - 1.6), cy + Math.sin(a) * (r - 1.6))
      g.stroke()
    }
    const na = a0 + (a1 - a0) * gauge + (red > 0 ? Math.sin(f.t * 60) * 0.06 * red : 0)
    g.strokeStyle = '#ffffff'
    g.lineWidth = 0.6
    g.beginPath()
    g.moveTo(cx, cy)
    g.lineTo(cx + Math.cos(na) * (r - 1), cy + Math.sin(na) * (r - 1))
    g.stroke()
    g.fillStyle = C.red3
    g.beginPath()
    g.arc(cx, cy, 1, 0, Math.PI * 2)
    g.fill()
    // Blitz-Symbol
    g.fillStyle = red > 0 ? C.red4 : C.lamp
    g.beginPath()
    g.moveTo(cx + 0.6, cy + 2.2)
    g.lineTo(cx - 1, cy + 4.6)
    g.lineTo(cx + 0.1, cy + 4.6)
    g.lineTo(cx - 0.6, cy + 6.6)
    g.lineTo(cx + 1.2, cy + 3.9)
    g.lineTo(cx + 0.1, cy + 3.9)
    g.closePath()
    g.fill()
    if (red > 0) {
      g.fillStyle = rgba('#ff2a1a', red * (0.15 + 0.15 * Math.sin(f.t * 20)))
      g.fillRect(SCR.x, SCR.y, SCR.w, SCR.h)
    }
  }
  if (flash > 0) {
    g.fillStyle = rgba('#ffffff', flash * 0.35)
    g.fillRect(SCR.x, SCR.y, SCR.w, SCR.h)
  }
  // Glanz
  const gl = g.createLinearGradient(SCR.x, SCR.y, SCR.x + SCR.w, SCR.y + SCR.h)
  gl.addColorStop(0, 'rgba(255,255,255,0.07)')
  gl.addColorStop(0.5, 'rgba(255,255,255,0)')
  g.fillStyle = gl
  g.fillRect(SCR.x, SCR.y, SCR.w, SCR.h)
  g.restore()
}

/** Benachrichtigungs-Blasen über dem Monitor (Nachrichten der letzten Sekunden). */
function toasts(f: Frame, x: number, y: number, size: number, maxAge = 3.2, count = 3) {
  const list = f.chat.filter((m) => m.at <= f.t && f.t - m.at < maxAge && m.from !== 'system').slice(-count)
  list.forEach((m, i) => {
    const age = f.t - m.at
    const k = list.length - 1 - i
    const yy = y - k * size * 2.3 - easeOut(clamp(age / maxAge)) * size * 1.2
    bubble(f, m, x, yy, { pop: popOf(f.t, m.at), alpha: clamp((maxAge - age) * 2), size, align: 'center' })
  })
}

/** Zeitpunkt der letzten Fan-Nachricht (für Handy-Summen, Lichtblitze). */
function lastFan(f: Frame): number {
  let last = -99
  for (const m of f.chat) {
    if (m.at > f.t) break
    if (m.from === 'fan') last = m.at
  }
  return last
}

interface RoomState {
  /** 0 dunkel (Nacht, nur Monitor) … 1 Licht an. */
  lights: number
  /** Redstone-Signal 0…1. */
  redstone: number
  heat: number
  fuse: number
  soot: boolean
  headphones: number
  gauge: number
  screen: 'chat' | 'gauge'
  bounce: number
}

/** Zimmer in Welt-Koordinaten zeichnen (Kamera muss gesetzt sein). */
function drawRoom(f: Frame, s: RoomState) {
  const g = f.g
  base ??= buildBase()
  at(f, 1)
  const v = visible(f, 1)
  g.fillStyle = C.ink
  g.fillRect(v.x0, v.y0, v.w, v.h)
  sky(f, 0)
  blit(g, base, X0, Y0)

  const fanAge = f.t - lastFan(f)
  const buzz = fanAge >= 0 && fanAge < 0.6 ? Math.sin(fanAge * 90) * 0.6 : 0
  const bob = s.bounce * f.pulse

  // Monitor
  g.fillStyle = '#0e0d13'
  g.fillRect(MON.x, MON.y - bob * 0.5, MON.w, MON.h)
  g.fillStyle = '#26252e'
  g.fillRect(MON.x, MON.y - bob * 0.5, MON.w, 1)
  g.fillStyle = '#1a1920'
  g.fillRect(MON.x + MON.w / 2 - 2, MON.y + MON.h, 4, 180 - MON.y - MON.h)
  g.fillRect(MON.x + MON.w / 2 - 7, 179, 14, 1)
  g.save()
  g.translate(0, -bob * 0.5)
  screenContent(f, s.screen, s.gauge, decay(fanAge, 5))
  g.restore()
  // Tasse (kalt: blaue Glitzer), Handy
  const mugHop = s.bounce > 0 ? Math.abs(Math.sin(f.beat.count * Math.PI)) * s.bounce * 2 : 0
  blit(g, MUG, 264, 173 - mugHop)
  for (let i = 0; i < 3; i++) {
    if (Math.sin(f.t * 3 + i * 2) > 0.7) {
      g.fillStyle = '#cfe6ff'
      g.fillRect(265 + i * 2, 172 - mugHop, 1, 1)
    }
  }
  blit(g, PHONE, 208 + buzz, 170)
  g.fillStyle = fanAge < 2 ? rgba(C.purple2, 0.9 * (1 - fanAge / 2)) : '#1d1b2b'
  g.fillRect(209 + buzz, 171, 5, 8)

  // Entwickler von hinten auf dem Stuhl
  const key = `back:${s.soot ? 1 : 0}:${s.headphones >= 1 ? 1 : 0}`
  const back = spr(key, () => personBack(DEV, s.headphones >= 1, s.soot))
  const swell = 1 + s.fuse * 0.35 * (0.6 + 0.4 * Math.sin(f.t * 40))
  const by = DEV_Y + Math.sin(f.t * 1.3) * 0.4 - bob
  g.save()
  if (swell !== 1) {
    g.translate(DEV_X + 9, by + 6)
    g.scale(swell, swell)
    g.translate(-(DEV_X + 9), -(by + 6))
  }
  blit(g, back, DEV_X, by)
  // Hitze: Kopf rot, Creeper-Zünden: weiß blinken
  const fuseBlink = s.fuse > 0 && Math.sin(f.t * (12 + s.fuse * 40)) > 0
  if (s.heat > 0 || fuseBlink) {
    g.fillStyle = fuseBlink ? 'rgba(255,255,255,0.85)' : rgba('#ff3a20', s.heat * 0.55)
    g.fillRect(DEV_X + 5, by + 2, 8, 8)
  }
  g.restore()
  // Kopfhörer fallen von oben auf den Kopf
  if (s.headphones > 0 && s.headphones < 1) {
    const y = lerp(by - 40, by, easeOutBack(s.headphones, 1.4))
    g.fillStyle = '#1b1b22'
    g.fillRect(DEV_X + 4, y + 1, 10, 1)
    g.fillStyle = C.red3
    g.fillRect(DEV_X + 3, y + 4, 2, 5)
    g.fillRect(DEV_X + 13, y + 4, 2, 5)
  }
  // Stuhl (vor der Figur)
  g.fillStyle = '#1d1b26'
  g.fillRect(DEV_X, 172 - bob, 18, 16)
  g.fillStyle = '#2c2a38'
  g.fillRect(DEV_X, 172 - bob, 18, 1)
  g.fillRect(DEV_X, 172 - bob, 1, 16)
  g.fillStyle = C.red2
  g.fillRect(DEV_X + 4, 175 - bob, 10, 1)
  g.fillStyle = '#101016'
  g.fillRect(DEV_X + 8, 188 - bob, 2, 8)
  g.fillRect(DEV_X + 2, 196, 14, 2)
  // Redstone-Lampe (Block)
  const lampOn = s.redstone > 0.3
  g.fillStyle = lampOn ? C.lamp : '#5a3a22'
  g.fillRect(LAMP.x, LAMP.y - 10, 10, 10)
  g.fillStyle = lampOn ? C.lamp3 : '#3a2416'
  g.fillRect(LAMP.x + 2, LAMP.y - 8, 2, 2)
  g.fillRect(LAMP.x + 6, LAMP.y - 5, 2, 2)

  // ——— Licht ———
  // Gesamt-Dunkelheit (Nacht) – multipliziert
  g.save()
  g.globalCompositeOperation = 'multiply'
  const amb = lerp(0.42, 0.85, s.lights)
  g.fillStyle = `rgb(${Math.round(150 * amb)},${Math.round(140 * amb)},${Math.round(215 * amb)})`
  g.fillRect(v.x0, v.y0, v.w, v.h)
  g.restore()
  // Mondlicht durchs Fenster
  lightCone(g, WIN.x + WIN.w / 2, WIN.y + WIN.h, WIN.w * 0.9, WIN.w * 1.8, FLOOR - WIN.y - WIN.h + 14, '#7a8cff', 0.05)
  // Monitor
  const mg = 0.55 + 0.25 * decay(fanAge, 4) + (s.screen === 'gauge' ? s.gauge * 0.5 : 0)
  glow(g, MON.x + MON.w / 2, MON.y + MON.h / 2, 70, s.screen === 'gauge' && s.gauge > 0.6 ? '#ff4a30' : '#6f86ff', mg * 0.5)
  glow(g, MON.x + MON.w / 2, MON.y + MON.h / 2, 26, '#c8d4ff', mg * 0.35)
  // Handy-Licht
  glow(g, 211, 175, 22, C.purple, 0.6 * decay(fanAge, 1.6))
  // Schreibtischlampe: warmes Licht auf den Tisch
  if (s.lights > 0.3) {
    g.fillStyle = C.lamp3
    g.fillRect(DESK_LAMP.x - 7, DESK_LAMP.y - 11, 3, 1)
    lightCone(g, DESK_LAMP.x - 5.5, DESK_LAMP.y - 11, 4, 22, 11, C.lamp2, 0.3)
    glow(g, DESK_LAMP.x - 5, DESK_LAMP.y - 2, 34, C.lamp, 0.4)
  }
  // Redstone
  wireLight(f, WIRE, s.redstone, 1)
  wireLight(f, WIRE2, s.redstone * 0.8, 1)
  for (const x of TORCHES) {
    g.fillStyle = C.red4
    g.fillRect(x - 1, 126, 3, 3)
    glow(g, x, 127, 22 + f.pulse * 6 * s.redstone, C.red3, 0.35 + 0.2 * s.redstone)
  }
  if (lampOn) glow(g, LAMP.x + 5, LAMP.y - 5, 50, C.lamp, 0.35 * s.redstone + f.pulse * 0.15)
  if (s.fuse > 0) glow(g, DEV_X + 9, by + 6, 30 * swell, '#ffffff', fuseBlink ? 0.35 : 0.05)
}

/** Großes Gesicht (Gegenschuss vom Monitor aus). */
function drawFace(f: Frame, mode: 'red' | 'soot' | 'hot', p: number) {
  const g = f.g
  base ??= buildBase()
  // Hintergrund: dunkle Zimmerwand, unscharf wirkend (stark abgedunkelt)
  at(f, 0.6)
  const v = visible(f, 0.6)
  g.fillStyle = '#08070f'
  g.fillRect(v.x0, v.y0, v.w, v.h)
  g.globalAlpha = 0.5
  soft(g, base, X0 + 30, Y0 - 20, WW, WH)
  g.globalAlpha = 1
  for (const x of TORCHES) glow(g, x + 20, 127, 30, C.red3, 0.3)
  at(f, 1)
  const blink = (f.t % 2.7) < 0.12 || (mode === 'soot' && p > 0.2 && p < 0.27)
  const k = `face:${blink ? 'blink' : mode}`
  const face = spr(k, () => faceHD(blink ? 'blink' : mode === 'hot' ? 'hot' : mode))
  blit(g, face, 228, 118)
  // Haare/Schultern
  // Schultern im Kapuzenpulli
  g.fillStyle = mode === 'soot' ? '#3a2a24' : DEV.shirt
  g.fillRect(224, 144, 32, 14)
  g.fillRect(220, 147, 40, 11)
  g.fillStyle = DEV.shirtD
  g.fillRect(232, 141, 16, 4)
  g.fillRect(238, 145, 1, 13)
  g.fillRect(241, 145, 1, 13)
  g.fillStyle = C.white
  g.fillRect(238, 150, 1, 3)
  g.fillRect(241, 150, 1, 3)
  // Monitor-Licht von vorn, Benachrichtigungen spiegeln sich lila
  const fanAge = f.t - lastFan(f)
  g.save()
  g.globalCompositeOperation = 'multiply'
  g.fillStyle = 'rgb(175,170,215)'
  g.fillRect(v.x0, v.y0, v.w, v.h)
  g.restore()
  glow(g, 240, 128, 40, '#8ea4ff', 0.35)
  glow(g, 262, 132, 30, C.lamp, 0.22)
  glow(g, 240, 130, 30, C.purple, 0.5 * decay(fanAge, 3))
  if (mode === 'red') {
    // pochende Adern
    glow(g, 236, 130, 5, C.red3, 0.3 + 0.3 * f.pulse)
    glow(g, 245, 130, 5, C.red3, 0.3 + 0.3 * f.pulse)
  }
  // kalte Tasse im Vordergrund
  at(f, 1.35)
  blit(g, MUG, 254, 144, 11, 11)
  for (let i = 0; i < 4; i++) if (Math.sin(f.t * 2.4 + i * 1.7) > 0.6) {
    g.fillStyle = '#d8ecff'
    g.fillRect(254 + i * 3, 141 + (i % 2), 1, 1)
  }
}

/** Außenansicht: der Zimmer-Würfel hebt ab (`lift` 0…1), `power` = Pre-Chorus 2 stärker. */
function drawLiftoff(f: Frame, lift: number, power: number) {
  const g = f.g
  base ??= buildBase()
  const rise = easeIn(lift) * 260 * power
  // Kamera folgt nach oben, neigt sich leicht
  setCam(f, 240, 140 - rise * 0.6, 1.0 + lift * 0.2, Math.sin(f.t * 0.7) * 0.025 * power)
  at(f, 0.2)
  const v = visible(f, 0.2)
  const sk = g.createLinearGradient(0, v.y0, 0, v.y1)
  sk.addColorStop(0, '#05040c')
  sk.addColorStop(0.55, lerpColor('#141032', '#05040c', lift))
  sk.addColorStop(1, lerpColor('#2a1d4f', '#120a26', lift))
  g.fillStyle = sk
  g.fillRect(v.x0, v.y0, v.w, v.h)
  stars(f, 0.2, 3, 260, 0.4 + lift)
  // Stadt in der Ferne
  at(f, 0.55)
  for (let i = 0; i < 40; i++) {
    const x = -200 + i * 22
    const h = 14 + hash(i * 3) * 36
    g.fillStyle = '#0b0920'
    g.fillRect(x, 238 - h, 20, h + 200)
    for (let w = 0; w < 6; w++) if (hash(i * 31 + w) > 0.55) {
      g.fillStyle = hash(i + w) > 0.5 ? C.lamp2 : '#ffa25a'
      g.fillRect(x + 3 + (w % 3) * 6, 242 - h + Math.floor(w / 3) * 8, 2, 2)
    }
  }
  // Boden mit Gras
  at(f, 1)
  g.fillStyle = C.dirtD
  g.fillRect(-300, 252, 1100, 400)
  g.fillStyle = C.grassD
  g.fillRect(-300, 250, 1100, 4)
  // Loch, wo das Zimmer war
  g.fillStyle = '#120c08'
  g.fillRect(156, 252, 168, 10)
  // Würfel: Zimmer (verkleinert) + Erde/Stein darunter
  const cx = 240
  const top = 150 - rise
  const wob = Math.sin(f.t * 30) * (1 - lift) * 0.6 * power
  const w = 168
  const h = 92
  g.save()
  g.translate(cx + wob - w / 2, top)
  g.rotate(Math.sin(f.t * 2) * 0.02 * power)
  // Innenraum: Ausschnitt der Zimmer-Ebene (Wand, Fenster, Schreibtisch) verkleinert
  g.fillStyle = '#0d0b1e'
  g.fillRect(0, 0, w, h)
  g.imageSmoothingEnabled = true
  g.drawImage(base, 130 - X0, 92 - Y0, 222, 122, 0, 0, w, h)
  g.imageSmoothingEnabled = false
  g.fillStyle = '#6f86ff'
  g.fillRect(w * 0.42, h * 0.58, 10, 7)
  g.fillStyle = C.stoneB
  g.fillRect(-3, -6, w + 6, 6)
  g.fillRect(-3, 0, 3, h)
  g.fillRect(w, 0, 3, h)
  for (let y = 0; y < 44; y++) {
    const inset = Math.floor(y * y / 30)
    for (let x = inset; x < w - inset; x += 3) {
      const r = hash2(x, y)
      g.fillStyle = y < 10 ? (r > 0.5 ? C.dirt : C.dirtD) : r > 0.7 ? C.stoneC : r > 0.3 ? C.stoneA : C.stoneD
      g.fillRect(x, h + y, 3, 1)
    }
  }
  g.fillStyle = C.grass
  g.fillRect(-3, h, w + 6, 2)
  g.restore()
  // Licht im Würfel, Funken/Schub darunter
  glow(g, cx, top + h / 2, 80, '#6f86ff', 0.25)
  glow(g, cx, top + 30, 60, C.red3, 0.2 + f.pulse * 0.2)
  const under = top + h + 44
  glow(g, cx, under, 70 * power, C.red3, 0.5 * clamp(lift * 4))
  glow(g, cx, under, 30 * power, C.lamp2, 0.6 * clamp(lift * 4))
  for (let i = 0; i < 50 * power; i++) {
    const age = (f.t * 1.4 + hash(i)) % 1
    const x = cx + (hash(i * 7) - 0.5) * 80 + (hash(i * 3) - 0.5) * 30 * age
    const y = under - 10 + age * 120
    g.globalAlpha = 1 - age
    g.fillStyle = i % 3 ? C.red4 : C.lamp2
    g.fillRect(x, y, 1.5, 1.5)
  }
  g.globalAlpha = 1
  // Erdbrocken fallen ab
  debris(f, cx, top + h + 20, (lift * 6) % 1.5, 18, 5 + Math.floor(lift * 4), [C.dirt, C.dirtD, C.stoneA], 25, 80, 3)
}

function lerpColor(a: string, b: string, t: number): string {
  const pa = Number.parseInt(a.slice(1), 16)
  const pb = Number.parseInt(b.slice(1), 16)
  const ch = (s: number) => Math.round(lerp((pa >> s) & 255, (pb >> s) & 255, clamp(t)))
  return `rgb(${ch(16)},${ch(8)},${ch(0)})`
}

export function renderRoom(f: Frame) {
  const { shot } = f
  const p = shot.p
  const t = shot.t
  const pre2 = shot.section === 'pre2'
  const power = pre2 ? 1.5 : 1
  const v1 = shot.section === 'verse1'
  const verseP = v1 ? shot.sec.p : 0
  const state: RoomState = {
    lights: shot.section === 'intro' ? 0 : 0.7,
    redstone: shot.section === 'intro' ? 0.15 : 0.6 + 0.4 * f.pulse,
    heat: v1 ? range(verseP, 0.45, 0.85) : 0,
    fuse: 0,
    soot: false,
    headphones: 0,
    gauge: 0,
    screen: 'chat',
    bounce: 0,
  }
  switch (shot.shot) {
    case 'night': {
      // Langsame Fahrt vom Fenster (Mond) zum leuchtenden Monitor, am Ende hinein ins Licht
      const k = easeInOut(clamp(p * 1.1))
      setCam(f, lerp(168, 240, k), lerp(138, 156, k), lerp(3.2, 2.6, k) + easeIn(range(p, 0.82, 1)) * 5, 0)
      drawRoom(f, state)
      at(f, 1)
      toasts(f, 240, 134, 4.5)
      return
    }
    case 'wide':
      setCam(f, lerp(236, 244, p), 158, lerp(2.15, 2.35, p))
      drawRoom(f, state)
      at(f, 1)
      toasts(f, 240, 132, 5)
      return
    case 'face':
      setCam(f, 240, 134, lerp(4.4, 4.9, p))
      drawFace(f, 'red', p)
      at(f, 1)
      toasts(f, 270, 118, 3.6, 2.6, 2)
      return
    case 'screen': {
      setCam(f, lerp(238, 242, p), lerp(158, 156, p), lerp(5.2, 5.9, p), -0.02)
      drawRoom(f, state)
      // Blasen direkt vor dem Bildschirm
      at(f, 1)
      toasts(f, 240, 158, 3, 3, 4)
      return
    }
    case 'wide2':
      setCam(f, lerp(256, 236, p), lerp(156, 162, p), lerp(2.7, 3.2, p), lerp(0.04, 0.01, p))
      drawRoom(f, state)
      at(f, 1)
      // Dampf steigt vom heißen Kopf
      for (let i = 0; i < 4; i++) {
        const age = (t * 0.8 + i * 0.25) % 1
        f.g.globalAlpha = Math.sin(age * Math.PI) * 0.5 * state.heat
        f.g.fillStyle = '#d8d0e8'
        f.g.fillRect(DEV_X + 6 + i * 2 + Math.sin(t * 3 + i) * 1.5, 156 - age * 14, 1.5, 1.5)
      }
      f.g.globalAlpha = 1
      toasts(f, 240, 130, 5)
      return
    case 'fuse':
      state.fuse = range(p, 0, 1)
      state.heat = 1
      f.shake += state.fuse * 1.4
      setCam(f, 240, lerp(160, 164, p), lerp(3.4, 6, easeIn(p)))
      drawRoom(f, state)
      return
    case 'boom': {
      // Explosion, Rauch verzieht sich, dann rußiges Gesicht (Nahaufnahme) – komisch, nicht gruselig
      const g = f.g
      if (t < 1.3) {
        state.soot = t > 0.35
        state.heat = 0
        f.shake += 3 * decay(t, 3)
        setCam(f, 240, 160, 3.2 - t * 0.4)
        drawRoom(f, state)
        at(f, 1)
        glow(g, DEV_X + 9, 164, 90 * (1 - t / 1.3), '#ffffff', 0.9 * decay(t, 4))
        glow(g, DEV_X + 9, 164, 50, '#ffb347', 0.7 * decay(t, 3))
        // Comic-Pixel-Explosion: Ring aus Blöcken, dann Rauch
        const ring = easeOut(clamp(t / 0.35))
        for (let i = 0; i < 16; i++) {
          const a = (i / 16) * Math.PI * 2
          const r = 6 + ring * 26
          g.globalAlpha = clamp(1 - t * 1.6)
          g.fillStyle = i % 2 ? '#ffd27a' : '#ff8a2a'
          const sz = 4 * (1 - ring * 0.5)
          g.fillRect(DEV_X + 9 + Math.cos(a) * r - sz / 2, 164 + Math.sin(a) * r * 0.8 - sz / 2, sz, sz)
        }
        g.globalAlpha = 1
        smoke(f, DEV_X + 9, 164, t, 12, 77, '#d8d2e0', 30, 10)
        debris(f, DEV_X + 9, 164, t, 30, 11, ['#3a3a48', '#ffffff', C.red3, C.grass], 90, 70, 2)
        return
      }
      setCam(f, 240, 134, 4.6)
      drawFace(f, 'soot', p)
      at(f, 1)
      smoke(f, 240, 120, t - 1.3, 5, 31, '#bdb6c8', 24, 7)
      return
    }
    case 'phones': {
      state.lights = 0.65
      state.headphones = range(t, 0.15, 1.1)
      state.redstone = 0.7 + 0.3 * f.pulse
      state.bounce = state.headphones >= 1 ? 1.2 * power : 0
      f.shake += pre2 ? 0.3 * f.pulse : 0
      const k = easeOut(clamp(p))
      setCam(f, 240, lerp(160, 158, k), lerp(4.6, 3.2, k), pre2 ? -0.05 : 0)
      drawRoom(f, state)
      return
    }
    case 'gauge': {
      state.lights = 0.6
      state.headphones = 1
      state.screen = 'gauge'
      state.gauge = clamp(easeIn(clamp(p * 1.05)) * 1.02 + (pre2 ? 0.25 : 0))
      state.redstone = 1
      state.bounce = 1.5 * power
      f.shake += (0.2 + p * 1.2) * power
      setCam(f, 240, lerp(152, 150, p), lerp(5.6, 7.4, easeIn(p)), Math.sin(f.t * 8) * 0.01 * p)
      drawRoom(f, state)
      at(f, 1)
      if (p > 0.5) glow(f.g, 240, 153, 40, '#ff3a20', (p - 0.5) * 0.8)
      return
    }
    case 'liftoff':
      f.shake += (1 - p) * 0.8 * power + f.pulse * 0.4
      drawLiftoff(f, p, power)
      return
    default:
      setCam(f, 240, 160, 2.2)
      drawRoom(f, state)
  }
}

