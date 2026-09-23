<script setup lang="ts">
// Vorderseite eines Umhangs als kleines Bild. TRS-Umhänge können animiert
// sein (senkrechter Streifen, Frame = floor(jetzt / frameTimeMs) % frames)
// und in höherer Auflösung (scale 1–4) vorliegen.
const props = withDefaults(
  defineProps<{
    texture: string | null
    scale?: number
    frames?: number
    frameTimeMs?: number | null
    /** Breite in CSS-Pixeln (Höhe ergibt sich aus 10:16). */
    width?: number
  }>(),
  { scale: 1, frames: 1, frameTimeMs: null, width: 30 },
)

const canvas = ref<HTMLCanvasElement | null>(null)
let image: HTMLImageElement | null = null
let timer: ReturnType<typeof setInterval> | null = null
let shown = -1

const height = computed(() => Math.round((props.width * 16) / 10))

function draw() {
  const el = canvas.value
  if (!el || !image) return
  const f = trsFrameIndex(Date.now(), props.frames, props.frameTimeMs)
  if (f === shown) return
  shown = f
  const s = props.scale
  const frameHeight = 32 * s
  el.width = 10 * s
  el.height = 16 * s
  const ctx = el.getContext('2d')
  if (!ctx) return
  ctx.imageSmoothingEnabled = false
  ctx.clearRect(0, 0, el.width, el.height)
  // Außenseite (von hinten gesehen): (1, 1, 10, 16) im 64×32-Raster.
  ctx.drawImage(image, s, f * frameHeight + s, 10 * s, 16 * s, 0, 0, 10 * s, 16 * s)
}

function stop() {
  if (timer) clearInterval(timer)
  timer = null
}

function load() {
  stop()
  shown = -1
  image = null
  if (!props.texture) return
  const img = new Image()
  img.onload = () => {
    image = img
    draw()
    if (props.frames > 1 && props.frameTimeMs) {
      timer = setInterval(() => {
        if (document.visibilityState === 'visible') draw()
      }, Math.max(40, Math.min(props.frameTimeMs / 2, 250)))
    }
  }
  img.src = props.texture
}

onMounted(load)
onBeforeUnmount(stop)
watch(() => [props.texture, props.scale, props.frames, props.frameTimeMs], load)
</script>

<template>
  <canvas
    v-if="texture"
    ref="canvas"
    class="rounded [image-rendering:pixelated]"
    :style="{ width: `${width}px`, height: `${height}px` }"
    aria-hidden="true"
  />
  <span v-else class="block rounded bg-base-800" :style="{ width: `${width}px`, height: `${height}px` }" />
</template>
