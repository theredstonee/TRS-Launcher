<script setup lang="ts">
import type { Rgba } from '~/utils/capeImage'
import type { CapeRegion } from '~/utils/capeLayout'
import {
  colorToHex,
  copyOuterToInner,
  duplicateFrame,
  emptyTexture,
  expandTimeline,
  hexToColor,
  moveFrame,
  removeFrame,
  replaceImage,
  setHold,
  type CapeDoc,
  type CapeFrame,
  type CapeTool,
  type Color,
} from '~/utils/capeEditor'
import { createHistory, MC_PALETTE, TRS_PALETTE, type MirrorMode } from '~/utils/iconEditor'

// Schritt 2 der Umhang-Werkstatt: Frames bearbeiten. Zeichenfläche mit Werkzeugen
// (Stift, Radierer, Pipette, Füllen, Linie, Verschieben, Spiegeln im Teil), Zwiebelhaut,
// Vorlage mit Hilfslinien, Zeitleiste, Rückgängig/Wiederholen und Live-3D-Vorschau.
// Bearbeitet wird im Faktor des Dokuments; hochskaliert wird erst beim Hochladen.
const doc = defineModel<CapeDoc>('doc', { required: true })
const frameTime = defineModel<number>('frameTime', { required: true })

const TOOLS: { id: CapeTool; key: string; path: string }[] = [
  { id: 'pencil', key: 'b', path: 'M4 20l4-1 11-11-3-3L5 16zM14 6l3 3' },
  { id: 'eraser', key: 'e', path: 'M8 20h12M5 15l8-8 6 6-5 5H9z' },
  { id: 'picker', key: 'i', path: 'M14 4l6 6-3 1-7 7H6v-4l7-7zM4 20l2-2' },
  { id: 'fill', key: 'g', path: 'M5 11l7-7 7 7-7 7zM19 15c1 2 2 3 2 4a2 2 0 0 1-4 0c0-1 1-2 2-4z' },
  { id: 'line', key: 'l', path: 'M5 19L19 5' },
  { id: 'move', key: 'm', path: 'M12 3v18M3 12h18M12 3l-3 3M12 3l3 3M12 21l-3-3M12 21l3-3M3 12l3-3M3 12l3 3M21 12l-3-3M21 12l-3 3' },
]
const MIRRORS: MirrorMode[] = ['none', 'x', 'y', 'xy']
const BRUSHES = [1, 2, 3, 4, 6, 8]

const tool = ref<CapeTool>('pencil')
const color = ref<string>(TRS_PALETTE[0])
const recent = ref<string[]>([])
const brush = ref(1)
const mirror = ref<MirrorMode>('none')
/** HD („wie Bild“): Zeichenfläche und Vorschau glatt, Raster aus (es gibt keine großen Pixel). */
const smooth = computed(() => doc.value.style === 'hd')
const grid = ref(!smooth.value)
const guides = ref(true)
const onionOn = ref(true)
const lightBackdrop = ref(false)
const current = ref(0)
const playing = ref(false)
const playTick = ref(0)

const frames = computed(() => doc.value.frames)
const s = computed(() => doc.value.scale)
const width = computed(() => 64 * s.value)

// --- Zoom -------------------------------------------------------------------------------

/** Größter Zoom: Leinwand höchstens 4096 px breit (Speicher), höchstens 24 px je Pixel. */
const maxZoom = computed(() => Math.max(1, Math.min(24, Math.floor(4096 / width.value))))
/** HD (1024 breit): auch verkleinert ansehen, damit der ganze Umhang ohne Scrollen passt. */
const minZoom = computed(() => (smooth.value ? 0.25 : 1))
function fitZoom() {
  if (smooth.value && width.value > 720) return Math.max(minZoom.value, Math.floor((720 / width.value) * 4) / 4)
  return Math.max(1, Math.min(maxZoom.value, Math.floor(720 / width.value)))
}
const zoom = ref(fitZoom())
watch([s, smooth], () => (zoom.value = fitZoom()))
function zoomBy(step: number) {
  const unit = zoom.value < 1 || (zoom.value === 1 && step < 0) ? 0.25 : 1
  zoom.value = Math.max(minZoom.value, Math.min(maxZoom.value, zoom.value + step * unit))
}

// --- Verlauf ----------------------------------------------------------------------------

