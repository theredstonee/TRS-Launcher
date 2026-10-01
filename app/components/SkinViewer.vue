<script setup lang="ts">
import type { SkinVariant } from '~/types'
import type { CosmeticInstance } from '~/utils/cosmetic-v2/view'
import { modelTop, validateModel, type CosmeticModel, type ViewerCosmetic } from '~/utils/cosmetic-v2/format'

// 3D-Vorschau des Skins (skinview3d, mitgeliefert â€“ kein CDN, damit die CSP
// eng bleibt). Texturen kommen als Data-URL aus dem Kern; dadurch darf WebGL
// sie ohne CORS-Ausnahme lesen. Kopf-Kosmetik (v2) hÃ¤ngt in DERSELBEN Szene am
// Kopf des Spielers â€“ eine WebGL-Szene fÃ¼r Skin, Umhang und Kosmetik.
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
    /** Kopf-Kosmetik (v2) am Kopf; Animation und Leuchten laufen zur Wanduhr wie im Spiel. */
    cosmetic?: ViewerCosmetic | null
    /** Begleiter (slot companion), zusätzlich zum Hut, dieselbe Pipeline. */
    companion?: ViewerCosmetic | null
    /** Nacht: Licht gedimmt, Leuchten bleibt voll hell. */
    night?: boolean
    /** Kamera: ganzer Spieler oder Kopf + Schultern (fÃ¼r Kopf-Kosmetik). */
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
    // Vor jedem Bild: Kosmetik zur Wanduhr stellen (Pose, Streifen, HÃ¶fe) und Kamera nachfÃ¼hren.
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
    console.error('3D-Vorschau nicht verfÃ¼gbar', e)
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

/** Streifen-Textur: aktuellen Frame auf eine eigene Leinwand kopieren und als Umhang setzen. */
async function applyAnimatedCape(src: string, frames: number, frameTime: number) {
  const token = ++capeToken
  const img = await loadImage(src)
  if (token !== capeToken || !viewer) return
  const width = img.naturalWidth
  const frameHeight = Math.floor(img.naturalHeight / frames)
  if (!width || !frameHeight) return
  const frame = document.createElement('canvas')
  frame.width = width
  frame.height = frameHeight
  const ctx = frame.getContext('2d')
  if (!ctx) return
  let shown = -1
  const draw = () => {
    if (!viewer || token !== capeToken) return
    const f = trsFrameIndex(Date.now(), frames, frameTime)
    if (f === shown) return
    shown = f
    ctx.clearRect(0, 0, width, frameHeight)
    ctx.drawImage(img, 0, f * frameHeight, width, frameHeight, 0, 0, width, frameHeight)
    void viewer.loadCape(frame)
  }
  draw()
  // KÃ¼rzer als die Frame-Dauer abtasten, damit der Wechsel zur Wanduhr passt.
  capeTimer = setInterval(draw, Math.max(20, Math.min(frameTime / 2, 250)))
}

async function applySkin() {
  if (!viewer) return
  if (props.skin) await viewer.loadSkin(props.skin, { model: model.value })
  else viewer.resetSkin()
  stopCapeAnimation()
  capeToken++
  if (props.cape && props.capeFrames > 1 && props.capeFrameTime) {
    try {
      await applyAnimatedCape(props.cape, props.capeFrames, props.capeFrameTime)
    } catch (e) {
      console.warn(e)
      viewer.resetCape()
    }
  } else if (props.cape) await viewer.loadCape(props.cape)
  else viewer.resetCape()
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
/** Laufender Kamera-Schwenk (Ziel + Position), weich Ã¼ber `CAMERA_MS`. */
let tween: { from: [Vec, Vec]; to: [Vec, Vec]; start: number } | null = null
const CAMERA_MS = 450
/** SchrÃ¤g von vorne-oben wie die Karten der Studio-Werkbank (â€žthreeâ€œ). */
const HEAD_DIR = normalize({ x: 0.78, y: 0.5, z: 0.95 })

function normalize(v: Vec): Vec {
  const l = Math.hypot(v.x, v.y, v.z) || 1
  return { x: v.x / l, y: v.y / l, z: v.z / l }
}

/**
 * Kamera-Ziel fÃ¼r den Fokus: Kopf + Schultern (mit Platz nach oben fÃ¼r hohe Teile) oder ganzer Spieler
 * samt Kopf-Kosmetik. `dir` = Blickrichtung (vom Ziel zur Kamera), damit die Drehung des Nutzers bleibt.
 */
function focusGoal(dir: Vec): [Vec, Vec] | null {
  if (!viewer) return null
  const fov = (viewer.camera.fov * Math.PI) / 180
  // skinview3d: Spieler-Mitte y = 0, FÃ¼ÃŸe âˆ’16, Nacken +8; Kosmetik-Koordinaten beginnen am Nacken.
  const neck = 8
  let target: Vec
  let distance: number
  if (props.focus === 'head') {
    const bottom = neck - 9
    const top = neck + cameraTop() + 1.5
    target = { x: 0, y: (bottom + top) / 2, z: 0 }
    distance = ((top - bottom) / 2 / Math.tan(fov / 2)) * 1.4
  } else {
    // Standard von skinview3d (passt fÃ¼r 16 + 16 Einheiten), bei hohen Teilen entsprechend weiter weg.
    const bottom = -16
    const top = Math.max(16, neck + cameraTop() + 1)
    target = { x: 0, y: (bottom + top) / 2, z: 0 }
    distance = (4.5 + 16.5 / Math.tan(fov / 2) / viewer.zoom) * ((top - bottom) / 32)
  }
  return [target, { x: target.x + dir.x * distance, y: target.y + dir.y * distance, z: target.z + dir.z * distance }]
}

let lastFocus: string | null = null
/** Kamera zum Fokus schwenken â€“ nur wenn sich Fokus oder ModellhÃ¶he geÃ¤ndert haben (sonst bleibt alles, wie der Nutzer es gedreht hat). */
function applyFocus(instant = false) {
  if (!viewer) return
  const key = `${props.focus}:${cameraTop()}`
  if (key === lastFocus) return
  const enteringHead = props.focus === 'head' && !lastFocus?.startsWith('head')
  lastFocus = key
  const target = viewer.controls.target
  const position = viewer.camera.position
  // Beim Wechsel auf den Kopf schrÃ¤g von vorne-oben wie die Karten, sonst die aktuelle Blickrichtung
  // (wÃ¤hrend eines Schwenks die, auf die er zulÃ¤uft).
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

watch(() => [props.skin, props.cape, props.variant, props.capeFrames, props.capeFrameTime], () => void applySkin())
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
    <div v-if="failed" class="absolute inset-0 grid place-items-center px-4 text-center text-sm text-base-400">
      {{ t('skins.viewer.failed') }}
    </div>
  </div>
</template>
