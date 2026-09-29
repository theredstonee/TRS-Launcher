import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { describe, expect, it } from 'vitest'
import {
  FACES,
  FACE_NORMAL,
  boneMatrices,
  faceCorners,
  faceUvCorners,
  frameAt,
  haloFacing,
  haloFalloff,
  haloIntensity,
  modelTop,
  multiply,
  rotationZYX,
  samplePoses,
  transformPoint,
  validateModel,
  type CosmeticModel,
  type Vec3,
} from '../app/utils/cosmetic-v2/format'

const fixture = () => JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures', 'cosmetic-v2', 'trs_cap.json'), 'utf8')) as CosmeticModel

const close = (a: number[], b: number[], eps = 1e-9) => a.forEach((v, i) => expect(v).toBeCloseTo(b[i]!, -Math.log10(eps)))
const sub = (a: Vec3, b: Vec3): Vec3 => [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
const cross = (a: Vec3, b: Vec3): Vec3 => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]

function tiny(): CosmeticModel {
  return {
    format: 2,
    id: 'krone',
    name: 'Krone',
    slot: 'hat',
    attach: 'head',
    texture: { file: 'texture.png', width: 16, height: 8, scale: 2 },
    glow: { file: 'glow.png', frames: 4, frameTimeMs: 100, blend: 'additive' },
    bones: [
      { id: 'root', pivot: [0, 8, 0] },
      { id: 'tip', parent: 'root', pivot: [0, 10, 0], rotation: [0, 0, 90] },
    ],
    cubes: [{ id: 'band', bone: 'root', from: [-4, 8, -4], to: [4, 10, 4], faces: { south: { uv: [0, 0, 8, 2] } } }],
    animations: [
      {
        id: 'idle',
        lengthMs: 1000,
        tracks: [
          { bone: 'root', channel: 'rotation', interpolation: 'linear', keys: [{ t: 0, v: [0, 0, 0] }, { t: 500, v: [10, 0, 0] }, { t: 1000, v: [0, 0, 0] }] },
          { bone: 'tip', channel: 'scale', interpolation: 'step', keys: [{ t: 0, v: [1, 1, 1] }, { t: 500, v: [2, 2, 2] }] },
        ],
      },
    ],
    halos: [{ bone: 'tip', pos: [0, 11, 0], size: 4, color: '#ff5c2b', intensity: 0.8, pulse: { periodMs: 1000, min: 0.25, max: 1 } }],
  }
}

describe('Kosmetik v2 – Mathematik', () => {
  it('dreht in der Reihenfolge ZYX (erst x, dann y, dann z)', () => {
    const composed = multiply(rotationZYX(0, 0, 30), multiply(rotationZYX(0, 20, 0), rotationZYX(10, 0, 0)))
    close(rotationZYX(10, 20, 30), composed)
    // Rx(+90) kippt +y nach +z.
    close(transformPoint(rotationZYX(90, 0, 0), [0, 1, 0]), [0, 0, 1])
  })

  it('Flächen sind von außen gegen den Uhrzeigersinn (Normale zeigt nach außen)', () => {
    for (const face of FACES) {
      const [ol, , ur, ul] = faceCorners([-1, -2, -3], [4, 5, 6], face)
      const n = cross(sub(ul, ol), sub(ur, ol))
      const len = Math.hypot(...n)
      close(n.map((v) => v / len), FACE_NORMAL[face])
    }
    // inflate vergrößert in alle Richtungen
    expect(faceCorners([0, 0, 0], [1, 1, 1], 'south', 0.5)[0]).toEqual([-0.5, 1.5, 1.5])
  })

  it('dreht UVs im Uhrzeigersinn', () => {
    const uv: [number, number, number, number] = [0, 0, 2, 1]
    expect(faceUvCorners(uv)).toEqual([[0, 0], [2, 0], [2, 1], [0, 1]])
    expect(faceUvCorners(uv, 90)).toEqual([[0, 1], [0, 0], [2, 0], [2, 1]])
    expect(faceUvCorners(uv, 270)).toEqual(faceUvCorners(uv, -90))
  })

  it('Knochen erben die Welt-Matrix der Eltern', () => {
    const m = tiny()
    const world = boneMatrices(m)
    // tip dreht um z (+90°) um seinen Pivot [0, 10, 0]: +x wird zu +y.
    close(transformPoint(world.get('tip')!, [1, 10, 0]), [0, 11, 0])
    // Animation: bei t = 250 ms dreht root um 5° um x (linear), tip ist noch nicht skaliert (step).
    const poses = samplePoses(m, 250)
    close(poses.get('root')!.rotation, [5, 0, 0])
    expect(poses.get('tip')!.scale).toEqual([1, 1, 1])
    expect(samplePoses(m, 750).get('tip')!.scale).toEqual([2, 2, 2])
    // Schleife: Wanduhr läuft über die Länge hinaus
    close(samplePoses(m, 10_250).get('root')!.rotation, [5, 0, 0])
    // andere Treiber (walk …) laufen nicht
    expect(samplePoses(m, 250, []).size).toBe(0)
  })

  it('smooth = u²(3 − 2u)', () => {
    const m = tiny()
    m.animations![0]!.tracks[0]!.interpolation = 'smooth'
    close(samplePoses(m, 125).get('root')!.rotation, [10 * 0.25 * 0.25 * (3 - 0.5), 0, 0])
  })

  it('Streifen-Bild, Hof-Helligkeit und Blickrichtung', () => {
    expect(frameAt(0, 4, 100)).toBe(0)
    expect(frameAt(250, 4, 100)).toBe(2)
    expect(frameAt(450, 4, 100)).toBe(0)
    expect(frameAt(450, 1, 100)).toBe(0)
    const halo = tiny().halos![0]!
    expect(haloIntensity(halo, 0)).toBeCloseTo(0.8 * 0.25)
    expect(haloIntensity(halo, 500)).toBeCloseTo(0.8)
    expect(haloIntensity({ intensity: 0.5 }, 123)).toBe(0.5)
    expect(haloFacing(0)).toBe(0)
    expect(haloFacing(-1)).toBe(0)
    expect(haloFacing(0.35)).toBe(1)
    expect(haloFalloff(0)).toBe(1)
    expect(haloFalloff(1)).toBe(0)
    expect(modelTop(tiny())).toBe(10)
  })
})

