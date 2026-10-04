<script setup lang="ts">
import {
  createHistory,
  flipGrid,
  floodFill,
  getPx,
  GRID_SIZES,
  hexToPx,
  linePoints,
  MC_PALETTE,
  paint,
  pxToHex,
  pxToRgba,
  rectPoints,
  resizeGrid,
  sameGrid,
  TRS_PALETTE,
  emptyGrid,
  type GridSize,
  type MirrorMode,
  type PixelGrid,
  type Point,
} from '~/utils/iconEditor'
import { gridPng } from '~/utils/iconRender'

// Pixel-Editor des Symbol-Editors: zeichnet auf einem 16er/32er-Raster, eigener Verlauf (Strg+Z/Y).
const props = defineProps<{ modelValue: PixelGrid; exportName?: string }>()
const emit = defineEmits<{ 'update:modelValue': [grid: PixelGrid] }>()

const toasts = useToasts()

type Tool = 'pencil' | 'eraser' | 'fill' | 'picker' | 'line' | 'rect' | 'rectFilled'
const TOOLS: { id: Tool; key: string; path: string }[] = [
  { id: 'pencil', key: 'b', path: 'M4 20l4-1 11-11-3-3L5 16zM14 6l3 3' },
  { id: 'eraser', key: 'e', path: 'M8 20h12M5 15l8-8 6 6-5 5H9z' },
  { id: 'fill', key: 'g', path: 'M5 11l7-7 7 7-7 7zM19 15c1 2 2 3 2 4a2 2 0 0 1-4 0c0-1 1-2 2-4z' },
  { id: 'picker', key: 'i', path: 'M14 4l6 6-3 1-7 7H6v-4l7-7zM4 20l2-2' },
  { id: 'line', key: 'l', path: 'M5 19L19 5' },
  { id: 'rect', key: 'r', path: 'M5 5h14v14H5z' },
  { id: 'rectFilled', key: '', path: 'M5 5h14v14H5zM8 8h8v8H8z' },
]
const MIRRORS: MirrorMode[] = ['none', 'x', 'y', 'xy']

const tool = ref<Tool>('pencil')
const color = ref<string>(TRS_PALETTE[0])
const mirror = ref<MirrorMode>('none')
const showGrid = ref(true)
const zoom = ref(props.modelValue.size === 16 ? 22 : 11)
const recent = ref<string[]>([])

const history = createHistory<PixelGrid>(props.modelValue)
const revision = ref(0)
const canUndo = computed(() => (revision.value, history.canUndo))
const canRedo = computed(() => (revision.value, history.canRedo))

// Von außen gesetztes Raster (Item gewählt, TRS-Symbol …) kommt in den Verlauf.
watch(
  () => props.modelValue,
  (grid) => {
    if (sameGrid(grid, history.current)) return
    if (grid.size !== history.current.size) zoom.value = grid.size === 16 ? 22 : 11
    history.push(grid)
    revision.value++
  },
)

function commit(grid: PixelGrid) {
  if (sameGrid(grid, history.current)) return
  history.push(grid)
  revision.value++
  emit('update:modelValue', grid)
}

function undo() {
  emit('update:modelValue', history.undo())
  revision.value++
}

function redo() {
  emit('update:modelValue', history.redo())
  revision.value++
}

function useColor(hex: string) {
  color.value = hex
  if (tool.value === 'eraser' || tool.value === 'picker') tool.value = 'pencil'
  recent.value = [hex, ...recent.value.filter((c) => c !== hex)].slice(0, 8)
}

// --- Zeichnen -----------------------------------------------------------------------

const canvas = ref<HTMLCanvasElement | null>(null)
/** Raster während eines Strichs (noch nicht im Verlauf). */
const working = shallowRef<PixelGrid | null>(null)
let start: Point | null = null
let last: Point | null = null
let erasing = false

const shown = computed(() => working.value ?? props.modelValue)
const side = computed(() => shown.value.size * zoom.value)

