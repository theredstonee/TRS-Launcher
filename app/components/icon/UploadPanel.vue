<script setup lang="ts">
import { buildPalette, hexToPx, MC_PALETTE, quantize, TRS_PALETTE, type PixelGrid } from '~/utils/iconEditor'
import { loadImage } from '~/utils/iconRender'

// Eigenes Bild als Motiv: Datei kommt geprüft aus dem Kern (PNG/JPEG/WebP, Magic Bytes, höchstens 512 px),
// hier quadratisch zuschneiden und wahlweise auf 32 × 32 verpixeln (Farben reduziert).
const emit = defineEmits<{ apply: [value: { kind: 'image'; png: string } | { kind: 'pixels'; grid: PixelGrid }] }>()

const toasts = useToasts()
const VIEW = 224
type PaletteMode = 'auto16' | 'auto8' | 'trs' | 'mc'

const image = shallowRef<HTMLImageElement | null>(null)
const picking = ref(false)
const zoom = ref(1)
/** Mitte des Ausschnitts in Bildpixeln. */
const center = ref<[number, number]>([0, 0])
const pixelate = ref(true)
const paletteMode = ref<PaletteMode>('auto16')
const view = ref<HTMLCanvasElement | null>(null)
const result = ref<HTMLCanvasElement | null>(null)

async function pick() {
  picking.value = true
  try {
    const picked = await backend.pickIconImage()
    if (!picked) return
    const img = await loadImage(picked.dataUrl)
    image.value = img
    zoom.value = 1
    center.value = [img.naturalWidth / 2, img.naturalHeight / 2]
  } catch (e) {
    toasts.error(e)
  } finally {
    picking.value = false
  }
}

/** Seitenlänge des Ausschnitts in Bildpixeln. */
const cropSide = computed(() => (image.value ? Math.min(image.value.naturalWidth, image.value.naturalHeight) / zoom.value : 0))

function clampCenter([x, y]: [number, number]): [number, number] {
  const img = image.value
  if (!img) return [x, y]
  const half = cropSide.value / 2
  return [Math.min(img.naturalWidth - half, Math.max(half, x)), Math.min(img.naturalHeight - half, Math.max(half, y))]
}

function cropRect(): [number, number, number] {
  const [x, y] = clampCenter(center.value)
  const s = cropSide.value
  return [x - s / 2, y - s / 2, s]
}

function drawCrop(target: HTMLCanvasElement, size: number, smooth: boolean) {
  const img = image.value
  if (!img) return
  target.width = size
  target.height = size
  const ctx = target.getContext('2d', { willReadFrequently: true })!
  ctx.clearRect(0, 0, size, size)
  ctx.imageSmoothingEnabled = smooth
  ctx.imageSmoothingQuality = 'high'
  const [sx, sy, s] = cropRect()
  ctx.drawImage(img, sx, sy, s, s, 0, 0, size, size)
}

function palette(data: Uint8ClampedArray): number[] {
  switch (paletteMode.value) {
    case 'trs':
      return TRS_PALETTE.map(hexToPx)
    case 'mc':
      return MC_PALETTE.map(hexToPx)
    case 'auto8':
      return buildPalette(data, 8)
    default:
      return buildPalette(data, 16)
  }
}

/** Ergebnis berechnen (Vorschau rechts unten im Panel). */
function compute(): { kind: 'image'; png: string } | { kind: 'pixels'; grid: PixelGrid } | null {
  if (!image.value) return null
  const canvas = document.createElement('canvas')
  if (pixelate.value) {
    drawCrop(canvas, 32, true)
    const data = canvas.getContext('2d', { willReadFrequently: true })!.getImageData(0, 0, 32, 32).data
    return { kind: 'pixels', grid: quantize(data, 32, palette(data)) }
  }
  drawCrop(canvas, 128, true)
  return { kind: 'image', png: canvas.toDataURL('image/png') }
}

