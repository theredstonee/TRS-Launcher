<script setup lang="ts">
// Logo zuschneiden (§44.4 PUT …/logo): quadratischer Rahmen mit rundem Ausschnitt, ziehen, zoomen (Regler, Mausrad,
// zwei Finger), 90° drehen, Hintergrund für transparente Logos. Vorschau in den echten Größen aus einem 128er-Bild
// wie auf dem Server; heraus kommt ein quadratisches PNG (512, bei > 1 MB kleiner).
const props = defineProps<{ file: File, name: string }>()
const emit = defineEmits<{ done: [blob: Blob, size: number], close: [], error: [key: string] }>()

const { fill } = useLang()
const { p } = usePartnerText()
const t = computed(() => p.value.dash.cropper)

const dialog = shallowRef<HTMLElement | null>(null)
const stageBox = shallowRef<HTMLElement | null>(null)
const canvas = shallowRef<HTMLCanvasElement | null>(null)
const img = shallowRef<ImageBitmap | HTMLImageElement | null>(null)
const state = ref<CropState>({ rot: 0, zoom: 1, ox: 0, oy: 0 })
const bg = ref<CropBackground>('none')
const stage = ref(320)
const preview = ref<string | null>(null)
const busy = ref(false)
const tooBig = ref(false)
const OUT = LOGO_OUTPUT_SIZES[0] ?? 512
const uid = useId()
let before: HTMLElement | null = null

// Rahmen = 76 % der Bühne, der Rand zeigt das Bild abgedunkelt.
const FRAME = 0.76
const frame = computed(() => stage.value * FRAME)
const inset = computed(() => (stage.value - frame.value) / 2)
const dims = computed(() => ({ w: img.value?.width ?? 1, h: img.value?.height ?? 1 }))
const zMin = computed(() => minZoom(dims.value.w, dims.value.h))
/** Bildschirm-Pixel je Bild-Pixel. */
const scale = computed(() => frame.value / frameSize(dims.value.w, dims.value.h, state.value.rot, state.value.zoom))
const zoomPct = computed(() => Math.round(state.value.zoom * 100))

function set(s: CropState) {
  state.value = clampCrop(s, dims.value.w, dims.value.h)
}

async function load() {
  try {
    img.value = await createImageBitmap(props.file)
  } catch {
    try {
      const url = URL.createObjectURL(props.file)
      const el = new Image()
      el.decoding = 'async'
      el.src = url
      await el.decode()
      URL.revokeObjectURL(url)
      img.value = el
    } catch {
      emit('error', 'read')
      return
    }
  }
  if (Math.min(img.value.width, img.value.height) < 32) {
    emit('error', 'small')
    return
  }
  state.value = initialCrop(img.value.width, img.value.height)
}