function draw() {
  const el = canvas.value
  if (!el) return
  const grid = shown.value
  const z = zoom.value
  el.width = grid.size * z
  el.height = grid.size * z
  const ctx = el.getContext('2d')!
  // Schachbrett für durchsichtige Pixel.
  for (let y = 0; y < grid.size; y++) {
    for (let x = 0; x < grid.size; x++) {
      ctx.fillStyle = (x + y) % 2 ? '#2b2833' : '#35313d'
      ctx.fillRect(x * z, y * z, z, z)
      const p = getPx(grid, x, y)
      if (p & 255) {
        const [r, g, b, a] = pxToRgba(p)
        ctx.fillStyle = `rgba(${r}, ${g}, ${b}, ${a / 255})`
        ctx.fillRect(x * z, y * z, z, z)
      }
    }
  }
  if (showGrid.value && z >= 6) {
    ctx.strokeStyle = 'rgba(0, 0, 0, 0.35)'
    ctx.lineWidth = 1
    ctx.beginPath()
    for (let i = 1; i < grid.size; i++) {
      ctx.moveTo(i * z + 0.5, 0)
      ctx.lineTo(i * z + 0.5, grid.size * z)
      ctx.moveTo(0, i * z + 0.5)
      ctx.lineTo(grid.size * z, i * z + 0.5)
    }
    ctx.stroke()
  }
  // Spiegelachsen andeuten.
  ctx.fillStyle = 'rgba(255, 90, 69, 0.55)'
  if (mirror.value === 'x' || mirror.value === 'xy') ctx.fillRect((grid.size * z) / 2 - 1, 0, 2, grid.size * z)
  if (mirror.value === 'y' || mirror.value === 'xy') ctx.fillRect(0, (grid.size * z) / 2 - 1, grid.size * z, 2)
}

watch([shown, zoom, showGrid, mirror], draw, { flush: 'post' })
onMounted(draw)

function cellOf(e: PointerEvent): Point {
  const rect = canvas.value!.getBoundingClientRect()
  const size = props.modelValue.size
  const x = Math.floor(((e.clientX - rect.left) / rect.width) * size)
  const y = Math.floor(((e.clientY - rect.top) / rect.height) * size)
  return [Math.min(size - 1, Math.max(0, x)), Math.min(size - 1, Math.max(0, y))]
}

function strokeColor(): number {
  return erasing || tool.value === 'eraser' ? 0 : hexToPx(color.value)
}

function onDown(e: PointerEvent) {
  if (e.button !== 0 && e.button !== 2) return
  e.preventDefault()
  canvas.value?.setPointerCapture(e.pointerId)
  erasing = e.button === 2
  const cell = cellOf(e)
  const base = props.modelValue
  if (tool.value === 'picker') {
    const p = getPx(base, cell[0], cell[1])
    if (p & 255) useColor(pxToHex(p).slice(0, 7))
    return
  }
  if (tool.value === 'fill') {
    commit(floodFill(base, cell[0], cell[1], strokeColor(), mirror.value))
    return
  }
  start = cell
  last = cell
  working.value = tool.value === 'pencil' || tool.value === 'eraser' ? paint(base, [cell], strokeColor(), mirror.value) : base
  if (tool.value !== 'pencil' && tool.value !== 'eraser') preview(cell)
}

function preview(cell: Point) {
  if (!start) return
  const points =
    tool.value === 'line'
      ? linePoints(start[0], start[1], cell[0], cell[1])
      : rectPoints(start[0], start[1], cell[0], cell[1], tool.value === 'rectFilled')
  working.value = paint(props.modelValue, points, strokeColor(), mirror.value)
}

function onMove(e: PointerEvent) {
  if (!start || !working.value) return
  const cell = cellOf(e)
  if (tool.value === 'pencil' || tool.value === 'eraser') {
    if (last && cell[0] === last[0] && cell[1] === last[1]) return
    working.value = paint(working.value, linePoints(last![0], last![1], cell[0], cell[1]), strokeColor(), mirror.value)
    last = cell
  } else {
    preview(cell)
  }
}

function onUp() {
  if (working.value) commit(working.value)
  working.value = null
  start = null
  last = null
  erasing = false
}

