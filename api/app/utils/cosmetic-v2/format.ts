// TRS Kosmetik-Format v2 – Prüfung, Mathematik und Animation (TypeScript-Port der Studio-Referenz
// `trs-studio/public/cosmetic-format.mjs`). Bewusst ohne Abhängigkeiten (kein Nuxt, kein three.js, kein DOM):
// dieselbe Datei läuft auf dem Server (Prüfung beim Start), auf der Website und im Launcher.
// Spezifikation: API.md §11.9 bzw. trs-studio/cosmetic-format.md. Nicht „verbessern“ – die Werte müssen
// exakt der Referenz entsprechen, sonst sehen Website, Launcher und TRS Client unterschiedlich aus.

export const FORMAT = 2

export type Vec3 = [number, number, number]
/** 4×4-Matrix, spaltenweise (wie three.js `Matrix4.elements` / JOML). */
export type Mat4 = number[]

export type FaceName = 'north' | 'south' | 'east' | 'west' | 'up' | 'down'
export type MaterialName = 'cutout' | 'emissive' | 'translucent'
export type Channel = 'rotation' | 'position' | 'scale'
export type Interpolation = 'linear' | 'smooth' | 'step'
export type Driver = 'idle' | 'walk' | 'sneak' | 'jump' | 'air'

/** Flächen und ihre Außennormale im Modellraum (+x = links vom Spieler, +y = oben, +z = vorne). */
export const FACES: readonly FaceName[] = ['north', 'south', 'east', 'west', 'up', 'down']
export const FACE_NORMAL: Readonly<Record<FaceName, Vec3>> = {
  north: [0, 0, -1],
  south: [0, 0, 1],
  east: [1, 0, 0],
  west: [-1, 0, 0],
  up: [0, 1, 0],
  down: [0, -1, 0],
}

export const MATERIALS: readonly MaterialName[] = ['cutout', 'emissive', 'translucent']
export const ATTACH = ['head'] as const
export const SLOTS = ['hat'] as const
export const CHANNELS: readonly Channel[] = ['rotation', 'position', 'scale']
export const INTERPOLATIONS: readonly Interpolation[] = ['linear', 'smooth', 'step']
export const DRIVERS: readonly Driver[] = ['idle', 'walk', 'sneak', 'jump', 'air']

export const LIMITS = {
  cubes: 64,
  bones: 32,
  /** Kante eines Bilds (Breite, Höhe je Frame) in echten Pixeln. */
  textureEdge: 1024,
  /** Höhe des ganzen Streifens (Höhe × Frames). */
  stripEdge: 4096,
  frames: 16,
  scale: [1, 2, 4, 8, 16],
  animations: 8,
  tracks: 32,
  keys: 64,
  halos: 16,
  /** |x|, |y|, |z| im Anhängepunkt-Raum. */
  coord: 48,
  /** Kantenlänge eines Würfels. */
  size: 32,
  inflate: 1,
  lengthMs: 60000,
} as const

// ---------------------------------------------------------------- Modell-Typen

export interface TextureSpec {
  file: string
  /** Größe in Einheiten (Texel bei Faktor 1), Vielfaches von 8. */
  width: number
  height: number
  /** Texel je Einheit: 1, 2, 4, 8 oder 16. */
  scale: number
  frames?: number
  frameTimeMs?: number
}

export interface GlowSpec {
  file: string
  frames?: number
  frameTimeMs?: number
  blend?: 'additive'
}

export interface Bone {
  id: string
  parent?: string | null
  pivot: Vec3
  rotation?: Vec3
}

export interface Face {
  uv: [number, number, number, number]
  rotation?: 0 | 90 | 180 | 270
  material?: MaterialName
}

export interface Cube {
  id?: string
  bone: string
  from: Vec3
  to: Vec3
  inflate?: number
  material?: MaterialName
  faces: Partial<Record<FaceName, Face | null>>
}

export interface Key {
  t: number
  v: Vec3
  interpolation?: Interpolation
}

export interface Track {
  bone: string
  channel: Channel
  interpolation?: Interpolation
  keys: Key[]
}

export interface Animation {
  id: string
  driver?: Driver
  lengthMs: number
  loop?: boolean
  offsetMs?: number
  tracks: Track[]
}

