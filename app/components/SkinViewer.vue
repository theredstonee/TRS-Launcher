<script setup lang="ts">
import type { SkinVariant } from '~/types'
import type { CosmeticInstance } from '~/utils/cosmetic-v2/view'
import {
  modelTop,
  petSlots,
  petTimeOffset,
  validateModel,
  type CosmeticModel,
  type CosmeticSlot as CosmeticSlotId,
  type OptionChoices,
  type PetState,
  type ViewerCosmetic,
  type WingState,
} from '~/utils/cosmetic-v2/format'
import { capeLooksSmooth } from '~/utils/capeHd'

// 3D-Vorschau des Skins (skinview3d, mitgeliefert – kein CDN, damit die CSP
// eng bleibt). Texturen kommen als Data-URL aus dem Kern; dadurch darf WebGL
// sie ohne CORS-Ausnahme lesen. Kopf-Kosmetik (v2) hängt in DERSELBEN Szene am
// Kopf des Spielers – eine WebGL-Szene für Skin, Umhang und Kosmetik.
const props = withDefaults(
  defineProps<{
    skin: string | null
    cape?: string | null
    variant?: SkinVariant
    animation?: 'walk' | 'idle' | 'none'
    height?: number
    /**
     * Animierter Umhang (TRS): Die Textur ist ein senkrechter Streifen mit
     * `frames` Bildern; gezeigt wird Frame floor(jetzt / frameTimeMs) % frames.
     */
    capeFrames?: number
    capeFrameTime?: number | null
    /**
     * Umhang als fertige Leinwände (Editor): je Takt eine, mit `capeFrameTime`
     * animiert, sonst steht `capeFrame`. Spart das Kodieren als PNG bei jeder Änderung.
     */
    capeCanvases?: HTMLCanvasElement[] | null
    capeFrame?: number
    /**
     * Umhang glatt filtern (linear + Mipmaps, wie HD-Umhänge im TRS Client) statt
     * scharfer Pixel. `null` = automatisch wie im Spiel: größer als 64×32 und nicht
     * aus einfarbigen k×k-Blöcken → glatt.
     */
    capeSmooth?: boolean | null
    /** Kopf-Kosmetik (v2) am Kopf; Animation und Leuchten laufen zur Wanduhr wie im Spiel. */
    cosmetic?: ViewerCosmetic | null
    /** Begleiter (slot companion), zusätzlich zum Hut, dieselbe Pipeline. */
    companion?: ViewerCosmetic | null
    /** Flügel (slot wings, `attach: body`): hängen am Rumpf, Ursprung am Nacken. */
    wings?: ViewerCosmetic | null
    /** Zustand für die Zustands-Animationen der Flügel (idle/air/sneak); sneak neigt den Rumpf. */
    wingState?: WingState
    /** Begleiter am Boden (slot pet, `attach: ground`): steht neben dem Spieler auf dem Boden. */
    pet?: ViewerCosmetic | null
    /** So viele Begleiter am Boden (1…4, vom Server), versetzt nebeneinander. */
    petCount?: number
    /** Zustand der Begleiter am Boden (idle/walk/slide/swim/air). */
    petState?: PetState
    /** Spieler-UUID für den Zeitversatz je Begleiter (sonst laufen alle im Gleichschritt). */
    petSeed?: string
    /** Wahl der abschaltbaren Teile je Fach (Anhang D), z. B. `{ hat: { beard: false } }`. */
    cosmeticOptions?: Partial<Record<CosmeticSlotId, OptionChoices>> | null
    /** Nacht: Licht gedimmt, Leuchten bleibt voll hell. */
    night?: boolean
    /** Kamera: ganzer Spieler, Kopf + Schultern (für Kopf-Kosmetik) oder der Umhang von hinten (Umhang-Werkstatt). */
    focus?: 'body' | 'head' | 'cape'
    /** Hochzählen setzt die Kamera auf die Standardansicht zurück. */
    resetTick?: number
  }>(),
  {
    cape: null,
    variant: 'classic',
    animation: 'walk',
    height: 340,
    capeFrames: 1,
    capeFrameTime: null,
    capeCanvases: null,
    capeFrame: 0,
    capeSmooth: null,
    cosmetic: null,
    companion: null,
    wings: null,
    wingState: 'idle',
    pet: null,
    petCount: 1,
    petState: 'idle',
    petSeed: '',
    cosmeticOptions: null,
    night: false,
    focus: 'body',
    resetTick: 0,
  },
)
const emit = defineEmits<{ cosmeticError: [] }>()

