// Die Fuchsfamilie im Szenen-Set „Kirschhain“ (Minecraft-Wald in der Abenddämmerung): eckige Minecraft-Füchse aus
// Quadern (Körper, Kopf, Schnauze, Ohren mit dunklen Spitzen, dicker Schwanz mit weißer Spitze, dunkle Pfoten) und
// ihre Bewegung als reine Funktion der Zeit – einer schläft eingerollt am Lagerfeuer, drei Junge purzeln aus dem
// Bau und spielen, einer bringt Süßbeeren im Maul und macht dann den typischen Minecraft-Pounce in den Beerenbusch.

import { canvas, clamp, ctx2d, easeInOut, easeOut, range, type Cv } from './kit'

export const FOX_COL = {
  o: '#e3823a',
  s: '#b8612a',
  l: '#f29a4e',
  w: '#ffffff',
  v: '#e3d6cc',
  d: '#3a2a26',
  e: '#1a1010',
  r: '#c8102e',
  R: '#ff5a6a',
  g: '#3f6a2a',
} as const

export type FoxPose = 'stand' | 'walk' | 'sit' | 'sleep' | 'crouch' | 'leap' | 'peek' | 'tumble'

export interface FoxLook {
  pose: FoxPose
  kit: boolean
  /** Laufbild 0/1 (Beine), Atmen beim Schlafen. */
  frame: number
  /** Süßbeeren im Maul. */
  berries: boolean
}

type Rect = [x0: number, y0: number, x1: number, y1: number, c: keyof typeof FOX_COL]

/** Ursprung im Sprite: Fußpunkt Mitte. */
export const FOX_OX = 26
export const FOX_OY = 30
const FOX_W = 52
const FOX_H = 34