export interface Pulse {
  periodMs: number
  min: number
  max: number
  phaseMs?: number
}

export interface Halo {
  bone: string
  pos: Vec3
  size: number
  color: string
  intensity?: number
  normal?: Vec3
  pulse?: Pulse
}

export interface CosmeticModel {
  format: 2
  id: string
  name: string
  slot: 'hat'
  attach: 'head'
  texture: TextureSpec
  glow?: GlowSpec | null
  bones: Bone[]
  cubes: Cube[]
  animations?: Animation[]
  halos?: Halo[]
  meta?: Record<string, unknown>
}

export interface Pose {
  position: Vec3
  rotation: Vec3
  scale: Vec3
}

export interface ImageSize {
  width: number
  height: number
}

export interface ValidationResult {
  ok: boolean
  errors: string[]
  warnings: string[]
  stats: {
    cubes?: number
    faces?: number
    bones?: number
    animations?: number
    halos?: number
    texture?: string
    glowFrames?: number
  }
}

const ID = /^[a-z][a-z0-9_]{0,39}$/
const FILE = /^[a-z0-9][a-z0-9_.-]{0,63}\.png$/
const COLOR = /^#[0-9a-f]{6}$/
/** Raster für Koordinaten (1 HD-Texel bei Faktor 8). */
const GRID = 0.125

const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v)
const isVec = (v: unknown): v is Vec3 => Array.isArray(v) && v.length === 3 && v.every(isNum)
const onGrid = (v: number, step: number) => Math.abs(v / step - Math.round(v / step)) < 1e-6

// ---------------------------------------------------------------- Mathematik (4×4, spaltenweise)

export const deg = (d: number): number => (d * Math.PI) / 180