function render() {
  if (view.value) drawCrop(view.value, VIEW, true)
  const out = result.value
  const value = compute()
  if (!out || !value) return
  out.width = 64
  out.height = 64
  const ctx = out.getContext('2d')!
  ctx.clearRect(0, 0, 64, 64)
  if (value.kind === 'pixels') {
    const small = document.createElement('canvas')
    small.width = 32
    small.height = 32
    const sctx = small.getContext('2d')!
    const img = sctx.createImageData(32, 32)
    value.grid.px.forEach((p, i) => img.data.set([(p >>> 24) & 255, (p >>> 16) & 255, (p >>> 8) & 255, p & 255], i * 4))
    sctx.putImageData(img, 0, 0)
    ctx.imageSmoothingEnabled = false
    ctx.drawImage(small, 0, 0, 64, 64)
  } else {
    drawCrop(out, 64, true)
  }
}

watch([image, zoom, center, pixelate, paletteMode], render, { flush: 'post' })

// Ziehen verschiebt den Ausschnitt.
let drag: { x: number; y: number; center: [number, number] } | null = null
function onDown(e: PointerEvent) {
  if (!image.value) return
  view.value?.setPointerCapture(e.pointerId)
  drag = { x: e.clientX, y: e.clientY, center: clampCenter(center.value) }
}
function onMove(e: PointerEvent) {
  if (!drag) return
  const scale = cropSide.value / VIEW
  center.value = clampCenter([drag.center[0] - (e.clientX - drag.x) * scale, drag.center[1] - (e.clientY - drag.y) * scale])
}
function onUp() {
  drag = null
}

function apply() {
  const value = compute()
  if (value) emit('apply', value)
}
</script>

<template>
  <div>
    <div v-if="!image" class="rounded-lg border border-dashed border-base-700 px-4 py-10 text-center">
      <button type="button" class="btn btn-primary" :disabled="picking" @click="pick">{{ t('iconEditor.upload.pick') }}</button>
      <p class="mt-3 text-xs text-base-400">{{ t('iconEditor.upload.hint') }}</p>
    </div>
    <div v-else class="flex flex-col gap-4 sm:flex-row">
      <div class="shrink-0">
        <canvas
          ref="view"
          class="block cursor-grab touch-none rounded-lg border border-base-700 bg-base-950 active:cursor-grabbing"
          :style="{ width: `${VIEW}px`, height: `${VIEW}px` }"
          role="img"
          :aria-label="t('iconEditor.upload.dragHint')"
          @pointerdown="onDown"
          @pointermove="onMove"
          @pointerup="onUp"
          @pointercancel="onUp"
        />
        <p class="mt-1.5 text-[11px] text-base-400">{{ t('iconEditor.upload.dragHint') }}</p>
      </div>
      <div class="min-w-0 flex-1 space-y-3">
        <label class="block text-xs text-base-400">
          {{ t('iconEditor.upload.zoom') }}
          <input v-model.number="zoom" type="range" min="1" max="4" step="0.05" class="mt-1 block w-full accent-redstone-500" />
        </label>
        <label class="flex items-center justify-between gap-3 text-sm text-base-200">
          {{ t('iconEditor.upload.pixelate') }}
          <ToggleSwitch v-model="pixelate" :label="t('iconEditor.upload.pixelate')" />
        </label>
        <label v-if="pixelate" class="block text-xs text-base-400">
          {{ t('iconEditor.upload.colours') }}
          <select v-model="paletteMode" class="field mt-1 py-1.5 text-xs">
            <option v-for="m in (['auto16', 'auto8', 'trs', 'mc'] as const)" :key="m" :value="m">{{ t(`iconEditor.upload.palette.${m}`) }}</option>
          </select>
        </label>
        <div class="flex items-center gap-3">
          <canvas ref="result" class="size-16 rounded-md border border-base-700 bg-base-950" style="image-rendering: pixelated" role="img" :aria-label="t('iconEditor.preview.title')" />
          <div class="flex flex-wrap gap-2">
            <button type="button" class="btn btn-primary py-1.5 text-xs" @click="apply">{{ t('iconEditor.upload.apply') }}</button>
            <button type="button" class="btn btn-ghost py-1.5 text-xs" :disabled="picking" @click="pick">{{ t('iconEditor.upload.other') }}</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
