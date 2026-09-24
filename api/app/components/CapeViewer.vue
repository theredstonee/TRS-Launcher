<script setup lang="ts">
import type { SkinViewer } from 'skinview3d'

// 3D-Vorschau eines Umhangs an einer Schaufensterpuppe (skinview3d, mitgebündelt – kein CDN).
const props = withDefaults(defineProps<{ cape: SiteCape | null, height?: number }>(), { height: 380 })

const canvas = shallowRef<HTMLCanvasElement | null>(null)
const box = shallowRef<HTMLElement | null>(null)
const failed = ref(false)
let viewer: SkinViewer | null = null
let observer: ResizeObserver | null = null
let capeTimer: ReturnType<typeof setInterval> | null = null
let token = 0

function stopAnimation() {
  if (capeTimer) clearInterval(capeTimer)
  capeTimer = null
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('cape texture failed to load'))
    img.src = src
  })
}

async function applyCape() {
  if (!viewer) return
  stopAnimation()
  const mine = ++token
  const cape = props.cape
  if (!cape) {
    viewer.resetCape()
    return
  }
  const img = await loadImage(cape.url)
  if (mine !== token || !viewer) return
  if (cape.frames <= 1 || !cape.frameTimeMs) {
    await viewer.loadCape(img)
    return
  }
  // Streifen: aktuellen Frame auf eine eigene Leinwand kopieren.
  const width = img.naturalWidth
  const frameHeight = Math.floor(img.naturalHeight / cape.frames)
  const frame = document.createElement('canvas')
  frame.width = width
  frame.height = frameHeight
  const ctx = frame.getContext('2d')
  if (!ctx) return
  let shown = -1
  const draw = () => {
    if (!viewer || mine !== token) return
    const f = trsFrameIndex(Date.now(), cape.frames, cape.frameTimeMs)
    if (f === shown) return
    shown = f
    ctx.clearRect(0, 0, width, frameHeight)
    ctx.drawImage(img, 0, f * frameHeight, width, frameHeight, 0, 0, width, frameHeight)
    void viewer.loadCape(frame)
  }
  draw()
  capeTimer = setInterval(draw, Math.max(20, Math.min(cape.frameTimeMs / 2, 250)))
}

onMounted(async () => {
  if (!canvas.value) return
  try {
    const { SkinViewer, WalkingAnimation } = await import('skinview3d')
    viewer = new SkinViewer({
      canvas: canvas.value,
      width: box.value?.clientWidth ?? 320,
      height: props.height,
      zoom: 0.62,
    })
    viewer.controls.enablePan = false
    viewer.controls.enableZoom = false
    viewer.globalLight.intensity = 2.6
    viewer.cameraLight.intensity = 0.7
    // Von schräg hinten – der Umhang ist die Hauptsache.
    viewer.playerWrapper.rotation.y = Math.PI * 0.8
    await viewer.loadSkin(mannequinSkin(), { model: 'default' })
    const walk = new WalkingAnimation()
    walk.speed = 0.55
    viewer.animation = walk
    await applyCape()
    observer = new ResizeObserver(() => {
      if (viewer && box.value) viewer.width = box.value.clientWidth
    })
    if (box.value) observer.observe(box.value)
  } catch (e) {
    console.error('3D preview unavailable', e)
    failed.value = true
  }
})

onBeforeUnmount(() => {
  stopAnimation()
  token++
  observer?.disconnect()
  viewer?.dispose()
  viewer = null
})

watch(() => props.cape?.id, () => void applyCape().catch(() => viewer?.resetCape()))
</script>

<template>
  <div ref="box" class="relative w-full" :style="{ height: `${height}px` }">
    <canvas ref="canvas" class="size-full cursor-grab active:cursor-grabbing" aria-hidden="true" />
    <div v-if="failed" class="absolute inset-0 grid place-items-center px-4 text-center text-sm text-base-400">
      WebGL?
    </div>
  </div>
</template>
