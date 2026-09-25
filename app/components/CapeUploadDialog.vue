<script setup lang="ts">
import type { TrsCape, TrsCapeSource } from '~/utils/trs'
import {
  CAPE_MAX_BYTES,
  CAPE_MAX_FRAMES,
  CAPE_MAX_SCALE,
  FRAME_TIME_DEFAULT,
  FRAME_TIME_MAX,
  FRAME_TIME_MIN,
  buildStrip,
  capeVisible,
  clampFrameTime,
  cropFor,
  dataUrlBytes,
  detectTexture,
  frameTimeFor,
  naturalCompare,
  sampleFrames,
  splitStrip,
  type Crop,
  type Rgba,
} from '~/utils/capeImage'

// Eigener Umhang wie bei Discord: Dateien wählen (Bild, GIF, mehrere Bilder als
// Frames, Sprite-Sheet, TRS-Studio-Export), Ausschnitt verschieben/zoomen, Auflösung
// und Bildtempo wählen – mit Live-Vorschau am Spieler. Heraus kommt ein PNG-Streifen
// (≤ 16 Frames, ≤ 512×256 je Frame), den der Kern vor dem Hochladen erneut prüft.
const emit = defineEmits<{ close: []; uploaded: [cape: TrsCape] }>()

const toasts = useToasts()

/** Mehrere Einzelbilder als Frames: jedes höchstens so groß (Speicher im Webview). */
const MAX_FRAME_SIDE = 2048
const MAX_ZOOM = 10

const picking = ref(false)
const uploading = ref(false)
const error = ref<string | null>(null)

// --- Quelle -------------------------------------------------------------------------

interface Loaded {
  label: string
  /** Bilder vor dem Zerlegen (ein Bild = evtl. Sprite-Sheet). */
  images: Rgba[]
  /** Dauer aller Frames (GIF) – daraus das Start-Tempo. */
  durationMs: number | null
  /** Frame-Zahl der Quelle vor dem Ausdünnen (GIF). */
  sourceFrames: number
  studioFrames: number | null
}

const loaded = shallowRef<Loaded | null>(null)
/** Sprite-Sheet: Frames untereinander im einen Bild. */
const sheetFrames = ref(1)
const mode = ref<'texture' | 'crop'>('crop')
const scale = ref(8)
const zoom = ref(1)
const center = reactive({ x: 0, y: 0 })
const frameTime = ref(FRAME_TIME_DEFAULT)
const name = ref('')

/** Alle Frames der Quelle (nach dem Zerlegen), noch nicht ausgedünnt. */
const allFrames = computed<Rgba[]>(() => {
  const l = loaded.value
  if (!l) return []
  if (l.images.length === 1) return splitStrip(l.images[0]!, sheetFrames.value)
  return l.images
})
const totalFrames = computed(() => loaded.value?.sourceFrames ?? allFrames.value.length)
/** Höchstens 16 gleichmäßig verteilte Frames gehen hoch. */
const frames = computed(() => sampleFrames(allFrames.value.length).map((i) => allFrames.value[i]!))
const first = computed(() => frames.value[0] ?? null)
/** Ist ein Frame schon eine Umhang-Textur (64:32 oder 22:17)? */
const textureLayout = computed(() => (first.value ? detectTexture(first.value.width, first.value.height, 1) : null))
const crop = computed<Crop | null>(() =>
  first.value ? cropFor(first.value.width, first.value.height, zoom.value, center.x, center.y) : null,
)

function resetCrop() {
  zoom.value = 1
  center.x = (first.value?.width ?? 0) / 2
  center.y = (first.value?.height ?? 0) / 2
}

function loadRgba(url: string): Promise<Rgba> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => {
      const canvas = document.createElement('canvas')
      canvas.width = img.naturalWidth
      canvas.height = img.naturalHeight
      const ctx = canvas.getContext('2d', { willReadFrequently: true })
      if (!ctx || !canvas.width || !canvas.height) return reject(new Error('canvas'))
      ctx.drawImage(img, 0, 0)
      const data = ctx.getImageData(0, 0, canvas.width, canvas.height)
      resolve({ width: data.width, height: data.height, data: data.data })
    }
    img.onerror = () => reject(new Error('image'))
    img.src = url
  })
}