/** Quader eines Fuchses (Kunst-Pixel, Fußpunkt = 0,0, Blick nach rechts). Spätere Rechtecke liegen vorn. */
export function foxRects(l: FoxLook): Rect[] {
  const R: Rect[] = []
  const box = (x0: number, y0: number, x1: number, y1: number, c: keyof typeof FOX_COL) => R.push([x0, y0, x1, y1, c])
  /** Quader mit hellerer Oberkante und dunklerer Unterkante (Minecraft-Schattierung). */
  const fur = (x0: number, y0: number, x1: number, y1: number) => {
    box(x0, y0, x1, y1, 'o')
    box(x0, y0, x1, y0, 'l')
    if (y1 - y0 >= 2) box(x0, y1, x1, y1, 's')
  }
  const leg = (x: number, top: number, bottom: number, far: boolean) => {
    box(x, top, x + 1, bottom, far ? 's' : 'o')
    box(x, Math.max(top, bottom - (bottom - top >= 4 ? 2 : 1)), x + 1, bottom, 'd')
  }
  const berries = (x: number, y: number) => {
    box(x, y, x + 1, y + 1, 'r')
    box(x + 2, y + 1, x + 3, y + 2, 'r')
    box(x - 1, y + 2, x, y + 3, 'r')
    box(x, y, x, y, 'R')
    box(x + 2, y + 1, x + 2, y + 1, 'R')
    box(x + 1, y - 1, x + 2, y - 1, 'g')
  }
  /** Kopf mit Ohren (dunkle Spitzen), weißen Wangen, Schnauze, Auge; (x, y) = links oben, Größe w×h. */
  const head = (x: number, y: number, w: number, h: number, sn: number, closed = false) => {
    fur(x, y, x + w - 1, y + h - 1)
    const ew = w >= 8 ? 3 : 2
    box(x + 1, y - 3, x + ew, y - 1, 'o')
    box(x + 1, y - 3, x + ew, y - 3, 'd')
    box(x + w - 1 - ew, y - 3, x + w - 2, y - 1, 'o')
    box(x + w - 1 - ew, y - 3, x + w - 2, y - 3, 'd')
    box(x, y + h - Math.ceil(h * 0.45), x + Math.floor(w * 0.4), y + h - 1, 'w')
    const sy = y + h - Math.ceil(h * 0.5)
    box(x + w, sy, x + w + sn - 1, y + h - 1, 'w')
    box(x + w, sy, x + w + sn - 2, sy, 'o')
    box(x + w + sn - 1, sy, x + w + sn - 1, sy + 1, 'd')
    const ey = y + Math.floor(h * 0.3)
    if (closed) box(x + w - 4, ey + 1, x + w - 2, ey + 1, 'e')
    else {
      box(x + w - 3, ey, x + w - 3, ey + 1, 'w')
      box(x + w - 2, ey, x + w - 2, ey + 1, 'e')
    }
  }
  const f = l.frame & 1
  if (!l.kit) {
    switch (l.pose) {
      case 'sleep': {
        // eingerollt: Körper rund, Kopf auf den Pfoten, Augen zu, buschiger Schwanz vorn um die Schnauze
        const br = l.frame & 1 ? 0 : 1
        fur(-11, -11 + br, 5, 0)
        box(-11, -11 + br, -11, -10 + br, 'l')
        fur(-15, -5, 4, 0)
        box(-15, -5, 4, -5, 'l')
        box(4, -5, 8, 0, 'w')
        box(4, -5, 8, -5, 'v')
        head(3, -10, 8, 8, 3, true)
        break
      }
      case 'sit': {
        // sitzend: Hinterteil unten, Brust aufrecht, Schwanz vorn um die Pfoten
        fur(-14, -4, 3, 0)
        fur(-8, -9, 3, 0)
        fur(0, -17, 7, -5)
        box(5, -16, 7, -7, 'w')
        leg(2, -6, 0, true)
        leg(5, -6, 0, false)
        box(3, -4, 8, 0, 'w')
        box(3, -4, 8, -4, 'v')
        head(1, -25, 9, 8, 4)
        if (l.berries) berries(14, -19)
        break
      }
      case 'peek': {
        // nur der Kopf schaut aus dem Busch (Fußpunkt = Kopfunterkante)
        head(-4, -8, 8, 8, 3)
        if (l.berries) berries(7, -3)
        break
      }
      default: {
        // stehen / laufen / ducken / Sprung
        const low = l.pose === 'crouch' ? 3 : 0
        const air = l.pose === 'leap'
        const walk = l.pose === 'walk'
        const sw = (a: number) => (walk ? (f ? a : -a) : 0)
        if (air) {
          box(5, -9, 9, -8, 's')
          box(-14, -9, -9, -8, 's')
        } else {
          leg(-7 + sw(1), -6 + low, 0, true)
          leg(3 + sw(-1), -6 + low, 0, true)
        }
        // Schwanz: dick, hinten leicht hängend, beim Ducken erhoben, weiße Spitze
        const ty = l.pose === 'crouch' ? -13 : -12
        fur(-15, ty, -9, ty + 5)
        fur(-21, ty + (l.pose === 'crouch' ? -1 : 1), -15, ty + (l.pose === 'crouch' ? 4 : 6))
        const tt = ty + (l.pose === 'crouch' ? -1 : 1)
        box(-24, tt, -21, tt + 5, 'w')
        box(-24, tt, -22, tt, 'v')
        // Körper
        fur(-10, -12 + low, 6, -5 + low)
        box(-10, -5 + low, 6, -5 + low, 's')
        if (!air) {
          leg(-9 + sw(-1), -6 + low, walk && f ? -1 : 0, false)
          leg(1 + sw(1), -6 + low, walk && !f ? -1 : 0, false)
        } else {
          box(5, -8, 11, -6, 'o')
          box(10, -8, 11, -6, 'd')
          box(-15, -8, -9, -6, 'o')
          box(-15, -8, -14, -6, 'd')
        }
        const hy = low + (air ? 1 : 0)
        head(4, -19 + hy, 9, 8, 4)
        if (l.berries) berries(17, -14 + hy)
      }
    }
    return R
  }
  // Junges: kleiner Körper, großer Kopf
  switch (l.pose) {
    case 'sit': {
      fur(-9, -3, 1, 0)
      fur(-5, -6, 1, 0)
      fur(0, -11, 5, -3)
      box(3, -10, 5, -5, 'w')
      leg(3, -4, 0, false)
      box(1, -3, 4, 0, 'w')
      head(0, -18, 7, 7, 3)
      break
    }
    default: {
      const low = l.pose === 'crouch' ? 2 : 0
      const walk = l.pose === 'walk'
      const sw = (a: number) => (walk ? (f ? a : -a) : 0)
      leg(-4 + sw(1), -4 + low, 0, true)
      leg(2 + sw(-1), -4 + low, 0, true)
      fur(-11, -9 + low, -5, -5 + low)
      box(-13, -8 + low, -11, -5 + low, 'w')
      fur(-6, -9 + low, 3, -3 + low)
      leg(-5 + sw(-1), -4 + low, 0, false)
      leg(1 + sw(1), -4 + low, 0, false)
      head(2, -15 + low, 7, 7, 3)
    }
  }
  return R
}

