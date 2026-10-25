// Die Fuchsfamilie im Szenen-Set „Fuchsbau“: echte Minecraft-Füchse aus dem Box-Modell des Spiels (Kopf 8×6×6 mit
// Schnauze 4×2×3 und spitzen Ohren, Körper 6×6×11, vier Beine 2×6×2 mit dunklen Socken, buschiger Schwanz 4×9×5 mit
// weißer Spitze), in 3/4-Ansicht von vorn-seitlich gerastert – harte Schattierung je Fläche (oben hell, Seite mittel,
// unten dunkel) wie im Spiel. Junge haben den großen Kopf und kleinen Körper der Minecraft-Babyfüchse.
// Posen wie im Spiel: Laufen, Sitzen, eingerollt Schlafen (Schwanz über der Schnauze), Schnüffeln, Ducken mit
// Po-Wackeln, Pounce (Körper schräg, Vorderbeine vorgestreckt, kopfüber), Süßbeere quer im Maul.
// Modell und Bewegung sind rein (testbar); gerastert wird einmal je Pose/Bild in einen Zwischenspeicher.

import { canvas, clamp, ctx2d, easeInOut, easeOut, range, type Cv } from './kit'

// ───────────────────────── Farben (Minecraft-Fuchs) ─────────────────────────

export const FOX_COL = {
  o: '#e07a3a',
  s: '#c8632a',
  l: '#f0a05a',
  w: '#f4efe8',
  v: '#ddd3c8',
  d: '#3a2a26',
  D: '#2a1c19',
  n: '#141010',
  e: '#1a1210',
  g: '#ffffff',
  k: '#2a1a16',
  r: '#c8102e',
  R: '#ff5a6a',
  q: '#8a0a1e',
  G: '#3f6a2a',
} as const
type Col = keyof typeof FOX_COL

// ───────────────────────── Box-Modell ─────────────────────────

export type V3 = [number, number, number]
type Axis = 'x' | 'y' | 'z'
export interface Xf {
  axis: Axis
  angle: number
  pivot: V3
}
export type Face = 'px' | 'nx' | 'py' | 'ny' | 'pz' | 'nz'
export type PartKind = 'head' | 'snout' | 'ear' | 'body' | 'leg' | 'tail' | 'berry'

export interface Part {
  kind: PartKind
  /** Ecke mit kleinsten Koordinaten (Einheit = ein Minecraft-Pixel; x vorn, y oben, z zur Kamera). */
  min: V3
  size: V3
  /** Drehungen (in dieser Reihenfolge) um Drehpunkte – Gelenke und ganze Körperhaltung. */
  xf: Xf[]
  /** Augen geschlossen (Kopf). */
  closed?: boolean
  /** Schwanzspitze vorn (+x) statt hinten. */
  tipFront?: boolean
}

export type FoxPose = 'stand' | 'walk' | 'sit' | 'sleep' | 'crouch' | 'leap' | 'peek' | 'tumble' | 'sniff'

export interface FoxLook {
  pose: FoxPose
  kit: boolean
  /** Laufbild 0–3 (Beine), Wackeln beim Ducken, Atmen beim Schlafen. */
  frame: number
  /** Süßbeere quer im Maul. */
  berries: boolean
  /** Neigung im Sprung (rad, + = Nase nach unten), wird auf 10°-Schritte gerundet. */
  pitch?: number
}

const rot = (axis: Axis, angle: number, pivot: V3): Xf => ({ axis, angle, pivot })

export function applyXf(p: V3, xf: readonly Xf[]): V3 {
  let [x, y, z] = p
  for (const t of xf) {
    const c = Math.cos(t.angle)
    const s = Math.sin(t.angle)
    const [px, py, pz] = t.pivot
    if (t.axis === 'z') {
      const dx = x - px
      const dy = y - py
      x = px + dx * c - dy * s
      y = py + dx * s + dy * c
    } else if (t.axis === 'y') {
      const dx = x - px
      const dz = z - pz
      x = px + dx * c + dz * s
      z = pz - dx * s + dz * c
    } else {
      const dy = y - py
      const dz = z - pz
      y = py + dy * c - dz * s
      z = pz + dy * s + dz * c
    }
  }
  return [x, y, z]
}

/** Höhe der Körpermitte über dem Boden (Einheiten) – Drehpunkt im Sprung, Ziel für die Sonne. */
export const FOX_CENTER = 9