async function pick() {
  if (picking.value) return
  picking.value = true
  error.value = null
  try {
    const sources = await backend.trs.pickCapeSources()
    if (sources) await use(sources)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    picking.value = false
  }
}

async function use(sources: TrsCapeSource[]) {
  const studio = sources.find((s) => s.kind === 'studio')
  const gifs = sources.filter((s) => s.kind === 'gif')
  const images = sources.filter((s) => s.kind === 'image').sort((a, b) => naturalCompare(a.name, b.name))
  if (!gifs.length && !images.length) {
    error.value = t('capes.editor.errors.noImage')
    return
  }
  if (gifs.length > 1 || (gifs.length && images.length)) {
    error.value = t('capes.editor.errors.mixed')
    return
  }
  let next: Loaded
  try {
    const gif = gifs[0]
    if (gif) {
      next = {
        label: gif.name,
        images: await Promise.all(gif.frames.map(loadRgba)),
        durationMs: gif.durationMs,
        sourceFrames: gif.sourceFrames,
        studioFrames: null,
      }
    } else {
      // Mehr als 16 Einzelbilder: schon vor dem Dekodieren ausdünnen.
      const picked = sampleFrames(images.length).map((i) => images[i]!)
      if (picked.length > 1) {
        const [w, h] = [picked[0]!.width, picked[0]!.height]
        if (picked.some((p) => p.width !== w || p.height !== h)) {
          error.value = t('capes.editor.errors.sizeMismatch')
          return
        }
        if (w > MAX_FRAME_SIDE || h > MAX_FRAME_SIDE) {
          error.value = t('capes.editor.errors.frameTooLarge')
          return
        }
      }
      next = {
        label: picked.length > 1 ? `${picked[0]!.name} …` : picked[0]!.name,
        images: await Promise.all(picked.map((p) => loadRgba(p.dataUrl))),
        durationMs: null,
        sourceFrames: images.length,
        studioFrames: studio?.frames ?? null,
      }
    }
  } catch {
    error.value = t('capes.editor.errors.readFailed')
    return
  }
  error.value = null
  loaded.value = next
  // Einzelbild: Umhang-Textur/Streifen erkennen (Frames aus dem Studio-JSON, falls gewählt).
  const single = next.images.length === 1 ? next.images[0]! : null
  const sheet = single ? detectTexture(single.width, single.height, next.studioFrames ?? undefined) : null
  sheetFrames.value = sheet?.frames ?? 1
  await nextTick()
  const layout = textureLayout.value
  mode.value = layout ? 'texture' : 'crop'
  scale.value = layout ? Math.min(CAPE_MAX_SCALE, Math.max(1, Math.round(layout.scale))) : frames.value.length > 4 ? 4 : 8
  frameTime.value =
    next.durationMs !== null
      ? frameTimeFor(next.durationMs, frames.value.length)
      : clampFrameTime((FRAME_TIME_DEFAULT * Math.max(totalFrames.value, 1)) / Math.max(frames.value.length, 1))
  if (!name.value.trim()) name.value = cleanName(next.label.replace(/ …$/, ''))
  resetCrop()
}