const history = createHistory<CapeFrame[]>(doc.value.frames, 60)
const revision = ref(0)
const canUndo = computed(() => (revision.value, history.canUndo))
const canRedo = computed(() => (revision.value, history.canRedo))

// Neues Dokument von außen (z. B. gekürzt beim Hochladen) → Verlauf neu.
watch(
  () => doc.value.frames,
  (list) => {
    if (list === history.current) return
    history.reset(list)
    revision.value++
  },
)

function commit(list: CapeFrame[]) {
  history.push(list)
  revision.value++
  doc.value = { ...doc.value, frames: list }
}
function undo() {
  doc.value = { ...doc.value, frames: history.undo() }
  revision.value++
}
function redo() {
  doc.value = { ...doc.value, frames: history.redo() }
  revision.value++
}

watch(
  () => frames.value.length,
  (n) => {
    if (current.value >= n) current.value = Math.max(0, n - 1)
  },
)

// --- Bild ändern ------------------------------------------------------------------------

const image = computed(() => frames.value[current.value]?.image ?? frames.value[0]!.image)
const onion = computed<Rgba | null>(() => {
  if (!onionOn.value || frames.value.length < 2 || playing.value) return null
  const prev = current.value > 0 ? current.value - 1 : frames.value.length - 1
  return frames.value[prev]?.image ?? null
})

function setImage(img: Rgba) {
  commit(replaceImage(frames.value, current.value, img))
}

function useColor(hex: string) {
  color.value = hex
  if (tool.value === 'eraser' || tool.value === 'picker') tool.value = 'pencil'
  recent.value = [hex, ...recent.value.filter((c) => c !== hex)].slice(0, 8)
}
function onPick(c: Color) {
  useColor(colorToHex(c))
}
const colorRgba = computed<Color>(() => hexToColor(color.value))

// --- Zeitleiste -------------------------------------------------------------------------

function select(i: number) {
  playing.value = false
  current.value = i
}
function move(from: number, to: number) {
  commit(moveFrame(frames.value, from, to))
  if (current.value === from) current.value = to
}
function duplicate(i: number) {
  const next = duplicateFrame(frames.value, i)
  if (next.length === frames.value.length) return
  commit(next)
  current.value = i + 1
}
function remove(i: number) {
  commit(removeFrame(frames.value, i))
}
function hold(i: number, n: number) {
  commit(setHold(frames.value, i, n))
}

// Abspielen: Takt für Takt zur Wanduhr; die Zeichenfläche zeigt mit (nur lesen).
let player: ReturnType<typeof setInterval> | null = null
function stopPlayer() {
  if (player) clearInterval(player)
  player = null
}
watch([playing, frameTime], ([on]) => {
  stopPlayer()
  if (!on) return
  playTick.value = firstTick.value
  const started = Date.now() - playTick.value * frameTime.value
  player = setInterval(() => {
    playTick.value = Math.floor((Date.now() - started) / frameTime.value)
    current.value = ticks.value[playTick.value % Math.max(1, ticks.value.length)] ?? 0
  }, Math.max(20, frameTime.value / 2))
})
onBeforeUnmount(stopPlayer)

/** Frame-Index je Takt (Standzeiten ausgeklappt). */
const ticks = computed(() => frames.value.flatMap((f, i) => Array.from({ length: f.hold }, () => i)))
/** Erster Takt des gewählten Frames (für die stehende Vorschau). */
const firstTick = computed(() => frames.value.slice(0, current.value).reduce((sum, f) => sum + f.hold, 0))

// --- 3D-Vorschau ------------------------------------------------------------------------

const canvases = new WeakMap<Rgba, HTMLCanvasElement>()
function canvasOf(img: Rgba): HTMLCanvasElement {
  let c = canvases.get(img)
  if (!c) {
    c = document.createElement('canvas')
    c.width = img.width
    c.height = img.height
    c.getContext('2d')?.putImageData(new ImageData(new Uint8ClampedArray(img.data), img.width, img.height), 0, 0)
    canvases.set(img, c)
  }
  return c
}
const previewCanvases = computed(() => expandTimeline(frames.value).map(canvasOf))

// --- Hilfen -----------------------------------------------------------------------------

const hover = ref<{ x: number; y: number; region: CapeRegion | null } | null>(null)

function copyOuter() {
  setImage(copyOuterToInner(image.value, s.value))
}
function clearFrame() {
  setImage(emptyTexture(s.value))
}