describe('Kosmetik v2 – Prüfung', () => {
  it('nimmt freigegebene Studio-Modelle an (inkl. Bildmaße)', () => {
    const m = fixture()
    const res = validateModel(m, { texture: { width: 256, height: 256 }, glow: { width: 256, height: 256 * 12 } })
    expect(res.errors).toEqual([])
    expect(res.ok).toBe(true)
  })

  it('findet kaputte Modelle', () => {
    const bad = (edit: (m: CosmeticModel & Record<string, unknown>) => void, images?: Parameters<typeof validateModel>[1]) => {
      const m = tiny() as CosmeticModel & Record<string, unknown>
      edit(m)
      return validateModel(m, images ?? null)
    }
    expect(validateModel(null).ok).toBe(false)
    expect(bad(() => {}).ok).toBe(true)
    expect(bad((m) => (m.format = 1 as 2)).ok).toBe(false)
    expect(bad((m) => (m.bones[1]!.parent = 'nope')).ok).toBe(false)
    expect(bad((m) => (m.cubes[0]!.bone = 'nope')).ok).toBe(false)
    expect(bad((m) => (m.cubes[0]!.to = [4, 10, 40])).ok).toBe(false)
    expect(bad((m) => (m.cubes[0]!.faces.south!.uv = [0, 0, 20, 2])).ok).toBe(false)
    expect(bad((m) => (m.cubes = Array.from({ length: 65 }, () => tiny().cubes[0]!))).errors.join()).toContain('höchstens 64')
    expect(bad((m) => (m.texture.scale = 3)).ok).toBe(false)
    expect(bad((m) => (m.glow!.frames = 17)).ok).toBe(false)
    expect(bad((m) => (m.animations![0]!.tracks[0]!.keys[1]!.t = 0)).ok).toBe(false)
    expect(bad((m) => (m.halos![0]!.color = 'red')).ok).toBe(false)
    // Bildmaße müssen zu den Angaben passen (32×16, Glow 4 Frames).
    expect(bad(() => {}, { texture: { width: 32, height: 16 }, glow: { width: 32, height: 64 } }).ok).toBe(true)
    expect(bad(() => {}, { texture: { width: 64, height: 16 } }).ok).toBe(false)
    expect(bad(() => {}, { texture: { width: 32, height: 16 }, glow: { width: 32, height: 16 } }).ok).toBe(false)
  })
})

// Gegenprobe mit der Studio-Referenz (nur, wenn das Studio auf diesem Rechner liegt):
// gleiche Posen und Matrizen für alle freigegebenen Teile über eine Animationsschleife.
const studio = 'E:/ai/trs-studio'
const hasStudio = fs.existsSync(path.join(studio, 'public', 'cosmetic-format.mjs'))
describe.skipIf(!hasStudio)('Kosmetik v2 – gleich wie die Studio-Referenz', () => {
  it('Posen, Knochen-Matrizen und Höfe stimmen überein', async () => {
    const ref = await import(pathToFileURL(path.join(studio, 'public', 'cosmetic-format.mjs')).href)
    const dir = path.join(studio, 'exports', 'cosmetic-v2')
    const models = fs.readdirSync(dir).filter((f) => f.endsWith('.json'))
    expect(models.length).toBeGreaterThanOrEqual(6)
    for (const file of models) {
      const m = JSON.parse(fs.readFileSync(path.join(dir, file), 'utf8')) as CosmeticModel
      expect(validateModel(m).errors, file).toEqual(ref.validateModel(m).errors)
      for (const t of [0, 137, 1234, 4321, 99_999, 1_759_150_000_123]) {
        const ours = boneMatrices(m, samplePoses(m, t))
        const theirs = ref.boneMatrices(m, ref.samplePoses(m, t)) as Map<string, number[]>
        for (const [id, mat] of theirs) close(ours.get(id)!, mat, 1e-9)
        for (const h of m.halos ?? []) expect(haloIntensity(h, t)).toBeCloseTo(ref.haloIntensity(h, t), 12)
        expect(frameAt(t, m.glow?.frames, m.glow?.frameTimeMs)).toBe(ref.frameAt(t, m.glow?.frames, m.glow?.frameTimeMs))
      }
    }
  })
})
