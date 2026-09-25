<script setup lang="ts">
// Pixelgenaue 2D-Ansicht einer Umhang-Textur (ganzer 64k×32k-Frame) mit Zoom und Frame-Leiste.
// Die Textur kommt von dieser Seite (gleiche Herkunft), gezeichnet wird nur – nichts ausgelesen.
const props = defineProps<{
  url: string
  scale: number
  frames: number
  /** Gezeigter Frame (0-basiert). */
  frame: number
  playing: boolean
  animated: boolean
}>()
const emit = defineEmits<{ 'update:frame': [value: number], 'update:playing': [value: boolean] }>()

const { m, fill } = useLang()

const texW = computed(() => 64 * props.scale)
const texH = computed(() => 32 * props.scale)

const main = shallowRef<HTMLCanvasElement | null>(null)
const box = shallowRef<HTMLElement | null>(null)
/** Vorschaubilder der Frame-Leiste (Reihenfolge über `data-frame`). */
const thumbs = shallowRef<HTMLCanvasElement[]>([])
const image = shallowRef<HTMLImageElement | null>(null)
const failed = ref(false)
const outline = ref(false)

// Zoom: CSS-Pixel je Texturpixel; „fit“ = so groß, wie die Fläche erlaubt (ab 1× ganzzahlig).
const ZOOMS = [1, 2, 3, 4, 6, 8, 12, 16]
const zoom = ref<number | 'fit'>('fit')
const boxWidth = ref(0)
const fitZoom = computed(() => {
  const avail = Math.max(1, boxWidth.value - 24)
  const z = avail / texW.value
  return z >= 1 ? Math.min(16, Math.floor(z)) : z
})
const effective = computed(() => (zoom.value === 'fit' ? fitZoom.value : zoom.value))
const zoomLabel = computed(() => `${Number.isInteger(effective.value) ? effective.value : effective.value.toFixed(2)}×`)

function zoomBy(dir: 1 | -1) {
  const cur = effective.value
  const next = dir > 0 ? ZOOMS.find((z) => z > cur + 0.001) : [...ZOOMS].reverse().find((z) => z < cur - 0.001)
  zoom.value = next ?? (dir > 0 ? ZOOMS[ZOOMS.length - 1]! : ZOOMS[0]!)
}

const sliderValue = computed({
  get: () => Math.max(0, ZOOMS.findIndex((z) => z >= effective.value - 0.001)),
  set: (i: number) => (zoom.value = ZOOMS[Math.min(ZOOMS.length - 1, Math.max(0, i))]!),
})

function drawFrame(canvas: HTMLCanvasElement | null, f: number) {
  const img = image.value
  if (!canvas || !img) return
  canvas.width = texW.value
  canvas.height = texH.value
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.imageSmoothingEnabled = false
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.drawImage(img, 0, f * texH.value, texW.value, texH.value, 0, 0, texW.value, texH.value)
}

function drawAll() {
  drawFrame(main.value, props.frame)
  for (const el of thumbs.value) drawFrame(el, Number(el.dataset.frame))
}

let token = 0
function load() {
  const mine = ++token
  failed.value = false
  image.value = null
  const img = new Image()
  img.onload = () => {
    if (mine !== token) return
    image.value = img
    void nextTick(drawAll)
  }
  img.onerror = () => {
    if (mine === token) failed.value = true
  }
  img.src = props.url
}

let observer: ResizeObserver | null = null
onMounted(() => {
  load()
  observer = new ResizeObserver(() => (boxWidth.value = box.value?.clientWidth ?? 0))
  if (box.value) {
    boxWidth.value = box.value.clientWidth
    observer.observe(box.value)
  }
})
onBeforeUnmount(() => observer?.disconnect())
watch(() => props.url, load)
watch(() => props.frame, () => drawFrame(main.value, props.frame))

function pick(f: number) {
  emit('update:playing', false)
  emit('update:frame', f)
}
</script>

