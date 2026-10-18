<script setup lang="ts">
// Vorderseite eines Umhangs als kleines Bild. TRS-Umhänge können animiert
// sein (senkrechter Streifen, Frame = floor(jetzt / frameTimeMs) % frames)
// und in höherer Auflösung (scale 1–16) vorliegen. Große HD-Umhänge werden im
// kleinen Bild gemittelt verkleinert (kein Pixel-Flimmern), kleine bleiben scharf.
const props = withDefaults(
  defineProps<{
    texture: string | null
    scale?: number
    frames?: number
    frameTimeMs?: number | null
    /** Breite in CSS-Pixeln (Höhe ergibt sich aus 10:16). */
    width?: number
    /** Auch beim Vergrößern glatt (HD-Umhänge); sonst scharfe Pixel. Verkleinert wird immer gemittelt. */
    smooth?: boolean
  }>(),
  { scale: 1, frames: 1, frameTimeMs: null, width: 30, smooth: false },
)

const canvas = ref<HTMLCanvasElement | null>(null)
let image: HTMLImageElement | null = null
let timer: ReturnType<typeof setInterval> | null = null
let shown = -1

const height = computed(() => Math.round((props.width * 16) / 10))
/** Wird die Außenseite verkleinert gezeigt (HD-Umhang im kleinen Bild)? Dann gemittelt statt Pixel auszulassen. */
const shrinking = ref(false)

function draw() {
  const el = canvas.value
  if (!el || !image) return
  const f = trsFrameIndex(Date.now(), props.frames, props.frameTimeMs)
  if (f === shown) return
  shown = f
  const s = props.scale
  const frameHeight = 32 * s
  // Ziel in Gerätepixeln: größer als die Quelle → Quelle 1:1 (CSS vergrößert), sonst glatt verkleinern.
  const target = Math.round(props.width * (window.devicePixelRatio || 1))
  shrinking.value = 10 * s > target
  const w = shrinking.value ? target : 10 * s
  const h = shrinking.value ? Math.round((target * 16) / 10) : 16 * s
  el.width = w
  el.height = h
  const ctx = el.getContext('2d')
  if (!ctx) return
  ctx.imageSmoothingEnabled = shrinking.value
  ctx.imageSmoothingQuality = 'high'
  ctx.clearRect(0, 0, el.width, el.height)
  // Außenseite (von hinten gesehen): (1, 1, 10, 16) im 64×32-Raster.
  ctx.drawImage(image, s, f * frameHeight + s, 10 * s, 16 * s, 0, 0, w, h)
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
watch(() => [props.texture, props.scale, props.frames, props.frameTimeMs, props.width], load)
</script>

<template>
  <canvas
    v-if="texture"
    ref="canvas"
    class="rounded"
    :class="{ '[image-rendering:pixelated]': !smooth && !shrinking }"
    :style="{ width: `${width}px`, height: `${height}px` }"
    aria-hidden="true"
  />
  <span v-else class="block rounded bg-base-800" :style="{ width: `${width}px`, height: `${height}px` }" />
</template>