/** Dateiname als Vorschlag – nur Zeichen, die der Server im Namen erlaubt. */
function cleanName(raw: string): string {
  const cleaned = raw.replace(/[_]+/g, ' ').replace(/[^\p{L}\p{N} .,'!?&()+-]/gu, '').trim().slice(0, 32)
  return trsCapeNameSchema.safeParse(cleaned).success ? cleaned : ''
}

// Sprite-Sheet-Zahl geändert → Ausschnitt passt nicht mehr.
watch(sheetFrames, () => resetCrop())

// --- Ergebnis & Vorschau ------------------------------------------------------------

const preview = ref<string | null>(null)
const previewBytes = ref(0)
const previewVisible = ref(true)
let timer: ReturnType<typeof setTimeout> | null = null

function build(): Rgba | null {
  if (!frames.value.length) return null
  return buildStrip({
    frames: frames.value,
    mode: mode.value === 'texture' && textureLayout.value ? 'texture' : 'crop',
    kind: textureLayout.value?.kind,
    crop: crop.value ?? undefined,
    scale: scale.value,
  })
}

function encode(strip: Rgba): string {
  const canvas = document.createElement('canvas')
  canvas.width = strip.width
  canvas.height = strip.height
  canvas.getContext('2d')?.putImageData(new ImageData(new Uint8ClampedArray(strip.data), strip.width, strip.height), 0, 0)
  return canvas.toDataURL('image/png')
}

function refresh() {
  const strip = build()
  if (!strip) {
    preview.value = null
    return
  }
  const url = encode(strip)
  preview.value = url
  previewBytes.value = dataUrlBytes(url)
  previewVisible.value = capeVisible(strip, scale.value, frames.value.length)
}

watch([frames, mode, scale, () => crop.value?.x, () => crop.value?.y, () => crop.value?.w], () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(refresh, 120)
})
onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
  stopLoop()
})

const animated = computed(() => frames.value.length > 1)
const tooLarge = computed(() => previewBytes.value > CAPE_MAX_BYTES)
const fps = computed(() => (1000 / frameTime.value).toFixed(1))

// --- Ausschnitt (Leinwand mit Rahmen) -------------------------------------------------

const viewport = ref<HTMLCanvasElement | null>(null)
const frameCanvases = computed(() =>
  frames.value.map((f) => {
    const c = document.createElement('canvas')
    c.width = f.width
    c.height = f.height
    c.getContext('2d')?.putImageData(new ImageData(new Uint8ClampedArray(f.data), f.width, f.height), 0, 0)
    return c
  }),
)

/** Rahmen der Außenseite (10:16) in der Leinwand. */
function windowRect(el: HTMLCanvasElement) {
  const h = el.height - 24 * devicePixelRatio
  const w = h * (10 / 16)
  return { x: (el.width - w) / 2, y: (el.height - h) / 2, w, h }
}

function draw() {
  const el = viewport.value
  const c = crop.value
  if (!el || !c || mode.value !== 'crop') return
  const box = el.getBoundingClientRect()
  const ratio = devicePixelRatio
  if (el.width !== Math.round(box.width * ratio) || el.height !== Math.round(box.height * ratio)) {
    el.width = Math.round(box.width * ratio)
    el.height = Math.round(box.height * ratio)
  }
  const ctx = el.getContext('2d')
  if (!ctx) return
  const f = animated.value ? trsFrameIndex(Date.now(), frames.value.length, frameTime.value) : 0
  const src = frameCanvases.value[f] ?? frameCanvases.value[0]
  const win = windowRect(el)
  const k = win.h / c.h
  ctx.clearRect(0, 0, el.width, el.height)
  ctx.imageSmoothingEnabled = k < 1
  if (src) ctx.drawImage(src, win.x - c.x * k, win.y - c.y * k, src.width * k, src.height * k)
  // Außerhalb abdunkeln, Rahmen hell.
  ctx.fillStyle = 'rgba(8, 8, 12, 0.62)'
  ctx.beginPath()
  ctx.rect(0, 0, el.width, el.height)
  ctx.rect(win.x, win.y, win.w, win.h)
  ctx.fill('evenodd')
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.85)'
  ctx.lineWidth = Math.max(1, ratio)
  ctx.strokeRect(win.x + 0.5, win.y + 0.5, win.w - 1, win.h - 1)
}

let raf = 0
let lastDraw = 0
function loop(now: number) {
  // Bewegte Frames: ~20 Bilder/s reichen für die Vorschau des Ausschnitts.
  if (now - lastDraw > 50) {
    lastDraw = now
    draw()
  }
  raf = requestAnimationFrame(loop)
}
function stopLoop() {
  if (raf) cancelAnimationFrame(raf)
  raf = 0
}
watch(
  () => mode.value === 'crop' && !!loaded.value,
  async (on) => {
    stopLoop()
    if (!on) return
    await nextTick()
    raf = requestAnimationFrame(loop)
  },
)

