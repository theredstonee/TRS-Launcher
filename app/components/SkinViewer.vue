<script setup lang="ts">
import type { SkinVariant } from '~/types'
import type { CosmeticInstance } from '~/utils/cosmetic-v2/view'
import { modelTop, validateModel, type CosmeticModel, type ViewerCosmetic } from '~/utils/cosmetic-v2/format'

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
    /** Umschalter „Umhang | Elytra“ im Bild zeigen. */
    equipmentToggle?: boolean
    /** Kopf-Kosmetik (v2) am Kopf; Animation und Leuchten laufen zur Wanduhr wie im Spiel. */
    cosmetic?: ViewerCosmetic | null
    /** Begleiter (slot companion), zusätzlich zum Hut, dieselbe Pipeline. */
    companion?: ViewerCosmetic | null
    /** Nacht: Licht gedimmt, Leuchten bleibt voll hell. */
    night?: boolean
    /** Kamera: ganzer Spieler oder Kopf + Schultern (für Kopf-Kosmetik). */
    focus?: 'body' | 'head'
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
    equipmentToggle: false,
    cosmetic: null,
    companion: null,
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
/** Am Rücken: Umhang oder Elytra (Flügel aus der Außenseite des Umhangs). */
const equipment = ref<'cape' | 'elytra'>('cape')
const hasCape = computed(() => !!props.cape || !!props.capeCanvases?.length)
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
    const { SkinViewer } = await import('skinview3d')
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

/** Elytra: Flügel immer aus der Außenseite erzeugen (wie im TRS Client); je Leinwand einmal. */
const elytraCache = new WeakMap<HTMLCanvasElement, HTMLCanvasElement>()
function withElytra(canvas: HTMLCanvasElement): HTMLCanvasElement {
  const cached = elytraCache.get(canvas)
  if (cached) return cached
  const s = canvas.width / 64
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  if (!Number.isInteger(s) || s < 1 || canvas.height !== 32 * s || !ctx) return canvas
  const data = ctx.getImageData(0, 0, canvas.width, canvas.height)
  const filled = fillElytraFromFront({ width: data.width, height: data.height, data: data.data }, s)
  const out = document.createElement('canvas')
  out.width = canvas.width
  out.height = canvas.height
  out.getContext('2d')?.putImageData(new ImageData(new Uint8ClampedArray(filled.data), filled.width, filled.height), 0, 0)
  elytraCache.set(canvas, out)
  return out
}