// ---------------------------------------------------------------- Zeichnen
let raf = 0
function schedule() {
  if (!raf) raf = requestAnimationFrame(paint)
}
const small = import.meta.client ? document.createElement('canvas') : null
function paint() {
  raf = 0
  const c = canvas.value
  const i = img.value
  if (!c || !i) return
  const dpr = Math.min(2, window.devicePixelRatio || 1)
  const px = Math.round(stage.value * dpr)
  if (c.width !== px) {
    c.width = px
    c.height = px
  }
  const g = c.getContext('2d')!
  g.setTransform(dpr, 0, 0, dpr, 0, 0)
  g.clearRect(0, 0, stage.value, stage.value)
  // Hintergrund: Schachbrett für transparent, sonst die gewählte Farbe (nur im Rahmen).
  checker(g, stage.value)
  if (bg.value !== 'none') {
    g.fillStyle = CROP_BACKGROUNDS[bg.value]
    g.fillRect(inset.value, inset.value, frame.value, frame.value)
  }
  drawCrop(g, i, state.value, frame.value, inset.value)
  // Außerhalb des Rahmens stark, außerhalb des Kreises leicht abdunkeln.
  const r = frame.value / 2
  const m = stage.value / 2
  g.save()
  g.beginPath()
  g.rect(0, 0, stage.value, stage.value)
  g.rect(inset.value, inset.value, frame.value, frame.value)
  g.fillStyle = 'rgb(8 9 12 / 0.72)'
  g.fill('evenodd')
  g.beginPath()
  g.rect(inset.value, inset.value, frame.value, frame.value)
  g.arc(m, m, r, 0, Math.PI * 2)
  g.fillStyle = 'rgb(8 9 12 / 0.45)'
  g.fill('evenodd')
  g.lineWidth = 2
  g.strokeStyle = 'rgb(255 255 255 / 0.9)'
  g.beginPath()
  g.arc(m, m, r - 1, 0, Math.PI * 2)
  g.stroke()
  g.setLineDash([5, 5])
  g.lineWidth = 1
  g.strokeStyle = 'rgb(255 255 255 / 0.45)'
  g.strokeRect(inset.value + 0.5, inset.value + 0.5, frame.value - 1, frame.value - 1)
  g.restore()
  // Vorschau: wie der Server (128×128).
  if (small) {
    small.width = 128
    small.height = 128
    const s = small.getContext('2d')!
    render(s, 128)
    preview.value = small.toDataURL('image/png')
  }
}
function checker(g: CanvasRenderingContext2D, size: number) {
  const q = 12
  g.fillStyle = '#2a2d35'
  g.fillRect(0, 0, size, size)
  g.fillStyle = '#353943'
  for (let y = 0; y < size; y += q) {
    for (let x = (y / q) % 2 ? q : 0; x < size; x += q * 2) g.fillRect(x, y, q, q)
  }
}
function render(g: CanvasRenderingContext2D, size: number) {
  g.clearRect(0, 0, size, size)
  if (bg.value !== 'none') {
    g.fillStyle = CROP_BACKGROUNDS[bg.value]
    g.fillRect(0, 0, size, size)
  }
  if (img.value) drawCrop(g, img.value, state.value, size)
}
watch([state, bg, stage, img], schedule, { deep: true })

// ---------------------------------------------------------------- Ziehen, Zoomen (Zeiger, Mausrad, zwei Finger)
const pointers = new Map<number, { x: number, y: number }>()
let pinch: { dist: number, mx: number, my: number } | null = null
const dragging = ref(false)
function rel(e: { clientX: number, clientY: number }) {
  const r = canvas.value!.getBoundingClientRect()
  return { x: e.clientX - r.left - r.width / 2, y: e.clientY - r.top - r.height / 2 }
}
function onDown(e: PointerEvent) {
  if (!img.value) return
  canvas.value?.setPointerCapture(e.pointerId)
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  dragging.value = true
  if (pointers.size === 2) pinch = pinchOf()
}
function pinchOf() {
  const [a, b] = [...pointers.values()]
  const m = rel({ clientX: (a!.x + b!.x) / 2, clientY: (a!.y + b!.y) / 2 })
  return { dist: Math.hypot(a!.x - b!.x, a!.y - b!.y) || 1, mx: m.x, my: m.y }
}
function onMove(e: PointerEvent) {
  const last = pointers.get(e.pointerId)
  if (!last) return
  const dx = e.clientX - last.x
  const dy = e.clientY - last.y
  pointers.set(e.pointerId, { x: e.clientX, y: e.clientY })
  const { w, h } = dims.value
  if (pointers.size >= 2 && pinch) {
    const now = pinchOf()
    let s = zoomAt(state.value, state.value.zoom * (now.dist / pinch.dist), now.mx, now.my, frame.value, w, h)
    s = panBy(s, now.mx - pinch.mx, now.my - pinch.my, frame.value / frameSize(w, h, s.rot, s.zoom), w, h)
    state.value = s
    pinch = now
  } else if (pointers.size === 1) {
    state.value = panBy(state.value, dx, dy, scale.value, w, h)
  }
}
function onUp(e: PointerEvent) {
  pointers.delete(e.pointerId)
  pinch = pointers.size === 2 ? pinchOf() : null
  if (!pointers.size) dragging.value = false
}
function onWheel(e: WheelEvent) {
  if (!img.value) return
  e.preventDefault()
  const at = rel(e)
  const k = Math.exp(-e.deltaY * (e.deltaMode === 1 ? 0.05 : 0.0015))
  state.value = zoomAt(state.value, state.value.zoom * k, at.x, at.y, frame.value, dims.value.w, dims.value.h)
}
function onStageKey(e: KeyboardEvent) {
  const step = (e.shiftKey ? 0.1 : 0.02) * frame.value
  const move: Record<string, [number, number]> = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }
  const mv = move[e.key]
  if (mv) {
    e.preventDefault()
    state.value = panBy(state.value, mv[0], mv[1], scale.value, dims.value.w, dims.value.h)
  } else if (e.key === '+' || e.key === '=') {
    e.preventDefault()
    zoomBy(1.1)
  } else if (e.key === '-' || e.key === '_') {
    e.preventDefault()
    zoomBy(1 / 1.1)
  } else if (e.key === 'r' || e.key === 'R') {
    e.preventDefault()
    rotate()
  }
}
function zoomBy(k: number) {
  state.value = zoomAt(state.value, state.value.zoom * k, 0, 0, frame.value, dims.value.w, dims.value.h)
}
function onSlider(e: Event) {
  const z = Number((e.target as HTMLInputElement).value) / 100
  state.value = zoomAt(state.value, z, 0, 0, frame.value, dims.value.w, dims.value.h)
}
const rotate = () => (state.value = rotateCrop(state.value, dims.value.w, dims.value.h))
const fit = () => set({ ...state.value, zoom: zMin.value, ox: 0, oy: 0 })
const fillFrame = () => set({ ...state.value, zoom: 1, ox: 0, oy: 0 })
const reset = () => {
  if (img.value) state.value = initialCrop(img.value.width, img.value.height)
  bg.value = 'none'
}