<template>
  <div>
    <!-- Werkzeugleiste: Zoom und Umriss -->
    <div class="flex flex-wrap items-center gap-2">
      <button type="button" class="btn-icon" :aria-label="m.admin.review.zoomOut" :title="m.admin.review.zoomOut" @click="zoomBy(-1)">
        <SiteIcon name="minus" class="size-4" />
      </button>
      <input
        v-model.number="sliderValue"
        type="range"
        min="0"
        :max="ZOOMS.length - 1"
        step="1"
        class="zoom-range w-28"
        :aria-label="m.admin.review.zoom"
        :aria-valuetext="zoomLabel"
      />
      <button type="button" class="btn-icon" :aria-label="m.admin.review.zoomIn" :title="m.admin.review.zoomIn" @click="zoomBy(1)">
        <SiteIcon name="plus" class="size-4" />
      </button>
      <span class="w-14 text-center font-mono text-xs tabular-nums text-base-200">{{ zoomLabel }}</span>
      <button type="button" class="btn btn-ghost py-1.5 text-xs" :aria-pressed="zoom === 'fit'" @click="zoom = 'fit'">
        <SiteIcon name="fit" class="size-4" />{{ m.admin.review.fit }}
      </button>
      <label class="ml-auto flex items-center gap-2 text-xs text-base-400">
        <input v-model="outline" type="checkbox" class="accent-redstone-500" />{{ m.admin.review.outline }}
      </label>
    </div>

    <!-- Textur -->
    <div ref="box" class="checker mt-3 max-h-[60vh] overflow-auto rounded-lg border border-base-800 p-3">
      <p v-if="failed" class="py-10 text-center text-sm text-base-400">{{ m.common.error }}</p>
      <div v-else class="relative" :style="{ width: `${texW * effective}px`, height: `${texH * effective}px` }">
        <canvas
          ref="main"
          class="block size-full [image-rendering:pixelated]"
          role="img"
          :aria-label="fill(m.admin.review.frameOf, { n: frame + 1, total: frames })"
        />
        <div
          v-if="outline"
          class="cape-outline"
          :style="{ width: `${22 * scale * effective}px`, height: `${17 * scale * effective}px` }"
          aria-hidden="true"
        />
      </div>
    </div>

    <!-- Frames -->
    <div class="mt-3 flex flex-wrap items-center gap-3">
      <button
        v-if="animated"
        type="button"
        class="btn btn-ghost py-1.5 text-xs"
        :aria-pressed="playing"
        @click="emit('update:playing', !playing)"
      >
        <SiteIcon :name="playing ? 'pause' : 'play'" class="size-4" />{{ playing ? m.admin.review.pause : m.admin.review.play }}
      </button>
      <span class="text-xs tabular-nums text-base-400" aria-live="polite">{{ fill(m.admin.review.frameOf, { n: frame + 1, total: frames }) }}</span>
    </div>
    <div v-if="frames > 1" class="mt-2 flex gap-2 overflow-x-auto pb-1" role="group" :aria-label="m.admin.review.frames">
      <button
        v-for="i in frames"
        :key="i"
        type="button"
        class="frame-btn"
        :class="{ 'frame-btn-on': frame === i - 1 }"
        :aria-pressed="frame === i - 1"
        :aria-label="fill(m.admin.review.frameOf, { n: i, total: frames })"
        :title="fill(m.admin.review.frameOf, { n: i, total: frames })"
        @click="pick(i - 1)"
      >
        <canvas ref="thumbs" :data-frame="i - 1" class="block h-9 w-[72px] [image-rendering:pixelated]" aria-hidden="true" />
        <span class="mt-0.5 block text-[10px] tabular-nums text-base-400">{{ i }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
/* Schachbrett zeigt, was durchsichtig ist. */
.checker {
  background-color: var(--color-base-950);
  background-image: repeating-conic-gradient(var(--color-base-850) 0 25%, transparent 0 50%);
  background-size: 16px 16px;
}
.cape-outline {
  position: absolute;
  left: 0;
  top: 0;
  outline: 1px dashed var(--color-lamp-400);
  pointer-events: none;
}
.frame-btn {
  flex-shrink: 0;
  padding: 0.25rem;
  border-radius: 0.375rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-950);
}
.frame-btn:hover {
  border-color: var(--color-base-600);
}
.frame-btn-on {
  border-color: var(--color-redstone-500);
  box-shadow: 0 0 0 1px var(--color-redstone-500);
}
.zoom-range {
  accent-color: var(--color-redstone-500);
}
</style>