/** Kopf samt Schnauze, Ohren und ggf. Beere; `o` = Ecke des Kopfes, `k` = Maßstab, `xf` = Kopfhaltung. */
function headParts(o: V3, k: number, xf: Xf[], opts: { closed?: boolean; berries?: boolean; earsBack?: boolean }): Part[] {
  const [x, y, z] = o
  const ear = (zz: number): Part => ({ kind: 'ear', min: [x + 1.5 * k, y + 6 * k, zz], size: [1 * k, 2 * k, 2 * k], xf: opts.earsBack ? [rot('z', 0.6, [x + 2 * k, y + 6 * k, 0]), ...xf] : xf })
  const parts: Part[] = [
    { kind: 'head', min: [x, y, z], size: [6 * k, 6 * k, 8 * k], xf, closed: opts.closed },
    { kind: 'snout', min: [x + 6 * k, y, z + 2 * k], size: [3 * k, 2 * k, 4 * k], xf },
    ear(z + 0.5 * k),
    ear(z + 5.5 * k),
  ]
  if (opts.berries) parts.push({ kind: 'berry', min: [x + 7.2 * k, y + 0.6 * k, z - 0.8 * k], size: [1.2 * k, 2.4 * k, 9.6 * k], xf })
  return parts
}

/** Teile eines Fuchses in einer Pose (rein). Fußpunkt unter der Körpermitte = (0, 0, 0), Blick nach +x. */
export function foxModel(l: FoxLook): Part[] {
  const B = l.kit ? 0.55 : 1
  const Hk = l.kit ? 0.82 : 1
  const f = l.frame & 3
  const parts: Part[] = []
  const bodyFront = 5.5 * B
  const legs = (h: number, swing: number[], body: Xf[] = []) => {
    // [vorn nah, vorn fern, hinten nah, hinten fern]
    const spots: [number, number][] = [
      [2.5, 1],
      [2.5, -3],
      [-5, 1],
      [-5, -3],
    ]
    spots.forEach(([lx, lz], i) => {
      const min: V3 = [lx * B, 0, lz * B]
      const size: V3 = [2 * B, h, 2 * B]
      const pivot: V3 = [(lx + 1) * B, h, 0]
      parts.push({ kind: 'leg', min, size, xf: [rot('z', swing[i] ?? 0, pivot), ...body] })
    })
  }
  const tail = (min: V3, xf: Xf[]) => parts.push({ kind: 'tail', min, size: [9 * B, 5 * B, 4 * B], xf })
  const head = (o: V3, xf: Xf[], opts: Parameters<typeof headParts>[3] = {}) => parts.push(...headParts(o, Hk, xf, { berries: l.berries, ...opts }))
  const headAt = (bodyTop: number): V3 => [bodyFront - 1.5 * Hk, bodyTop - 3 * Hk, -4 * Hk]

  if (l.pose === 'tumble') {
    // Überschlag: geduckt, als Ganzes um die Körpermitte gerollt (3D)
    const ps = foxModel({ ...l, pose: 'crouch' })
    const ang = Math.round(((l.pitch ?? 0) * 180) / Math.PI / 30) * 30 * (Math.PI / 180)
    for (const p of ps) p.xf = [...p.xf, rot('z', -ang, [0, 6 * B, 0])]
    return ps
  }
  switch (l.pose) {
    case 'sleep': {
      // eingerollt: Körper flach am Boden, Kopf zur Kamera gedreht auf den Pfoten, Schwanz vorn über der Schnauze
      const breath = f % 2 ? 0.25 : 0
      parts.push({ kind: 'body', min: [-5.5 * B, 0, -3 * B], size: [11 * B, 5 * B + breath, 6 * B], xf: [] })
      const ho: V3 = [bodyFront - 2.5 * Hk, 0, -3.5 * Hk]
      head(ho, [rot('y', -0.55, [ho[0] + 3 * Hk, 0, 0])], { closed: true, earsBack: true, berries: false })
      // Schwanz vorn an der Kameraseite entlang, Spitze vor der Schnauze
      parts.push({ kind: 'tail', min: [-3.5 * B, 0, 2.4 * B], size: [10 * B, 3.6 * B, 4 * B], xf: [rot('y', -0.12, [0, 0, 3 * B])], tipFront: true })
      break
    }
    case 'sit': {
      // sitzend: Körper schräg aufgerichtet, Vorderbeine gerade, Schwanz vorn um die Pfoten
      const tilt = 0.85
      const pivot: V3 = [-5.5 * B, 1.5 * B, 0]
      const body: Xf[] = [rot('z', tilt, pivot)]
      // Schwanz liegt hinten am Boden
      tail([-15 * B, 0, -2 * B], [rot('z', 0.05, [-6 * B, 2 * B, 0])])
      parts.push({ kind: 'body', min: [-5.5 * B, 1.5 * B, -3 * B], size: [11 * B, 6 * B, 6 * B], xf: body })
      const chest = applyXf([bodyFront, 1.5 * B + 6 * B, 0], body)
      const legH = Math.max(3, applyXf([bodyFront, 1.5 * B, 0], body)[1] + 0.5)
      ;[1, -3].forEach((lz) => parts.push({ kind: 'leg', min: [chest[0] - 3.5 * B, 0, lz * B], size: [2 * B, legH, 2 * B], xf: [] }))
      const ho: V3 = [chest[0] - 3.5 * Hk, chest[1] - 1.5 * Hk, -4 * Hk]
      head(ho, [rot('z', -0.08, [ho[0], ho[1], 0])])
      break
    }
    case 'peek': {
      // nur Kopf (schaut aus dem Busch): Fußpunkt = Kinn
      head([-3 * Hk, 0, -4 * Hk], [rot('z', 0.08, [0, 0, 0])])
      break
    }
    case 'leap': {
      // Pounce: ganzer Körper geneigt, Vorderbeine vorgestreckt, Hinterbeine nach hinten, Schwanz gestreckt
      const pitch = Math.round(((l.pitch ?? 0) * 180) / Math.PI / 10) * 10 * (Math.PI / 180)
      const all: Xf[] = [rot('z', -pitch, [0, FOX_CENTER * B, 0])]
      parts.push({ kind: 'body', min: [-5.5 * B, 6 * B, -3 * B], size: [11 * B, 6 * B, 6 * B], xf: all })
      const legsAir: [number, number, number][] = [
        [2.5, 1, 1.35],
        [2.5, -3, 1.2],
        [-5, 1, -1.25],
        [-5, -3, -1.1],
      ]
      for (const [lx, lz, sw] of legsAir) parts.push({ kind: 'leg', min: [lx * B, 0.5 * B, lz * B], size: [2 * B, 6 * B, 2 * B], xf: [rot('z', sw, [(lx + 1) * B, 6.5 * B, 0]), ...all] })
      tail([-14.5 * B, 6.8 * B, -2 * B], [rot('z', -0.12, [-5.5 * B, 9.5 * B, 0]), ...all])
      head(headAt(12 * B), [rot('z', -0.1, [bodyFront, 10 * B, 0]), ...all], { earsBack: true })
      break
    }
    default: {
      // stehen, laufen, schnüffeln, ducken (mit Po-Wackeln)
      const crouch = l.pose === 'crouch'
      const sniff = l.pose === 'sniff'
      const walk = l.pose === 'walk'
      const low = crouch ? 2.6 * B : 0
      const legH = 6 * B - low
      const wig = crouch ? [0.14, 0, -0.14, 0][f]! : 0
      const rear: Xf[] = wig ? [rot('y', wig, [1.5 * B, 0, 0])] : []
      const bob = walk && f % 2 ? 0.3 * B : 0
      const sw = walk ? [0.45, -0.45, -0.45, 0.45].map((a) => a * (f === 0 ? 1 : f === 1 ? 0.3 : f === 2 ? -1 : -0.3)) : [0, 0, 0, 0]
      // ferne Beine zuerst
      legs(legH, sw, [])
      // hintere Beine wackeln mit dem Po
      for (const p of parts) if (p.kind === 'leg' && p.min[0] < 0) p.xf = [...p.xf, ...rear]
      parts.push({ kind: 'body', min: [-5.5 * B, legH + bob, -3 * B], size: [11 * B, 6 * B, 6 * B], xf: rear })
      const tailUp = crouch ? 0.42 : walk ? 0.05 : -0.18
      tail([-14.5 * B, legH + bob + 0.8 * B, -2 * B], [rot('z', -tailUp + (walk ? (f % 2 ? 0.06 : -0.06) : 0), [-5.5 * B, legH + bob + 3.5 * B, 0]), ...rear])
      const ho = headAt(legH + bob + 6 * B)
      const nod = sniff ? -0.55 : crouch ? -0.22 : 0
      const tiltX = sniff ? 0.18 : 0
      head(ho, [rot('z', nod, [ho[0] + 1, ho[1] + 1, 0]), ...(tiltX ? [rot('x', tiltX, [ho[0], ho[1] + 3 * Hk, 0])] : [])])
    }
  }
  return parts
}

