// Darstellung eines v2-Kosmetik-Modells in three.js – Port der Studio-Referenz
// (`trs-studio/public/cosmetic-view.mjs`), damit Launcher und Studio-Werkbank gleich aussehen:
// Flächen beidseitig, cutout = Alpha-Test 0,5, emissive = voll hell, Leucht-Schicht additiv über
// dieselben Flächen, Leucht-Höfe als additive Billboards (§6, §8, §9 in cosmetic-format.md).
// Gleiche three.js-Ausgabe wie skinview3d (r156) – das Modell hängt direkt am Kopf des Spielers.

import * as THREE from 'three'
import {
  FACES,
  boneLocal,
  faceCorners,
  faceUvCorners,
  frameAt,
  haloFacing,
  haloFalloff,
  haloIntensity,
  samplePoses,
  type CosmeticHalo,
  type CosmeticModel,
  type Vec3,
} from './format'

const MAT_INDEX = { cutout: 0, emissive: 1, translucent: 2 } as const

export type CosmeticImage = HTMLImageElement | HTMLCanvasElement | ImageBitmap

export interface CosmeticInstance {
  /** An den Kopf hängen (Ursprung = Nacken, wie ModelPart head). */
  root: THREE.Group
  /** Pose, Streifen-Bilder und Höfe zur Wanduhr `timeMs` setzen. */
  update(timeMs: number, opts?: { animate?: boolean; glow?: boolean; camera?: THREE.Camera | null }): void
  /** Umgebungslicht 0…1 (nur cutout/translucent; emissive und Leuchten bleiben voll hell). */
  setLight(f: number): void
  dispose(): void
}

function stripTexture(image: CosmeticImage, frames: number): THREE.Texture {
  const t = new THREE.Texture(image as HTMLImageElement)
  t.magFilter = THREE.NearestFilter
  // wie Minecraft bei Entity-Texturen: keine Mipmaps (sonst frisst der Alpha-Test Kanten an)
  t.minFilter = THREE.NearestFilter
  t.colorSpace = THREE.SRGBColorSpace
  t.generateMipmaps = false
  t.repeat.set(1, 1 / frames)
  t.offset.set(0, (frames - 1) / frames)
  t.needsUpdate = true
  return t
}

function setFrame(tex: THREE.Texture, f: number, frames: number) {
  tex.offset.y = (frames - 1 - f) / frames
}

let haloTexture: THREE.Texture | null = null
/** Kleine Graustufen-Textur mit dem Verlauf `haloFalloff` (einmal je Seite). */
function haloMap(): THREE.Texture {
  if (haloTexture) return haloTexture
  const n = 64
  const c = document.createElement('canvas')
  c.width = n
  c.height = n
  const g = c.getContext('2d')!
  const img = g.createImageData(n, n)
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      const r = Math.hypot(x + 0.5 - n / 2, y + 0.5 - n / 2) / (n / 2)
      img.data.set([255, 255, 255, Math.round(haloFalloff(r) * 255)], (y * n + x) * 4)
    }
  }
  g.putImageData(img, 0, 0)
  haloTexture = new THREE.CanvasTexture(c)
  haloTexture.colorSpace = THREE.SRGBColorSpace
  return haloTexture
}

interface HaloState {
  h: CosmeticHalo
  s: THREE.Sprite
  mat: THREE.SpriteMaterial
  color: THREE.Color
  anchor: THREE.Vector3
  normal: THREE.Vector3 | null
}

