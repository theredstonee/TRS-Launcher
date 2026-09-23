<script setup lang="ts">
import type { SkinVariant } from '~/types'

// 3D-Vorschau des Skins (skinview3d, mitgeliefert – kein CDN, damit die CSP
// eng bleibt). Texturen kommen als Data-URL aus dem Kern; dadurch darf WebGL
// sie ohne CORS-Ausnahme lesen.
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
  }>(),
  { cape: null, variant: 'classic', animation: 'walk', height: 340, capeFrames: 1, capeFrameTime: null },
)

type Viewer = import('skinview3d').SkinViewer
const canvas = ref<HTMLCanvasElement | null>(null)
const box = ref<HTMLElement | null>(null)
const failed = ref(false)
let viewer: Viewer | null = null
let observer: ResizeObserver | null = null
let capeTimer: ReturnType<typeof setInterval> | null = null
let capeToken = 0

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
    viewer.globalLight.intensity = 2.6
    viewer.cameraLight.intensity = 0.7
    await applySkin()
    await setAnimation(props.animation)
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
    img.onerror = () => reject(new Error('Umhang-Textur konnte nicht geladen werden'))
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
  // Kürzer als die Frame-Dauer abtasten, damit der Wechsel zur Wanduhr passt.
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
  observer?.disconnect()
  viewer?.dispose()
  viewer = null
})

watch(() => [props.skin, props.cape, props.variant, props.capeFrames, props.capeFrameTime], () => void applySkin())
watch(() => props.animation, (kind) => void setAnimation(kind))
watch(() => props.height, resize)
</script>

<template>
  <div ref="box" class="relative w-full" :style="{ height: `${height}px` }">
    <canvas ref="canvas" class="size-full cursor-grab active:cursor-grabbing" :aria-label="t('skins.viewer.label')" />
    <div v-if="failed" class="absolute inset-0 grid place-items-center px-4 text-center text-sm text-base-400">
      {{ t('skins.viewer.failed') }}
    </div>
  </div>
</template>