let drag: { id: number; x: number; y: number } | null = null
function pixelsPerSource(): number {
  const el = viewport.value
  const c = crop.value
  if (!el || !c) return 1
  return windowRect(el).h / c.h / devicePixelRatio
}
function onPointerDown(e: PointerEvent) {
  drag = { id: e.pointerId, x: e.clientX, y: e.clientY }
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
}
function onPointerMove(e: PointerEvent) {
  if (!drag || drag.id !== e.pointerId || !crop.value) return
  const k = pixelsPerSource()
  moveBy(-(e.clientX - drag.x) / k, -(e.clientY - drag.y) / k)
  drag.x = e.clientX
  drag.y = e.clientY
}
function onPointerUp(e: PointerEvent) {
  if (drag?.id === e.pointerId) drag = null
}
/** Mitte verschieben – auf den erlaubten Bereich begrenzt, damit sie nicht „wegläuft“. */
function moveBy(dx: number, dy: number) {
  const c = crop.value
  if (!c) return
  center.x = c.x + c.w / 2 + dx
  center.y = c.y + c.h / 2 + dy
}
function onWheel(e: WheelEvent) {
  zoomTo(zoom.value * (e.deltaY < 0 ? 1.1 : 1 / 1.1))
}
function zoomTo(value: number) {
  const c = crop.value
  if (c) {
    center.x = c.x + c.w / 2
    center.y = c.y + c.h / 2
  }
  zoom.value = Math.min(MAX_ZOOM, Math.max(1, value))
}
function onKey(e: KeyboardEvent) {
  const c = crop.value
  if (!c) return
  const step = c.w / 20
  const moves: Record<string, [number, number]> = {
    ArrowLeft: [-step, 0],
    ArrowRight: [step, 0],
    ArrowUp: [0, -step],
    ArrowDown: [0, step],
  }
  const move = moves[e.key]
  if (move) moveBy(...move)
  else if (e.key === '+' || e.key === '=') zoomTo(zoom.value * 1.1)
  else if (e.key === '-') zoomTo(zoom.value / 1.1)
  else return
  e.preventDefault()
}

// --- Hochladen ----------------------------------------------------------------------

const nameError = ref<string | null>(null)

async function upload() {
  const parsed = trsCapeNameSchema.safeParse(name.value)
  if (!parsed.success) {
    nameError.value = firstIssue(parsed.error)
    return
  }
  nameError.value = null
  const strip = build()
  if (!strip || uploading.value) return
  const url = encode(strip)
  const bytes = dataUrlBytes(url)
  if (bytes > CAPE_MAX_BYTES) {
    error.value = t('capes.editor.errors.tooLarge', { size: formatFileSize(bytes) })
    return
  }
  if (!capeVisible(strip, scale.value, frames.value.length)) {
    error.value = t('capes.editor.errors.empty')
    return
  }
  error.value = null
  uploading.value = true
  try {
    const cape = await backend.trs.uploadCape({
      png: url.slice(url.indexOf(',') + 1),
      frames: frames.value.length,
      frameTimeMs: animated.value ? frameTime.value : null,
      name: parsed.data || null,
    })
    toasts.ok(t('capes.toasts.uploaded'))
    emit('uploaded', cape)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    uploading.value = false
  }
}

const scales = Array.from({ length: CAPE_MAX_SCALE }, (_, i) => i + 1)
</script>