// ───────────────────────── Texturen ─────────────────────────

/** Texel einer Fläche (u nach rechts, v nach unten; W×H = Flächengröße in Einheiten). */
export function foxTexel(p: Part, face: Face, u: number, v: number, W: number, H: number): Col {
  const n = ((u * 7 + v * 13 + (face.length + p.kind.length) * 3) % 11) / 11
  switch (p.kind) {
    case 'head': {
      if (face === 'px') {
        // Gesicht: Augen dunkel mit hellem Glanzpixel, unten weiße Wangen
        const eyeRow = Math.round(H * 0.34)
        const e1 = Math.round(W * 0.14)
        const e2 = W - 1 - Math.round(W * 0.14) - 1
        if (p.closed) {
          if (v === eyeRow + 1 && ((u >= e1 && u <= e1 + 1) || (u >= e2 && u <= e2 + 1))) return 'k'
        } else if ((v === eyeRow || v === eyeRow + 1) && ((u >= e1 && u <= e1 + 1) || (u >= e2 && u <= e2 + 1))) {
          return v === eyeRow && (u === e1 + 1 || u === e2) ? 'g' : 'e'
        }
        if (v >= H - 2) return u <= 1 || u >= W - 2 ? 'w' : 'v'
        return v === 0 ? 'l' : 'o'
      }
      if (face === 'pz' || face === 'nz') {
        const front = face === 'pz' ? u >= W * 0.34 : u < W * 0.66
        if (v >= H - 2 && front) return 'w'
        return v === 0 ? 'l' : n > 0.85 ? 's' : 'o'
      }
      if (face === 'py') return n > 0.8 ? 'o' : 'l'
      if (face === 'ny') return 'w'
      return 'o'
    }
    case 'snout': {
      if (face === 'px') return v === 0 && u >= W * 0.25 && u < W * 0.75 ? 'n' : 'w'
      if (face === 'py') return u === W - 1 && v >= H * 0.25 && v < H * 0.75 ? 'n' : u >= W - 1 ? 'w' : 'l'
      if (face === 'ny') return 'v'
      return v === 0 ? 'o' : 'w'
    }
    case 'ear':
      if (face === 'py') return 'k'
      return v === 0 ? 'k' : face === 'px' && u > 0 && W > 1 ? 'v' : 'o'
    case 'body': {
      if (face === 'ny') return 'w'
      if (face === 'px') return v === 0 ? 'o' : 'w'
      if (face === 'py') return n > 0.82 ? 'o' : 'l'
      if (face === 'pz' || face === 'nz') return v === 0 ? 'l' : v >= H - 1 ? 's' : n > 0.9 ? 's' : 'o'
      return 'o'
    }
    case 'leg': {
      const sock = v >= H * 0.5
      if (face === 'ny') return 'D'
      if (face === 'py') return 'o'
      return sock ? (v >= H - 1 ? 'D' : 'd') : v === 0 ? 'l' : 'o'
    }
    case 'tail': {
      // Spitze am hinteren Ende (x klein) weiß – oder vorn, wenn der Schwanz um die Schnauze liegt
      const tf = !!p.tipFront
      if (face === (tf ? 'px' : 'nx')) return 'w'
      if (face === (tf ? 'nx' : 'px')) return 'o'
      const alongX = face === 'pz' || face === 'py' || face === 'ny' ? u : W - 1 - u
      const fromTip = tf ? W - 1 - alongX : alongX
      if (fromTip < 3) return fromTip === 2 && n > 0.5 ? 'v' : 'w'
      if (face === 'py') return n > 0.75 ? 'o' : 'l'
      if (face === 'ny') return 's'
      return v === 0 ? 'l' : n > 0.82 ? 's' : 'o'
    }
    case 'berry': {
      const k = (u * 5 + v * 3) % 7
      if (face === 'py' && u % 4 === 1) return 'G'
      return k === 0 ? 'R' : k === 3 ? 'q' : 'r'
    }
  }
}