export function identity(): Mat4 {
  return [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
}

export function multiply(a: Mat4, b: Mat4): Mat4 {
  const o = new Array<number>(16)
  for (let c = 0; c < 4; c++) {
    for (let r = 0; r < 4; r++) {
      o[c * 4 + r] = a[r]! * b[c * 4]! + a[4 + r]! * b[c * 4 + 1]! + a[8 + r]! * b[c * 4 + 2]! + a[12 + r]! * b[c * 4 + 3]!
    }
  }
  return o
}

export function translation(x: number, y: number, z: number): Mat4 {
  const m = identity()
  m[12] = x
  m[13] = y
  m[14] = z
  return m
}

/** Drehung in Grad, Reihenfolge ZYX: erst um x, dann um y, dann um z (Matrix Rz·Ry·Rx, wie ModelPart / Blockbench). */
export function rotationZYX(rx: number, ry: number, rz: number): Mat4 {
  const a = deg(rx)
  const b = deg(ry)
  const c = deg(rz)
  const ca = Math.cos(a)
  const sa = Math.sin(a)
  const cb = Math.cos(b)
  const sb = Math.sin(b)
  const cc = Math.cos(c)
  const sc = Math.sin(c)
  return [
    cc * cb, sc * cb, -sb, 0,
    cc * sb * sa - sc * ca, sc * sb * sa + cc * ca, cb * sa, 0,
    cc * sb * ca + sc * sa, sc * sb * ca - cc * sa, cb * ca, 0,
    0, 0, 0, 1,
  ]
}

/** Umkehrung einer ZYX-Drehmatrix in Grad [rx, ry, rz] (auf 4 Nachkommastellen). */
export function eulerZYX(m: Mat4): Vec3 {
  const r20 = m[2]!
  const b = Math.asin(Math.max(-1, Math.min(1, -r20)))
  let a: number
  let c: number
  if (Math.abs(r20) < 0.999999) {
    a = Math.atan2(m[6]!, m[10]!)
    c = Math.atan2(m[1]!, m[0]!)
  } else {
    a = Math.atan2(-m[9]!, m[5]!)
    c = 0
  }
  const r = (v: number) => Math.round(((v * 180) / Math.PI) * 1e4) / 1e4 + 0
  return [r(a), r(b), r(c)]
}

export function scaling(x: number, y: number, z: number): Mat4 {
  const m = identity()
  m[0] = x
  m[5] = y
  m[10] = z
  return m
}

export function transformPoint(m: Mat4, p: Vec3): Vec3 {
  return [
    m[0]! * p[0] + m[4]! * p[1] + m[8]! * p[2] + m[12]!,
    m[1]! * p[0] + m[5]! * p[1] + m[9]! * p[2] + m[13]!,
    m[2]! * p[0] + m[6]! * p[1] + m[10]! * p[2] + m[14]!,
  ]
}

export function transformDir(m: Mat4, d: Vec3): Vec3 {
  return [
    m[0]! * d[0] + m[4]! * d[1] + m[8]! * d[2],
    m[1]! * d[0] + m[5]! * d[1] + m[9]! * d[2],
    m[2]! * d[0] + m[6]! * d[1] + m[10]! * d[2],
  ]
}

/** Umkehrung einer Matrix aus Drehung, Verschiebung und (positiver) Skalierung. */
export function invertAffine(m: Mat4): Mat4 {
  const a = m[0]!
  const b = m[4]!
  const c = m[8]!
  const d = m[1]!
  const e = m[5]!
  const f = m[9]!
  const g = m[2]!
  const h = m[6]!
  const i = m[10]!
  const A = e * i - f * h
  const B = -(d * i - f * g)
  const C = d * h - e * g
  const det = a * A + b * B + c * C
  const inv = [
    A / det, B / det, C / det,
    -(b * i - c * h) / det, (a * i - c * g) / det, -(a * h - b * g) / det,
    (b * f - c * e) / det, -(a * f - c * d) / det, (a * e - b * d) / det,
  ]
  // inv liegt bereits spaltenweise vor (Spalte 0 = [A, B, C] / det)
  const o = [inv[0]!, inv[1]!, inv[2]!, 0, inv[3]!, inv[4]!, inv[5]!, 0, inv[6]!, inv[7]!, inv[8]!, 0, 0, 0, 0, 1]
  const t = transformDir(o, [m[12]!, m[13]!, m[14]!])
  o[12] = -t[0]
  o[13] = -t[1]
  o[14] = -t[2]
  return o
}

// ---------------------------------------------------------------- Geometrie

type Corners = [Vec3, Vec3, Vec3, Vec3]
type UvCorners = [[number, number], [number, number], [number, number], [number, number]]

/**
 * Ecken einer Würfelfläche in fester Reihenfolge [oben-links, oben-rechts, unten-rechts, unten-links],
 * von außen betrachtet (nie gespiegelt). Seiten: oben = +y. up: oben = Norden (−z). down: oben = Süden (+z).
 */
export function faceCorners(from: Vec3, to: Vec3, face: FaceName, inflate = 0): Corners {
  const x0 = from[0] - inflate
  const y0 = from[1] - inflate
  const z0 = from[2] - inflate
  const x1 = to[0] + inflate
  const y1 = to[1] + inflate
  const z1 = to[2] + inflate
  switch (face) {
    case 'south': return [[x0, y1, z1], [x1, y1, z1], [x1, y0, z1], [x0, y0, z1]]
    case 'north': return [[x1, y1, z0], [x0, y1, z0], [x0, y0, z0], [x1, y0, z0]]
    case 'east': return [[x1, y1, z1], [x1, y1, z0], [x1, y0, z0], [x1, y0, z1]]
    case 'west': return [[x0, y1, z0], [x0, y1, z1], [x0, y0, z1], [x0, y0, z0]]
    case 'up': return [[x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]]
    case 'down': return [[x0, y0, z1], [x1, y0, z1], [x1, y0, z0], [x0, y0, z0]]
  }
  throw new Error(`Unbekannte Fläche ${String(face)}`)
}

/** Größe einer Fläche in Einheiten [Breite, Höhe] (ohne inflate). */
export function faceSize(from: Vec3, to: Vec3, face: FaceName): [number, number] {
  const s = [to[0] - from[0], to[1] - from[1], to[2] - from[2]] as const
  if (face === 'north' || face === 'south') return [s[0], s[1]]
  if (face === 'east' || face === 'west') return [s[2], s[1]]
  return [s[0], s[2]]
}

/**
 * UV je Ecke (gleiche Reihenfolge wie faceCorners) in Textur-Einheiten.
 * rotation 90/180/270 dreht das Bild im Uhrzeigersinn auf der Fläche.
 */
export function faceUvCorners(uv: readonly [number, number, number, number], rotation = 0): UvCorners {
  const [u0, v0, u1, v1] = uv
  const img: UvCorners = [[u0, v0], [u1, v0], [u1, v1], [u0, v1]]
  const q = (((rotation / 90) % 4) + 4) % 4
  return [0, 1, 2, 3].map((i) => img[(i - q + 4) % 4]!) as UvCorners
}

// ---------------------------------------------------------------- Knochen und Animation

/** Lokale Matrix eines Knochens: T(pivot + pos) · R(rest + rot) · S(scale) · T(−pivot). */
export function boneLocal(bone: Bone, pose?: Partial<Pose> | null): Mat4 {
  const p = bone.pivot
  const r = bone.rotation ?? [0, 0, 0]
  const pos = pose?.position ?? [0, 0, 0]
  const rot = pose?.rotation ?? [0, 0, 0]
  const sc = pose?.scale ?? [1, 1, 1]
  let m = translation(p[0] + pos[0], p[1] + pos[1], p[2] + pos[2])
  m = multiply(m, rotationZYX(r[0] + rot[0], r[1] + rot[1], r[2] + rot[2]))
  if (sc[0] !== 1 || sc[1] !== 1 || sc[2] !== 1) m = multiply(m, scaling(sc[0], sc[1], sc[2]))
  return multiply(m, translation(-p[0], -p[1], -p[2]))
}

/** Welt-Matrizen aller Knochen (Anhängepunkt-Raum). Eltern stehen immer vor ihren Kindern. */
export function boneMatrices(model: Pick<CosmeticModel, 'bones'>, poses: Map<string, Pose> | null = null): Map<string, Mat4> {
  const out = new Map<string, Mat4>()
  for (const b of model.bones) {
    const local = boneLocal(b, poses?.get(b.id))
    out.set(b.id, b.parent ? multiply(out.get(b.parent)!, local) : local)
  }
  return out
}

const smooth = (u: number) => u * u * (3 - 2 * u)

/** Wert einer Spur zur Zeit `t` (ms innerhalb der Animation). */
export function sampleTrack(track: Pick<Track, 'keys' | 'interpolation'>, t: number): Vec3 {
  const k = track.keys
  if (t <= k[0]!.t) return k[0]!.v
  if (t >= k[k.length - 1]!.t) return k[k.length - 1]!.v
  let i = 0
  while (i < k.length - 2 && t >= k[i + 1]!.t) i++
  const a = k[i]!
  const b = k[i + 1]!
  const mode = b.interpolation ?? track.interpolation ?? 'linear'
  if (mode === 'step') return a.v
  let u = (t - a.t) / (b.t - a.t)
  if (mode === 'smooth') u = smooth(u)
  return [a.v[0] + (b.v[0] - a.v[0]) * u, a.v[1] + (b.v[1] - a.v[1]) * u, a.v[2] + (b.v[2] - a.v[2]) * u]
}

/**
 * Pose aller Knochen zur Wanduhr `timeMs` (currentTimeMillis). `active` = aktive Treiber
 * (Standard nur idle). Drehung/Position werden addiert, Skalierung multipliziert.
 */
export function samplePoses(model: Pick<CosmeticModel, 'animations'>, timeMs: number, active: readonly Driver[] = ['idle']): Map<string, Pose> {
  const poses = new Map<string, Pose>()
  for (const anim of model.animations ?? []) {
    if (!active.includes(anim.driver ?? 'idle')) continue
    const len = anim.lengthMs
    let t = timeMs + (anim.offsetMs ?? 0)
    t = anim.loop === false ? Math.min(Math.max(t, 0), len) : ((t % len) + len) % len
    for (const tr of anim.tracks) {
      const v = sampleTrack(tr, t)
      const p = poses.get(tr.bone) ?? { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] }
      if (tr.channel === 'scale') p.scale = [p.scale[0] * v[0], p.scale[1] * v[1], p.scale[2] * v[2]]
      else {
        const cur = p[tr.channel]
        p[tr.channel] = [cur[0] + v[0], cur[1] + v[1], cur[2] + v[2]]
      }
      poses.set(tr.bone, p)
    }
  }
  return poses
}