function onKey(e: KeyboardEvent) {
  const target = e.target as HTMLElement | null
  if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable)) return
  const key = e.key.toLowerCase()
  if ((e.ctrlKey || e.metaKey) && key === 'z') {
    e.preventDefault()
    if (e.shiftKey) {
      if (canRedo.value) redo()
    } else if (canUndo.value) undo()
  } else if ((e.ctrlKey || e.metaKey) && key === 'y') {
    e.preventDefault()
    if (canRedo.value) redo()
  } else if (e.ctrlKey || e.metaKey || e.altKey) {
    return
  } else if (key === ' ' && frames.value.length > 1) {
    e.preventDefault()
    playing.value = !playing.value
  } else if (key === 'arrowleft' || key === 'arrowright') {
    if (target?.closest('[data-testid="cape-timeline"]')) return
    e.preventDefault()
    const n = frames.value.length
    select((current.value + (key === 'arrowleft' ? n - 1 : 1)) % n)
  } else if (key === '[' || key === ']') {
    const i = BRUSHES.indexOf(brush.value)
    brush.value = BRUSHES[Math.max(0, Math.min(BRUSHES.length - 1, i + (key === '[' ? -1 : 1)))]!
  } else if (key === 'o') {
    onionOn.value = !onionOn.value
  } else {
    const hit = TOOLS.find((x) => x.key === key)
    if (hit) tool.value = hit.id
  }
}
// Tasten nur, solange der Editor zu sehen ist (der Dialog hält ihn beim Schrittwechsel am Leben).
function listen(on: boolean) {
  window.removeEventListener('keydown', onKey)
  if (on) window.addEventListener('keydown', onKey)
  else playing.value = false
}
onMounted(() => listen(true))
onActivated(() => listen(true))
onDeactivated(() => listen(false))
onBeforeUnmount(() => listen(false))
</script>

