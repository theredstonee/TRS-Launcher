import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import * as THREE from 'three'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  FACE_NORMAL,
  FACES,
  boneLocal,
  boneMatrices,
  eulerZYX,
  faceCorners,
  faceSize,
  faceUvCorners,
  frameAt,
  haloFacing,
  haloFalloff,
  haloIntensity,
  identity,
  invertAffine,
  multiply,
  parseModel,
  rotationZYX,
  sampleTrack,
  samplePoses,
  transformDir,
  transformPoint,
  validateModel,
  type CosmeticModel,
  type Mat4,
  type Vec3,
} from '../app/utils/cosmetic-v2/format'
import { applyDayNight, createCosmetic, frameHead } from '../app/utils/cosmetic-v2/view'

const V2 = join(__dirname, '..', 'assets', 'cosmetics', 'v2')
const IDS = ['trs_cap', 'lamp_helmet', 'top_hat', 'witch_hat', 'pumpkin_head', 'bat_buddy']
const model = (id: string): CosmeticModel => JSON.parse(readFileSync(join(V2, `${id}.json`), 'utf8'))
const pngSize = (file: string) => {
  const b = readFileSync(file)
  return { width: b.readUInt32BE(16), height: b.readUInt32BE(20) }
}

const close = (a: number[], b: number[], eps = 1e-9) => {
  expect(a.length).toBe(b.length)
  a.forEach((v, i) => expect(Math.abs(v - b[i]!)).toBeLessThan(eps))
}
const sub = (a: Vec3, b: Vec3): Vec3 => [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
const cross = (a: Vec3, b: Vec3): Vec3 => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
const norm = (a: Vec3): Vec3 => {
  const l = Math.hypot(...a)
  return [a[0] / l, a[1] / l, a[2] / l]
}

/** Ein kleines gültiges Modell zum Verändern. */
function tiny(): CosmeticModel {
  return {
    format: 2,
    id: 'tiny',
    name: 'Tiny',
    slot: 'hat',
    attach: 'head',
    texture: { file: 'tiny.png', width: 16, height: 16, scale: 8 },
    glow: { file: 'tiny-glow.png', frames: 4, frameTimeMs: 100, blend: 'additive' },
    bones: [
      { id: 'root', pivot: [0, 8, 0] },
      { id: 'top', parent: 'root', pivot: [0, 10, 0], rotation: [0, 0, 10] },
    ],
    cubes: [
      { id: 'band', bone: 'root', from: [-4.5, 8, -4.5], to: [4.5, 10, 4.5], faces: { south: { uv: [0, 0, 9, 2] }, north: { uv: [0, 2, 9, 4], rotation: 90 }, up: { uv: [0, 4, 9, 13], material: 'emissive' } } },
      { id: 'plate', bone: 'top', from: [-2, 10, 0], to: [2, 12, 0], material: 'translucent', faces: { south: { uv: [9, 0, 13, 2] } } },
    ],
    animations: [
      {
        id: 'idle', driver: 'idle', lengthMs: 1000, tracks: [
          { bone: 'top', channel: 'rotation', interpolation: 'linear', keys: [{ t: 0, v: [0, 0, 0] }, { t: 500, v: [0, 90, 0] }, { t: 1000, v: [0, 0, 0] }] },
          { bone: 'top', channel: 'scale', keys: [{ t: 0, v: [1, 1, 1] }, { t: 1000, v: [2, 2, 2] }] },
        ],
      },
      { id: 'walk', driver: 'walk', lengthMs: 400, tracks: [{ bone: 'root', channel: 'position', keys: [{ t: 0, v: [0, 1, 0] }] }] },
    ],
    halos: [{ bone: 'top', pos: [0, 11, 0.5], size: 4, color: '#ff8800', intensity: 0.8, normal: [0, 0, 1], pulse: { periodMs: 1000, min: 0.25, max: 1, phaseMs: 250 } }],
  }
}

describe('cosmetic v2 math', () => {
  it('rotationZYX = Rz·Ry·Rx, right-handed (Rx(+90) turns +y towards +z)', () => {
    const rx = rotationZYX(30, 0, 0)
    const ry = rotationZYX(0, -45, 0)
    const rz = rotationZYX(0, 0, 70)
    close(rotationZYX(30, -45, 70), multiply(rz, multiply(ry, rx)))
    close(transformPoint(rotationZYX(90, 0, 0), [0, 1, 0]), [0, 0, 1], 1e-12)
    close(transformPoint(rotationZYX(0, 90, 0), [0, 0, 1]), [1, 0, 0], 1e-12)
    close(transformPoint(rotationZYX(0, 0, 90), [1, 0, 0]), [0, 1, 0], 1e-12)
    // gleiche Konvention wie three.js Euler 'ZYX' (Matrix Rz·Ry·Rx)
    const m = new THREE.Matrix4().makeRotationFromEuler(new THREE.Euler(THREE.MathUtils.degToRad(30), THREE.MathUtils.degToRad(-45), THREE.MathUtils.degToRad(70), 'ZYX'))
    close(rotationZYX(30, -45, 70), m.elements, 1e-12)
  })

  it('eulerZYX inverts rotationZYX, invertAffine inverts bone matrices', () => {
    expect(eulerZYX(rotationZYX(12.5, -33, 71))).toEqual([12.5, -33, 71])
    const b = { id: 'x', pivot: [1, 9.5, -2] as Vec3, rotation: [10, 20, -30] as Vec3 }
    const m = boneLocal(b, { position: [0.5, 0, 0], rotation: [0, 15, 0], scale: [1.5, 1, 0.5] })
    close(multiply(invertAffine(m), m), identity(), 1e-12)
    // Drehpunkt bleibt ohne Pose fest
    close(transformPoint(boneLocal(b), b.pivot), b.pivot, 1e-12)
    close(transformDir(identity(), [1, 2, 3]), [1, 2, 3])
  })

  it('boneMatrices chain parents (W = W_parent · L)', () => {
    const t = tiny()
    const w = boneMatrices(t)
    close(w.get('top')!, multiply(w.get('root')!, boneLocal(t.bones[1]!)))
    const poses = samplePoses(t, 250)
    const wp = boneMatrices(t, poses)
    close(wp.get('top')!, multiply(boneLocal(t.bones[0]!, poses.get('root')), boneLocal(t.bones[1]!, poses.get('top'))))
  })

  it('face corners are seen from outside (CCW triangles OL, UL, UR → outward normal) and sized correctly', () => {
    const from: Vec3 = [-1, 2, -3]
    const to: Vec3 = [2, 4, 1]
    for (const f of FACES) {
      const [ol, , ur, ul] = faceCorners(from, to, f)
      close(norm(cross(sub(ul, ol), sub(ur, ol))), FACE_NORMAL[f], 1e-12)
    }
    expect(faceSize(from, to, 'south')).toEqual([3, 2])
    expect(faceSize(from, to, 'east')).toEqual([4, 2])
    expect(faceSize(from, to, 'up')).toEqual([3, 4])
    expect(faceCorners(from, to, 'up', 0.5)[0]).toEqual([-1.5, 4.5, -3.5])
  })

  it('UV rotation turns the image clockwise on the face', () => {
    const uv = [0, 0, 4, 2] as const
    expect(faceUvCorners(uv, 0)).toEqual([[0, 0], [4, 0], [4, 2], [0, 2]])
    expect(faceUvCorners(uv, 90)).toEqual([[0, 2], [0, 0], [4, 0], [4, 2]])
    expect(faceUvCorners(uv, 180)).toEqual([[4, 2], [0, 2], [0, 0], [4, 0]])
    expect(faceUvCorners(uv, 270)).toEqual(faceUvCorners(uv, -90))
  })
})

describe('cosmetic v2 animation', () => {
  it('samples linear, smooth and step, holds before/after the keys', () => {
    const keys = [{ t: 100, v: [0, 0, 0] as Vec3 }, { t: 300, v: [10, 20, -10] as Vec3 }]
    expect(sampleTrack({ keys }, 0)).toEqual([0, 0, 0])
    expect(sampleTrack({ keys }, 999)).toEqual([10, 20, -10])
    expect(sampleTrack({ keys, interpolation: 'linear' }, 150)).toEqual([2.5, 5, -2.5])
    expect(sampleTrack({ keys, interpolation: 'smooth' }, 150)[0]).toBeCloseTo(10 * 0.15625, 12) // u=0.25 → u²(3−2u)
    expect(sampleTrack({ keys, interpolation: 'step' }, 299)).toEqual([0, 0, 0])
    // Interpolation je Schlüssel gilt für das Stück BIS zu ihm
    expect(sampleTrack({ keys: [keys[0]!, { ...keys[1]!, interpolation: 'step' }], interpolation: 'linear' }, 200)).toEqual([0, 0, 0])
  })

  it('wall clock: loop modulo, offset, non-looping clamp, drivers; rotation adds, scale multiplies', () => {
    const t = tiny()
    expect(samplePoses(t, 250).get('top')!.rotation).toEqual([0, 45, 0])
    expect(samplePoses(t, 1250).get('top')!.rotation).toEqual([0, 45, 0])
    expect(samplePoses(t, -750).get('top')!.rotation).toEqual([0, 45, 0])
    expect(samplePoses(t, 500).get('top')!.scale).toEqual([1.5, 1.5, 1.5])
    expect(samplePoses(t, 0).has('root')).toBe(false) // walk-Treiber nicht aktiv
    expect(samplePoses(t, 0, ['idle', 'walk']).get('root')!.position).toEqual([0, 1, 0])
    const two = { animations: [t.animations![0]!, { ...t.animations![0]!, id: 'b', offsetMs: 250 }] }
    expect(samplePoses(two, 0).get('top')!.rotation).toEqual([0, 45, 0])
    expect(samplePoses(two, 0).get('top')!.scale[0]).toBeCloseTo(1.25, 12)
    const once = { animations: [{ ...t.animations![0]!, loop: false }] }
    expect(samplePoses(once, 5000).get('top')!.scale).toEqual([2, 2, 2])
  })

  it('frames, halo pulse, facing and falloff', () => {
    expect(frameAt(0, 12, 140)).toBe(0)
    expect(frameAt(140 * 13, 12, 140)).toBe(1)
    expect(frameAt(999, 1, 100)).toBe(0)
    const h = tiny().halos![0]!
    expect(haloIntensity(h, 250)).toBeCloseTo(0.8, 12) // Phase 0,5 → max
    expect(haloIntensity(h, 750)).toBeCloseTo(0.8 * 0.25, 12) // Phase 0 → min
    expect(haloIntensity({ intensity: 0.5 }, 1234)).toBe(0.5)
    expect(haloFacing(-0.2)).toBe(0)
    expect(haloFacing(0.35)).toBe(1)
    expect(haloFacing(0.175)).toBeCloseTo(0.5, 12)
    expect(haloFalloff(0)).toBe(1)
    expect(haloFalloff(1)).toBe(0)
    expect(haloFalloff(0.5)).toBeCloseTo(0.5 ** 2.5, 12)
  })
})

describe('cosmetic v2 validation', () => {
  const errs = (patch: (m: CosmeticModel & Record<string, unknown>) => void, images?: Parameters<typeof validateModel>[1]) => {
    const m = tiny() as CosmeticModel & Record<string, unknown>
    patch(m)
    return validateModel(m, images).errors.join('\n')
  }

  it('accepts the bundled models and a small valid model', () => {
    expect(validateModel(tiny(), { texture: { width: 128, height: 128 }, glow: { width: 128, height: 512 } }).errors).toEqual([])
    for (const id of IDS) {
      const m = model(id)
      const r = validateModel(m, { texture: pngSize(join(V2, `${id}.png`)), glow: m.glow ? pngSize(join(V2, `${id}-glow.png`)) : null })
      expect(r.errors, id).toEqual([])
      expect(r.stats.cubes).toBe(m.cubes.length)
    }
    expect(() => parseModel({ format: 1 })).toThrow(/Ungültiges Kosmetik-Modell/)
  })

  it('reports the typical mistakes', () => {
    expect(validateModel(null).ok).toBe(false)
    expect(errs((m) => { m.format = 1 as 2 })).toMatch(/format/)
    expect(errs((m) => { m.id = 'Bad-Id' })).toMatch(/^id:/m)
    expect(errs((m) => { m.slot = 'wings' as 'hat' })).toMatch(/slot/)
    expect(errs((m) => { m.texture.scale = 3 })).toMatch(/texture.scale/)
    expect(errs((m) => { m.texture.width = 12 })).toMatch(/Vielfaches von 8/)
    expect(errs((m) => { m.texture.width = 256 })).toMatch(/Kante höchstens 1024/)
    expect(errs((m) => { m.glow!.frames = 17 })).toMatch(/glow.frames/)
    expect(errs((m) => { m.glow!.blend = 'screen' as 'additive' })).toMatch(/nur additive/)
    expect(errs(() => {}, { texture: { width: 64, height: 128 } })).toMatch(/Bild ist 64×128, erwartet 128×128/)
    expect(errs(() => {}, { texture: { width: 128, height: 128 }, glow: { width: 128, height: 128 } })).toMatch(/glow: Bild ist/)
    expect(errs((m) => { m.bones[1]!.parent = 'later' })).toMatch(/muss vorher stehen/)
    expect(errs((m) => { m.bones.push({ ...m.bones[0]! }) })).toMatch(/doppelt/)
    expect(errs((m) => { m.cubes[0]!.bone = 'nope' })).toMatch(/unbekannter Knochen nope/)
    expect(errs((m) => { m.cubes[0]!.to = [4.5, 10, 4.3] })).toMatch(/Raster/)
    expect(errs((m) => { m.cubes[0]!.to = [4.5, 50, 4.5] })).toMatch(/Kante y|±48/)
    expect(errs((m) => { m.cubes[1]!.faces.north = { uv: [0, 0, 1, 1] } })).toMatch(/nur south angeben/)
    expect(errs((m) => { m.cubes[1]!.faces.east = { uv: [0, 0, 1, 1] } })).toMatch(/keine Ausdehnung/)
    expect(errs((m) => { m.cubes[0]!.faces.south = { uv: [0, 0, 17, 2] } })).toMatch(/außerhalb der Textur/)
    expect(errs((m) => { m.cubes[0]!.faces.south = { uv: [1, 1, 1, 2] } })).toMatch(/leer/)
    expect(errs((m) => { m.cubes[0]!.faces.south!.rotation = 45 as 90 })).toMatch(/rotation 0\/90/)
    expect(errs((m) => { (m.cubes[0]!.faces as Record<string, unknown>).front = { uv: [0, 0, 1, 1] } })).toMatch(/unbekannte Fläche front/)
    expect(errs((m) => { m.cubes = Array.from({ length: 65 }, () => ({ ...m.cubes[0]!, id: undefined })) })).toMatch(/höchstens 64/)
    expect(errs((m) => { m.animations![0]!.tracks[0]!.keys[1]!.t = 0 })).toMatch(/aufsteigend/)
    expect(errs((m) => { m.animations![0]!.tracks[1]!.keys[1]!.v = [0, 1, 1] })).toMatch(/scale 0…4/)
    expect(errs((m) => { m.animations![0]!.driver = 'dance' as 'idle' })).toMatch(/driver/)
    expect(errs((m) => { m.halos![0]!.color = '#FFF' })).toMatch(/color/)
    expect(errs((m) => { m.halos![0]!.pulse!.min = 1.5 })).toMatch(/pulse.min/)
    expect(errs((m) => { m.halos![0]!.normal = [0, 0, 0] })).toMatch(/normal/)
    // Warnung (kein Fehler): uv nicht auf dem Texel-Raster
    const w = tiny()
    w.cubes[0]!.faces.south!.uv = [0, 0, 9.01, 2]
    const r = validateModel(w)
    expect(r.ok).toBe(true)
    expect(r.warnings.join()).toMatch(/Texel-Raster/)
  })
})

// Studio-Referenz liegt nur auf dem Rechner des Autors – dort wird der Port 1:1 gegen sie geprüft.
const STUDIO = 'E:/ai/trs-studio/public/cosmetic-format.mjs'
describe.skipIf(!existsSync(STUDIO))('cosmetic v2 port = studio reference', () => {
  it('same validation, poses, bone matrices, frames and halo values for all bundled models', async () => {
    const ref = await import(/* @vite-ignore */ pathToFileURL(STUDIO).href)
    for (const id of IDS) {
      const m = model(id)
      const images = { texture: pngSize(join(V2, `${id}.png`)), glow: m.glow ? pngSize(join(V2, `${id}-glow.png`)) : null }
      expect(validateModel(m, images)).toEqual(ref.validateModel(m, images))
      for (const t of [0, 137, 1234, 4800, 99_999, 1_759_152_000_123]) {
        const mine = boneMatrices(m, samplePoses(m, t))
        const theirs: Map<string, Mat4> = ref.boneMatrices(m, ref.samplePoses(m, t))
        for (const [bone, mat] of theirs) close(mine.get(bone)!, mat, 1e-9)
        if (m.glow) expect(frameAt(t, m.glow.frames, m.glow.frameTimeMs)).toBe(ref.frameAt(t, m.glow.frames, m.glow.frameTimeMs))
        for (const h of m.halos ?? []) expect(haloIntensity(h, t)).toBe(ref.haloIntensity(h, t))
      }
      for (const c of m.cubes) {
        for (const f of FACES) {
          const face = c.faces[f]
          if (!face) continue
          expect(faceCorners(c.from, c.to, f, c.inflate ?? 0)).toEqual(ref.faceCorners(c.from, c.to, f, c.inflate ?? 0))
          expect(faceUvCorners(face.uv, face.rotation ?? 0)).toEqual(ref.faceUvCorners(face.uv, face.rotation ?? 0))
        }
      }
    }
  })
})

describe('cosmetic v2 three.js scene', () => {
  afterEach(() => vi.unstubAllGlobals())

  /** Minimaler DOM-Ersatz für die Hof-Textur (Leinwand), damit der Aufbau in Node läuft. */
  function stubCanvas() {
    vi.stubGlobal('ImageData', class {
      constructor(public data: Uint8ClampedArray, public width: number, public height: number) {}
    })
    vi.stubGlobal('document', {
      createElement: () => ({
        width: 0,
        height: 0,
        getContext: () => ({ putImageData: () => {}, createImageData: (w: number, h: number) => ({ data: new Uint8ClampedArray(w * h * 4) }) }),
      }),
    })
  }
  const img = (w: number, h: number) => ({ width: w, height: h }) as unknown as HTMLCanvasElement

  const VIEW_REF = 'E:/ai/trs-studio/public/cosmetic-view.mjs'
  it.skipIf(!existsSync(VIEW_REF))('builds exactly the same scene as the studio workbench (all bundled models)', async () => {
    stubCanvas()
    const ref = await import(/* @vite-ignore */ pathToFileURL(VIEW_REF).href)
    const cam = new THREE.PerspectiveCamera(32)
    cam.position.set(20, 30, 25)
    for (const id of IDS) {
      const m = model(id)
      const images = { texture: img(m.texture.width * m.texture.scale, m.texture.height * m.texture.scale), glow: m.glow ? img(1, 1) : null }
      const mine = createCosmetic(THREE, m, images)
      const theirs = ref.createCosmetic(THREE, m, images)
      const parentA = new THREE.Group()
      const parentB = new THREE.Group()
      parentA.position.set(0, 24, 0)
      parentB.position.set(0, 24, 0)
      parentA.add(mine.root)
      parentB.add(theirs.root)
      for (const t of [0, 777, 1_759_152_000_123]) {
        mine.update(t, { camera: cam })
        theirs.update(t, { camera: cam })
        const a: THREE.Object3D[] = []
        const b: THREE.Object3D[] = []
        mine.root.traverse((o) => a.push(o))
        theirs.root.traverse((o: THREE.Object3D) => b.push(o))
        expect(a.length, id).toBe(b.length)
        a.forEach((o, i) => {
          const r = b[i]!
          expect(o.type).toBe(r.type)
          expect(o.visible).toBe(r.visible)
          expect(o.renderOrder).toBe(r.renderOrder)
          close(o.matrixWorld.elements, r.matrixWorld.elements, 1e-9)
          if ((o as THREE.Mesh).isMesh) {
            const ga = (o as THREE.Mesh).geometry
            const gb = (r as THREE.Mesh).geometry
            expect(Array.from(ga.getAttribute('position').array)).toEqual(Array.from(gb.getAttribute('position').array))
            expect(Array.from(ga.getAttribute('uv').array)).toEqual(Array.from(gb.getAttribute('uv').array))
            expect(Array.from(ga.getIndex()!.array)).toEqual(Array.from(gb.getIndex()!.array))
            expect(ga.groups).toEqual(gb.groups)
          }
          if ((o as THREE.Sprite).isSprite) {
            const ma = (o as THREE.Sprite).material
            const mb = (r as THREE.Sprite).material
            expect(ma.opacity).toBeCloseTo(mb.opacity, 12)
            expect(ma.color.toArray()).toEqual(mb.color.toArray())
          }
        })
      }
      mine.dispose()
      theirs.dispose()
    }
  })

  it('builds meshes per bone with material groups, UVs and a glow overlay; update() animates like samplePoses', () => {
    stubCanvas()
    const t = tiny()
    const cos = createCosmetic(THREE, t, { texture: img(128, 128), glow: img(128, 512) })
    const bones = new Map<string, THREE.Object3D>()
    cos.root.traverse((o) => bones.set(o.name, o))
    const rootMeshes = bones.get('root')!.children.filter((o): o is THREE.Mesh => (o as THREE.Mesh).isMesh)
    expect(rootMeshes).toHaveLength(2) // Grund + Leuchten
    const [base, glow] = rootMeshes as [THREE.Mesh, THREE.Mesh]
    expect(glow.userData.glow).toBe(true)
    expect(glow.renderOrder).toBe(2)
    expect((glow.material as THREE.MeshBasicMaterial).blending).toBe(THREE.AdditiveBlending)
    // Gruppen: cutout (south + north) = 12 Indizes, emissive (up) = 6
    expect(base.geometry.groups).toEqual([{ start: 0, count: 12, materialIndex: 0 }, { start: 12, count: 6, materialIndex: 1 }])
    const pos = base.geometry.getAttribute('position')
    const uv = base.geometry.getAttribute('uv')
    const c0 = t.cubes[0]!
    // Reihenfolge FACES (north, south, …): erst north (rotation 90 → Bildecken verschoben), dann south;
    // Ecken wie faceCorners, UV = u/Breite, 1 − v/Höhe
    faceCorners(c0.from, c0.to, 'north').forEach((p, i) => close([pos.getX(i), pos.getY(i), pos.getZ(i)], p, 1e-6))
    faceUvCorners(c0.faces.north!.uv, 90).forEach(([u, v], i) => close([uv.getX(i), uv.getY(i)], [u / 16, 1 - v / 16], 1e-6))
    faceCorners(c0.from, c0.to, 'south').forEach((p, i) => close([pos.getX(4 + i), pos.getY(4 + i), pos.getZ(4 + i)], p, 1e-6))
    faceUvCorners(c0.faces.south!.uv).forEach(([u, v], i) => close([uv.getX(4 + i), uv.getY(4 + i)], [u / 16, 1 - v / 16], 1e-6))
    expect(Array.from(base.geometry.getIndex()!.array.slice(0, 6))).toEqual([0, 3, 2, 0, 2, 1])
    // translucent auf dem Kind-Knochen
    const plate = bones.get('top')!.children.find((o) => (o as THREE.Mesh).isMesh && !o.userData.glow) as THREE.Mesh
    expect(plate.geometry.groups).toEqual([{ start: 0, count: 6, materialIndex: 2 }])
    const mats = base.material as THREE.MeshBasicMaterial[]
    expect(mats.map((m) => [m.alphaTest, m.transparent, m.depthWrite, m.side])).toEqual([
      [0.5, false, true, THREE.DoubleSide], [0.5, false, true, THREE.DoubleSide], [0, true, false, THREE.DoubleSide],
    ])
    expect(mats[0]!.map!.magFilter).toBe(THREE.NearestFilter)
    expect(mats[0]!.map!.generateMipmaps).toBe(false)

    cos.update(250)
    close(bones.get('top')!.matrix.elements, boneLocal(t.bones[1]!, samplePoses(t, 250).get('top')), 1e-6)
    cos.update(250, { animate: false })
    close(bones.get('top')!.matrix.elements, boneLocal(t.bones[1]!), 1e-6)
    // Leucht-Streifen: Bild 2 von 4 (offset.y = (F − 1 − f) / F)
    cos.update(250)
    expect((glow.material as THREE.MeshBasicMaterial).map!.offset.y).toBeCloseTo((4 - 1 - 2) / 4, 12)
    cos.update(250, { glow: false })
    expect(glow.visible).toBe(false)

    cos.setLight(0.2)
    expect(mats[0]!.color.r).toBeCloseTo(0.2, 6)
    expect(mats[1]!.color.r).toBe(1) // emissive bleibt voll hell
    cos.dispose()
  })

  it('halos: billboards pushed size/2 towards the camera, faded by pulse and facing', () => {
    stubCanvas()
    const t = tiny()
    const cos = createCosmetic(THREE, { ...t, animations: [] }, { texture: img(128, 128), glow: img(128, 512) })
    const sprite = cos.root.getObjectByProperty('type', 'Sprite') as THREE.Sprite
    expect(sprite.scale.x).toBe(4)
    const cam = new THREE.PerspectiveCamera()
    cam.position.set(0, 11, 40)
    cos.update(250, { camera: cam })
    const w = sprite.getWorldPosition(new THREE.Vector3())
    // Anker (Kind-Knochen gedreht um z 10°) + 2 Einheiten Richtung Kamera
    const anchor = sprite.parent!.localToWorld(new THREE.Vector3(0, 11, 0.5))
    expect(w.distanceTo(anchor)).toBeCloseTo(2, 5)
    const mat = sprite.material
    expect(mat.opacity).toBeCloseTo(0.8, 5) // Phase max, Normale zeigt zur Kamera
    // Kamera hinten: leuchtende Seite zeigt weg → aus
    cam.position.set(0, 11, -40)
    cos.update(250, { camera: cam })
    expect(sprite.visible).toBe(false)
    cos.dispose()
  })

  it('day/night and head framing work on a skinview3d-like viewer', () => {
    const head = new THREE.Group()
    head.position.set(0, 24, 0)
    const playerObject = Object.assign(new THREE.Group(), { skin: { head } })
    playerObject.add(head)
    const viewer = {
      globalLight: new THREE.AmbientLight(0xffffff, 3),
      cameraLight: new THREE.PointLight(0xffffff, 0.6),
      camera: new THREE.PerspectiveCamera(32),
      controls: { target: new THREE.Vector3(), update: () => {} },
      playerWrapper: new THREE.Group(),
      playerObject,
    }
    const setLight = vi.fn()
    applyDayNight(viewer as never, { setLight } as never, true)
    expect(viewer.globalLight.intensity).toBeCloseTo(0.54, 12)
    expect(viewer.cameraLight.intensity).toBeCloseTo(0.12, 12)
    expect(setLight).toHaveBeenCalledWith(0.2)
    applyDayNight(viewer as never, null, false)
    expect(viewer.globalLight.intensity).toBe(3)
    frameHead(THREE, viewer as never, model('top_hat'), 'three')
    // Ziel = Nacken + 8 + Anhebung nach Modellhöhe
    const top = Math.max(12.5, ...model('top_hat').cubes.map((c) => c.to[1]))
    expect(viewer.controls.target.y).toBeCloseTo(24 + 8 + Math.max(0, (top - 12.5) / 2), 9)
    expect(viewer.camera.position.distanceTo(viewer.controls.target)).toBeCloseTo(34 * (1 + Math.max(0, (top - 12.5) / 2) / 9), 9)
  })
})