/** Leinwand je Frame als Umhang bzw. Elytra (animiert zur Wanduhr oder fester Frame). */
function applyCanvasCape(canvases: HTMLCanvasElement[], frameTime: number | null) {
  const token = capeToken
  const back = equipment.value
  let shown: HTMLCanvasElement | null = null
  const draw = () => {
    if (!viewer || token !== capeToken) return
    const f = frameTime && canvases.length > 1 ? trsFrameIndex(Date.now(), canvases.length, frameTime) : Math.min(props.capeFrame, canvases.length - 1)
    const canvas = canvases[Math.max(0, f)]
    if (!canvas || canvas === shown) return
    shown = canvas
    viewer.loadCape(back === 'elytra' ? withElytra(canvas) : canvas, { backEquipment: back })
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
  if (!animated && equipment.value === 'cape') {
    await viewer.loadCape(props.cape, { backEquipment: 'cape' })
    return
  }
  try {
    const canvases = await splitCape(props.cape, animated ? props.capeFrames : 1)
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
  inst: CosmeticInstance | null
  top: number
  token: number
  /** Ohne Modell: Kamera-Oberkante (Hut 8 = Nacken, Begleiter 0). */
  emptyTop: number
}

const hatSlot: CosmeticSlot = { inst: null, top: 8, token: 0, emptyTop: 8 }
const companionSlot: CosmeticSlot = { inst: null, top: 0, token: 0, emptyTop: 0 }

/** Oberkante für die Kamera: Hut und Begleiter zusammen, mindestens der Nacken. */
function cameraTop(): number {
  const hat = hatSlot.inst ? hatSlot.top : hatSlot.emptyTop
  const buddy = companionSlot.inst ? companionSlot.top : companionSlot.emptyTop
  return Math.max(hat, buddy)
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
    slot.top = modelTop(data.model as unknown as CosmeticModel)
    viewer.playerObject.skin.head.add(next.root)
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

function applyCosmetics() {
  void applySlot(hatSlot, props.cosmetic)
  void applySlot(companionSlot, props.companion)
}

function applyLight() {
  if (!viewer) return
  viewer.globalLight.intensity = LIGHT.global * (props.night ? NIGHT.global : 1)
  viewer.cameraLight.intensity = LIGHT.camera * (props.night ? NIGHT.camera : 1)
  const light = props.night ? NIGHT.cosmetic : 1
  hatSlot.inst?.setLight(light)
  companionSlot.inst?.setLight(light)
}

// --- Kamera ------------------------------------------------------------------------------------

type Vec = { x: number; y: number; z: number }
/** Laufender Kamera-Schwenk (Ziel + Position), weich über `CAMERA_MS`. */
let tween: { from: [Vec, Vec]; to: [Vec, Vec]; start: number } | null = null
const CAMERA_MS = 450
/** Schräg von vorne-oben wie die Karten der Studio-Werkbank („three“). */
const HEAD_DIR = normalize({ x: 0.78, y: 0.5, z: 0.95 })

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
  } else {
    // Standard von skinview3d (passt für 16 + 16 Einheiten), bei hohen Teilen entsprechend weiter weg.
    const bottom = -16
    const top = Math.max(16, neck + cameraTop() + 1)
    target = { x: 0, y: (bottom + top) / 2, z: 0 }
    distance = (4.5 + 16.5 / Math.tan(fov / 2) / viewer.zoom) * ((top - bottom) / 32)
  }
  return [target, { x: target.x + dir.x * distance, y: target.y + dir.y * distance, z: target.z + dir.z * distance }]
}

let lastFocus: string | null = null
/** Kamera zum Fokus schwenken – nur wenn sich Fokus oder Modellhöhe geändert haben (sonst bleibt alles, wie der Nutzer es gedreht hat). */
function applyFocus(instant = false) {
  if (!viewer) return
  const key = `${props.focus}:${cameraTop()}`
  if (key === lastFocus) return
  const enteringHead = props.focus === 'head' && !lastFocus?.startsWith('head')
  lastFocus = key
  const target = viewer.controls.target
  const position = viewer.camera.position
  // Beim Wechsel auf den Kopf schräg von vorne-oben wie die Karten, sonst die aktuelle Blickrichtung
  // (während eines Schwenks die, auf die er zuläuft).
  const [aim, eye] = tween ? tween.to : [target, position]
  const dir = enteringHead ? HEAD_DIR : normalize({ x: eye.x - aim.x, y: eye.y - aim.y, z: eye.z - aim.z })
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
  const goal = focusGoal(props.focus === 'head' ? HEAD_DIR : BODY_DIR)
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
  }
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
  hatSlot.inst?.dispose()
  companionSlot.inst?.dispose()
  hatSlot.inst = null
  companionSlot.inst = null
  observer?.disconnect()
  viewer?.dispose()
  viewer = null
})

watch(() => [props.skin, props.variant], () => void applySkin())
watch(() => [props.cape, props.capeFrames, props.capeFrameTime, props.capeCanvases, props.capeFrame, equipment.value], () => void applyCape())
watch(() => props.animation, (kind) => void setAnimation(kind))
watch(() => props.height, resize)
watch(() => props.cosmetic, () => void applySlot(hatSlot, props.cosmetic))
watch(() => props.companion, () => void applySlot(companionSlot, props.companion))
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
    <div
      v-if="equipmentToggle && hasCape && !failed"
      class="absolute bottom-2 left-2 flex gap-0.5 rounded-md bg-black/45 p-0.5 text-[11px]"
      role="radiogroup"
      :aria-label="t('skins.viewer.equipment')"
      data-testid="viewer-equipment"
    >
      <button
        v-for="kind in (['cape', 'elytra'] as const)"
        :key="kind"
        type="button"
        class="rounded px-2 py-0.5 transition-colors"
        :class="equipment === kind ? 'bg-white/90 text-black' : 'text-white hover:bg-white/15'"
        role="radio"
        :aria-checked="equipment === kind"
        @click="equipment = kind"
      >
        {{ t(`skins.viewer.${kind}`) }}
      </button>
    </div>
    <div v-if="failed" class="absolute inset-0 grid place-items-center px-4 text-center text-sm text-base-400">
      {{ t('skins.viewer.failed') }}
    </div>
  </div>
</template>