/** Sprite-Leinwand (52×34, Fußpunkt bei FOX_OX/FOX_OY). `silhouette` = Gegenlicht vor der Sonne mit warmem Saum. */
export function foxCanvas(l: FoxLook, silhouette = false, rim = '#ffc27a'): Cv {
  const c = canvas(FOX_W, FOX_H)
  const g = ctx2d(c)
  for (const [x0, y0, x1, y1, col] of foxRects(l)) {
    g.fillStyle = FOX_COL[col]
    g.fillRect(FOX_OX + x0, FOX_OY + y0, x1 - x0 + 1, y1 - y0 + 1)
  }
  if (silhouette) {
    const img = g.getImageData(0, 0, FOX_W, FOX_H)
    const d = img.data
    const on = (x: number, y: number) => x >= 0 && y >= 0 && x < FOX_W && y < FOX_H && d[(y * FOX_W + x) * 4 + 3]! > 0
    const rr = Number.parseInt(rim.slice(1, 3), 16)
    const rg = Number.parseInt(rim.slice(3, 5), 16)
    const rb = Number.parseInt(rim.slice(5, 7), 16)
    const out = new Uint8ClampedArray(d.length)
    for (let y = 0; y < FOX_H; y++) {
      for (let x = 0; x < FOX_W; x++) {
        const i = (y * FOX_W + x) * 4
        if (!on(x, y)) continue
        const edge = !on(x, y - 1) || !on(x + 1, y) || !on(x - 1, y)
        const white = d[i]! > 240 && d[i + 1]! > 240
        out[i] = edge ? rr : white ? 74 : 42
        out[i + 1] = edge ? rg : white ? 40 : 20
        out[i + 2] = edge ? rb : white ? 52 : 32
        out[i + 3] = 255
      }
    }
    g.putImageData(new ImageData(out, FOX_W, FOX_H), 0, 0)
  }
  return c
}

// ───────────────────────── Bewegung (rein) ─────────────────────────

/** Orte im Wald (Kunst-Pixel, Boden = 0). */
export const DEN = { x: 364, hole: [346, 382] as const, top: -32 } as const
/** Der Schläfer liegt oben auf dem Bau (über dem Loch, im Schein des Lagerfeuers). */
export const SLEEPER = { x: 322, y: -32 } as const
export const KIT_SPOTS = [298, 330, 408] as const
/** Pounce: Absprung → Scheitel (vor der Sonne) → Eintauchen in den Süßbeerenbusch. */
export const POUNCE = { from: { x: 470, y: 0 }, apex: { x: 522, y: -62 }, to: { x: 576, y: -6 } } as const
export const BERRY_DROP = { x: 418 } as const

export interface FamilyCues {
  /** Junge purzeln aus dem Bau (je Junges; < 0 = schon draußen). */
  kits: number[]
  /** Fuchs mit Süßbeeren: [kommt ins Bild, legt sie ab]; null = schon abgelegt. */
  berries: [number, number] | null
  /** Pounce [duckt sich, Absprung, taucht in den Busch]. */
  pounce: [number, number, number]
  /** Takt (s) für Hüpfer und Spiel. */
  beat: number
  impact: number
}

export interface FoxState {
  id: string
  x: number
  y: number
  look: FoxLook
  flip: boolean
  rot: number
  /** Im Sprung (Gegenlicht). */
  airborne: boolean
  /** Im dunklen Bau (abgedunkelt) 0…1. */
  shade: number
}

/** Sprungbahn des Pounce: Parabel durch den Scheitel, mit kurzer „Schwebe“ oben, Nase folgt der Bahn. */
export function pounceAt(u: number) {
  const { from: a, apex: m, to: b } = POUNCE
  const s = u + (0.35 * Math.sin(2 * Math.PI * u)) / (2 * Math.PI)
  // quadratische Bézier mit Kontrollpunkt so, dass s = 0,5 den Scheitel trifft
  const cx = 2 * m.x - (a.x + b.x) / 2
  const cy = 2 * m.y - (a.y + b.y) / 2
  const x = (1 - s) ** 2 * a.x + 2 * (1 - s) * s * cx + s * s * b.x
  const y = (1 - s) ** 2 * a.y + 2 * (1 - s) * s * cy + s * s * b.y
  const dx = 2 * (1 - s) * (cx - a.x) + 2 * s * (b.x - cx)
  const dy = 2 * (1 - s) * (cy - a.y) + 2 * s * (b.y - cy)
  // Kopf voran: aufwärts flach, abwärts steil (Minecraft-Pounce)
  const rot = clamp(Math.atan2(dy, dx) * (dy > 0 ? 1.15 : 0.6), -0.7, 1.25)
  return { x, y, rot }
}

