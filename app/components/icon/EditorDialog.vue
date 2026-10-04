<script setup lang="ts">
import {
  buildPalette,
  decodeGrid,
  defaultSource,
  emptyGrid,
  encodeGrid,
  isEmptyGrid,
  quantize,
  type IconBackground,
  type IconResult,
  type IconSource,
  type PixelGrid,
} from '~/utils/iconEditor'
import { loadImage, renderIconPng } from '~/utils/iconRender'

/*
 * Symbol-Editor (wiederverwendbar): Pixel-Editor, Minecraft-Items/-Blöcke, TRS-Symbole, eigenes Bild –
 * dazu Hintergrund und Vorschau in allen Größen. Speichert nichts selbst: `save` liefert das fertige
 * 128-px-PNG + die Quelle (Format docs/icon-format.md); Instanzen nutzen `IconInstanceEditor`.
 */
const props = defineProps<{ initial?: IconSource | null; title?: string; hint?: string | null; exportName?: string; saving?: boolean }>()
const emit = defineEmits<{ close: []; save: [result: IconResult] }>()

type Tab = 'pixel' | 'mc' | 'trs' | 'upload'
const TABS: Tab[] = ['pixel', 'mc', 'trs', 'upload']

const start = props.initial ?? defaultSource()
const tab = ref<Tab>(start.origin.kind === 'mc' ? 'mc' : start.origin.kind === 'trs' ? 'trs' : 'pixel')
const bg = ref<IconBackground>({ ...start.bg })
const scale = ref(start.scale)
const origin = ref<IconSource['origin']>({ ...start.origin })
const grid = ref<PixelGrid>(start.layer.kind === 'pixels' ? (decodeGrid(start.layer) ?? emptyGrid(16)) : emptyGrid(16))
const imagePng = ref<string | null>(start.layer.kind === 'image' ? start.layer.png : null)

const source = computed<IconSource>(() => ({
  v: 1,
  bg: bg.value,
  layer: imagePng.value ? { kind: 'image', png: imagePng.value } : isEmptyGrid(grid.value) ? { kind: 'none' } : encodeGrid(grid.value),
  scale: scale.value,
  origin: origin.value,
}))

// Vorschau: genau das Bild, das gespeichert wird (128 px), in allen Größen.
const preview = ref<string | null>(null)
let renderTimer: ReturnType<typeof setTimeout> | undefined
let renderRun = 0
watch(
  source,
  (value) => {
    clearTimeout(renderTimer)
    renderTimer = setTimeout(async () => {
      const run = ++renderRun
      const png = await renderIconPng(value)
      if (run === renderRun) preview.value = `data:image/png;base64,${png}`
    }, 40)
  },
  { immediate: true },
)
onBeforeUnmount(() => clearTimeout(renderTimer))

// Escape schließt nur den Editor – nicht auch den Dialog darunter (Neue Instanz, Einstellungen).
function onEscape(e: KeyboardEvent) {
  if (e.key !== 'Escape') return
  e.stopImmediatePropagation()
  emit('close')
}
onMounted(() => window.addEventListener('keydown', onEscape, true))
onBeforeUnmount(() => window.removeEventListener('keydown', onEscape, true))

const PREVIEWS = [
  { size: 24, key: 'sidebar' },
  { size: 48, key: 'card' },
  { size: 88, key: 'header' },
] as const

function rounded(size: number) {
  return size >= 64 ? 'rounded-xl' : size >= 36 ? 'rounded-lg' : 'rounded-md'
}

function setPixels(next: PixelGrid, from: IconSource['origin']) {
  imagePng.value = null
  grid.value = next
  origin.value = from
}

function onPixels(next: PixelGrid) {
  grid.value = next
  if (imagePng.value) imagePng.value = null
}

function onUpload(value: { kind: 'image'; png: string } | { kind: 'pixels'; grid: PixelGrid }) {
  if (value.kind === 'image') {
    imagePng.value = value.png
    origin.value = { kind: 'upload', ref: null }
  } else {
    setPixels(value.grid, { kind: 'upload', ref: null })
    tab.value = 'pixel'
  }
}