/** Bild eines Streifens zur Wanduhr (wie bei Umhängen). */
export function frameAt(timeMs: number, frames: number | undefined, frameTimeMs: number | undefined): number {
  if (!frames || frames <= 1 || !frameTimeMs) return 0
  return Math.floor(timeMs / frameTimeMs) % frames
}

/** Helligkeit eines Leucht-Hofs (0 … intensity). */
export function haloIntensity(halo: Pick<Halo, 'intensity' | 'pulse'>, timeMs: number): number {
  const base = halo.intensity ?? 1
  const p = halo.pulse
  if (!p) return base
  const ph = ((timeMs + (p.phaseMs ?? 0)) % p.periodMs) / p.periodMs
  return base * (p.min + (p.max - p.min) * (0.5 - 0.5 * Math.cos(2 * Math.PI * ph)))
}

/**
 * Ausblenden nach Blickrichtung: `d` = Skalarprodukt aus Hof-Normale (Welt) und Richtung Hof → Kamera.
 * Voll sichtbar ab d ≥ 0,35, unsichtbar bei d ≤ 0 (die leuchtende Seite zeigt weg).
 */
export function haloFacing(d: number): number {
  const u = Math.max(0, Math.min(1, d / 0.35))
  return u * u * (3 - 2 * u)
}