/** Baut das Modell. `images.texture` = Grundtextur (Streifen), `images.glow` = Leucht-Schicht (optional). */
export function createCosmetic(model: CosmeticModel, images: { texture: CosmeticImage; glow?: CosmeticImage | null }): CosmeticInstance {
  const tex = model.texture
  const texW = tex.width
  const texH = tex.height
  const texFrames = tex.frames ?? 1
  const baseMap = stripTexture(images.texture, texFrames)
  const glowFrames = model.glow?.frames ?? 1
  const glowMap = images.glow && model.glow ? stripTexture(images.glow, glowFrames) : null

  const mats = [
    new THREE.MeshBasicMaterial({ map: baseMap, alphaTest: 0.5, side: THREE.DoubleSide }),
    new THREE.MeshBasicMaterial({ map: baseMap, alphaTest: 0.5, side: THREE.DoubleSide }),
    new THREE.MeshBasicMaterial({ map: baseMap, transparent: true, depthWrite: false, side: THREE.DoubleSide }),
  ]
  const glowMat = glowMap
    ? new THREE.MeshBasicMaterial({
        map: glowMap,
        transparent: true,
        blending: THREE.AdditiveBlending,
        depthWrite: false,
        side: THREE.DoubleSide,
        polygonOffset: true,
        polygonOffsetFactor: -1,
        polygonOffsetUnits: -2,
        toneMapped: false,
      })
    : null

  const root = new THREE.Group()
  root.name = `cosmetic:${model.id}`
  const bones = new Map<string, THREE.Group>()
  for (const b of model.bones) {
    const g = new THREE.Group()
    g.name = b.id
    g.matrixAutoUpdate = false
    g.matrix.fromArray(boneLocal(b, null))
    ;(b.parent ? bones.get(b.parent)! : root).add(g)
    bones.set(b.id, g)
  }

  // Flächen je Knochen einsammeln, nach Material gruppiert
  type FaceGeo = { corners: Vec3[]; uv: [number, number][] }
  const perBone = new Map<string, FaceGeo[][]>()
  for (const c of model.cubes) {
    const list = perBone.get(c.bone) ?? [[], [], []]
    perBone.set(c.bone, list)
    for (const face of FACES) {
      const f = c.faces[face]
      if (!f) continue
      const m = MAT_INDEX[f.material ?? c.material ?? 'cutout']
      list[m]!.push({ corners: faceCorners(c.from, c.to, face, c.inflate ?? 0), uv: faceUvCorners(f.uv, f.rotation ?? 0) })
    }
  }
  const geometries: THREE.BufferGeometry[] = []
  for (const [boneId, groups] of perBone) {
    const pos: number[] = []
    const uv: number[] = []
    const idx: number[] = []
    const geo = new THREE.BufferGeometry()
    let start = 0
    groups.forEach((faces, mi) => {
      for (const f of faces) {
        const base = pos.length / 3
        for (let i = 0; i < 4; i++) {
          pos.push(...f.corners[i]!)
          uv.push(f.uv[i]![0] / texW, 1 - f.uv[i]![1] / texH)
        }
        idx.push(base, base + 3, base + 2, base, base + 2, base + 1)
      }
      const count = faces.length * 6
      if (count) geo.addGroup(start, count, mi)
      start += count
    })
    geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3))
    geo.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2))
    geo.setIndex(idx)
    geometries.push(geo)
    const bone = bones.get(boneId)!
    bone.add(new THREE.Mesh(geo, mats))
    if (glowMat) {
      const gm = new THREE.Mesh(geo, glowMat)
      gm.renderOrder = 2
      gm.userData.glow = true
      bone.add(gm)
    }
  }

  // Leucht-Höfe
  const halos: HaloState[] = (model.halos ?? []).map((h) => {
    const mat = new THREE.SpriteMaterial({
      map: haloMap(),
      color: new THREE.Color(h.color),
      blending: THREE.AdditiveBlending,
      transparent: true,
      depthWrite: false,
      toneMapped: false,
    })
    const s = new THREE.Sprite(mat)
    s.position.set(...h.pos)
    s.scale.set(h.size, h.size, 1)
    s.renderOrder = 3
    s.userData.glow = true
    bones.get(h.bone)!.add(s)
    return {
      h,
      s,
      mat,
      color: new THREE.Color(h.color),
      anchor: new THREE.Vector3(...h.pos),
      normal: h.normal ? new THREE.Vector3(...h.normal).normalize() : null,
    }
  })

  const tmp = new THREE.Vector3()
  const tmp2 = new THREE.Vector3()
  const tmp3 = new THREE.Vector3()
  const camPos = new THREE.Vector3()
  const scl = new THREE.Vector3()
  const worldScale = (o: THREE.Object3D) => o.getWorldScale(scl).x
  let glowOn = true

  return {
    root,
    update(timeMs, { animate = true, glow = true, camera = null } = {}) {
      const poses = animate ? samplePoses(model, timeMs) : null
      for (const b of model.bones) bones.get(b.id)!.matrix.fromArray(boneLocal(b, poses?.get(b.id)))
      root.updateMatrixWorld(true)
      if (glow !== glowOn) {
        glowOn = glow
        root.traverse((o) => {
          if (o.userData.glow) o.visible = glow
        })
      }
      if (glowMap && model.glow) setFrame(glowMap, frameAt(timeMs, glowFrames, model.glow.frameTimeMs), glowFrames)
      if (texFrames > 1) setFrame(baseMap, frameAt(timeMs, texFrames, tex.frameTimeMs), texFrames)
      if (camera) camera.getWorldPosition(camPos)
      for (const { h, s, mat, color, anchor, normal } of halos) {
        let facing = 1
        // Hof um size/2 zur Kamera schieben: sonst schneidet die Billboard-Ebene die eigene Geometrie
        if (camera && s.parent) {
          const w = s.parent.localToWorld(tmp.copy(anchor))
          const dir = tmp2.copy(camPos).sub(w).normalize()
          if (normal) facing = haloFacing(tmp3.copy(normal).transformDirection(s.parent.matrixWorld).dot(dir))
          s.position.copy(s.parent.worldToLocal(w.addScaledVector(dir, (h.size / 2) * worldScale(s.parent))))
        }
        const i = haloIntensity(h, timeMs) * facing
        s.visible = glowOn && i > 0.001
        mat.opacity = Math.min(1, i)
        mat.color.copy(color).multiplyScalar(Math.max(1, i))
      }
    },
    setLight(f) {
      mats[0]!.color.setScalar(f)
      mats[2]!.color.setScalar(f)
    },
    dispose() {
      root.parent?.remove(root)
      for (const g of geometries) g.dispose()
      for (const m of mats) m.dispose()
      glowMat?.dispose()
      baseMap.dispose()
      glowMap?.dispose()
      for (const { mat } of halos) mat.dispose()
    },
  }
}