type Viewer = import('skinview3d').SkinViewer
const canvas = ref<HTMLCanvasElement | null>(null)
const box = ref<HTMLElement | null>(null)
const failed = ref(false)
let viewer: Viewer | null = null
let observer: ResizeObserver | null = null
let capeTimer: ReturnType<typeof setInterval> | null = null
let capeToken = 0

/** Grundlicht wie bisher; nachts wie in der Studio-Werkbank gedimmt (Faktoren 0,18 / 0,2). */
const LIGHT = { global: 2.6, camera: 0.7 }
const NIGHT = { global: 0.18, camera: 0.2, cosmetic: 0.2 }

/** Armbreite: `slim` = Alex, `default` = Steve. */
const model = computed<'slim' | 'default'>(() => (props.variant === 'slim' ? 'slim' : 'default'))

async function setAnimation(kind: 'walk' | 'idle' | 'none') {
  if (!viewer) return
  const { IdleAnimation, WalkingAnimation } = await import('skinview3d')
  if (kind === 'none') {
    viewer.animation = null
    return
  }
  const animation = kind === 'walk' ? new WalkingAnimation() : new IdleAnimation()
  animation.speed = kind === 'walk' ? 0.7 : 1
  viewer.animation = animation
}

async function build() {
  if (!canvas.value || viewer) return
  try {
    const [{ SkinViewer }, three] = await Promise.all([import('skinview3d'), import('three')])
    threeFilters = three
    viewer = new SkinViewer({
      canvas: canvas.value,
      width: box.value?.clientWidth ?? 320,
      height: props.height,
      zoom: 0.78,
    })
    viewer.controls.enablePan = false
    viewer.controls.enableZoom = true
    // Vor jedem Bild: Kosmetik zur Wanduhr stellen (Pose, Streifen, Höfe) und Kamera nachführen.
    const render = viewer.render.bind(viewer)
    viewer.render = () => {
      beforeRender()
      render()
    }
    applyLight()
    await applySkin()
    await setAnimation(props.animation)
    applyCosmetics()
    applyFocus(true)
  } catch (e) {
    console.error('3D-Vorschau nicht verfügbar', e)
    failed.value = true
  }
}

function stopCapeAnimation() {
  if (capeTimer) clearInterval(capeTimer)
  capeTimer = null
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('Textur konnte nicht geladen werden'))
    img.src = src
  })
}

/** Streifen-Textur in Leinwände je Frame zerlegen (einmal, danach nur noch umschalten). */
async function splitCape(src: string, frames: number): Promise<HTMLCanvasElement[]> {
  const img = await loadImage(src)
  const width = img.naturalWidth
  const frameHeight = Math.floor(img.naturalHeight / Math.max(1, frames))
  if (!width || !frameHeight) return []
  return Array.from({ length: Math.max(1, frames) }, (_, f) => {
    const c = document.createElement('canvas')
    c.width = width
    c.height = frameHeight
    c.getContext('2d')?.drawImage(img, 0, f * frameHeight, width, frameHeight, 0, 0, width, frameHeight)
    return c
  })
}