<template>
  <div class="space-y-3" data-testid="cape-studio-editor">
    <div class="grid gap-3 lg:grid-cols-[2.5rem_minmax(0,1fr)_15.5rem]">
      <!-- Werkzeuge -->
      <div class="flex flex-wrap gap-1 lg:flex-col" role="toolbar" :aria-label="t('iconEditor.pixel.toolsLabel')">
        <button
          v-for="item in TOOLS"
          :key="item.id"
          type="button"
          class="btn-icon"
          :class="tool === item.id ? '!bg-redstone-600 !text-white' : ''"
          :aria-pressed="tool === item.id"
          :aria-label="t(`capes.studio.tools.${item.id}`)"
          :title="`${t(`capes.studio.tools.${item.id}`)} (${item.key.toUpperCase()})`"
          @click="tool = item.id"
        >
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path :d="item.path" /></svg>
        </button>
        <span class="my-1 hidden h-px bg-base-800 lg:block" />
        <button type="button" class="btn-icon" :disabled="!canUndo" :aria-label="t('iconEditor.pixel.undo')" :title="`${t('iconEditor.pixel.undo')} (Ctrl+Z)`" @click="undo">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 14L4 9l5-5M4 9h10a6 6 0 0 1 0 12h-3" /></svg>
        </button>
        <button type="button" class="btn-icon" :disabled="!canRedo" :aria-label="t('iconEditor.pixel.redo')" :title="`${t('iconEditor.pixel.redo')} (Ctrl+Y)`" @click="redo">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 14l5-5-5-5M20 9H10a6 6 0 0 0 0 12h3" /></svg>
        </button>
      </div>

      <!-- Zeichenfläche -->
      <div class="flex min-w-0 flex-col gap-2">
        <div class="max-h-[30rem] overflow-auto rounded-lg border border-base-800 bg-base-950 p-2">
          <CapePixelCanvas
            class="mx-auto"
            :image="image"
            :onion="onion"
            :scale="s"
            :tool="tool"
            :color="colorRgba"
            :brush="brush"
            :mirror="mirror"
            :zoom="zoom"
            :grid="grid"
            :guides="guides"
            :smooth="smooth"
            :readonly="playing"
            @commit="setImage"
            @pick="onPick"
            @hover="hover = $event"
          />
        </div>
        <div class="flex flex-wrap items-center gap-x-3 gap-y-1.5 text-xs text-base-300">
          <span class="flex items-center gap-1">
            <button type="button" class="btn-icon size-7" :aria-label="t('iconEditor.pixel.zoomOut')" :title="t('iconEditor.pixel.zoomOut')" :disabled="zoom <= minZoom" @click="zoomBy(-1)">−</button>
            <span class="w-9 text-center tabular-nums">{{ zoom }}×</span>
            <button type="button" class="btn-icon size-7" :aria-label="t('iconEditor.pixel.zoomIn')" :title="t('iconEditor.pixel.zoomIn')" :disabled="zoom >= maxZoom" @click="zoomBy(1)">+</button>
          </span>
          <label class="flex items-center gap-1.5"><input v-model="guides" type="checkbox" class="accent-redstone-500" />{{ t('capes.studio.guides') }}</label>
          <label class="flex items-center gap-1.5"><input v-model="grid" type="checkbox" class="accent-redstone-500" />{{ t('iconEditor.pixel.grid') }}</label>
          <label class="flex items-center gap-1.5" :title="`${t('capes.studio.onion')} (O)`"><input v-model="onionOn" type="checkbox" class="accent-redstone-500" />{{ t('capes.studio.onion') }}</label>
          <label class="flex items-center gap-1.5">
            {{ t('capes.studio.brush') }}
            <select v-model.number="brush" class="field w-auto py-1 text-xs">
              <option v-for="b in BRUSHES" :key="b" :value="b">{{ b }} px</option>
            </select>
          </label>
          <label class="flex items-center gap-1.5">
            {{ t('iconEditor.pixel.mirror.label') }}
            <select v-model="mirror" class="field w-auto py-1 text-xs">
              <option v-for="m in MIRRORS" :key="m" :value="m">{{ t(`iconEditor.pixel.mirror.${m}`) }}</option>
            </select>
          </label>
          <span class="ml-auto tabular-nums text-base-500" aria-live="off">
            <template v-if="hover">{{ hover.x }}, {{ hover.y }}<template v-if="hover.region"> · {{ t(`capes.studio.regions.${hover.region.id}`) }}</template></template>
            <template v-else>{{ image.width }}×{{ image.height }}</template>
          </span>
        </div>
        <div class="flex flex-wrap gap-1.5">
          <button type="button" class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="playing" @click="copyOuter">{{ t('capes.studio.copyOuter') }}</button>
          <button type="button" class="btn btn-ghost px-2.5 py-1 text-xs" :disabled="playing" @click="clearFrame">{{ t('capes.studio.clearFrame') }}</button>
        </div>
      </div>

      <!-- Vorschau + Farben -->
      <div class="space-y-3">
        <div class="relative overflow-hidden rounded-lg transition-colors" :class="lightBackdrop ? 'bg-[#d9dce3]' : 'bg-base-900'">
          <SkinViewer
            :skin="null"
            :cape-canvases="previewCanvases"
            :cape-frame-time="playing ? frameTime : null"
            :cape-frame="playing ? 0 : firstTick"
            :cape-smooth="smooth"
            focus="cape"
            animation="walk"
            :height="230"
          />
          <button
            type="button"
            class="absolute top-2 right-2 rounded-md bg-black/45 px-2 py-0.5 text-[11px] text-white hover:bg-black/65"
            :aria-pressed="lightBackdrop"
            @click="lightBackdrop = !lightBackdrop"
          >
            {{ lightBackdrop ? t('capes.studio.backdrop.dark') : t('capes.studio.backdrop.light') }}
          </button>
        </div>
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
        <ul v-if="guides" class="space-y-1 text-[11px] text-base-400" :aria-label="t('capes.studio.legend')">
          <li class="flex items-center gap-2"><span class="h-2.5 w-4 rounded-sm border-2 border-[#ff5a45]" />{{ t('capes.studio.legendFaces') }}</li>
          <li class="flex items-center gap-2"><span class="h-2.5 w-4 rounded-sm border-2 border-dashed border-[#f2c230]" />{{ t('capes.studio.legendEdges') }}</li>
        </ul>
      </div>
    </div>

    <CapeTimeline
      v-model:frame-time="frameTime"
      :frames="frames"
      :current="current"
      :playing="playing"
      :scale="s"
      :smooth="smooth"
      @select="select"
      @move="move"
      @duplicate="duplicate"
      @remove="remove"
      @hold="hold"
      @toggle-play="playing = !playing"
    />
    <p class="text-[11px] text-base-500">{{ t('capes.studio.shortcuts') }}</p>
  </div>
</template>