/** Bild-Motiv nachträglich verpixeln, damit der Pixel-Editor es bearbeiten kann. */
async function pixelateImage() {
  if (!imagePng.value) return
  const img = await loadImage(imagePng.value)
  const canvas = document.createElement('canvas')
  canvas.width = 32
  canvas.height = 32
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(img, 0, 0, 32, 32)
  const data = ctx.getImageData(0, 0, 32, 32).data
  setPixels(quantize(data, 32, buildPalette(data, 16)), origin.value)
}

const busy = ref(false)
async function save() {
  if (busy.value || props.saving) return
  busy.value = true
  try {
    const value = source.value
    emit('save', { png: await renderIconPng(value), source: value })
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <BaseDialog :title="title ?? t('iconEditor.title')" huge @close="emit('close')">
    <div class="grid gap-5 lg:grid-cols-[minmax(0,1fr)_17rem]">
      <!-- Quellen -->
      <div class="min-w-0">
        <div class="mb-4 flex flex-wrap gap-1 border-b border-base-800 pb-2" role="tablist">
          <button
            v-for="id in TABS"
            :key="id"
            type="button"
            class="tab"
            :class="{ 'tab-on': tab === id }"
            role="tab"
            :aria-selected="tab === id"
            :data-tab="id"
            @click="tab = id"
          >
            {{ t(`iconEditor.tabs.${id}`) }}
          </button>
        </div>

        <template v-if="tab === 'pixel'">
          <div v-if="imagePng" class="mb-3 flex flex-wrap items-center justify-between gap-3 rounded-lg border border-base-700 bg-base-900 px-3 py-2 text-sm text-base-300">
            {{ t('iconEditor.pixel.imageLayer') }}
            <button type="button" class="btn btn-ghost py-1 text-xs" @click="pixelateImage">{{ t('iconEditor.pixel.pixelate') }}</button>
          </div>
          <IconPixelEditor :model-value="grid" :export-name="exportName" @update:model-value="onPixels" />
        </template>
        <IconMcPicker v-else-if="tab === 'mc'" :selected="origin.kind === 'mc' ? origin.ref : null" @pick="(p) => setPixels(p.grid, { kind: 'mc', ref: p.key })" />
        <IconTrsPicker v-else-if="tab === 'trs'" :selected="origin.kind === 'trs' ? origin.ref : null" @pick="(p) => setPixels(p.grid, { kind: 'trs', ref: p.id })" />
        <IconUploadPanel v-else @apply="onUpload" />
      </div>

      <!-- Vorschau + Hintergrund -->
      <aside class="space-y-4 lg:border-l lg:border-base-800 lg:pl-5">
        <div>
          <p class="label">{{ t('iconEditor.preview.title') }}</p>
          <div class="flex items-end justify-around gap-3 rounded-lg bg-base-950 px-3 pt-4 pb-2">
            <div v-for="p in PREVIEWS" :key="p.key" class="flex flex-col items-center gap-1.5">
              <img v-if="preview" :src="preview" alt="" class="bg-base-800 ring-1 ring-white/5" :class="rounded(p.size)" :style="{ width: `${p.size}px`, height: `${p.size}px` }" draggable="false" />
              <div v-else class="skeleton" :class="rounded(p.size)" :style="{ width: `${p.size}px`, height: `${p.size}px` }" />
              <span class="text-[11px] text-base-400">{{ t(`iconEditor.preview.${p.key}`) }}</span>
            </div>
          </div>
          <p v-if="hint" class="mt-2 text-xs text-base-400">{{ hint }}</p>
        </div>
        <div>
          <p class="label">{{ t('iconEditor.bg.title') }}</p>
          <IconBackgroundPanel v-model="bg" v-model:scale="scale" />
        </div>
      </aside>
    </div>

    <template #actions>
      <button type="button" class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button type="button" class="btn btn-primary" :disabled="busy || saving" @click="save">
        {{ saving ? t('iconEditor.saving') : t('iconEditor.save') }}
      </button>
    </template>
  </BaseDialog>
</template>