/** Glatt oder scharf? Automatisch einmal je Leinwand bestimmt (Prüfung wie im TRS Client). */
const smoothCache = new WeakMap<HTMLCanvasElement, boolean>()
function capeSmoothFor(source: HTMLCanvasElement): boolean {
  if (props.capeSmooth !== null) return props.capeSmooth
  let smooth = smoothCache.get(source)
  if (smooth === undefined) {
    smooth = false
    try {
      const data = source.getContext('2d', { willReadFrequently: true })?.getImageData(0, 0, source.width, source.height)
      if (data) smooth = capeLooksSmooth({ width: data.width, height: data.height, data: data.data })
    } catch {
      smooth = false
    }
    smoothCache.set(source, smooth)
  }
  return smooth
}

let threeFilters: typeof import('three') | null = null
/**
 * Filter der gerade geladenen Umhang-Textur: HD glatt (linear, Mipmaps, anisotrop –
 * kein Flimmern beim Drehen), Pixel-Art scharf (skinview3d-Standard).
 */
function applyCapeFilter(smooth: boolean) {
  if (!viewer || !threeFilters || !smooth) return
  // Dieselbe Textur hängt an Umhang und Elytra (skinview3d legt sie bei jedem Laden neu an).
  const tex = viewer.playerObject.cape.map
  if (!tex) return
  tex.magFilter = threeFilters.LinearFilter
  tex.minFilter = threeFilters.LinearMipmapLinearFilter
  tex.generateMipmaps = true
  tex.anisotropy = viewer.renderer.capabilities.getMaxAnisotropy()
  tex.needsUpdate = true
}

/** Umhang aus einer Leinwand laden und filtern. */
function loadCapeCanvas(canvas: HTMLCanvasElement) {
  if (!viewer) return
  viewer.loadCape(canvas, { backEquipment: 'cape' })
  applyCapeFilter(capeSmoothFor(canvas))
}

/** Leinwand je Frame als Umhang (animiert zur Wanduhr oder fester Frame). */
function applyCanvasCape(canvases: HTMLCanvasElement[], frameTime: number | null) {
  const token = capeToken
  let shown: HTMLCanvasElement | null = null
  const draw = () => {
    if (!viewer || token !== capeToken) return
    const f = frameTime && canvases.length > 1 ? trsFrameIndex(Date.now(), canvases.length, frameTime) : Math.min(props.capeFrame, canvases.length - 1)
    const canvas = canvases[Math.max(0, f)]
    if (!canvas || canvas === shown) return
    shown = canvas
    loadCapeCanvas(canvas)
  }
  draw()
  // Kürzer als die Frame-Dauer abtasten, damit der Wechsel zur Wanduhr passt.
  if (frameTime && canvases.length > 1) capeTimer = setInterval(draw, Math.max(20, Math.min(frameTime / 2, 250)))
}

async function applyCape() {
  if (!viewer) return
  stopCapeAnimation()
  const token = ++capeToken
  if (props.capeCanvases?.length) {
    applyCanvasCape(props.capeCanvases, props.capeFrameTime)
    return
  }
  if (!props.cape) {
    viewer.resetCape()
    return
  }
  const animated = props.capeFrames > 1 && !!props.capeFrameTime
  try {
    // Auch ein einzelnes Bild als Leinwand: so lässt sich prüfen, ob es glatt gefiltert werden soll.
    // Streifen ohne Tempo zeigen den Frame `capeFrame` statt des ganzen Streifens.
    const canvases = await splitCape(props.cape, props.capeFrames)
    if (token !== capeToken || !viewer) return
    if (canvases.length) applyCanvasCape(canvases, animated ? props.capeFrameTime : null)
    else viewer.resetCape()
  } catch (e) {
    console.warn(e)
    viewer?.resetCape()
  }
}

async function applySkin() {
  if (!viewer) return
  if (props.skin) await viewer.loadSkin(props.skin, { model: model.value })
  else viewer.resetSkin()
  await applyCape()
}

// --- Kopf-Kosmetik (v2) + Begleiter -----------------------------------------------------------

interface CosmeticSlot {
  id: CosmeticSlotId
  inst: CosmeticInstance | null
  top: number
  token: number
  /** Ohne Modell: Kamera-Oberkante (Hut 8 = Nacken, Begleiter 0). */
  emptyTop: number
}

