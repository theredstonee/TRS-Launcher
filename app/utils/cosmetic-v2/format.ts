// TRS Kosmetik-Format v2: Typen, Prüfung und Mathematik – Port der Studio-Referenz
// (`trs-studio/public/cosmetic-format.mjs`, Beschreibung `cosmetic-format.md`). Bewusst
// ohne Abhängigkeiten (auch ohne three.js), damit es sich ohne WebGL testen lässt.
// Achsen: +x = links vom Spieler, +y = oben, +z = vorne (wie three.js/skinview3d).

export const FORMAT = 2

export type Vec3 = [number, number, number]
/** 4×4-Matrix spaltenweise (wie three.js `Matrix4.elements`). */
export type Mat4 = number[]

export const FACES = ['north', 'south', 'east', 'west', 'up', 'down'] as const
export type Face = (typeof FACES)[number]
export const MATERIALS = ['cutout', 'emissive', 'translucent'] as const
export type Material = (typeof MATERIALS)[number]
export const CHANNELS = ['rotation', 'position', 'scale'] as const
export type Channel = (typeof CHANNELS)[number]
export const INTERPOLATIONS = ['linear', 'smooth', 'step'] as const
export type Interpolation = (typeof INTERPOLATIONS)[number]
export const DRIVERS = ['idle', 'walk', 'sneak', 'jump', 'air'] as const
export type Driver = (typeof DRIVERS)[number]

export const FACE_NORMAL: Record<Face, Vec3> = {
  north: [0, 0, -1],
  south: [0, 0, 1],
  east: [1, 0, 0],
  west: [-1, 0, 0],
  up: [0, 1, 0],
  down: [0, -1, 0],
}

export const LIMITS = {
  cubes: 64,
  bones: 32,
  textureEdge: 1024,
  stripEdge: 4096,
  frames: 16,
  scale: [1, 2, 4, 8, 16],
  animations: 8,
  tracks: 32,
  keys: 64,
  halos: 16,
  coord: 48,
  size: 32,
  inflate: 1,
  lengthMs: 60000,
} as const

export interface CosmeticFace {
  uv: [number, number, number, number]
  rotation?: 0 | 90 | 180 | 270
  material?: Material
}

export interface CosmeticBone {
  id: string
  parent?: string | null
  pivot: Vec3
  rotation?: Vec3
}

export interface CosmeticCube {
  id?: string
  bone: string
  from: Vec3
  to: Vec3
  inflate?: number
  material?: Material
  faces: Partial<Record<Face, CosmeticFace | null>>
}

export interface CosmeticKey {
  t: number
  v: Vec3
  interpolation?: Interpolation
}

export interface CosmeticTrack {
  bone: string
  channel: Channel
  interpolation?: Interpolation
  keys: CosmeticKey[]
}

export interface CosmeticAnimation {
  id: string
  driver?: Driver
  lengthMs: number
  loop?: boolean
  offsetMs?: number
  tracks: CosmeticTrack[]
}

export interface CosmeticPulse {
  periodMs: number
  min: number
  max: number
  phaseMs?: number
}

export interface CosmeticHalo {
  bone: string
  pos: Vec3
  size: number
  color: string
  intensity?: number
  normal?: Vec3
  pulse?: CosmeticPulse
}

export interface CosmeticStrip {
  file: string
  frames?: number
  frameTimeMs?: number
}

export interface CosmeticModel {
  format: 2
  id: string
  name: string
  slot: 'hat'
  attach: 'head'
  texture: CosmeticStrip & { width: number; height: number; scale: number }
  glow?: (CosmeticStrip & { blend?: 'additive' }) | null
  bones: CosmeticBone[]
  cubes: CosmeticCube[]
  animations?: CosmeticAnimation[]
  halos?: CosmeticHalo[]
  meta?: unknown
}

/** Kopf-Kosmetik für die 3D-Vorschau (`SkinViewer`): Modell + Texturen als PNG-Data-URLs. */
export interface ViewerCosmetic {
  model: Record<string, unknown>
  texture: string
  glow: string | null
}

export interface BonePose {
  position: Vec3
  rotation: Vec3
  scale: Vec3
}

const ID = /^[a-z][a-z0-9_]{0,39}$/
const FILE = /^[a-z0-9][a-z0-9_.-]{0,63}\.png$/
const COLOR = /^#[0-9a-f]{6}$/
const GRID = 0.125