/** Alle Füchse zur Zeit t (hinten zuerst). */
export function familyAt(c: FamilyCues, t: number): FoxState[] {
  const out: FoxState[] = []
  const beatT = c.beat
  // Schläfer am Lagerfeuer (atmet langsam)
  out.push({ id: 'sleeper', x: SLEEPER.x, y: SLEEPER.y, look: { pose: 'sleep', kit: false, frame: Math.floor(t / 1.1), berries: false }, flip: false, rot: 0, airborne: false, shade: 0 })
  // Junge
  const berryDown = c.berries ? c.berries[1] : -1
  c.kits.forEach((te, i) => {
    const spot = KIT_SPOTS[i]!
    if (te >= 0 && t < te - 0.25) return
    const age = te < 0 ? 99 : t - te
    if (age < 0) {
      // schaut aus dem Loch
      out.push({ id: `kit${i}`, x: DEN.x, y: 0, look: { pose: 'crouch', kit: true, frame: 0, berries: false }, flip: spot < DEN.x, rot: 0, airborne: false, shade: 0.8 })
      return
    }
    if (age < 0.45) {
      // purzelt heraus: Bogen mit Überschlag
      const k = easeOut(age / 0.45)
      const x = DEN.x + (spot - DEN.x) * k
      const y = -Math.sin(Math.PI * Math.min(1, age / 0.38)) * 10
      out.push({ id: `kit${i}`, x, y, look: { pose: 'tumble', kit: true, frame: 0, berries: false }, flip: spot < DEN.x, rot: (spot < DEN.x ? -1 : 1) * k * Math.PI * 2, airborne: false, shade: clamp(0.8 - age * 4) })
      return
    }
    let x: number = spot
    let y = 0
    let pose: FoxPose = 'stand'
    let flip = false
    if (t >= c.impact - beatT) {
      // vor dem Aufblühen: alle sitzen und schauen zur Sonne
      pose = 'sit'
    } else if (i === 0) {
      // hüpft auf den Schlägen
      const ph = ((t - c.impact) / beatT) % 1
      const p = ph < 0 ? ph + 1 : ph
      y = -Math.max(0, Math.sin(Math.PI * Math.min(1, p / 0.55))) * 7
      pose = y < -1 ? 'crouch' : 'stand'
    } else if (i === 1) {
      // jagt den eigenen Schwanz (dreht auf jedem halben Schlag)
      const n = Math.floor((t - c.impact) / (beatT / 2))
      flip = n % 2 === 0
      x = spot + Math.sin(((t - c.impact) / beatT) * Math.PI) * 5
      pose = 'walk'
    } else {
      // wartet, läuft zu den Beeren und knabbert
      if (berryDown >= 0 && t > berryDown + 0.1) {
        const k = easeInOut(range(t, berryDown + 0.1, berryDown + 0.6))
        x = spot + (BERRY_DROP.x - 4 - spot) * k
        pose = k < 1 ? 'walk' : 'crouch'
      } else pose = 'sit'
      flip = false
    }
    const frame = Math.floor(t * 8)
    out.push({ id: `kit${i}`, x, y, look: { pose, kit: true, frame, berries: false }, flip, rot: 0, airborne: false, shade: 0 })
  })
  // Fuchs mit Süßbeeren → Pounce
  const [p0, p1, p2] = c.pounce
  if (t < p1) {
    let x: number = POUNCE.from.x
    let pose: FoxPose = 'crouch'
    let berries = false
    let frame = Math.floor(t * 9)
    if (c.berries && t < c.berries[1]) {
      const [b0, b1] = c.berries
      x = -40 + (BERRY_DROP.x - 4 + 40) * range(t, b0 - 1.2, b1)
      pose = 'walk'
      berries = true
    } else if (t < p0) {
      const from = c.berries ? c.berries[1] : p0 - 0.6
      const k = easeInOut(range(t, from + 0.15, p0))
      x = BERRY_DROP.x + 6 + (POUNCE.from.x - BERRY_DROP.x - 6) * k
      pose = k > 0 && k < 1 ? 'walk' : 'stand'
    } else {
      // Ducken mit Po-Wackeln (Minecraft)
      x = POUNCE.from.x + Math.round(Math.sin(t * 40) * 0.6)
      frame = 0
    }
    out.push({ id: 'hunter', x, y: 0, look: { pose, kit: false, frame, berries }, flip: false, rot: 0, airborne: false, shade: 0 })
  } else if (t < p2) {
    const p = pounceAt(range(t, p1, p2))
    out.push({ id: 'hunter', x: p.x, y: p.y, look: { pose: 'leap', kit: false, frame: 0, berries: false }, flip: false, rot: p.rot, airborne: true, shade: 0 })
  } else if (t > p2 + 0.35) {
    // schaut mit einer Beere im Maul aus dem Busch
    const k = easeOut(range(t, p2 + 0.35, p2 + 0.6))
    out.push({ id: 'hunter', x: POUNCE.to.x - 2, y: POUNCE.to.y - 8 + (1 - k) * 9, look: { pose: 'peek', kit: false, frame: 0, berries: true }, flip: false, rot: 0, airborne: false, shade: 0 })
  }
  return out
}