const hatSlot: CosmeticSlot = { id: 'hat', inst: null, top: 8, token: 0, emptyTop: 8 }
const companionSlot: CosmeticSlot = { id: 'companion', inst: null, top: 0, token: 0, emptyTop: 0 }
/** Flügel am Rumpf (Kamera: zählen nur, wenn sie über den Nacken ragen). */
const wingsSlot: CosmeticSlot = { id: 'wings', inst: null, top: 0, token: 0, emptyTop: 0 }

/** Begleiter am Boden: bis zu 4 Exemplare desselben Modells, je mit eigenem Zeitversatz. */
const petSlot = { list: [] as { inst: CosmeticInstance; offset: number }[], token: 0 }

/** Oberkante für die Kamera: Hut und Begleiter zusammen, mindestens der Nacken. */
function cameraTop(): number {
  const hat = hatSlot.inst ? hatSlot.top : hatSlot.emptyTop
  const buddy = companionSlot.inst ? companionSlot.top : companionSlot.emptyTop
  const wings = wingsSlot.inst ? wingsSlot.top : wingsSlot.emptyTop
  return Math.max(hat, buddy, wings)
}

async function applySlot(slot: CosmeticSlot, data: ViewerCosmetic | null | undefined) {
  const token = ++slot.token
  if (!viewer) return
  if (!data) {
    slot.inst?.dispose()
    slot.inst = null
    slot.top = slot.emptyTop
    applyFocus()
    return
  }
  try {
    const [{ createCosmetic }, texture, glow] = await Promise.all([
      import('~/utils/cosmetic-v2/view'),
      loadImage(data.texture),
      data.glow ? loadImage(data.glow) : Promise.resolve(null),
    ])
    if (token !== slot.token || !viewer) return
    const hasGlow = !!glow && data.model.glow != null
    const size = (img: HTMLImageElement) => ({ width: img.naturalWidth, height: img.naturalHeight })
    const check = validateModel(data.model, { texture: size(texture), glow: hasGlow ? size(glow!) : null })
    if (!check.ok) throw new Error(`Kosmetik-Modell ungültig: ${check.errors.slice(0, 3).join('; ')}`)
    const next = createCosmetic(data.model as unknown as CosmeticModel, { texture, glow: hasGlow ? glow : null })
    slot.inst?.dispose()
    slot.inst = next
    next.setOptions(props.cosmeticOptions?.[slot.id])
    slot.top = modelTop(data.model as unknown as CosmeticModel)
    if (slot === wingsSlot) {
      // Rumpf von skinview3d: Mitte bei y = −6, also Nacken (Ursprung von attach: body) bei +6 im Rumpf.
      slot.top = Math.max(0, ...(data.model as unknown as CosmeticModel).cubes.map((c) => c.to[1]))
      next.root.position.y = 6
      viewer.playerObject.skin.body.add(next.root)
    } else viewer.playerObject.skin.head.add(next.root)
    applyLight()
    next.update(Date.now(), { camera: viewer.camera })
    applyFocus()
  } catch (e) {
    if (token !== slot.token) return
    console.warn(e)
    slot.inst?.dispose()
    slot.inst = null
    emit('cosmeticError')
  }
}

function clearPets() {
  for (const p of petSlot.list) p.inst.dispose()
  petSlot.list = []
}

/**
 * Begleiter am Boden (`attach: ground`) neben den Spieler stellen: Ursprung = Boden unter der Mitte,
 * +z = Laufrichtung. Mehrere (Creator-Stufe) stehen versetzt dahinter, jeder mit eigenem Zeitversatz.
 */