<template>
  <BaseDialog :title="t('capes.editor.title')" huge @close="emit('close')">
    <!-- Noch nichts gewählt -->
    <div v-if="!loaded" class="grid gap-6 py-2 md:grid-cols-[1fr_16rem]" data-testid="cape-editor-pick">
      <div>
        <p class="text-sm text-base-200">{{ t('capes.editor.pick.lead') }}</p>
        <ul class="mt-3 list-disc space-y-1 pl-4 text-xs text-base-400">
          <li>{{ t('capes.editor.pick.image') }}</li>
          <li>{{ t('capes.editor.pick.gif') }}</li>
          <li>{{ t('capes.editor.pick.frames') }}</li>
          <li>{{ t('capes.editor.pick.sheet') }}</li>
          <li>{{ t('capes.editor.pick.studio') }}</li>
        </ul>
        <button class="btn btn-primary mt-5" :disabled="picking" data-testid="cape-editor-choose" @click="pick">
          {{ picking ? t('capes.editor.pick.loading') : t('capes.editor.pick.choose') }}
        </button>
      </div>
      <ul class="space-y-1 self-start rounded-lg bg-base-900 p-3 text-[11px] text-base-400">
        <li>{{ t('capes.editor.rules.size') }}</li>
        <li>{{ t('capes.editor.rules.review') }}</li>
        <li>{{ t('capes.editor.rules.rights') }}</li>
      </ul>
    </div>

    <!-- Bearbeiten -->
    <div v-else class="grid gap-5 md:grid-cols-[minmax(0,1fr)_17rem]" data-testid="cape-editor">
      <div class="min-w-0 space-y-4">
        <div class="flex flex-wrap items-center gap-2 text-xs">
          <span class="min-w-0 flex-1 truncate text-base-200">
            <strong class="text-base-50">{{ loaded.label }}</strong>
            · {{ first?.width }}×{{ first?.height }}
            <template v-if="totalFrames > 1"> · {{ t('capes.editor.frameCount', totalFrames) }}</template>
          </span>
          <button class="btn btn-ghost px-3 py-1 text-xs" :disabled="picking" @click="pick">{{ t('capes.editor.change') }}</button>
        </div>

        <div v-if="textureLayout" class="flex gap-1 rounded-lg bg-base-900 p-1 text-xs" role="radiogroup" :aria-label="t('capes.editor.mode.label')">
          <button
            v-for="m in (['texture', 'crop'] as const)"
            :key="m"
            class="seg flex-1 rounded-md"
            :class="{ 'seg-on': mode === m }"
            role="radio"
            :aria-checked="mode === m"
            @click="mode = m"
          >
            {{ t(`capes.editor.mode.${m}`) }}
          </button>
        </div>

        <!-- Ausschnitt wählen -->
        <div v-if="mode === 'crop'">
          <canvas
            ref="viewport"
            class="h-72 w-full cursor-grab touch-none rounded-lg bg-[repeating-conic-gradient(#1b1c22_0_25%,#23242b_0_50%)] bg-[length:16px_16px] outline-none focus-visible:ring-2 focus-visible:ring-redstone-500 active:cursor-grabbing"
            tabindex="0"
            role="img"
            :aria-label="t('capes.editor.crop.viewport')"
            data-testid="cape-editor-viewport"
            @pointerdown="onPointerDown"
            @pointermove="onPointerMove"
            @pointerup="onPointerUp"
            @pointercancel="onPointerUp"
            @wheel.prevent="onWheel"
            @keydown="onKey"
          />
          <div class="mt-2 flex items-center gap-3 text-xs text-base-400">
            <span>{{ t('capes.editor.crop.zoom') }}</span>
            <input
              :value="zoom"
              type="range"
              min="1"
              :max="MAX_ZOOM"
              step="0.01"
              class="flex-1 accent-redstone-500"
              :aria-label="t('capes.editor.crop.zoom')"
              @input="zoomTo(Number(($event.target as HTMLInputElement).value))"
            />
            <button class="btn btn-ghost px-2 py-1 text-xs" @click="resetCrop">{{ t('capes.editor.crop.reset') }}</button>
          </div>
          <p class="mt-1 text-[11px] text-base-600">{{ t('capes.editor.crop.hint') }}</p>
        </div>
        <div v-else class="grid place-items-center rounded-lg bg-base-900 p-3">
          <img
            v-if="preview"
            :src="preview"
            :alt="t('capes.editor.preview.texture')"
            class="max-h-72 w-full object-contain object-top [image-rendering:pixelated]"
          />
        </div>

        <div class="grid gap-3 sm:grid-cols-2">
          <label v-if="loaded.images.length === 1" class="block">
            <span class="label">{{ t('capes.editor.sheet.label') }}</span>
            <input v-model.number="sheetFrames" type="number" min="1" max="64" class="field" />
            <span class="mt-1 block text-[11px] text-base-600">{{ t('capes.editor.sheet.hint') }}</span>
          </label>
          <label class="block">
            <span class="label">{{ t('capes.editor.scale.label') }}</span>
            <select v-model.number="scale" class="field">
              <option v-for="k in scales" :key="k" :value="k">{{ t('capes.editor.scale.option', { w: 64 * k, h: 32 * k, k }) }}</option>
            </select>
          </label>
        </div>

        <div v-if="animated">
          <div class="flex items-center justify-between text-xs">
            <span class="label mb-0">{{ t('capes.editor.speed.label') }}</span>
            <span class="text-base-400 tabular-nums">{{ t('capes.editor.speed.value', { ms: frameTime, fps }) }}</span>
          </div>
          <input
            v-model.number="frameTime"
            type="range"
            :min="FRAME_TIME_MIN"
            :max="FRAME_TIME_MAX"
            step="10"
            class="mt-1 w-full accent-redstone-500"
            :aria-label="t('capes.editor.speed.label')"
          />
          <p v-if="totalFrames > CAPE_MAX_FRAMES" class="mt-1 text-[11px] text-lamp-300">
            {{ t('capes.editor.sampled', { total: totalFrames, max: CAPE_MAX_FRAMES }) }}
          </p>
        </div>
      </div>

      <!-- Vorschau -->
      <div class="space-y-3">
        <div class="rounded-lg bg-base-900">
          <SkinViewer
            :skin="null"
            :cape="preview"
            :cape-frames="frames.length"
            :cape-frame-time="animated ? frameTime : null"
            animation="walk"
            :height="250"
          />
        </div>
        <div class="flex items-center gap-3">
          <div class="rounded-lg bg-base-900 p-2">
            <CapeThumb
              :texture="preview"
              :scale="scale"
              :frames="frames.length"
              :frame-time-ms="animated ? frameTime : null"
              :width="44"
            />
          </div>
          <dl class="text-[11px] text-base-400">
            <dt class="sr-only">{{ t('capes.editor.scale.label') }}</dt>
            <dd>{{ 64 * scale }}×{{ 32 * scale }} · {{ t('capes.editor.frameCount', frames.length) }}</dd>
            <dt class="sr-only">{{ t('capes.editor.sizeLabel') }}</dt>
            <dd :class="{ 'text-redstone-300': tooLarge }">{{ t('capes.editor.size', { size: formatFileSize(previewBytes) }) }}</dd>
          </dl>
        </div>
        <p v-if="!previewVisible" class="text-[11px] text-redstone-300">{{ t('capes.editor.errors.empty') }}</p>

        <label class="block">
          <span class="label">{{ t('capes.editor.nameLabel') }}</span>
          <input v-model="name" class="field" maxlength="32" :placeholder="t('capes.editor.placeholder')" @keydown.enter="upload" />
        </label>
        <p v-if="nameError" role="alert" class="text-xs text-redstone-300">{{ nameError }}</p>
        <ul class="list-disc space-y-1 pl-4 text-[11px] text-base-400">
          <li>{{ t('capes.editor.rules.review') }}</li>
          <li>{{ t('capes.editor.rules.rights') }}</li>
        </ul>
      </div>
    </div>

    <p v-if="error" role="alert" class="mt-3 text-xs text-redstone-300">{{ error }}</p>

    <template #actions>
      <button class="btn btn-ghost" @click="emit('close')">{{ t('common.actions.cancel') }}</button>
      <button
        v-if="loaded"
        class="btn btn-primary"
        :disabled="uploading || !preview || tooLarge || !previewVisible"
        data-testid="cape-editor-upload"
        @click="upload"
      >
        {{ uploading ? t('capes.editor.uploading') : t('capes.editor.upload') }}
      </button>
    </template>
  </BaseDialog>
</template>