// ───────────────────────── Projektion + Rastern ─────────────────────────

/** Kamera der Szene: 3/4-Ansicht leicht von vorn-seitlich und etwas von oben. */
export const FOX_VIEW = { yaw: 0.6, pitch: 0.3 } as const
/** Kunst-Pixel je Minecraft-Pixel. */
export const FOX_SCALE = 2.5

const V = (() => {
  const a = FOX_VIEW.yaw
  const p = FOX_VIEW.pitch
  return {
    r: [Math.cos(a), 0, -Math.sin(a)] as V3,
    u: [-Math.sin(a) * Math.sin(p), Math.cos(p), -Math.cos(a) * Math.sin(p)] as V3,
    d: [Math.sin(a) * Math.cos(p), Math.sin(p), Math.cos(a) * Math.cos(p)] as V3,
  }
})()
const dot = (a: V3, b: V3) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
const sub = (a: V3, b: V3): V3 => [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
const cross = (a: V3, b: V3): V3 => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
const LIGHT = (() => {
  const l: V3 = [0.3, 1, 0.55]
  const n = Math.hypot(...l)
  return l.map((c) => c / n) as V3
})()

/** Welt (Einheiten) → Bild (Kunst-Pixel relativ zum Fußpunkt) + Tiefe (größer = näher). */
export function projectFox(p: V3, scale = FOX_SCALE) {
  return { x: dot(p, V.r) * scale, y: -dot(p, V.u) * scale, z: dot(p, V.d) }
}

/** Harte Minecraft-Schattierung je Fläche nach ihrer Lage zum Licht (oben hell … unten dunkel). */
export function faceShade(n: V3): number {
  const l = dot(n, LIGHT)
  return l > 0.75 ? 1 : l > 0.42 ? 0.86 : l > 0.1 ? 0.74 : l > -0.3 ? 0.62 : 0.5
}

const FACES: { face: Face; o: [number, number, number]; u: [number, number, number]; v: [number, number, number]; W: 0 | 1 | 2; H: 0 | 1 | 2 }[] = [
  { face: 'pz', o: [0, 1, 1], u: [1, 1, 1], v: [0, 0, 1], W: 0, H: 1 },
  { face: 'nz', o: [1, 1, 0], u: [0, 1, 0], v: [1, 0, 0], W: 0, H: 1 },
  { face: 'px', o: [1, 1, 1], u: [1, 1, 0], v: [1, 0, 1], W: 2, H: 1 },
  { face: 'nx', o: [0, 1, 0], u: [0, 1, 1], v: [0, 0, 0], W: 2, H: 1 },
  { face: 'py', o: [0, 1, 0], u: [1, 1, 0], v: [0, 1, 1], W: 0, H: 2 },
  { face: 'ny', o: [0, 0, 1], u: [1, 0, 1], v: [0, 0, 0], W: 0, H: 2 },
]

export interface FoxSprite {
  cv: Cv
  /** Fußpunkt im Sprite. */
  ox: number
  oy: number
}

const hexRgb = (h: string): [number, number, number] => {
  const n = Number.parseInt(h.slice(1), 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}
const PAL = Object.fromEntries(Object.entries(FOX_COL).map(([k, v]) => [k, hexRgb(v)])) as Record<Col, [number, number, number]>
const WARM: [number, number, number] = [255, 214, 170]

/**
 * Fuchs rastern (Software, ohne Glättung): jede sichtbare Box-Fläche als Parallelogramm, Texel per Rückprojektion,
 * Tiefenpuffer. `silhouette` = Gegenlicht vor der Sonne (dunkle Form mit warmem Saum).
 */
export function renderFox(l: FoxLook, silhouette = false, rim = '#ffc27a', scale = FOX_SCALE): FoxSprite {
  const parts = foxModel(l)
  type F = { o: { x: number; y: number; z: number }; e1: { x: number; y: number; z: number }; e2: { x: number; y: number; z: number }; W: number; H: number; shade: number; top: boolean; part: Part; face: Face }
  const faces: F[] = []
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const p of parts) {
    const corner = (c: [number, number, number]): V3 => applyXf([p.min[0] + c[0] * p.size[0], p.min[1] + c[1] * p.size[1], p.min[2] + c[2] * p.size[2]], p.xf)
    for (const fd of FACES) {
      const O = corner(fd.o)
      const U = corner(fd.u)
      const Vv = corner(fd.v)
      const E1 = sub(U, O)
      const E2 = sub(Vv, O)
      const nrm = cross(E2, E1)
      const len = Math.hypot(...nrm) || 1
      const n: V3 = [nrm[0] / len, nrm[1] / len, nrm[2] / len]
      if (dot(n, V.d) <= 0.0001) continue
      const po = projectFox(O, scale)
      const pu = projectFox(U, scale)
      const pv = projectFox(Vv, scale)
      const e1 = { x: pu.x - po.x, y: pu.y - po.y, z: pu.z - po.z }
      const e2 = { x: pv.x - po.x, y: pv.y - po.y, z: pv.z - po.z }
      for (const q of [po, pu, pv, { x: po.x + e1.x + e2.x, y: po.y + e1.y + e2.y }]) {
        minX = Math.min(minX, q.x)
        minY = Math.min(minY, q.y)
        maxX = Math.max(maxX, q.x)
        maxY = Math.max(maxY, q.y)
      }
      faces.push({ o: po, e1, e2, W: Math.max(1, Math.round(p.size[fd.W])), H: Math.max(1, Math.round(p.size[fd.H])), shade: faceShade(n), top: n[1] > 0.6, part: p, face: fd.face })
    }
  }
  const x0 = Math.floor(minX) - 1
  const y0 = Math.floor(minY) - 1
  const w = Math.ceil(maxX) - x0 + 2
  const h = Math.ceil(maxY) - y0 + 2
  const data = new Uint8ClampedArray(w * h * 4)
  const depth = new Float32Array(w * h).fill(-Infinity)
  for (const f of faces) {
    const det = f.e1.x * f.e2.y - f.e1.y * f.e2.x
    if (Math.abs(det) < 1e-6) continue
    const bx0 = Math.max(0, Math.floor(Math.min(f.o.x, f.o.x + f.e1.x, f.o.x + f.e2.x, f.o.x + f.e1.x + f.e2.x) - x0))
    const bx1 = Math.min(w - 1, Math.ceil(Math.max(f.o.x, f.o.x + f.e1.x, f.o.x + f.e2.x, f.o.x + f.e1.x + f.e2.x) - x0))
    const by0 = Math.max(0, Math.floor(Math.min(f.o.y, f.o.y + f.e1.y, f.o.y + f.e2.y, f.o.y + f.e1.y + f.e2.y) - y0))
    const by1 = Math.min(h - 1, Math.ceil(Math.max(f.o.y, f.o.y + f.e1.y, f.o.y + f.e2.y, f.o.y + f.e1.y + f.e2.y) - y0))
    for (let py = by0; py <= by1; py++) {
      for (let px = bx0; px <= bx1; px++) {
        const dx = px + 0.5 + x0 - f.o.x
        const dy = py + 0.5 + y0 - f.o.y
        const u = (dx * f.e2.y - dy * f.e2.x) / det
        const v = (f.e1.x * dy - f.e1.y * dx) / det
        if (u < 0 || v < 0 || u >= 1 || v >= 1) continue
        const z = f.o.z + u * f.e1.z + v * f.e2.z
        const i = py * w + px
        if (z <= depth[i]!) continue
        depth[i] = z
        const col = PAL[foxTexel(f.part, f.face, Math.min(f.W - 1, Math.floor(u * f.W)), Math.min(f.H - 1, Math.floor(v * f.H)), f.W, f.H)]
        let r = col[0] * f.shade
        let g = col[1] * f.shade
        let b = col[2] * f.shade
        if (f.top) {
          r += (WARM[0] - r) * 0.12
          g += (WARM[1] - g) * 0.12
          b += (WARM[2] - b) * 0.12
        }
        data[i * 4] = r
        data[i * 4 + 1] = g
        data[i * 4 + 2] = b
        data[i * 4 + 3] = 255
      }
    }
  }
  if (silhouette) {
    // Gegenlicht: dunkle Form, die Flächen bleiben in zwei, drei Tönen erkennbar (3D-Gefühl), warmer Saum am Rand
    const [rr, rg, rb] = hexRgb(rim)
    const src = data.slice()
    const on = (x: number, y: number) => x >= 0 && y >= 0 && x < w && y < h && src[(y * w + x) * 4 + 3]! > 0
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const i = (y * w + x) * 4
        if (!on(x, y)) continue
        const lum = (0.3 * src[i]! + 0.59 * src[i + 1]! + 0.11 * src[i + 2]!) / 255
        const k = Math.round(lum * 3) / 3
        let r = 34 + 52 * k
        let g = 16 + 26 * k
        let b = 26 + 30 * k
        const top = !on(x, y - 1) || !on(x, y - 2)
        const side = !on(x + 1, y) || !on(x - 1, y)
        const a = !on(x, y - 1) ? 1 : top ? 0.45 : side ? 0.6 : 0
        r += (rr - r) * a
        g += (rg - g) * a
        b += (rb - b) * a
        data[i] = r
        data[i + 1] = g
        data[i + 2] = b
      }
    }
  }
  const cv = canvas(w, h)
  ctx2d(cv).putImageData(new ImageData(data as Uint8ClampedArray<ArrayBuffer>, w, h), 0, 0)
  return { cv, ox: -x0, oy: -y0 }
}