async function applyPets() {
  const token = ++petSlot.token
  if (!viewer) return
  const data = props.pet
  if (!data) {
    clearPets()
    applyFocus()
    return
  }
  try {
    const [{ createCosmetic }, texture, glow] = await Promise.all([
      import('~/utils/cosmetic-v2/view'),
      loadImage(data.texture),
      data.glow ? loadImage(data.glow) : Promise.resolve(null),
    ])
    if (token !== petSlot.token || !viewer) return
    const hasGlow = !!glow && data.model.glow != null
    const size = (img: HTMLImageElement) => ({ width: img.naturalWidth, height: img.naturalHeight })
    const check = validateModel(data.model, { texture: size(texture), glow: hasGlow ? size(glow!) : null })
    if (!check.ok) throw new Error(`Kosmetik-Modell ungültig: ${check.errors.slice(0, 3).join('; ')}`)
    const model = data.model as unknown as CosmeticModel
    if (model.attach !== 'ground') throw new Error('Begleiter am Boden braucht attach ground')
    clearPets()
    petSlot.list = petSlots(props.petCount).map((spot, i) => {
      const inst = createCosmetic(model, { texture, glow: hasGlow ? glow : null })
      inst.root.position.set(spot.x, spot.y, spot.z)
      inst.root.rotation.y = spot.yaw
      inst.setOptions(props.cosmeticOptions?.pet)
      viewer!.playerObject.add(inst.root)
      return { inst, offset: petTimeOffset(props.petSeed || model.id, i) }
    })
    applyLight()
    const now = Date.now()
    for (const p of petSlot.list) p.inst.update(now + p.offset, { camera: viewer.camera, state: props.petState })
    applyFocus()
  } catch (e) {
    if (token !== petSlot.token) return
    console.warn(e)
    clearPets()
    emit('cosmeticError')
  }
}

function applyCosmetics() {
  void applySlot(hatSlot, props.cosmetic)
  void applySlot(companionSlot, props.companion)
  void applySlot(wingsSlot, props.wings)
  void applyPets()
}

/** Gewählte Optionen (Bart an/aus …) auf alle gezeigten Teile anwenden. */
function applyOptions() {
  const o = props.cosmeticOptions
  hatSlot.inst?.setOptions(o?.hat)
  companionSlot.inst?.setOptions(o?.companion)
  wingsSlot.inst?.setOptions(o?.wings)
  for (const p of petSlot.list) p.inst.setOptions(o?.pet)
}

function applyLight() {
  if (!viewer) return
  viewer.globalLight.intensity = LIGHT.global * (props.night ? NIGHT.global : 1)
  viewer.cameraLight.intensity = LIGHT.camera * (props.night ? NIGHT.camera : 1)
  const light = props.night ? NIGHT.cosmetic : 1
  hatSlot.inst?.setLight(light)
  companionSlot.inst?.setLight(light)
  wingsSlot.inst?.setLight(light)
  for (const p of petSlot.list) p.inst.setLight(light)
}

// --- Kamera ------------------------------------------------------------------------------------

type Vec = { x: number; y: number; z: number }
/** Laufender Kamera-Schwenk (Ziel + Position), weich über `CAMERA_MS`. */
let tween: { from: [Vec, Vec]; to: [Vec, Vec]; start: number } | null = null
const CAMERA_MS = 450
/** Schräg von vorne-oben wie die Karten der Studio-Werkbank („three“). */
const HEAD_DIR = normalize({ x: 0.78, y: 0.5, z: 0.95 })
/** Umhang-Werkstatt: leicht schräg von hinten auf die Außenseite des Umhangs. */
const CAPE_DIR = normalize({ x: 0.35, y: 0.12, z: -1 })

function normalize(v: Vec): Vec {
  const l = Math.hypot(v.x, v.y, v.z) || 1
  return { x: v.x / l, y: v.y / l, z: v.z / l }
}

/**
 * Kamera-Ziel für den Fokus: Kopf + Schultern (mit Platz nach oben für hohe Teile) oder ganzer Spieler
 * samt Kopf-Kosmetik. `dir` = Blickrichtung (vom Ziel zur Kamera), damit die Drehung des Nutzers bleibt.
 */