const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v)
const isVec = (v: unknown): v is Vec3 => Array.isArray(v) && v.length === 3 && v.every(isNum)
const onGrid = (v: number, step: number) => Math.abs(v / step - Math.round(v / step)) < 1e-6
const isObject = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)

// --- Mathematik ------------------------------------------------------------------------------

export const deg = (d: number) => (d * Math.PI) / 180

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

/** Drehung in Grad, Reihenfolge ZYX: erst um x, dann um y, dann um z (R = Rz · Ry · Rx, wie ModelPart). */
export function rotationZYX(rx: number, ry: number, rz: number): Mat4 {
  const a = deg(rx)
  const b = deg(ry)
  const c = deg(rz)
  const [ca, sa, cb, sb, cc, sc] = [Math.cos(a), Math.sin(a), Math.cos(b), Math.sin(b), Math.cos(c), Math.sin(c)]
  return [
    cc * cb, sc * cb, -sb, 0,
    cc * sb * sa - sc * ca, sc * sb * sa + cc * ca, cb * sa, 0,
    cc * sb * ca + sc * sa, sc * sb * ca - cc * sa, cb * ca, 0,
    0, 0, 0, 1,
  ]
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

// --- Geometrie -------------------------------------------------------------------------------

/** Ecken einer Fläche [oben-links, oben-rechts, unten-rechts, unten-links], von außen gesehen. */
export function faceCorners(from: Vec3, to: Vec3, face: Face, inflate = 0): [Vec3, Vec3, Vec3, Vec3] {
  const [x0, y0, z0] = from.map((v) => v - inflate) as Vec3
  const [x1, y1, z1] = to.map((v) => v + inflate) as Vec3
  switch (face) {
    case 'south':
      return [[x0, y1, z1], [x1, y1, z1], [x1, y0, z1], [x0, y0, z1]]
    case 'north':
      return [[x1, y1, z0], [x0, y1, z0], [x0, y0, z0], [x1, y0, z0]]
    case 'east':
      return [[x1, y1, z1], [x1, y1, z0], [x1, y0, z0], [x1, y0, z1]]
    case 'west':
      return [[x0, y1, z0], [x0, y1, z1], [x0, y0, z1], [x0, y0, z0]]
    case 'up':
      return [[x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]]
    case 'down':
      return [[x0, y0, z1], [x1, y0, z1], [x1, y0, z0], [x0, y0, z0]]
  }
}

/** Größe einer Fläche in Einheiten [Breite, Höhe] (ohne inflate). */
export function faceSize(from: Vec3, to: Vec3, face: Face): [number, number] {
  const s = [to[0] - from[0], to[1] - from[1], to[2] - from[2]] as const
  if (face === 'north' || face === 'south') return [s[0], s[1]]
  if (face === 'east' || face === 'west') return [s[2], s[1]]
  return [s[0], s[2]]
}

/** UV je Ecke (Reihenfolge wie `faceCorners`); `rotation` dreht das Bild im Uhrzeigersinn. */
export function faceUvCorners(uv: [number, number, number, number], rotation = 0): [number, number][] {
  const [u0, v0, u1, v1] = uv
  const img: [number, number][] = [[u0, v0], [u1, v0], [u1, v1], [u0, v1]]
  const q = (((rotation / 90) % 4) + 4) % 4
  return [0, 1, 2, 3].map((i) => img[(i - q + 4) % 4]!)
}

// --- Knochen und Animation --------------------------------------------------------------------

/** Lokale Matrix eines Knochens: T(pivot + pos) · R(rest + rot) · S(scale) · T(−pivot). */
export function boneLocal(bone: CosmeticBone, pose?: BonePose | null): Mat4 {
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
export function boneMatrices(model: Pick<CosmeticModel, 'bones'>, poses: Map<string, BonePose> | null = null): Map<string, Mat4> {
  const out = new Map<string, Mat4>()
  for (const b of model.bones) {
    const local = boneLocal(b, poses?.get(b.id))
    out.set(b.id, b.parent ? multiply(out.get(b.parent)!, local) : local)
  }
  return out
}

const smooth = (u: number) => u * u * (3 - 2 * u)

export function sampleTrack(track: CosmeticTrack, t: number): Vec3 {
  const k = track.keys
  const first = k[0]!
  const last = k[k.length - 1]!
  if (t <= first.t) return first.v
  if (t >= last.t) return last.v
  let i = 0
  while (i < k.length - 2 && t >= k[i + 1]!.t) i++
  const a = k[i]!
  const b = k[i + 1]!
  const mode = b.interpolation ?? track.interpolation ?? 'linear'
  if (mode === 'step') return a.v
  let u = (t - a.t) / (b.t - a.t)
  if (mode === 'smooth') u = smooth(u)
  return [0, 1, 2].map((j) => a.v[j]! + (b.v[j]! - a.v[j]!) * u) as Vec3
}

/**
 * Pose aller Knochen zur Wanduhr `timeMs` (Date.now()). `active` = aktive Treiber (Standard nur idle).
 * Drehung/Position werden addiert, Skalierung multipliziert.
 */
export function samplePoses(model: Pick<CosmeticModel, 'animations'>, timeMs: number, active: readonly string[] = ['idle']): Map<string, BonePose> {
  const poses = new Map<string, BonePose>()
  for (const anim of model.animations ?? []) {
    if (!active.includes(anim.driver ?? 'idle')) continue
    const len = anim.lengthMs
    let t = timeMs + (anim.offsetMs ?? 0)
    t = anim.loop === false ? Math.min(Math.max(t, 0), len) : ((t % len) + len) % len
    for (const tr of anim.tracks) {
      const v = sampleTrack(tr, t)
      const p = poses.get(tr.bone) ?? { position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1] }
      if (tr.channel === 'scale') p.scale = p.scale.map((s, j) => s * v[j]!) as Vec3
      else p[tr.channel] = p[tr.channel].map((s, j) => s + v[j]!) as Vec3
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
export function haloIntensity(halo: Pick<CosmeticHalo, 'intensity' | 'pulse'>, timeMs: number): number {
  const base = halo.intensity ?? 1
  const p = halo.pulse
  if (!p) return base
  const ph = ((timeMs + (p.phaseMs ?? 0)) % p.periodMs) / p.periodMs
  return base * (p.min + (p.max - p.min) * (0.5 - 0.5 * Math.cos(2 * Math.PI * ph)))
}

/** Ausblenden nach Blickrichtung: voll sichtbar ab d ≥ 0,35, unsichtbar bei d ≤ 0. */
export function haloFacing(d: number): number {
  const u = Math.max(0, Math.min(1, d / 0.35))
  return u * u * (3 - 2 * u)
}

/** Helligkeitsverlauf eines Leucht-Hofs über den Radius r ∈ [0, 1]. */
export const haloFalloff = (r: number) => Math.pow(Math.max(0, 1 - r), 2.5)

/** Höchster Punkt des Modells (y, Ruhepose) – für die Kamera. */
export function modelTop(model: Pick<CosmeticModel, 'cubes'>): number {
  return Math.max(8, ...model.cubes.map((c) => c.to[1]))
}

// --- Prüfung -----------------------------------------------------------------------------------

export interface ValidationResult {
  ok: boolean
  errors: string[]
  warnings: string[]
}

type ImageSize = { width: number; height: number }

/**
 * Prüft ein v2-Modell (wie `validateModel` der Studio-Referenz). `images` optional: echte
 * Bildmaße der Textur/Glow-PNGs, um sie gegen die Angaben zu prüfen.
 */
export function validateModel(m: unknown, images: { texture?: ImageSize | null; glow?: ImageSize | null } | null = null): ValidationResult {
  const errors: string[] = []
  const warnings: string[] = []
  const err = (p: string, msg: string) => errors.push(`${p}: ${msg}`)
  if (!isObject(m)) return { ok: false, errors: ['Modell ist kein Objekt'], warnings }
  if (m.format !== FORMAT) err('format', `muss ${FORMAT} sein`)
  if (!ID.test(String(m.id ?? ''))) err('id', 'a–z, 0–9, _ (Anfang Buchstabe), max. 40')
  if (typeof m.name !== 'string' || !m.name.trim() || m.name.length > 60) err('name', '1–60 Zeichen')
  if (m.slot !== 'hat') err('slot', 'hat')
  if (m.attach !== 'head') err('attach', 'head')

  const tex = (isObject(m.texture) ? m.texture : {}) as Record<string, number | string | undefined>
  const texOk = checkImage('texture', tex, err, true)
  const glow = m.glow == null ? null : isObject(m.glow) ? (m.glow as Record<string, number | string | undefined>) : {}
  if (glow) {
    checkImage('glow', glow, err, false)
    if (glow.blend != null && glow.blend !== 'additive') err('glow.blend', 'nur additive')
  }
  const tw = Number(tex.width ?? 0)
  const th = Number(tex.height ?? 0)
  const scale = Number(tex.scale ?? 1)
  const texFrames = Number(tex.frames ?? 1)
  if (texOk) {
    const fh = th * scale
    if (fh * texFrames > LIMITS.stripEdge) err('texture', `Streifen höher als ${LIMITS.stripEdge} px`)
    if (glow && fh * Number(glow.frames ?? 1) > LIMITS.stripEdge) err('glow', `Streifen höher als ${LIMITS.stripEdge} px`)
    if (images?.texture) {
      const w = tw * scale
      const h = th * scale * texFrames
      if (images.texture.width !== w || images.texture.height !== h) err('texture', `Bild ist ${images.texture.width}×${images.texture.height}, erwartet ${w}×${h}`)
    }
    if (images?.glow && glow) {
      const w = tw * scale
      const h = th * scale * Number(glow.frames ?? 1)
      if (images.glow.width !== w || images.glow.height !== h) err('glow', `Bild ist ${images.glow.width}×${images.glow.height}, erwartet ${w}×${h}`)
    }
  }

  // Knochen
  const bones = new Set<string>()
  const boneList = Array.isArray(m.bones) ? (m.bones as unknown[]) : []
  if (!boneList.length) err('bones', 'mindestens ein Knochen')
  else if (boneList.length > LIMITS.bones) err('bones', `höchstens ${LIMITS.bones}`)
  boneList.forEach((raw, i) => {
    const p = `bones[${i}]`
    const b = isObject(raw) ? raw : {}
    const id = String(b.id ?? '')
    if (!ID.test(id)) {
      err(p, 'ungültige id')
      return
    }
    if (bones.has(id)) err(p, `id ${id} doppelt`)
    if (b.parent != null && !bones.has(String(b.parent))) err(p, `Eltern-Knochen ${String(b.parent)} muss vorher stehen`)
    if (!isVec(b.pivot) || b.pivot.some((v) => Math.abs(v) > LIMITS.coord)) err(p, 'pivot [x,y,z] fehlt oder zu groß')
    if (b.rotation != null && (!isVec(b.rotation) || b.rotation.some((v) => Math.abs(v) > 360))) err(p, 'rotation [x,y,z] in Grad (±360)')
    bones.add(id)
  })

  // Würfel
  const cubeList = Array.isArray(m.cubes) ? (m.cubes as unknown[]) : []
  if (!cubeList.length) err('cubes', 'mindestens ein Würfel')
  else if (cubeList.length > LIMITS.cubes) err('cubes', `höchstens ${LIMITS.cubes}`)
  const cubeIds = new Set<string>()
  cubeList.forEach((raw, i) => {
    const c = isObject(raw) ? raw : {}
    const p = `cubes[${i}]${c.id ? ` (${String(c.id)})` : ''}`
    if (c.id != null) {
      if (!ID.test(String(c.id))) err(p, 'ungültige id')
      else if (cubeIds.has(String(c.id))) err(p, 'id doppelt')
      cubeIds.add(String(c.id))
    }
    if (!bones.has(String(c.bone))) err(p, `unbekannter Knochen ${String(c.bone)}`)
    if (!isVec(c.from) || !isVec(c.to)) {
      err(p, 'from/to [x,y,z] fehlen')
      return
    }
    const from = c.from
    const to = c.to
    for (let a = 0; a < 3; a++) {
      const size = to[a]! - from[a]!
      if (size < 0 || size > LIMITS.size) err(p, `Kante ${'xyz'[a]} muss 0…${LIMITS.size} sein`)
      if (Math.abs(from[a]!) > LIMITS.coord || Math.abs(to[a]!) > LIMITS.coord) err(p, 'außerhalb ±48')
      if (!onGrid(from[a]!, GRID) || !onGrid(to[a]!, GRID)) err(p, `Koordinaten im ${GRID}er-Raster`)
    }
    const flat = [0, 1, 2].filter((a) => to[a] === from[a])
    if (flat.length > 1) err(p, 'höchstens eine Achse darf die Dicke 0 haben')
    if (c.inflate != null && (!isNum(c.inflate) || Math.abs(c.inflate) > LIMITS.inflate)) err(p, 'inflate ±1')
    if (c.material != null && !(MATERIALS as readonly unknown[]).includes(c.material)) err(p, `material eins von ${MATERIALS.join(', ')}`)
    if (!isObject(c.faces)) {
      err(p, 'faces fehlt')
      return
    }
    const faces = c.faces
    for (const key of Object.keys(faces)) if (!(FACES as readonly string[]).includes(key)) err(p, `unbekannte Fläche ${key}`)
    for (const face of FACES) {
      const f = faces[face]
      if (f == null) continue
      const fp = `${p}.faces.${face}`
      if (!isObject(f)) {
        err(fp, 'Objekt erwartet')
        continue
      }
      const [fw, fh] = faceSize(from, to, face)
      if (fw === 0 || fh === 0) {
        err(fp, 'Fläche hat keine Ausdehnung – null setzen')
        continue
      }
      if (flat.length === 1) {
        const pair = ([['west', 'east'], ['down', 'up'], ['north', 'south']] as const)[flat[0]!]!
        if (face === pair[0] && faces[pair[1]] != null) err(fp, `flacher Würfel: nur ${pair[1]} angeben`)
      }
      const uv = f.uv
      if (!Array.isArray(uv) || uv.length !== 4 || !uv.every(isNum)) {
        err(fp, 'uv [u0,v0,u1,v1] fehlt')
        continue
      }
      const [u0, v0, u1, v1] = uv as number[]
      if (Math.min(u0!, u1!) < 0 || Math.max(u0!, u1!) > tw || Math.min(v0!, v1!) < 0 || Math.max(v0!, v1!) > th) err(fp, 'uv außerhalb der Textur')
      if (!(uv as number[]).every((v) => onGrid(v * scale, 1))) warnings.push(`${fp}: uv liegt nicht auf dem Texel-Raster`)
      if (u0 === u1 || v0 === v1) err(fp, 'uv-Rechteck ist leer')
      if (f.rotation != null && ![0, 90, 180, 270].includes(f.rotation as number)) err(fp, 'rotation 0/90/180/270')
      if (f.material != null && !(MATERIALS as readonly unknown[]).includes(f.material)) err(fp, `material eins von ${MATERIALS.join(', ')}`)
    }
  })

  // Animationen
  const anims = m.animations ?? []
  if (!Array.isArray(anims)) err('animations', 'Liste erwartet')
  else if (anims.length > LIMITS.animations) err('animations', `höchstens ${LIMITS.animations}`)
  ;(Array.isArray(anims) ? (anims as unknown[]) : []).forEach((raw, i) => {
    const a = isObject(raw) ? raw : {}
    const p = `animations[${i}]`
    if (!ID.test(String(a.id ?? ''))) err(p, 'ungültige id')
    if (!(DRIVERS as readonly unknown[]).includes(a.driver ?? 'idle')) err(p, `driver eins von ${DRIVERS.join(', ')}`)
    const len = a.lengthMs
    if (!Number.isInteger(len) || (len as number) < 50 || (len as number) > LIMITS.lengthMs) err(p, `lengthMs 50…${LIMITS.lengthMs}`)
    if (a.offsetMs != null && !Number.isInteger(a.offsetMs)) err(p, 'offsetMs ganzzahlig')
    if (!Array.isArray(a.tracks) || !a.tracks.length || a.tracks.length > LIMITS.tracks) {
      err(p, `1–${LIMITS.tracks} tracks`)
      return
    }
    ;(a.tracks as unknown[]).forEach((rawTrack, j) => {
      const tr = isObject(rawTrack) ? rawTrack : {}
      const tp = `${p}.tracks[${j}]`
      if (!bones.has(String(tr.bone))) err(tp, `unbekannter Knochen ${String(tr.bone)}`)
      if (!(CHANNELS as readonly unknown[]).includes(tr.channel)) err(tp, `channel eins von ${CHANNELS.join(', ')}`)
      if (tr.interpolation != null && !(INTERPOLATIONS as readonly unknown[]).includes(tr.interpolation)) err(tp, 'unbekannte interpolation')
      if (!Array.isArray(tr.keys) || !tr.keys.length || tr.keys.length > LIMITS.keys) {
        err(tp, `1–${LIMITS.keys} keys`)
        return
      }
      let last = -1
      ;(tr.keys as unknown[]).forEach((rawKey, k) => {
        const key = isObject(rawKey) ? rawKey : {}
        const kt = key.t as number
        if (!Number.isInteger(kt) || kt < 0 || kt > (len as number) || kt <= last) err(`${tp}.keys[${k}]`, 't aufsteigend in 0…lengthMs')
        last = Number.isInteger(kt) ? kt : last
        if (!isVec(key.v)) err(`${tp}.keys[${k}]`, 'v [x,y,z] fehlt')
        if (key.interpolation != null && !(INTERPOLATIONS as readonly unknown[]).includes(key.interpolation)) err(`${tp}.keys[${k}]`, 'unbekannte interpolation')
        if (tr.channel === 'scale' && isVec(key.v) && key.v.some((s) => s <= 0 || s > 4)) err(`${tp}.keys[${k}]`, 'scale 0…4')
      })
    })
  })

  // Leucht-Höfe
  const halos = m.halos ?? []
  if (!Array.isArray(halos) || halos.length > LIMITS.halos) err('halos', `höchstens ${LIMITS.halos}`)
  ;(Array.isArray(halos) ? (halos as unknown[]) : []).forEach((raw, i) => {
    const h = isObject(raw) ? raw : {}
    const p = `halos[${i}]`
    if (!bones.has(String(h.bone))) err(p, `unbekannter Knochen ${String(h.bone)}`)
    if (!isVec(h.pos)) err(p, 'pos [x,y,z] fehlt')
    if (!isNum(h.size) || h.size <= 0 || h.size > 32) err(p, 'size 0…32')
    if (!COLOR.test(String(h.color ?? ''))) err(p, 'color #rrggbb')
    if (h.intensity != null && (!isNum(h.intensity) || h.intensity < 0 || h.intensity > 2)) err(p, 'intensity 0…2')
    if (h.normal != null && (!isVec(h.normal) || Math.hypot(...h.normal) < 1e-6)) err(p, 'normal [x,y,z] ≠ 0')
    if (h.pulse != null) {
      const q = isObject(h.pulse) ? h.pulse : {}
      if (!Number.isInteger(q.periodMs) || (q.periodMs as number) < 100 || (q.periodMs as number) > LIMITS.lengthMs) err(p, 'pulse.periodMs 100…60000')
      if (!isNum(q.min) || !isNum(q.max) || q.min < 0 || q.max > 2 || q.min > q.max) err(p, 'pulse.min ≤ max, 0…2')
      if (q.phaseMs != null && !Number.isInteger(q.phaseMs)) err(p, 'pulse.phaseMs ganzzahlig')
    }
  })

  return { ok: errors.length === 0, errors, warnings }
}

function checkImage(p: string, t: Record<string, number | string | undefined>, err: (p: string, msg: string) => void, main: boolean): boolean {
  let ok = true
  if (!FILE.test(String(t.file ?? ''))) {
    err(`${p}.file`, 'Dateiname *.png')
    ok = false
  }
  if (main) {
    if (!(LIMITS.scale as readonly unknown[]).includes(t.scale)) {
      err(`${p}.scale`, `eins von ${LIMITS.scale.join(', ')}`)
      ok = false
    }
    for (const k of ['width', 'height'] as const) {
      const v = t[k]
      if (!Number.isInteger(v) || (v as number) < 8 || (v as number) % 8 !== 0) {
        err(`${p}.${k}`, 'Vielfaches von 8')
        ok = false
      }
    }
    if (ok && (Number(t.width) * Number(t.scale) > LIMITS.textureEdge || Number(t.height) * Number(t.scale) > LIMITS.textureEdge)) {
      err(p, `Kante höchstens ${LIMITS.textureEdge} px`)
      ok = false
    }
  }
  const frames = t.frames ?? 1
  if (!Number.isInteger(frames) || (frames as number) < 1 || (frames as number) > LIMITS.frames) {
    err(`${p}.frames`, `1–${LIMITS.frames}`)
    ok = false
  }
  if ((frames as number) > 1 && (!Number.isInteger(t.frameTimeMs) || (t.frameTimeMs as number) < 16 || (t.frameTimeMs as number) > 10000)) {
    err(`${p}.frameTimeMs`, '16–10000')
    ok = false
  }
  return ok
}