// ---------------------------------------------------------------- Übernehmen
async function apply() {
  if (!img.value || busy.value) return
  busy.value = true
  tooBig.value = false
  try {
    for (const size of LOGO_OUTPUT_SIZES) {
      const c = document.createElement('canvas')
      c.width = size
      c.height = size
      render(c.getContext('2d')!, size)
      const blob = await new Promise<Blob | null>((r) => c.toBlob(r, 'image/png'))
      if (blob && blob.size <= LOGO_UPLOAD_MAX_BYTES) {
        emit('done', blob, size)
        return
      }
    }
    tooBig.value = true
  } finally {
    busy.value = false
  }
}

// ---------------------------------------------------------------- Fenster
const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])'
function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.preventDefault()
    emit('close')
    return
  }
  if (e.key !== 'Tab' || !dialog.value) return
  const items = [...dialog.value.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((el) => el.offsetParent !== null)
  if (!items.length) return
  const first = items[0]!
  const last = items[items.length - 1]!
  if (e.shiftKey && (document.activeElement === first || !dialog.value.contains(document.activeElement))) {
    e.preventDefault()
    last.focus()
  } else if (!e.shiftKey && document.activeElement === last) {
    e.preventDefault()
    first.focus()
  }
}
let ro: ResizeObserver | null = null
onMounted(async () => {
  before = document.activeElement as HTMLElement | null
  document.addEventListener('keydown', onKey)
  document.documentElement.classList.add('modal-open')
  ro = new ResizeObserver(([entry]) => {
    const w = Math.floor(entry?.contentRect.width ?? 320)
    if (w > 0) stage.value = Math.min(380, w)
  })
  if (stageBox.value) ro.observe(stageBox.value)
  canvas.value?.addEventListener('wheel', onWheel, { passive: false })
  await load()
  await nextTick()
  canvas.value?.focus()
  schedule()
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKey)
  document.documentElement.classList.remove('modal-open')
  canvas.value?.removeEventListener('wheel', onWheel)
  ro?.disconnect()
  if (raf) cancelAnimationFrame(raf)
  if (img.value && 'close' in img.value) img.value.close()
  before?.focus?.()
})

