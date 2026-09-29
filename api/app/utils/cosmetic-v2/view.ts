// Darstellung eines v2-Kosmetik-Modells in three.js – TypeScript-Port der Studio-Werkbank
// (`trs-studio/public/cosmetic-view.mjs`, Ansicht/Licht aus `workbench.js`). Regeln exakt nach dem Format:
// Flächen beidseitig, cutout = Alpha-Test 0,5, emissive = voll hell, Leucht-Schicht additiv über dieselben
// Flächen, Leucht-Höfe als additive Billboards. Ohne Nuxt: three.js wird hineingereicht (`THREE` = das Modul
// `three`), skinview3d nur als Typ – so kann der Launcher dieselbe Datei übernehmen (README.md daneben).

import type * as ThreeNs from 'three'
import type { SkinViewer } from 'skinview3d'
import {
  FACES,
  boneLocal,
  faceCorners,
  faceUvCorners,
  frameAt,
  haloFacing,
  haloFalloff,
  haloIntensity,
  parseModel,
  samplePoses,
  type CosmeticModel,
  type Driver,
  type MaterialName,
} from './format'

/** Das three.js-Modul (`import * as THREE from 'three'` bzw. `await import('three')`). */
export type Three = typeof ThreeNs

/** Bildquellen für Grundtextur und Leucht-Streifen (Bild, Canvas, ImageBitmap …). */
export type CosmeticImage = HTMLImageElement | HTMLCanvasElement | ImageBitmap | OffscreenCanvas

export interface CosmeticImages {
  texture: CosmeticImage
  glow?: CosmeticImage | null
}

export interface CosmeticUpdateOptions {
  /** Knochen-Animationen abspielen (sonst Ruhepose). Standard true. */
  animate?: boolean
  /** Leucht-Schicht und Höfe zeigen. Standard true. */
  glow?: boolean
  /** Kamera für die Höfe (zur Kamera schieben, nach Blickrichtung ausblenden). */
  camera?: ThreeNs.Camera | null
  /** Aktive Treiber (Standard nur `idle`). */
  drivers?: readonly Driver[]
}

export interface CosmeticInstance {
  /** An den Kopf hängen (Ursprung = Nacken, wie ModelPart head / skinview3d `skin.head`). */
  readonly root: ThreeNs.Group
  readonly model: CosmeticModel
  /** Pose, Streifen-Bilder und Höfe zur Wanduhr `timeMs` setzen – vor jedem Bild aufrufen. */
  update(timeMs: number, opts?: CosmeticUpdateOptions): void
  /** Umgebungslicht 0…1 (nur cutout/translucent; emissive bleibt voll hell). */
  setLight(f: number): void
  /** Umriss um einen Würfel (Werkzeug-Ansicht), `null` = aus. */
  highlight(cubeId: string | null): void
  dispose(): void
}

const MAT_INDEX: Record<MaterialName, number> = { cutout: 0, emissive: 1, translucent: 2 }

function stripTexture(THREE: Three, image: CosmeticImage, frames: number): ThreeNs.Texture {
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

function setFrame(tex: ThreeNs.Texture, f: number, frames: number): void {
  tex.offset.y = (frames - 1 - f) / frames
}

/** Graustufen-Verlauf der Höfe (64×64, a(r) = (1 − r)^2,5), je three.js-Modul einmal erzeugt. */
const haloTextures = new WeakMap<Three, ThreeNs.Texture>()
function haloMap(THREE: Three): ThreeNs.Texture {
  const cached = haloTextures.get(THREE)
  if (cached) return cached
  const n = 64
  const data = new Uint8ClampedArray(n * n * 4)
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      const r = Math.hypot(x + 0.5 - n / 2, y + 0.5 - n / 2) / (n / 2)
      const a = haloFalloff(r)
      data.set([255, 255, 255, Math.round(a * 255)], (y * n + x) * 4)
    }
  }
  // Wie die Referenz über eine Leinwand (Premultiply/Farbraum identisch zur Studio-CanvasTexture).
  const c = document.createElement('canvas')
  c.width = n
  c.height = n
  const g = c.getContext('2d')
  if (!g) throw new Error('2D canvas unavailable')
  g.putImageData(new ImageData(data, n, n), 0, 0)
  const tex = new THREE.CanvasTexture(c)
  tex.colorSpace = THREE.SRGBColorSpace
  haloTextures.set(THREE, tex)
  return tex
}

interface FaceGeo {
  corners: ReturnType<typeof faceCorners>
  uv: ReturnType<typeof faceUvCorners>
}

interface HaloObj {
  size: number
  s: ThreeNs.Sprite
  mat: ThreeNs.SpriteMaterial
  color: ThreeNs.Color
  anchor: ThreeNs.Vector3
  normal: ThreeNs.Vector3 | null
  h: NonNullable<CosmeticModel['halos']>[number]
}