// ───────────────────────── Bewegung (rein) ─────────────────────────

/** Orte im Wald (Kunst-Pixel, Boden = 0). */
export const DEN = { x: 364, hole: [346, 382] as const, top: -32 } as const
/** Der Schläfer liegt oben auf dem Bau (über dem Loch, im Schein des Lagerfeuers). */
export const SLEEPER = { x: 344, y: -32 } as const
export const KIT_SPOTS = [272, 316, 410] as const
/** Pounce: Absprung → Scheitel (vor der Sonne) → Eintauchen in den Süßbeerenbusch. */
export const POUNCE = { from: { x: 486, y: 0 }, apex: { x: 535, y: -50 }, to: { x: 584, y: -8 } } as const
export const BERRY_DROP = { x: 420 } as const

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
  /** Blick nach links (Sprite gespiegelt). */
  flip: boolean
  /** 2D-Drehung (nur beim Purzeln). */
  rot: number
  /** Im Sprung (Gegenlicht). */
  airborne: boolean
  /** Im dunklen Bau (abgedunkelt) 0…1. */
  shade: number
}

/** Sprungbahn des Pounce: Parabel durch den Scheitel, mit kurzer „Schwebe“ oben; Neigung folgt der Bahn. */
export function pounceAt(u: number) {
  const { from: a, apex: m, to: b } = POUNCE
  const s = u + (0.35 * Math.sin(2 * Math.PI * u)) / (2 * Math.PI)
  const cx = 2 * m.x - (a.x + b.x) / 2
  const cy = 2 * m.y - (a.y + b.y) / 2
  const x = (1 - s) ** 2 * a.x + 2 * (1 - s) * s * cx + s * s * b.x
  const y = (1 - s) ** 2 * a.y + 2 * (1 - s) * s * cy + s * s * b.y
  const dx = 2 * (1 - s) * (cx - a.x) + 2 * s * (b.x - cx)
  const dy = 2 * (1 - s) * (cy - a.y) + 2 * s * (b.y - cy)
  // Kopf voran: aufwärts flach, abwärts steil (Minecraft-Pounce); + = Nase nach unten
  const rot = clamp(Math.atan2(dy, dx) * (dy > 0 ? 1.2 : 0.6), -0.7, 1.3)
  return { x, y, rot }
}