const SIZES = [
  { key: 'popup', px: 112 },
  { key: 'tile', px: 104 },
  { key: 'home', px: 60 },
  { key: 'banner', px: 40 },
] as const
</script>

<template>
  <div class="crop-backdrop" @click.self="emit('close')">
    <div ref="dialog" class="crop-modal" role="dialog" aria-modal="true" :aria-labelledby="`${uid}-title`" :aria-describedby="`${uid}-lead`">
      <header class="flex items-start gap-3">
        <div class="min-w-0 flex-1">
          <h2 :id="`${uid}-title`" class="text-xl font-bold text-base-50">{{ t.title }}</h2>
          <p :id="`${uid}-lead`" class="mt-1 text-sm text-base-400">{{ t.lead }}</p>
        </div>
        <button type="button" class="btn-icon" :aria-label="t.cancel" @click="emit('close')"><SiteIcon name="close" class="size-5" /></button>
      </header>

      <div class="crop-body mt-4">
        <div>
          <div ref="stageBox" class="stage-box">
            <canvas
              ref="canvas"
              class="stage"
              :class="{ dragging }"
              :style="{ width: `${stage}px`, height: `${stage}px` }"
              tabindex="0"
              role="img"
              :aria-label="`${t.stage}: ${name}`"
              :aria-describedby="`${uid}-keys`"
              @pointerdown="onDown"
              @pointermove="onMove"
              @pointerup="onUp"
              @pointercancel="onUp"
              @keydown="onStageKey"
            />
            <div v-if="!img" class="stage-wait skeleton" />
          </div>
          <p :id="`${uid}-keys`" class="mt-2 text-xs text-base-400">{{ t.keys }}</p>
        </div>

        <div class="grid content-start gap-4">
          <div>
            <label :for="`${uid}-zoom`" class="flex items-baseline text-xs font-semibold tracking-wider text-base-300 uppercase">
              <span class="flex-1">{{ t.zoom }}</span><span class="text-base-400 tabular-nums">{{ zoomPct }} %</span>
            </label>
            <div class="mt-1.5 flex items-center gap-2">
              <button type="button" class="btn-icon size-8" :aria-label="`${t.zoom} −`" :disabled="state.zoom <= zMin + 1e-6" @click="zoomBy(1 / 1.2)"><SiteIcon name="minus" class="size-4" /></button>
              <input :id="`${uid}-zoom`" type="range" class="zoom" :min="Math.floor(zMin * 100)" :max="CROP_MAX_ZOOM * 100" step="1" :value="zoomPct" :aria-valuetext="`${zoomPct} %`" @input="onSlider" />
              <button type="button" class="btn-icon size-8" :aria-label="`${t.zoom} +`" :disabled="state.zoom >= CROP_MAX_ZOOM" @click="zoomBy(1.2)"><SiteIcon name="plus" class="size-4" /></button>
            </div>
          </div>

          <div class="flex flex-wrap gap-2">
            <button type="button" class="btn btn-ghost h-9 px-3 text-sm" @click="rotate"><SiteIcon name="rotate" class="size-4" />{{ t.rotate }}</button>
            <button type="button" class="btn btn-ghost h-9 px-3 text-sm" @click="fit"><SiteIcon name="fit" class="size-4" />{{ t.fit }}</button>
            <button type="button" class="btn btn-ghost h-9 px-3 text-sm" @click="fillFrame">{{ t.fill }}</button>
            <button type="button" class="btn btn-ghost h-9 px-3 text-sm" @click="reset"><SiteIcon name="refresh" class="size-4" />{{ t.reset }}</button>
          </div>

          <fieldset>
            <legend class="text-xs font-semibold tracking-wider text-base-300 uppercase">{{ t.background }}</legend>
            <div class="bg-seg mt-1.5" role="radiogroup" :aria-label="t.background">
              <label v-for="b in (['none', 'white', 'dark'] as const)" :key="b" class="bg-opt" :data-on="bg === b">
                <input v-model="bg" type="radio" :name="`${uid}-bg`" :value="b" class="sr-only" />
                <span class="swatch" :data-bg="b" aria-hidden="true" />{{ t.bg[b] }}
              </label>
            </div>
            <p class="mt-1 text-xs text-base-400">{{ t.bgHint }}</p>
          </fieldset>

          <div>
            <p class="text-xs font-semibold tracking-wider text-base-300 uppercase">{{ t.preview }}</p>
            <ul class="sizes mt-2">
              <li v-for="s in SIZES" :key="s.key">
                <PartnerLogo :logo="preview" :name="name" :size="s.px" />
                <span class="mt-1 block text-[11px] leading-tight text-base-400">{{ t.sizes[s.key] }}<br /><span class="tabular-nums text-base-500">{{ s.px }} px</span></span>
              </li>
            </ul>
          </div>
        </div>
      </div>

      <p v-if="tooBig" role="alert" class="mt-4 text-sm text-redstone-300">{{ t.errors.big }}</p>
      <footer class="mt-5 flex flex-wrap items-center gap-3">
        <p class="w-full text-xs text-base-400 sm:w-auto sm:min-w-0 sm:flex-1">{{ fill(t.output, { size: OUT }) }}</p>
        <button type="button" class="btn btn-ghost" @click="emit('close')">{{ t.cancel }}</button>
        <button type="button" class="btn btn-primary" :disabled="!img || busy" @click="apply"><SiteIcon name="check" class="size-4" />{{ t.apply }}</button>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.crop-backdrop {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: grid;
  place-items: center;
  padding: 1rem;
  background: color-mix(in srgb, black 70%, transparent);
  backdrop-filter: blur(3px);
}
.crop-modal {
  width: min(52rem, 100%);
  max-height: calc(100dvh - 2rem);
  overflow-y: auto;
  overscroll-behavior: contain;
  border-radius: 1.1rem;
  border: 1px solid var(--color-base-800);
  background: var(--color-base-900);
  box-shadow: 0 30px 80px -20px rgb(0 0 0 / 0.75);
  padding: 1.25rem;
}
.crop-body {
  display: grid;
  gap: 1.25rem;
}
@media (min-width: 720px) {
  .crop-modal {
    padding: 1.5rem;
  }
  .crop-body {
    grid-template-columns: minmax(0, 380px) 1fr;
  }
}
.stage-box {
  position: relative;
  width: 100%;
  max-width: 380px;
}
.stage {
  display: block;
  border-radius: 0.75rem;
  cursor: grab;
  touch-action: none;
  user-select: none;
}
.stage.dragging {
  cursor: grabbing;
}
.stage:focus-visible {
  outline: 2px solid var(--color-redstone-400);
  outline-offset: 3px;
}
.stage-wait {
  position: absolute;
  inset: 0;
  border-radius: 0.75rem;
}
.zoom {
  flex: 1;
  accent-color: var(--color-redstone-500);
  min-width: 0;
}
.bg-seg {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
}
.bg-opt {
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  border-radius: 999px;
  border: 1px solid var(--color-base-700);
  background: var(--color-base-850);
  padding: 0.35rem 0.75rem 0.35rem 0.4rem;
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--color-base-200);
  cursor: pointer;
}
.bg-opt[data-on='true'] {
  border-color: var(--color-redstone-500);
  color: var(--color-base-50);
  background: color-mix(in srgb, var(--color-redstone-500) 14%, var(--color-base-850));
}
.bg-opt:has(input:focus-visible) {
  outline: 2px solid var(--color-redstone-400);
  outline-offset: 2px;
}
.swatch {
  width: 1.1rem;
  height: 1.1rem;
  border-radius: 999px;
  border: 1px solid var(--color-base-600);
}
.swatch[data-bg='none'] {
  background: repeating-conic-gradient(#3a3e48 0 25%, #262930 0 50%) 0 0 / 8px 8px;
}
.swatch[data-bg='white'] {
  background: #fff;
}
.swatch[data-bg='dark'] {
  background: #14161b;
}
.sizes {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 0.9rem 0.8rem;
}
.sizes li {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}
</style>
