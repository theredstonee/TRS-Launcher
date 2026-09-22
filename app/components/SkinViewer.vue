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
  }>(),
  { cape: null, variant: 'classic', animation: 'walk', height: 340 },
)

type Viewer = import('skinview3d').SkinViewer
const canvas = ref<HTMLCanvasElement | null>(null)
const box = ref<HTMLElement | null>(null)
const failed = ref(false)
let viewer: Viewer | null = null
let observer: ResizeObserver | null = null

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

async function applySkin() {
  if (!viewer) return
  if (props.skin) await viewer.loadSkin(props.skin, { model: model.value })
  else viewer.resetSkin()
  if (props.cape) await viewer.loadCape(props.cape)
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
  observer?.disconnect()
  viewer?.dispose()
  viewer = null
})

watch(() => [props.skin, props.cape, props.variant], () => void applySkin())
watch(() => props.animation, (kind) => void setAnimation(kind))
watch(() => props.height, resize)
</script>

<template>
  <div ref="box" class="relative w-full" :style="{ height: `${height}px` }">
    <canvas ref="canvas" class="size-full cursor-grab active:cursor-grabbing" aria-label="3D-Vorschau des Skins" />
    <div v-if="failed" class="absolute inset-0 grid place-items-center px-4 text-center text-sm text-base-400">
      Die 3D-Vorschau konnte nicht gestartet werden. Deine Grafiktreiber unterstützen vermutlich kein WebGL.
    </div>
  </div>
</template>