/** Alle Füchse zur Zeit t (hinten zuerst). */
export function familyAt(c: FamilyCues, t: number): FoxState[] {
  const out: FoxState[] = []
  const beatT = c.beat
  out.push({ id: 'sleeper', x: SLEEPER.x, y: SLEEPER.y, look: { pose: 'sleep', kit: false, frame: Math.floor(t / 1.1), berries: false }, flip: true, rot: 0, airborne: false, shade: 0 })
  // Der Jäger läuft hinter den Jungen vorbei (weiter hinten im Bild)
  const [p0, p1, p2] = c.pounce
  if (t < p1) {
    let x: number = POUNCE.from.x
    let pose: FoxPose = 'crouch'
    let berries = false
    let frame = Math.floor(t * 9)
    if (c.berries && t < c.berries[1]) {
      const [b0, b1] = c.berries
      x = -60 + (BERRY_DROP.x + 60) * range(t, b0 - 1.2, b1)
      pose = 'walk'
      berries = true
    } else if (t < p0) {
      const from = c.berries ? c.berries[1] : p0 - 0.6
      const k = easeInOut(range(t, from + 0.15, p0))
      x = BERRY_DROP.x + (POUNCE.from.x - BERRY_DROP.x) * k
      pose = k <= 0 ? 'sniff' : k < 1 ? 'walk' : 'stand'
    } else {
      // Ducken mit Po-Wackeln (Minecraft)
      frame = Math.floor(t * 14)
    }
    out.push({ id: 'hunter', x, y: 0, look: { pose, kit: false, frame, berries }, flip: false, rot: 0, airborne: false, shade: 0 })
  } else if (t < p2) {
    const u = range(t, p1, p2)
    const p = pounceAt(u)
    // Gegenlicht nur, solange er vor der Sonne ist (um den Scheitel) – davor und beim Eintauchen farbig
    out.push({ id: 'hunter', x: p.x, y: p.y, look: { pose: 'leap', kit: false, frame: 0, berries: false, pitch: p.rot }, flip: false, rot: 0, airborne: Math.abs(u - 0.5) < 0.24, shade: 0 })
  } else if (t > p2 + 0.35) {
    // schaut mit einer Süßbeere quer im Maul aus dem Busch
    const k = easeOut(range(t, p2 + 0.35, p2 + 0.6))
    out.push({ id: 'hunter', x: POUNCE.to.x - 4, y: POUNCE.to.y - 14 + (1 - k) * 14, look: { pose: 'peek', kit: false, frame: 0, berries: true }, flip: false, rot: 0, airborne: false, shade: 0 })
  }
  const berryDown = c.berries ? c.berries[1] : -1
  c.kits.forEach((te, i) => {
    const spot = KIT_SPOTS[i]!
    if (te >= 0 && t < te - 0.25) return
    const age = te < 0 ? 99 : t - te
    const left = spot < DEN.x
    if (age < 0) {
      // schaut aus dem Loch
      out.push({ id: `kit${i}`, x: DEN.x, y: 0, look: { pose: 'sniff', kit: true, frame: 0, berries: false }, flip: left, rot: 0, airborne: false, shade: 0.8 })
      return
    }
    if (age < 0.45) {
      // purzelt heraus: Bogen mit Überschlag
      const k = easeOut(age / 0.45)
      const x = DEN.x + (spot - DEN.x) * k
      const y = -Math.sin(Math.PI * Math.min(1, age / 0.38)) * 12
      out.push({ id: `kit${i}`, x, y, look: { pose: 'tumble', kit: true, frame: 0, berries: false, pitch: k * Math.PI * 2 }, flip: left, rot: 0, airborne: false, shade: clamp(0.8 - age * 4) })
      return
    }
    let x: number = spot
    let y = 0
    let pose: FoxPose = 'stand'
    let flip = false
    if (t >= c.impact - beatT) {
      pose = 'sit'
    } else if (i === 0) {
      // hüpft auf den Schlägen
      const ph = ((t - c.impact) / beatT) % 1
      const p = ph < 0 ? ph + 1 : ph
      y = -Math.max(0, Math.sin(Math.PI * Math.min(1, p / 0.55))) * 8
      pose = y < -1 ? 'crouch' : 'stand'
    } else if (i === 1) {
      // jagt den eigenen Schwanz (dreht auf jedem halben Schlag)
      const n = Math.floor((t - c.impact) / (beatT / 2))
      flip = n % 2 === 0
      x = spot + Math.sin(((t - c.impact) / beatT) * Math.PI) * 6
      pose = 'walk'
    } else if (berryDown >= 0 && t > berryDown + 0.1) {
      // läuft zu den Beeren und schnüffelt daran
      const k = easeInOut(range(t, berryDown + 0.1, berryDown + 0.6))
      x = spot + (BERRY_DROP.x - 14 - spot) * k
      pose = k < 1 ? 'walk' : 'sniff'
    } else pose = 'sit'
    const frame = Math.floor(t * 8)
    out.push({ id: `kit${i}`, x, y, look: { pose, kit: true, frame, berries: false }, flip, rot: 0, airborne: false, shade: 0 })
  })
  return out
}