/**
 * Baut das Modell. `images` = { texture, glow? } (Bild/Canvas). Das Modell sollte vorher mit
 * `parseModel`/`validateModel` geprüft sein. `root` an den Kopf hängen und vor jedem Bild `update()` aufrufen.
 */
export function createCosmetic(THREE: Three, model: CosmeticModel, images: CosmeticImages): CosmeticInstance {
  const tex = model.texture
  const texW = tex.width
  const texH = tex.height
  const texFrames = tex.frames ?? 1
  const baseMap = stripTexture(THREE, images.texture, texFrames)
  const glowFrames = model.glow?.frames ?? 1
  const glowMap = images.glow && model.glow ? stripTexture(THREE, images.glow, glowFrames) : null

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
  const bones = new Map<string, ThreeNs.Group>()
  for (const b of model.bones) {
    const g = new THREE.Group()
    g.name = b.id
    g.matrixAutoUpdate = false
    g.matrix.fromArray(boneLocal(b, null))
    ;(b.parent ? bones.get(b.parent)! : root).add(g)
    bones.set(b.id, g)
  }

  // Flächen je Knochen einsammeln, nach Material gruppiert
  const perBone = new Map<string, [FaceGeo[], FaceGeo[], FaceGeo[]]>()
  const cubeInfo = new Map<string, CosmeticModel['cubes'][number]>()
  for (const c of model.cubes) {
    let list = perBone.get(c.bone)
    if (!list) {
      list = [[], [], []]
      perBone.set(c.bone, list)
    }
    if (c.id) cubeInfo.set(c.id, c)
    for (const face of FACES) {
      const f = c.faces[face]
      if (!f) continue
      const m = MAT_INDEX[f.material ?? c.material ?? 'cutout']
      list[m]!.push({ corners: faceCorners(c.from, c.to, face, c.inflate ?? 0), uv: faceUvCorners(f.uv, f.rotation ?? 0) })
    }
  }
  const geometries: ThreeNs.BufferGeometry[] = []
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
  const halos: HaloObj[] = (model.halos ?? []).map((h) => {
    const mat = new THREE.SpriteMaterial({
      map: haloMap(THREE),
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
      size: h.size,
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
  const scl = new THREE.Vector3()
  const worldScale = (o: ThreeNs.Object3D) => o.getWorldScale(scl).x
  let highlightObj: ThreeNs.LineSegments | null = null
  let glowOn = true

  return {
    root,
    model,
    update(timeMs, { animate = true, glow = true, camera = null, drivers } = {}) {
      const poses = animate ? samplePoses(model, timeMs, drivers) : null
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
      for (const { h, s, mat, color, anchor, normal } of halos) {
        let facing = 1
        // Hof um size/2 zur Kamera schieben: sonst schneidet die Billboard-Ebene die eigene Geometrie
        if (camera && s.parent) {
          const w = s.parent.localToWorld(tmp.copy(anchor))
          const dir = tmp2.copy(camera.position).sub(w).normalize()
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
    highlight(cubeId) {
      if (highlightObj) {
        highlightObj.parent?.remove(highlightObj)
        highlightObj.geometry.dispose()
        ;(highlightObj.material as ThreeNs.Material).dispose()
        highlightObj = null
      }
      const c = cubeId ? cubeInfo.get(cubeId) : undefined
      if (!c) return
      const size = [0, 1, 2].map((a) => Math.max(0.02, c.to[a]! - c.from[a]! + 0.1)) as [number, number, number]
      const box = new THREE.EdgesGeometry(new THREE.BoxGeometry(...size))
      highlightObj = new THREE.LineSegments(box, new THREE.LineBasicMaterial({ color: 0x5ee0ff, depthTest: false, transparent: true }))
      highlightObj.renderOrder = 10
      highlightObj.position.set((c.from[0] + c.to[0]) / 2, (c.from[1] + c.to[1]) / 2, (c.from[2] + c.to[2]) / 2)
      bones.get(c.bone)!.add(highlightObj)
    },
    dispose() {
      this.highlight(null)
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

// ---------------------------------------------------------------- Laden

export interface CosmeticSources {
  /** URL von `model.json`. */
  model: string
  /** URL der Grundtextur. */
  texture: string
  /** URL des Leucht-Streifens oder null. */
  glow?: string | null
}

export interface LoadedCosmetic {
  model: CosmeticModel
  images: CosmeticImages
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.decoding = 'async'
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error(`image failed to load: ${src}`))
    img.src = src
  })
}

/**
 * Lädt Modell + Bilder und prüft das Modell samt Bildmaßen (wirft bei Fehlern). `fetchJson` optional
 * (z. B. Tauri-Kern statt `fetch`), `loadImg` optional (z. B. Data-URLs).
 */
export async function loadCosmetic(
  src: CosmeticSources,
  deps: { fetchJson?: (url: string) => Promise<unknown>, loadImg?: (url: string) => Promise<CosmeticImage> } = {},
): Promise<LoadedCosmetic> {
  const fetchJson = deps.fetchJson ?? (async (url: string) => {
    const r = await fetch(url)
    if (!r.ok) throw new Error(`model failed to load: ${r.status}`)
    return r.json() as Promise<unknown>
  })
  const img = deps.loadImg ?? loadImage
  const [json, texture, glow] = await Promise.all([fetchJson(src.model), img(src.texture), src.glow ? img(src.glow) : Promise.resolve(null)])
  const size = (i: CosmeticImage | null) => (i ? { width: 'naturalWidth' in i ? i.naturalWidth : i.width, height: 'naturalHeight' in i ? i.naturalHeight : i.height } : null)
  const model = parseModel(json, { texture: size(texture), glow: size(glow) })
  return { model, images: { texture, glow: model.glow ? glow : null } }
}

// ---------------------------------------------------------------- skinview3d: Anbringen, Kamera, Licht

/** Blickrichtungen der Studio-Werkbank (Vektor von Ziel zur Kamera). */
export const VIEWS = {
  front: [0, 0.22, 1],
  three: [0.78, 0.5, 0.95],
  side: [1, 0.2, 0.02],
  back: [-0.35, 0.3, -1],
  top: [0.001, 1, 0.42],
} as const satisfies Record<string, readonly [number, number, number]>

export type ViewName = keyof typeof VIEWS

/**
 * Kamera wie die Studio-Werkbank auf den Kopf richten: hohe Teile (Zylinder, Heiligenschein) ganz ins Bild,
 * Ziel und Abstand nach Modellhöhe. Setzt Spieler-Drehung zurück. Werkbank: fov 32.
 */
export function frameHead(THREE: Three, viewer: SkinViewer, model: CosmeticModel | null, view: ViewName = 'three', zoom = 1): void {
  viewer.playerWrapper.rotation.y = 0
  viewer.playerObject.rotation.y = 0
  viewer.playerObject.updateMatrixWorld(true)
  const neck = new THREE.Vector3()
  viewer.playerObject.skin.head.getWorldPosition(neck)
  const top = Math.max(12.5, ...(model?.cubes ?? []).map((c) => c.to[1]))
  const lift = Math.max(0, (top - 12.5) / 2)
  const target = neck.clone().add(new THREE.Vector3(0, 8 + lift, 0))
  const dist = 34 * (1 + lift / 9) * zoom
  const dir = new THREE.Vector3(...VIEWS[view]).normalize()
  viewer.camera.position.copy(target).addScaledVector(dir, dist)
  viewer.controls.target.copy(target)
  viewer.controls.update()
}

/** Tag/Nacht wie die Werkbank: Nacht = Umgebungslicht 18 %, Kameralicht 20 %, Modell-Licht 0,2 (Leuchten bleibt). */
export function applyDayNight(viewer: SkinViewer, cos: CosmeticInstance | null, night: boolean): void {
  viewer.globalLight.intensity = 3 * (night ? 0.18 : 1)
  viewer.cameraLight.intensity = 0.6 * (night ? 0.2 : 1)
  cos?.setLight(night ? 0.2 : 1)
}

export interface MountOptions {
  animate?: boolean
  glow?: boolean
  night?: boolean
  /** Feste Zeit (ms) statt Wanduhr – für Standbilder/Tests. */
  fixedTime?: number | null
}

export interface MountedCosmetic {
  readonly cosmetic: CosmeticInstance
  set(opts: Partial<MountOptions>): void
  /** Abnehmen + aufräumen (Viewer bleibt bestehen). */
  dispose(): void
}

/**
 * Hängt ein Modell an den Kopf eines skinview3d-Spielers und hält es mit einer eigenen Bild-Schleife
 * aktuell (Wanduhr wie im Spiel: alle Clients zeigen dieselbe Phase). Animation aus = Ruhepose,
 * Leuchten steht – wie die Werkbank.
 */
export function mountOnSkinViewer(THREE: Three, viewer: SkinViewer, loaded: LoadedCosmetic, opts: MountOptions = {}): MountedCosmetic {
  const cos = createCosmetic(THREE, loaded.model, loaded.images)
  viewer.playerObject.skin.head.add(cos.root)
  const state: Required<MountOptions> = { animate: true, glow: true, night: false, fixedTime: null, ...opts }
  applyDayNight(viewer, cos, state.night)
  let frozenAt = state.fixedTime ?? Date.now()
  let raf = 0
  let alive = true
  const now = () => {
    if (state.fixedTime != null) return state.fixedTime
    if (!state.animate) return frozenAt
    frozenAt = Date.now()
    return frozenAt
  }
  const tick = () => {
    if (!alive) return
    cos.update(now(), { animate: state.animate || state.fixedTime != null, glow: state.glow, camera: viewer.camera })
    raf = requestAnimationFrame(tick)
  }
  tick()
  return {
    cosmetic: cos,
    set(next) {
      Object.assign(state, next)
      if (next.night !== undefined) applyDayNight(viewer, cos, state.night)
    },
    dispose() {
      alive = false
      cancelAnimationFrame(raf)
      cos.dispose()
    },
  }
}