/** Helligkeitsverlauf eines Leucht-Hofs über den Radius r ∈ [0, 1] (Clients erzeugen daraus eine Textur). */
export function haloFalloff(r: number): number {
  return Math.pow(Math.max(0, 1 - r), 2.5)
}

// ---------------------------------------------------------------- Prüfung

type Err = (path: string, msg: string) => void

/* eslint-disable @typescript-eslint/no-explicit-any -- die Prüfung nimmt beliebiges JSON entgegen */

/**
 * Prüft ein v2-Modell. `images` optional: { texture: {width, height}, glow: {width, height} } in echten
 * Pixeln, um die Bildmaße gegen die Angaben zu prüfen. Liefert { ok, errors, warnings, stats }.
 */
export function validateModel(m: any, images: { texture?: ImageSize | null, glow?: ImageSize | null } | null = null): ValidationResult {
  const errors: string[] = []
  const warnings: string[] = []
  const err: Err = (p, msg) => errors.push(`${p}: ${msg}`)
  if (!m || typeof m !== 'object' || Array.isArray(m)) return { ok: false, errors: ['Modell ist kein Objekt'], warnings, stats: {} }
  if (m.format !== FORMAT) err('format', `muss ${FORMAT} sein`)
  if (!ID.test(m.id ?? '')) err('id', 'a–z, 0–9, _ (Anfang Buchstabe), max. 40')
  if (typeof m.name !== 'string' || !m.name.trim() || m.name.length > 60) err('name', '1–60 Zeichen')
  if (!(SLOTS as readonly string[]).includes(m.slot)) err('slot', `eins von ${SLOTS.join(', ')}`)
  if (!(ATTACH as readonly string[]).includes(m.attach)) err('attach', `eins von ${ATTACH.join(', ')}`)

  // Textur
  const tex = m.texture ?? {}
  const texOk = checkImage('texture', tex, err, true)
  if (m.glow != null) {
    checkImage('glow', m.glow, err, false)
    if (m.glow.blend != null && m.glow.blend !== 'additive') err('glow.blend', 'nur additive')
  }
  if (texOk) {
    const fh = tex.height * tex.scale
    if (fh * (tex.frames ?? 1) > LIMITS.stripEdge) err('texture', `Streifen höher als ${LIMITS.stripEdge} px`)
    if (m.glow && fh * (m.glow.frames ?? 1) > LIMITS.stripEdge) err('glow', `Streifen höher als ${LIMITS.stripEdge} px (weniger Frames oder breiteres Blatt)`)
  }
  if (images?.texture && texOk) {
    const w = tex.width * tex.scale
    const h = tex.height * tex.scale * (tex.frames ?? 1)
    if (images.texture.width !== w || images.texture.height !== h) err('texture', `Bild ist ${images.texture.width}×${images.texture.height}, erwartet ${w}×${h}`)
  }
  if (images?.glow && m.glow) {
    const w = tex.width * tex.scale
    const h = tex.height * tex.scale * (m.glow.frames ?? 1)
    if (images.glow.width !== w || images.glow.height !== h) err('glow', `Bild ist ${images.glow.width}×${images.glow.height}, erwartet ${w}×${h}`)
  }

  // Knochen
  const bones = new Map<string, any>()
  if (!Array.isArray(m.bones) || m.bones.length < 1) err('bones', 'mindestens ein Knochen')
  else if (m.bones.length > LIMITS.bones) err('bones', `höchstens ${LIMITS.bones}`)
  for (const [i, b] of (Array.isArray(m.bones) ? (m.bones as any[]) : []).entries()) {
    const p = `bones[${i}]`
    if (!ID.test(b?.id ?? '')) {
      err(p, 'ungültige id')
      continue
    }
    if (bones.has(b.id)) err(p, `id ${b.id} doppelt`)
    if (b.parent != null && !bones.has(b.parent)) err(p, `Eltern-Knochen ${b.parent} muss vorher stehen`)
    if (!isVec(b.pivot) || b.pivot.some((v: number) => Math.abs(v) > LIMITS.coord)) err(p, 'pivot [x,y,z] fehlt oder zu groß')
    if (b.rotation != null && (!isVec(b.rotation) || b.rotation.some((v: number) => Math.abs(v) > 360))) err(p, 'rotation [x,y,z] in Grad (±360)')
    bones.set(b.id, b)
  }

  // Würfel
  const scale: number = tex.scale ?? 1
  const tw: number = tex.width ?? 0
  const th: number = tex.height ?? 0
  let faceCount = 0
  if (!Array.isArray(m.cubes) || m.cubes.length < 1) err('cubes', 'mindestens ein Würfel')
  else if (m.cubes.length > LIMITS.cubes) err('cubes', `höchstens ${LIMITS.cubes} (hat ${m.cubes.length})`)
  const cubeIds = new Set<string>()
  for (const [i, c] of (Array.isArray(m.cubes) ? (m.cubes as any[]) : []).entries()) {
    const p = `cubes[${i}]${c?.id ? ` (${c.id})` : ''}`
    if (c?.id != null) {
      if (!ID.test(c.id)) err(p, 'ungültige id')
      else if (cubeIds.has(c.id)) err(p, 'id doppelt')
      cubeIds.add(c.id)
    }
    if (!bones.has(c?.bone)) err(p, `unbekannter Knochen ${c?.bone}`)
    if (!isVec(c?.from) || !isVec(c?.to)) {
      err(p, 'from/to [x,y,z] fehlen')
      continue
    }
    for (let a = 0; a < 3; a++) {
      const size = c.to[a] - c.from[a]
      if (size < 0 || size > LIMITS.size) err(p, `Kante ${'xyz'[a]} muss 0…${LIMITS.size} sein`)
      if (Math.abs(c.from[a]) > LIMITS.coord || Math.abs(c.to[a]) > LIMITS.coord) err(p, 'außerhalb ±48')
      if (!onGrid(c.from[a], GRID) || !onGrid(c.to[a], GRID)) err(p, `Koordinaten im ${GRID}er-Raster`)
    }
    const flat = [0, 1, 2].filter((a) => c.to[a] === c.from[a])
    if (flat.length > 1) err(p, 'höchstens eine Achse darf die Dicke 0 haben')
    if (c.inflate != null && (!isNum(c.inflate) || Math.abs(c.inflate) > LIMITS.inflate)) err(p, 'inflate ±1')
    if (c.material != null && !(MATERIALS as readonly string[]).includes(c.material)) err(p, `material eins von ${MATERIALS.join(', ')}`)
    if (!c.faces || typeof c.faces !== 'object') {
      err(p, 'faces fehlt')
      continue
    }
    for (const key of Object.keys(c.faces)) if (!(FACES as readonly string[]).includes(key)) err(p, `unbekannte Fläche ${key}`)
    for (const face of FACES) {
      const f = c.faces[face]
      if (f == null) continue
      const fp = `${p}.faces.${face}`
      const [fw, fh] = faceSize(c.from, c.to, face)
      if (fw === 0 || fh === 0) {
        err(fp, 'Fläche hat keine Ausdehnung – null setzen')
        continue
      }
      if (flat.length === 1) {
        const axis = flat[0]!
        const pair = ([['west', 'east'], ['down', 'up'], ['north', 'south']] as const)[axis]!
        if (face === pair[0] && c.faces[pair[1]] != null) err(fp, `flacher Würfel: nur ${pair[1]} angeben (wird beidseitig gezeichnet)`)
      }
      if (!Array.isArray(f.uv) || f.uv.length !== 4 || !f.uv.every(isNum)) {
        err(fp, 'uv [u0,v0,u1,v1] fehlt')
        continue
      }
      const [u0, v0, u1, v1] = f.uv as [number, number, number, number]
      if (Math.min(u0, u1) < 0 || Math.max(u0, u1) > tw || Math.min(v0, v1) < 0 || Math.max(v0, v1) > th) err(fp, 'uv außerhalb der Textur')
      if (!(f.uv as number[]).every((v) => onGrid(v * scale, 1))) warnings.push(`${fp}: uv liegt nicht auf dem Texel-Raster (Faktor ${scale})`)
      if (u0 === u1 || v0 === v1) err(fp, 'uv-Rechteck ist leer')
      if (f.rotation != null && ![0, 90, 180, 270].includes(f.rotation)) err(fp, 'rotation 0/90/180/270')
      if (f.material != null && !(MATERIALS as readonly string[]).includes(f.material)) err(fp, `material eins von ${MATERIALS.join(', ')}`)
      faceCount++
    }
  }

  // Animationen
  const anims = m.animations ?? []
  if (!Array.isArray(anims)) err('animations', 'Liste erwartet')
  else if (anims.length > LIMITS.animations) err('animations', `höchstens ${LIMITS.animations}`)
  for (const [i, a] of (Array.isArray(anims) ? (anims as any[]) : []).entries()) {
    const p = `animations[${i}]`
    if (!ID.test(a?.id ?? '')) err(p, 'ungültige id')
    if (!(DRIVERS as readonly string[]).includes(a?.driver ?? 'idle')) err(p, `driver eins von ${DRIVERS.join(', ')}`)
    if (!Number.isInteger(a?.lengthMs) || a.lengthMs < 50 || a.lengthMs > LIMITS.lengthMs) err(p, `lengthMs 50…${LIMITS.lengthMs}`)
    if (a?.offsetMs != null && !Number.isInteger(a.offsetMs)) err(p, 'offsetMs ganzzahlig')
    if (!Array.isArray(a?.tracks) || !a.tracks.length || a.tracks.length > LIMITS.tracks) {
      err(p, `1–${LIMITS.tracks} tracks`)
      continue
    }
    for (const [j, tr] of (a.tracks as any[]).entries()) {
      const tp = `${p}.tracks[${j}]`
      if (!bones.has(tr?.bone)) err(tp, `unbekannter Knochen ${tr?.bone}`)
      if (!(CHANNELS as readonly string[]).includes(tr?.channel)) err(tp, `channel eins von ${CHANNELS.join(', ')}`)
      if (tr?.interpolation != null && !(INTERPOLATIONS as readonly string[]).includes(tr.interpolation)) err(tp, `interpolation eins von ${INTERPOLATIONS.join(', ')}`)
      if (!Array.isArray(tr?.keys) || !tr.keys.length || tr.keys.length > LIMITS.keys) {
        err(tp, `1–${LIMITS.keys} keys`)
        continue
      }
      let last = -1
      for (const [k, key] of (tr.keys as any[]).entries()) {
        if (!Number.isInteger(key?.t) || key.t < 0 || key.t > a.lengthMs || key.t <= last) err(`${tp}.keys[${k}]`, 't aufsteigend in 0…lengthMs')
        last = key?.t ?? last
        if (!isVec(key?.v)) err(`${tp}.keys[${k}]`, 'v [x,y,z] fehlt')
        if (key?.interpolation != null && !(INTERPOLATIONS as readonly string[]).includes(key.interpolation)) err(`${tp}.keys[${k}]`, 'unbekannte interpolation')
        if (tr.channel === 'scale' && isVec(key?.v) && key.v.some((s: number) => s <= 0 || s > 4)) err(`${tp}.keys[${k}]`, 'scale 0…4')
      }
    }
  }

  // Leucht-Höfe
  const halos = m.halos ?? []
  if (!Array.isArray(halos) || halos.length > LIMITS.halos) err('halos', `höchstens ${LIMITS.halos}`)
  for (const [i, h] of (Array.isArray(halos) ? (halos as any[]) : []).entries()) {
    const p = `halos[${i}]`
    if (!bones.has(h?.bone)) err(p, `unbekannter Knochen ${h?.bone}`)
    if (!isVec(h?.pos)) err(p, 'pos [x,y,z] fehlt')
    if (!isNum(h?.size) || h.size <= 0 || h.size > 32) err(p, 'size 0…32')
    if (!COLOR.test(h?.color ?? '')) err(p, 'color #rrggbb')
    if (h?.intensity != null && (!isNum(h.intensity) || h.intensity < 0 || h.intensity > 2)) err(p, 'intensity 0…2')
    if (h?.normal != null && (!isVec(h.normal) || Math.hypot(...h.normal) < 1e-6)) err(p, 'normal [x,y,z] ≠ 0')
    if (h?.pulse != null) {
      const q = h.pulse
      if (!Number.isInteger(q.periodMs) || q.periodMs < 100 || q.periodMs > LIMITS.lengthMs) err(p, 'pulse.periodMs 100…60000')
      if (!isNum(q.min) || !isNum(q.max) || q.min < 0 || q.max > 2 || q.min > q.max) err(p, 'pulse.min ≤ max, 0…2')
      if (q.phaseMs != null && !Number.isInteger(q.phaseMs)) err(p, 'pulse.phaseMs ganzzahlig')
    }
  }

  const stats = {
    cubes: Array.isArray(m.cubes) ? m.cubes.length : 0,
    faces: faceCount,
    bones: Array.isArray(m.bones) ? m.bones.length : 0,
    animations: Array.isArray(anims) ? anims.length : 0,
    halos: Array.isArray(halos) ? halos.length : 0,
    texture: texOk ? `${tw * scale}×${th * scale}${tex.frames > 1 ? ` ×${tex.frames}` : ''}` : '–',
    glowFrames: m.glow?.frames ?? 0,
  }
  return { ok: errors.length === 0, errors, warnings, stats }
}

