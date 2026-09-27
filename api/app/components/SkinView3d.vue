<script setup lang="ts">
import type { SkinViewer } from 'skinview3d'

// Echter Minecraft-Skin als drehbare 3D-Figur (skinview3d, mitgebündelt). Browser erlauben nur wenige
// WebGL-Flächen gleichzeitig → die Figur entsteht erst, wenn die Karte in Sichtweite kommt, und wird beim
// Verlassen wieder abgebaut (dazwischen der große Kopf). Mit „weniger Bewegung“ dreht sie sich nicht von selbst.
const props = withDefaults(defineProps<{
  uuid: string
  name: string
  skin: { url: string | null, model: 'classic' | 'slim' } | null
  cape?: { url: string, frames: number } | null
  height?: number
  label?: string
}>(), { cape: null, height: 260, label: '' })

const SKIN_URL = /^https:\/\/textures\.minecraft\.net\/texture\/[0-9a-f]{1,128}$/
const box = shallowRef<HTMLElement | null>(null)
const canvas = shallowRef<HTMLCanvasElement | null>(null)
const active = ref(false)
const ready = ref(false)
const failed = ref(false)
let viewer: SkinViewer | null = null
let observer: IntersectionObserver | null = null
let resize: ResizeObserver | null = null
let token = 0

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.referrerPolicy = 'no-referrer'
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('texture failed to load'))
    img.src = src
  })
}

/** Erster Frame eines animierten Umhangs (Streifen untereinander). */
function firstFrame(img: HTMLImageElement, frames: number): HTMLCanvasElement | HTMLImageElement {
  if (frames <= 1) return img
  const c = document.createElement('canvas')
  c.width = img.naturalWidth
  c.height = Math.floor(img.naturalHeight / frames)
  c.getContext('2d')?.drawImage(img, 0, 0, c.width, c.height, 0, 0, c.width, c.height)
  return c
}

async function start() {
  if (viewer || !canvas.value) return
  const mine = ++token
  try {
    const { SkinViewer, IdleAnimation } = await import('skinview3d')
    if (mine !== token || !canvas.value) return
    const calm = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const v = new SkinViewer({
      canvas: canvas.value,
      width: box.value?.clientWidth ?? 220,
      height: props.height,
      zoom: 0.78,
    })
    viewer = v
    v.controls.enablePan = false
    v.controls.enableZoom = false
    v.globalLight.intensity = 2.6
    v.cameraLight.intensity = 0.7
    v.playerWrapper.rotation.y = -0.45
    v.autoRotate = !calm
    v.autoRotateSpeed = 0.55
    if (!calm) v.animation = new IdleAnimation()
    const url = props.skin?.url && SKIN_URL.test(props.skin.url) ? props.skin.url : null
    const model = props.skin?.model === 'slim' ? 'slim' : 'default'
    if (url) await v.loadSkin(await loadImage(url), { model })
    else v.loadSkin(mannequinSkin(), { model: 'default' })
    if (props.cape?.url && props.cape.url.startsWith('/v1/capes/')) {
      try {
        v.loadCape(firstFrame(await loadImage(props.cape.url), props.cape.frames))
      } catch {
        // Ohne Umhang weiter.
      }
    }
    if (mine !== token) return
    resize = new ResizeObserver(() => {
      if (viewer && box.value) viewer.width = box.value.clientWidth
    })
    if (box.value) resize.observe(box.value)
    ready.value = true
  } catch (e) {
    console.error('3D skin unavailable', e)
    failed.value = true
    stop()
  }
}

function stop() {
  token++
  resize?.disconnect()
  resize = null
  viewer?.dispose()
  viewer = null
  ready.value = false
}

onMounted(() => {
  if (!box.value) return
  observer = new IntersectionObserver((entries) => {
    const visible = entries.some((e) => e.isIntersecting)
    active.value = visible
    if (visible) void nextTick(start)
    else stop()
  }, { rootMargin: '200px 0px' })
  observer.observe(box.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  stop()
})
</script>

<template>
  <div ref="box" class="skin3d relative w-full" :style="{ height: `${height}px` }" role="img" :aria-label="label || name">
    <canvas v-if="active && !failed" ref="canvas" class="size-full cursor-grab touch-pan-y active:cursor-grabbing" :class="{ 'opacity-0': !ready }" aria-hidden="true" />
    <div v-if="!ready" class="absolute inset-0 grid place-items-center" aria-hidden="true">
      <PlayerHead :uuid="uuid" :name="name" :skin="skin?.url ?? null" :size="Math.round(height * 0.34)" />
    </div>
  </div>
</template>

<style scoped>
.skin3d canvas {
  transition: opacity 0.35s ease;
}
</style>