function focusGoal(dir: Vec): [Vec, Vec] | null {
  if (!viewer) return null
  const fov = (viewer.camera.fov * Math.PI) / 180
  // skinview3d: Spieler-Mitte y = 0, Füße −16, Nacken +8; Kosmetik-Koordinaten beginnen am Nacken.
  const neck = 8
  let target: Vec
  let distance: number
  if (props.focus === 'head') {
    const bottom = neck - 9
    const top = neck + cameraTop() + 1.5
    target = { x: 0, y: (bottom + top) / 2, z: 0 }
    distance = ((top - bottom) / 2 / Math.tan(fov / 2)) * 1.4
  } else if (props.focus === 'cape') {
    // Umhang hängt vom Nacken (+8) 16 Einheiten nach unten, hinter dem Rücken; etwas Rand fürs Schwingen.
    const bottom = neck - 18
    const top = neck + 1.5
    target = { x: 0, y: (bottom + top) / 2, z: -2 }
    distance = ((top - bottom) / 2 / Math.tan(fov / 2)) * 1.15
  } else {
    // Standard von skinview3d (passt für 16 + 16 Einheiten), bei hohen Teilen entsprechend weiter weg.
    const bottom = -16
    const top = Math.max(16, neck + cameraTop() + 1)
    target = { x: 0, y: (bottom + top) / 2, z: 0 }
    distance = (4.5 + 16.5 / Math.tan(fov / 2) / viewer.zoom) * ((top - bottom) / 32)
    // Begleiter am Boden: Bild nach links (+x) erweitern, damit Spieler und Begleiter hineinpassen.
    const reach = petReach()
    if (reach > 8) {
      target.x = (reach - 8) / 2
      distance *= 1 + (reach - 8) / 64
    }
  }
  return [target, { x: target.x + dir.x * distance, y: target.y + dir.y * distance, z: target.z + dir.z * distance }]
}

/** Äußerste x-Kante der Begleiter am Boden (0 = keine). */
function petReach(): number {
  return petSlot.list.reduce((m, p) => Math.max(m, p.inst.root.position.x + 8), 0)
}

let lastFocus: string | null = null
/** Kamera zum Fokus schwenken – nur wenn sich Fokus oder Modellhöhe geändert haben (sonst bleibt alles, wie der Nutzer es gedreht hat). */
function applyFocus(instant = false) {
  if (!viewer) return
  const key = `${props.focus}:${cameraTop()}:${petReach()}`
  if (key === lastFocus) return
  const enteringHead = props.focus === 'head' && !lastFocus?.startsWith('head')
  const enteringCape = props.focus === 'cape' && !lastFocus?.startsWith('cape')
  lastFocus = key
  const target = viewer.controls.target
  const position = viewer.camera.position
  // Beim Wechsel auf den Kopf schräg von vorne-oben wie die Karten, sonst die aktuelle Blickrichtung
  // (während eines Schwenks die, auf die er zuläuft).
  const [aim, eye] = tween ? tween.to : [target, position]
  const dir = enteringHead ? HEAD_DIR : enteringCape ? CAPE_DIR : normalize({ x: eye.x - aim.x, y: eye.y - aim.y, z: eye.z - aim.z })
  const goal = focusGoal(dir)
  if (!goal) return
  if (instant) {
    target.set(goal[0].x, goal[0].y, goal[0].z)
    position.set(goal[1].x, goal[1].y, goal[1].z)
    viewer.controls.update()
    return
  }
  tween = { from: [{ ...target }, { ...position }], to: goal, start: performance.now() }
}

function stepCamera() {
  if (!viewer || !tween) return
  const u = Math.min(1, (performance.now() - tween.start) / CAMERA_MS)
  const s = u * u * (3 - 2 * u)
  const lerp = (a: Vec, b: Vec): [number, number, number] => [a.x + (b.x - a.x) * s, a.y + (b.y - a.y) * s, a.z + (b.z - a.z) * s]
  viewer.controls.target.set(...lerp(tween.from[0], tween.to[0]))
  viewer.camera.position.set(...lerp(tween.from[1], tween.to[1]))
  viewer.camera.lookAt(viewer.controls.target)
  if (u >= 1) {
    tween = null
    viewer.controls.update()
  }
}