function checkImage(p: string, t: any, err: Err, main: boolean): boolean {
  let ok = true
  if (!FILE.test(t.file ?? '')) {
    err(`${p}.file`, 'Dateiname *.png')
    ok = false
  }
  if (main) {
    if (!(LIMITS.scale as readonly number[]).includes(t.scale)) {
      err(`${p}.scale`, `eins von ${LIMITS.scale.join(', ')}`)
      ok = false
    }
    for (const k of ['width', 'height']) {
      if (!Number.isInteger(t[k]) || t[k] < 8 || t[k] % 8 !== 0) {
        err(`${p}.${k}`, 'Vielfaches von 8')
        ok = false
      }
    }
    if (ok && (t.width * t.scale > LIMITS.textureEdge || t.height * t.scale > LIMITS.textureEdge)) {
      err(p, `Kante höchstens ${LIMITS.textureEdge} px`)
      ok = false
    }
  }
  const frames = t.frames ?? 1
  if (!Number.isInteger(frames) || frames < 1 || frames > LIMITS.frames) {
    err(`${p}.frames`, `1–${LIMITS.frames}`)
    ok = false
  }
  if (frames > 1 && (!Number.isInteger(t.frameTimeMs) || t.frameTimeMs < 16 || t.frameTimeMs > 10000)) {
    err(`${p}.frameTimeMs`, '16–10000')
    ok = false
  }
  return ok
}

/* eslint-enable @typescript-eslint/no-explicit-any */

/** Streifen-Höhe prüfen (braucht texture-Maße). */
export function stripOk(m: Pick<CosmeticModel, 'texture' | 'glow'>): boolean {
  const t = m.texture
  const h = t.height * t.scale
  return h * (t.frames ?? 1) <= LIMITS.stripEdge && (!m.glow || h * (m.glow.frames ?? 1) <= LIMITS.stripEdge)
}

/**
 * Prüft und liefert das Modell typisiert zurück – wirft bei Fehlern (Meldungen zusammengefasst).
 * Für Clients, die ein Modell aus dem Netz laden.
 */
export function parseModel(json: unknown, images: { texture?: ImageSize | null, glow?: ImageSize | null } | null = null): CosmeticModel {
  const r = validateModel(json, images)
  if (!r.ok) throw new Error(`Ungültiges Kosmetik-Modell: ${r.errors.slice(0, 5).join('; ')}${r.errors.length > 5 ? ` (+${r.errors.length - 5})` : ''}`)
  return json as CosmeticModel
}
