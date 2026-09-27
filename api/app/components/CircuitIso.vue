<script setup lang="ts">
// Isometrische Vorschau einer Schaltung (Canvas 2D, app/utils/circuits/iso.ts). Passt sich der Breite an,
// scharf auf HiDPI. Optional mit Knöpfen zum Drehen und einer Schicht-Auswahl.
import { gridOf, type CircuitGrid } from '#shared/circuits'
import { IsoPainter } from '~/utils/circuits/iso'

const props = withDefaults(defineProps<{
  /** Palette + Schichten (Client-Format) oder ein fertiges Raster. */
  circuit?: { palette: Record<string, string>, layers: string[][] } | null
  grid?: CircuitGrid | null
  height?: number
  controls?: boolean
  markers?: boolean
  label?: string
  highlight?: { x: number, y: number, z: number } | null
  /** Aktuelle Schicht (Editor, v-model:layer); −1 = alle. */
  layer?: number
  rotation?: number
}>(), { circuit: null, grid: null, height: 320, controls: false, markers: false, label: '', highlight: null, layer: undefined, rotation: undefined })
const emit = defineEmits<{ 'update:layer': [n: number], 'update:rotation': [n: number] }>()

const { c, fill } = useCircuitText()
const canvas = shallowRef<HTMLCanvasElement | null>(null)
const wrap = shallowRef<HTMLElement | null>(null)
const width = ref(0)
const ownRotation = ref(0)
const ownLayer = ref(-1)
const rot = computed({
  get: () => props.rotation ?? ownRotation.value,
  set: (v: number) => {
    ownRotation.value = v
    emit('update:rotation', v)
  },
})
const lay = computed({
  get: () => props.layer ?? ownLayer.value,
  set: (v: number) => {
    ownLayer.value = v
    emit('update:layer', v)
  },
})

const g = computed<CircuitGrid | null>(() => props.grid ?? (props.circuit ? gridOf(props.circuit) : null))

function draw() {
  const el = canvas.value
  const grid = g.value
  if (!el || !grid || width.value <= 0) return
  const dpr = Math.min(3, window.devicePixelRatio || 1)
  el.width = Math.round(width.value * dpr)
  el.height = Math.round(props.height * dpr)
  const ctx = el.getContext('2d')
  if (!ctx) return
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  new IsoPainter(ctx, width.value, props.height).draw(grid, {
    rotation: rot.value,
    layer: lay.value,
    highlight: props.highlight,
    markers: props.markers,
    padding: 14,
  })
}

let ro: ResizeObserver | null = null
onMounted(() => {
  if (wrap.value) {
    width.value = wrap.value.clientWidth
    ro = new ResizeObserver((entries) => {
      const w = Math.round(entries[0]?.contentRect.width ?? 0)
      if (w !== width.value) width.value = w
    })
    ro.observe(wrap.value)
  }
  draw()
})
onBeforeUnmount(() => ro?.disconnect())
watch([g, rot, lay, width, () => props.highlight, () => props.height, () => props.markers], draw, { deep: true })

// Ziehen dreht in Vierteln.
let dragX: number | null = null
function down(e: PointerEvent) {
  if (!props.controls) return
  dragX = e.clientX
}
function move(e: PointerEvent) {
  if (dragX === null) return
  const d = e.clientX - dragX
  if (Math.abs(d) > 60) {
    rot.value = (rot.value + (d > 0 ? 3 : 1)) % 4
    dragX = e.clientX
  }
}
function up() {
  dragX = null
}
const layers = computed(() => g.value?.y ?? 1)
</script>

<template>
  <div class="iso-wrap">
    <div ref="wrap" class="relative w-full" :style="{ height: `${height}px` }">
      <canvas
        ref="canvas"
        class="block h-full w-full"
        :class="{ 'cursor-grab': controls }"
        role="img"
        :aria-label="label"
        @pointerdown="down"
        @pointermove="move"
        @pointerup="up"
        @pointerleave="up"
      />
    </div>
    <div v-if="controls" class="mt-2 flex flex-wrap items-center gap-2 px-1">
      <button type="button" class="btn-icon size-8" :aria-label="c.common.rotateLeft" :title="c.common.rotateLeft" @click="rot = (rot + 3) % 4"><SiteIcon name="refresh" class="size-4 -scale-x-100" /></button>
      <button type="button" class="btn-icon size-8" :aria-label="c.common.rotateRight" :title="c.common.rotateRight" @click="rot = (rot + 1) % 4"><SiteIcon name="refresh" class="size-4" /></button>
      <template v-if="layers > 1">
        <input
          v-model.number="lay"
          type="range"
          class="iso-range min-w-24 flex-1"
          :min="-1"
          :max="layers - 1"
          step="1"
          :aria-label="lay < 0 ? c.common.allLayers : fill(c.common.layer, { n: lay + 1, total: layers })"
        />
        <span class="min-w-28 text-right text-xs text-base-400 tabular-nums">{{ lay < 0 ? c.common.allLayers : fill(c.common.layer, { n: lay + 1, total: layers }) }}</span>
      </template>
    </div>
  </div>
</template>

<style scoped>
.iso-range {
  accent-color: var(--color-redstone-500);
}
</style>