/** Standardblick von skinview3d: gerade von vorne, Kamera auf der z-Achse. */
const BODY_DIR = normalize({ x: 0, y: 0, z: 1 })

/** Kamera auf die Standardansicht des aktuellen Fokus zurück (Drehung des Nutzers verwerfen). */
function resetCamera() {
  if (!viewer) return
  const goal = focusGoal(props.focus === 'head' ? HEAD_DIR : props.focus === 'cape' ? CAPE_DIR : BODY_DIR)
  if (!goal) return
  const target = viewer.controls.target
  const position = viewer.camera.position
  tween = { from: [{ ...target }, { ...position }], to: goal, start: performance.now() }
}

function beforeRender() {
  stepCamera()
  if (viewer) {
    const now = Date.now()
    hatSlot.inst?.update(now, { camera: viewer.camera })
    companionSlot.inst?.update(now, { camera: viewer.camera })
    wingsSlot.inst?.update(now, { camera: viewer.camera, state: props.wingState })
    for (const p of petSlot.list) p.inst.update(now + p.offset, { camera: viewer.camera, state: props.petState })
    leanForSneak()
  }
}

/** Schleich-Vorschau: Rumpf 0,5 rad um den Nacken nach vorn neigen (wie Vanilla, §7). */
const SNEAK_LEAN = 0.5
function leanForSneak() {
  const body = viewer?.playerObject.skin.body
  if (!body) return
  const lean = props.wingState === 'sneak' && !!wingsSlot.inst ? SNEAK_LEAN : 0
  if (lean === 0 && body.rotation.x === 0) return
  body.rotation.x = lean
  body.position.y = -6 * Math.cos(lean)
  body.position.z = -6 * Math.sin(lean)
}

function resize() {
  if (!viewer || !box.value) return
  viewer.width = box.value.clientWidth
  viewer.height = props.height
}

onMounted(async () => {
  await build()
  if (box.value) {
    observer = new ResizeObserver(resize)
    observer.observe(box.value)
  }
})

onBeforeUnmount(() => {
  stopCapeAnimation()
  capeToken++
  hatSlot.token++
  companionSlot.token++
  wingsSlot.token++
  petSlot.token++
  clearPets()
  hatSlot.inst?.dispose()
  companionSlot.inst?.dispose()
  wingsSlot.inst?.dispose()
  hatSlot.inst = null
  companionSlot.inst = null
  wingsSlot.inst = null
  observer?.disconnect()
  viewer?.dispose()
  viewer = null
})

watch(() => [props.skin, props.variant], () => void applySkin())
watch(() => [props.cape, props.capeFrames, props.capeFrameTime, props.capeCanvases, props.capeFrame, props.capeSmooth], () => void applyCape())
watch(() => props.animation, (kind) => void setAnimation(kind))
watch(() => props.height, resize)
watch(() => props.cosmetic, () => void applySlot(hatSlot, props.cosmetic))
watch(() => props.companion, () => void applySlot(companionSlot, props.companion))
watch(() => props.wings, () => void applySlot(wingsSlot, props.wings))
watch(() => [props.pet, props.petCount, props.petSeed], () => void applyPets())
watch(() => props.cosmeticOptions, applyOptions, { deep: true })
watch(() => props.night, applyLight)
watch(() => props.focus, () => applyFocus())
watch(
  () => props.resetTick,
  () => resetCamera(),
)
</script>

<template>
  <div ref="box" class="relative w-full" :style="{ height: `${height}px` }">
    <canvas ref="canvas" class="size-full cursor-grab active:cursor-grabbing" :aria-label="t('skins.viewer.label')" />
    <div v-if="failed" class="absolute inset-0 grid place-items-center px-4 text-center text-sm text-base-400">
      {{ t('skins.viewer.failed') }}
    </div>
  </div>
</template>