// --- Tastatur ---------------------------------------------------------------------

function onKey(e: KeyboardEvent) {
  const target = e.target as HTMLElement | null
  if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable)) return
  const key = e.key.toLowerCase()
  if ((e.ctrlKey || e.metaKey) && key === 'z') {
    e.preventDefault()
    if (e.shiftKey) redo()
    else undo()
  } else if ((e.ctrlKey || e.metaKey) && key === 'y') {
    e.preventDefault()
    redo()
  } else if (!e.ctrlKey && !e.metaKey && !e.altKey) {
    const hit = TOOLS.find((t) => t.key && t.key === key)
    if (hit) tool.value = hit.id
  }
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

// --- Aktionen ---------------------------------------------------------------------

function setSize(size: GridSize) {
  if (size === props.modelValue.size) return
  zoom.value = size === 16 ? 22 : 11
  commit(resizeGrid(props.modelValue, size))
}

function zoomBy(step: number) {
  zoom.value = Math.min(40, Math.max(6, zoom.value + step * (props.modelValue.size === 16 ? 4 : 2)))
}

const exporting = ref(false)
async function exportPng() {
  exporting.value = true
  try {
    const scale = props.modelValue.size === 16 ? 16 : 8
    if (await backend.saveIconPng(gridPng(props.modelValue, scale), props.exportName ?? 'icon')) toasts.ok(t('iconEditor.pixel.exported'))
  } catch (e) {
    toasts.error(e)
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <div class="flex flex-col gap-3 sm:flex-row">
    <!-- Werkzeuge -->
    <div class="flex shrink-0 flex-wrap gap-1 sm:w-10 sm:flex-col" role="toolbar" :aria-label="t('iconEditor.pixel.toolsLabel')">
      <button
        v-for="item in TOOLS"
        :key="item.id"
        type="button"
        class="btn-icon"
        :class="tool === item.id ? '!bg-redstone-600 !text-white' : ''"
        :aria-pressed="tool === item.id"
        :aria-label="t(`iconEditor.pixel.tools.${item.id}`)"
        :title="t(`iconEditor.pixel.tools.${item.id}`)"
        @click="tool = item.id"
      >
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path :d="item.path" /></svg>
      </button>
      <span class="my-1 hidden h-px bg-base-800 sm:block" />
      <button type="button" class="btn-icon" :disabled="!canUndo" :aria-label="t('iconEditor.pixel.undo')" :title="t('iconEditor.pixel.undo')" @click="undo">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 14L4 9l5-5M4 9h10a6 6 0 0 1 0 12h-3" /></svg>
      </button>
      <button type="button" class="btn-icon" :disabled="!canRedo" :aria-label="t('iconEditor.pixel.redo')" :title="t('iconEditor.pixel.redo')" @click="redo">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 14l5-5-5-5M20 9H10a6 6 0 0 0 0 12h3" /></svg>
      </button>
    </div>

    <!-- Zeichenfläche -->
    <div class="flex min-w-0 flex-1 flex-col items-center gap-2">
      <div class="grid max-w-full place-items-center overflow-auto rounded-lg border border-base-800 bg-base-950 p-2" style="max-height: 26rem">
        <canvas
          ref="canvas"
          class="block cursor-crosshair touch-none"
          :style="{ width: `${side}px`, height: `${side}px`, imageRendering: 'pixelated' }"
          :aria-label="t('iconEditor.pixel.canvasLabel', { size: modelValue.size })"
          role="img"
          @pointerdown="onDown"
          @pointermove="onMove"
          @pointerup="onUp"
          @pointercancel="onUp"
          @contextmenu.prevent
        />
      </div>
      <div class="flex flex-wrap items-center justify-center gap-1.5 text-xs">
        <div class="flex gap-1 rounded-lg bg-base-900 p-1" role="radiogroup" :aria-label="t('iconEditor.pixel.size')">
          <button v-for="s in GRID_SIZES" :key="s" type="button" class="seg rounded-md text-xs" :class="{ 'seg-on': modelValue.size === s }" role="radio" :aria-checked="modelValue.size === s" @click="setSize(s)">
            {{ s }}×{{ s }}
          </button>
        </div>
        <button type="button" class="btn-icon size-8" :aria-label="t('iconEditor.pixel.zoomOut')" :title="t('iconEditor.pixel.zoomOut')" @click="zoomBy(-1)">−</button>
        <button type="button" class="btn-icon size-8" :aria-label="t('iconEditor.pixel.zoomIn')" :title="t('iconEditor.pixel.zoomIn')" @click="zoomBy(1)">+</button>
        <label class="flex items-center gap-1.5 px-1 text-base-300">
          <input v-model="showGrid" type="checkbox" class="accent-redstone-500" />{{ t('iconEditor.pixel.grid') }}
        </label>
        <label class="flex items-center gap-1.5 text-base-300">
          {{ t('iconEditor.pixel.mirror.label') }}
          <select v-model="mirror" class="field w-auto py-1 text-xs">
            <option v-for="m in MIRRORS" :key="m" :value="m">{{ t(`iconEditor.pixel.mirror.${m}`) }}</option>
          </select>
        </label>
      </div>
      <div class="flex flex-wrap justify-center gap-1.5">
        <button type="button" class="btn btn-ghost px-2.5 py-1 text-xs" @click="commit(flipGrid(modelValue, 'x'))">{{ t('iconEditor.pixel.flipX') }}</button>
        <button type="button" class="btn btn-ghost px-2.5 py-1 text-xs" @click="commit(flipGrid(modelValue, 'y'))">{{ t('iconEditor.pixel.flipY') }}</button>
        <button type="button" class="btn btn-ghost px-2.5 py-1 text-xs" @click="commit(emptyGrid(modelValue.size))">{{ t('iconEditor.pixel.clear') }}</button>
        <button type="button" class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="exporting" @click="exportPng">{{ t('iconEditor.pixel.export') }}</button>
      </div>
    </div>

    <!-- Farben -->
    <div class="w-full shrink-0 space-y-2.5 sm:w-44">
      <div class="flex items-center gap-2">
        <span class="size-8 shrink-0 rounded-md ring-2 ring-base-600" :style="{ background: color }" />
        <label class="min-w-0 flex-1 text-xs text-base-400">
          {{ t('iconEditor.pixel.palette.custom') }}
          <input type="color" class="mt-0.5 block h-7 w-full cursor-pointer rounded border border-base-700 bg-base-900" :value="color" @change="useColor(($event.target as HTMLInputElement).value)" />
        </label>
      </div>
      <div>
        <p class="label mb-1">{{ t('iconEditor.pixel.palette.trs') }}</p>
        <div class="grid grid-cols-7 gap-1">
          <button v-for="c in TRS_PALETTE" :key="c" type="button" class="aspect-square rounded ring-1 ring-black/40 transition-transform hover:scale-110" :class="{ 'ring-2 !ring-white': color === c }" :style="{ background: c }" :aria-label="c" :title="c" @click="useColor(c)" />
        </div>
      </div>
      <div>
        <p class="label mb-1">{{ t('iconEditor.pixel.palette.mc') }}</p>
        <div class="grid grid-cols-8 gap-1">
          <button v-for="c in MC_PALETTE" :key="c" type="button" class="aspect-square rounded ring-1 ring-black/40 transition-transform hover:scale-110" :class="{ 'ring-2 !ring-white': color === c }" :style="{ background: c }" :aria-label="c" :title="c" @click="useColor(c)" />
        </div>
      </div>
      <div v-if="recent.length">
        <p class="label mb-1">{{ t('iconEditor.pixel.palette.recent') }}</p>
        <div class="grid grid-cols-8 gap-1">
          <button v-for="c in recent" :key="c" type="button" class="aspect-square rounded ring-1 ring-black/40" :style="{ background: c }" :aria-label="c" :title="c" @click="useColor(c)" />
        </div>
      </div>
      <p class="text-[11px] leading-snug text-base-400">{{ t('iconEditor.pixel.hint') }}</p>
    </div>
  </div>
</template>
