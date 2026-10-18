<script setup lang="ts">
import type { TrsCapeSource, TrsCapeVideo } from '~/utils/trs'
import type { FfmpegStatus } from '~/types'
import {
  CAPE_MAX_FRAMES,
  CAPE_MAX_SCALE,
  FRAME_TIME_DEFAULT,
  clampFrameTime,
  cropFor,
  detectTexture,
  guessSheetFor,
  naturalCompare,
  plausibleTexture,
  sampleFrames,
  splitSheet,
  textureFromFace,
  textureFromTexture,
  type Crop,
  type Rgba,
  type SheetGrid,
} from '~/utils/capeImage'
import { holdsFromDelays, newFrame, type CapeImportResult } from '~/utils/capeEditor'
import {
  HD_SCALE,
  colorHex,
  hexColor,
  renderFace,
  shrinkSource,
  suggestFit,
  suggestStyle,
  textureFromTextureHd,
  type Backdrop,
  type CapeStyle,
} from '~/utils/capeHd'
import type { FitMode } from '~/utils/capeImage'

// Schritt 1 der Umhang-Werkstatt: Quelle wählen (Bilder, GIF/APNG, Sprite-Sheet,
// TRS-Studio-Export oder ein Video-Ausschnitt), Reihenfolge und Ausschnitt festlegen.
// Stil „Pixel-Art“: Frames im Bearbeitungs-Faktor („Detail“), hochskaliert wird erst
// beim Hochladen. Stil „HD – wie Bild“: gleich in 1024×512 je Frame, glatt skaliert
// (Lanczos), volle Farben – die Vorschau ist dann ebenfalls glatt. Ausschnitt:
// „Füllen“ (Ränder abschneiden) oder „Einpassen“ (ganzes Bild, Rest Hintergrund).
const emit = defineEmits<{ done: [result: CapeImportResult] }>()

const toasts = useToasts()
const tasks = useTasksStore()

/** Mehrere Einzelbilder als Frames: jedes höchstens so groß (Speicher im Webview). */
const MAX_FRAME_SIDE = 2048
const MAX_ZOOM = 10
const FPS_CHOICES = [5, 8, 10, 12, 15, 20, 24, 30]

const picking = ref(false)
const error = ref<string | null>(null)

// --- Quelle -------------------------------------------------------------------------

interface Loaded {
  label: string
  kind: 'single' | 'images' | 'animation'
  /** Bilder vor dem Zerlegen (ein Bild = evtl. Sprite-Sheet). */
  images: Rgba[]
  /** Vorschaubilder für die Reihenfolge (gleiche Reihenfolge wie `images`). */
  urls: string[]
  /** Standzeiten je Frame (GIF, APNG, Video) – daraus Tempo und Standzeiten. */
  delays: number[] | null
  /** Frame-Zahl der Quelle vor dem Ausdünnen. */
  sourceFrames: number
  studioFrames: number | null
}

const loaded = shallowRef<Loaded | null>(null)
/** Reihenfolge der Einzelbilder (Indizes in `images`), per Ziehen änderbar. */
const order = ref<number[]>([])
const grid = reactive<SheetGrid>({ cols: 1, rows: 1, count: 1 })
const mode = ref<'texture' | 'crop'>('crop')
const zoom = ref(1)
const center = reactive({ x: 0, y: 0 })
/** Bearbeitungs-Faktor (64·k × 32·k); `null` = automatisch aus der Quelle. */
const detailChoice = ref<number | null>(null)
/** Stil; `null` = automatisch (Vorschlag aus der Quelle). */
const styleChoice = ref<CapeStyle | null>(null)
/** Ausschnitt: füllen (abschneiden) oder einpassen (ganzes Bild, Hintergrund drumherum). */
const fit = ref<FitMode>('cover')
const backdropKind = ref<'color' | 'blur' | 'transparent'>('blur')
const backdropColor = ref('#202127')
const backdrop = computed<Backdrop>(() =>
  backdropKind.value === 'blur'
    ? { kind: 'blur' }
    : { kind: 'color', color: backdropKind.value === 'transparent' ? [0, 0, 0, 0] : hexColor(backdropColor.value) },
)

/** Alle Frames der Quelle (nach dem Zerlegen), noch nicht ausgedünnt. */
const allFrames = computed<Rgba[]>(() => {
  const l = loaded.value
  if (!l) return []
  if (l.kind === 'single') return splitSheet(l.images[0]!, grid)
  return order.value.map((i) => l.images[i]!).filter(Boolean)
})
const totalFrames = computed(() => (loaded.value?.kind === 'animation' ? loaded.value.sourceFrames : allFrames.value.length))
/** Höchstens 32 gleichmäßig verteilte Frames. */
const picked = computed(() => sampleFrames(allFrames.value.length, CAPE_MAX_FRAMES))
const frames = computed(() => picked.value.map((i) => allFrames.value[i]!))
const first = computed(() => frames.value[0] ?? null)
/** Ist ein Frame schon eine Umhang-Textur (64:32 oder 22:17)? */
const textureLayout = computed(() => (first.value ? detectTexture(first.value.width, first.value.height, 1) : null))
const crop = computed<Crop | null>(() =>
  first.value ? cropFor(first.value.width, first.value.height, zoom.value, center.x, center.y, undefined, fit.value) : null,
)
const useTexture = computed(() => mode.value === 'texture' && !!textureLayout.value)

/** Stil-Vorschlag: hochskalierte Blöcke, kleine Quellen oder wenige harte Farben → Pixel-Art, sonst HD. */
const autoStyle = computed<CapeStyle>(() => {
  const f = first.value
  if (!f) return 'pixel'
  const faceHeight = useTexture.value ? 16 * textureLayout.value!.scale : cropFor(f.width, f.height, 1, 0, 0).h
  return suggestStyle(f, faceHeight)
})
const style = computed<CapeStyle>(() => styleChoice.value ?? autoStyle.value)
const hd = computed(() => style.value === 'hd')

/** Automatischer Faktor: HD → 16, Textur → ihr Faktor, Ausschnitt → so fein wie die Quelle (höchstens 16). */
const autoDetail = computed(() => {
  if (hd.value) return HD_SCALE
  if (useTexture.value) return clampScale(textureLayout.value!.scale)
  return clampScale((crop.value?.h ?? 16) / 16)
})
const detail = computed(() => (hd.value ? HD_SCALE : (detailChoice.value ?? autoDetail.value)))

function clampScale(v: number): number {
  return Math.max(1, Math.min(CAPE_MAX_SCALE, Math.round(v)))
}

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
  const animations = sources.filter((s) => s.kind === 'animation')
  const images = sources.filter((s) => s.kind === 'image').sort((a, b) => naturalCompare(a.name, b.name))
  if (!animations.length && !images.length) {
    error.value = t('capes.editor.errors.noImage')
    return
  }
  if (animations.length > 1 || (animations.length && images.length)) {
    error.value = t('capes.editor.errors.mixed')
    return
  }
  let next: Loaded
  try {
    const anim = animations[0]
    if (anim) {
      next = {
        label: anim.name,
        kind: 'animation',
        images: await Promise.all(anim.frames.map(loadRgba)),
        urls: anim.frames,
        delays: anim.delaysMs.length === anim.frames.length ? anim.delaysMs : null,
        sourceFrames: anim.sourceFrames,
        studioFrames: null,
      }
    } else {
      if (images.length > 1) {
        const [w, h] = [images[0]!.width, images[0]!.height]
        if (images.some((p) => p.width !== w || p.height !== h)) {
          error.value = t('capes.editor.errors.sizeMismatch')
          return
        }
        if (w > MAX_FRAME_SIDE || h > MAX_FRAME_SIDE) {
          error.value = t('capes.editor.errors.frameTooLarge')
          return
        }
      }
      // Mehr als 64 Einzelbilder kommen nie an; alle bleiben in der Reihenfolge-Liste,
      // ausgedünnt wird erst nach dem Sortieren.
      next = {
        label: images.length > 1 ? `${images[0]!.name} …` : images[0]!.name,
        kind: images.length > 1 ? 'images' : 'single',
        images: await Promise.all(images.map((p) => loadRgba(p.dataUrl))),
        urls: images.map((p) => p.dataUrl),
        delays: null,
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
  order.value = next.images.map((_, i) => i)
  detailChoice.value = null
  styleChoice.value = null
  const single = next.kind === 'single' ? next.images[0]! : null
  // Raster nur, wenn die Zellen wirklich Umhang-Texturen sind (kein quadratisches Logo in zwei „Frames“).
  Object.assign(grid, single ? guessSheetFor(single, next.studioFrames ?? undefined) : { cols: 1, rows: 1, count: 1 })
  await nextTick()
  const layout = textureLayout.value
  mode.value = layout && (next.studioFrames !== null || plausibleTexture(first.value!, layout)) ? 'texture' : 'crop'
  // Logos/Sticker (durchsichtiger oder einfarbiger Rand) einpassen statt abschneiden.
  const suggested = first.value ? suggestFit(first.value) : null
  fit.value = suggested?.fit ?? 'cover'
  if (suggested?.backdrop.kind === 'color') {
    backdropKind.value = 'color'
    backdropColor.value = colorHex(suggested.backdrop.color)
  } else backdropKind.value = 'blur'
  resetCrop()
}

function setFit(next: FitMode) {
  if (fit.value === next) return
  fit.value = next
  resetCrop()
}

// Sprite-Sheet geändert → Ausschnitt passt nicht mehr.
watch(() => [grid.cols, grid.rows], () => {
  grid.count = Math.min(Math.max(1, grid.cols * grid.rows), 64)
  resetCrop()
})

// --- Reihenfolge (mehrere Bilder) --------------------------------------------------

const dragFrom = ref<number | null>(null)
function dropAt(to: number) {
  const from = dragFrom.value
  dragFrom.value = null
  if (from === null || from === to) return
  const list = order.value.slice()
  const [item] = list.splice(from, 1)
  list.splice(to, 0, item!)
  order.value = list
}
function nudge(index: number, by: number) {
  const to = index + by
  if (to < 0 || to >= order.value.length) return
  dragFrom.value = index
  dropAt(to)
}
function reverseOrder() {
  order.value = order.value.slice().reverse()
}

// --- Vorschau (klein, schnell) ------------------------------------------------------

/**
 * Ein Frame als Umhang-Textur mit Faktor `s` – im gewählten Stil. `factor` < 1: die
 * Quelle ist für die Vorschau schon verkleinert (Ausschnitt entsprechend umrechnen).
 */
function textureOf(frame: Rgba, s: number, factor = 1): Rgba {
  if (useTexture.value) {
    return hd.value ? textureFromTextureHd(frame, textureLayout.value!.kind, s) : textureFromTexture(frame, textureLayout.value!.kind, s)
  }
  const c = crop.value ?? { x: 0, y: 0, w: frame.width / factor, h: frame.height / factor }
  const scaled = { x: c.x * factor, y: c.y * factor, w: c.w * factor, h: c.h * factor }
  return textureFromFace(renderFace(frame, scaled, s, { style: style.value, fit: fit.value, backdrop: backdrop.value }), s)
}

function toCanvas(img: Rgba): HTMLCanvasElement {
  const c = document.createElement('canvas')
  c.width = img.width
  c.height = img.height
  c.getContext('2d')?.putImageData(new ImageData(new Uint8ClampedArray(img.data), img.width, img.height), 0, 0)
  return c
}

const timing = computed(() => {
  const l = loaded.value
  const n = frames.value.length
  if (l?.delays && l.delays.length === n) return holdsFromDelays(l.delays)
  return { frameTime: clampFrameTime((FRAME_TIME_DEFAULT * Math.max(totalFrames.value, 1)) / Math.max(n, 1)), holds: frames.value.map(() => 1) }
})

/**
 * HD-Vorschau: Quelle einmal glatt verkleinert (lange Kante höchstens 1024 bzw. 512
 * bei vielen Frames) – an der Spielfigur sieht das genauso aus wie das Ergebnis,
 * rechnet aber ein Vielfaches schneller, während man den Ausschnitt verschiebt.
 */
const previewSources = computed(() => {
  if (!hd.value || useTexture.value) return null
  const maxSide = frames.value.length > 4 ? 512 : 1024
  return frames.value.map((f) => shrinkSource(f, maxSide))
})

const previewCanvases = shallowRef<HTMLCanvasElement[]>([])
let timer: ReturnType<typeof setTimeout> | null = null
let previewToken = 0
/** Vorschau Frame für Frame (mit Pausen, damit Ziehen flüssig bleibt); neuere Änderungen brechen ab. */
async function refreshPreview() {
  const mine = ++previewToken
  // HD in voller Auflösung (die Vorschau zeigt den Umhang groß von hinten); Pixel-Art im eigenen
  // Faktor (bis 8), damit die Vorschau keine gröberen Pixel zeigt als später hochgeladen.
  const s = hd.value ? HD_SCALE : Math.min(detail.value, 8)
  const list = frames.value
  const sources = previewSources.value
  const textures: HTMLCanvasElement[] = []
  for (let i = 0; i < list.length; i++) {
    if (i > 0) await new Promise((resolve) => setTimeout(resolve, 0))
    if (mine !== previewToken) return
    const src = sources?.[i]
    textures.push(toCanvas(src ? textureOf(src.image, s, src.factor) : textureOf(list[i]!, s)))
  }
  if (mine !== previewToken) return
  previewCanvases.value = textures.flatMap((c, i) => Array.from({ length: timing.value.holds[i] ?? 1 }, () => c))
}
watch(
  [frames, mode, detail, style, fit, backdrop, () => crop.value?.x, () => crop.value?.y, () => crop.value?.w],
  () => {
    if (timer) clearTimeout(timer)
    timer = setTimeout(() => void refreshPreview(), 120)
  },
)

// --- Ausschnitt (Leinwand mit Rahmen) -------------------------------------------------

const viewport = ref<HTMLCanvasElement | null>(null)
const frameCanvases = computed(() => frames.value.map(toCanvas))
/** Unscharfer Hintergrund fürs Einpassen (klein je Frame – die Leinwand streckt ihn weich). */
const backdropCanvases = computed(() => {
  if (fit.value !== 'contain' || backdropKind.value !== 'blur') return null
  return frames.value.map((f) => {
    const small = shrinkSource(f, 256).image
    // Ausschnitt neben dem Bild → nur der Hintergrund bleibt übrig.
    const empty = { x: -small.width - 1, y: 0, w: 1, h: 1 }
    return toCanvas(renderFace(small, empty, 8, { style: 'hd', fit: 'contain', backdrop: { kind: 'blur' } }))
  })
})

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
  const f = frames.value.length > 1 ? trsFrameIndex(Date.now(), frames.value.length, timing.value.frameTime) : 0
  const src = frameCanvases.value[f] ?? frameCanvases.value[0]
  const win = windowRect(el)
  const k = win.h / c.h
  ctx.clearRect(0, 0, el.width, el.height)
  // Einpassen: Hintergrund im Rahmen, darüber das ganze Bild.
  if (fit.value === 'contain' && backdropKind.value !== 'transparent') {
    const back = backdropCanvases.value?.[f] ?? backdropCanvases.value?.[0]
    ctx.imageSmoothingEnabled = true
    ctx.imageSmoothingQuality = 'high'
    if (back) ctx.drawImage(back, win.x, win.y, win.w, win.h)
    else if (backdropKind.value === 'color') {
      ctx.fillStyle = backdropColor.value
      ctx.fillRect(win.x, win.y, win.w, win.h)
    }
  }
  // HD: immer glatt (so wird es auch gerechnet); Pixel-Art: vergrößert scharf, verkleinert gemittelt.
  ctx.imageSmoothingEnabled = hd.value || k < 1
  ctx.imageSmoothingQuality = 'high'
  if (src) ctx.drawImage(src, win.x - c.x * k, win.y - c.y * k, src.width * k, src.height * k)
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
onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
  stopLoop()
})
// Der Dialog hält diesen Schritt am Leben: Leinwand nur zeichnen, solange er zu sehen ist.
onDeactivated(stopLoop)
onActivated(() => {
  if (mode.value === 'crop' && loaded.value && !raf) raf = requestAnimationFrame(loop)
})

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

// --- Video ----------------------------------------------------------------------------

const videoSupported = computed(() => platformCaps.value.clips)
const ffmpeg = ref<FfmpegStatus | null>(null)
const ffmpegDownload = computed(() => tasks.active.find((a) => a.kind === 'ffmpeg') ?? null)
const video = ref<TrsCapeVideo | null>(null)
const range = reactive({ start: 0, end: 0 })
const fps = ref(10)
const videoTaskKey = computed(() => (video.value ? taskKey('cape-video', video.value.token) : ''))
const extracting = computed(() => (videoTaskKey.value ? tasks.get(videoTaskKey.value) : null))
const extractRunning = computed(() => extracting.value?.status === 'running')
/** So viele Frames ergeben Bereich und Bildrate (höchstens 32). */
const videoFrames = computed(() => Math.max(1, Math.min(CAPE_MAX_FRAMES, Math.ceil(((range.end - range.start) * fps.value) / 1000))))
/** So viel vom Bereich passt in 32 Frames (Sekunden). */
const videoCovered = computed(() => Math.min(range.end - range.start, (CAPE_MAX_FRAMES * 1000) / fps.value) / 1000)

async function refreshFfmpeg() {
  try {
    ffmpeg.value = await backend.ffmpegStatus()
  } catch {
    ffmpeg.value = null
  }
}

function installFfmpeg() {
  void tasks
    .run(
      {
        key: taskKey('ffmpeg'),
        kind: 'ffmpeg',
        title: t('settings.clips.ffmpegTaskTitle'),
        stage: t('settings.clips.ffmpegTaskStage'),
        cancellable: true,
        pausable: true,
        doneText: t('settings.clips.ffmpegTaskDone'),
      },
      async (ctx) => {
        await backend.installFfmpeg((p) => ctx.progress(p), ctx.taskId)
      },
    )
    .then(async () => {
      await refreshFfmpeg()
      if (ffmpeg.value?.installed) void pickVideo()
    })
}

const videoIntro = ref(false)
async function startVideo() {
  error.value = null
  await refreshFfmpeg()
  if (ffmpeg.value?.installed) await pickVideo()
  else videoIntro.value = true
}

async function pickVideo() {
  if (picking.value) return
  picking.value = true
  error.value = null
  try {
    const picked = await backend.trs.pickCapeVideo()
    if (!picked) return
    videoIntro.value = false
    video.value = picked
    range.start = 0
    range.end = Math.min(picked.durationMs, Math.round((CAPE_MAX_FRAMES * 1000) / fps.value))
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    picking.value = false
  }
}

function setStart(ms: number) {
  range.start = Math.max(0, Math.min(ms, range.end - 100))
}
function setEnd(ms: number) {
  range.end = Math.min(video.value?.durationMs ?? ms, Math.max(ms, range.start + 100))
}

async function extractVideo() {
  const v = video.value
  if (!v) return
  error.value = null
  const result = await tasks.run(
    {
      key: videoTaskKey.value,
      kind: 'import',
      title: t('capes.studio.video.taskTitle', { name: v.name }),
      stage: t('capes.studio.video.taskStage'),
      cancellable: true,
      record: false,
      notify: false,
    },
    (ctx) => backend.trs.capeVideoFrames(v.token, { startMs: range.start, endMs: range.end, fps: fps.value }, (p) => ctx.progress(p), ctx.taskId),
  )
  if (result.ok) {
    video.value = null
    await use([result.value])
  } else if (!result.cancelled) {
    error.value = errorMessage(result.error)
  }
}

function cancelExtract() {
  if (videoTaskKey.value) void tasks.cancel(videoTaskKey.value)
}

function seconds(ms: number): string {
  return (ms / 1000).toFixed(1)
}

// --- Weiter ---------------------------------------------------------------------------

const preparing = ref<{ done: number; total: number } | null>(null)
const ready = computed(() => !!loaded.value && frames.value.length > 0 && !preparing.value)

/** Alle Frames im Bearbeitungs-Faktor bauen (Frame für Frame, die Oberfläche bleibt bedienbar). */
async function finish() {
  const l = loaded.value
  if (!l || !ready.value) return
  const s = detail.value
  const list = frames.value
  const holds = timing.value.holds
  preparing.value = { done: 0, total: list.length }
  try {
    const out = []
    for (let i = 0; i < list.length; i++) {
      await new Promise((resolve) => setTimeout(resolve, 0))
      out.push(newFrame(textureOf(list[i]!, s), holds[i] ?? 1))
      preparing.value = { done: i + 1, total: list.length }
    }
    emit('done', {
      doc: { scale: s, frames: out, style: style.value },
      frameTime: timing.value.frameTime,
      name: cleanName(l.label.replace(/ …$/, '')),
      sourceFrames: totalFrames.value,
    })
  } catch (e) {
    toasts.error(e)
  } finally {
    preparing.value = null
  }
}

/** Dateiname als Vorschlag – nur Zeichen, die der Server im Namen erlaubt. */
function cleanName(raw: string): string {
  const cleaned = raw.replace(/[_]+/g, ' ').replace(/[^\p{L}\p{N} .,'!?&()+-]/gu, '').trim().slice(0, 32)
  return trsCapeNameSchema.safeParse(cleaned).success ? cleaned : ''
}

const scales = Array.from({ length: CAPE_MAX_SCALE }, (_, i) => i + 1)
defineExpose({ finish, ready })
</script>

<template>
  <!-- Video: Bereich und Bildrate wählen -->
  <div v-if="video" class="grid gap-5 md:grid-cols-[minmax(0,1fr)_16rem]" data-testid="cape-video">
    <div class="space-y-4">
      <p class="text-sm text-base-200">
        <strong class="text-base-50">{{ video.name }}</strong>
        · {{ video.width }}×{{ video.height }} · {{ t('capes.studio.video.length', { s: seconds(video.durationMs) }) }}
      </p>
      <div class="space-y-3 rounded-lg bg-base-900 p-4">
        <label class="block">
          <span class="flex justify-between text-xs"><span class="label mb-0">{{ t('capes.studio.video.start') }}</span><span class="tabular-nums text-base-300">{{ seconds(range.start) }} s</span></span>
          <input :value="range.start" type="range" min="0" :max="video.durationMs" step="50" class="mt-1 w-full accent-redstone-500" :disabled="extractRunning" @input="setStart(Number(($event.target as HTMLInputElement).value))" />
        </label>
        <label class="block">
          <span class="flex justify-between text-xs"><span class="label mb-0">{{ t('capes.studio.video.end') }}</span><span class="tabular-nums text-base-300">{{ seconds(range.end) }} s</span></span>
          <input :value="range.end" type="range" min="0" :max="video.durationMs" step="50" class="mt-1 w-full accent-redstone-500" :disabled="extractRunning" @input="setEnd(Number(($event.target as HTMLInputElement).value))" />
        </label>
        <label class="block">
          <span class="label">{{ t('capes.studio.video.fps') }}</span>
          <select v-model.number="fps" class="field" :disabled="extractRunning">
            <option v-for="f in FPS_CHOICES" :key="f" :value="f">{{ t('capes.studio.video.fpsOption', { fps: f }) }}</option>
          </select>
        </label>
        <p class="text-xs text-base-300">
          {{ t('capes.studio.video.result', { frames: videoFrames, s: videoCovered.toFixed(1) }) }}
        </p>
        <p v-if="videoCovered * 1000 < range.end - range.start - 1" class="text-[11px] text-lamp-300">{{ t('capes.studio.video.capped') }}</p>
      </div>
      <div v-if="extractRunning" class="space-y-2" role="status">
        <RedstoneWire :percent="extracting?.percent ?? 0" :indeterminate="extracting?.percent == null" />
        <p class="text-xs text-base-400">{{ t('capes.studio.video.extracting', { percent: extracting?.percent ?? 0 }) }}</p>
      </div>
      <div class="flex flex-wrap gap-2">
        <button v-if="!extractRunning" class="btn btn-primary" data-testid="cape-video-extract" @click="extractVideo">{{ t('capes.studio.video.extract') }}</button>
        <button v-else class="btn btn-ghost" @click="cancelExtract">{{ t('common.actions.cancel') }}</button>
        <button class="btn btn-ghost" :disabled="extractRunning" @click="video = null">{{ t('capes.studio.video.back') }}</button>
      </div>
    </div>
    <ul class="space-y-1 self-start rounded-lg bg-base-900 p-3 text-[11px] text-base-400">
      <li>{{ t('capes.studio.video.hintRange') }}</li>
      <li>{{ t('capes.studio.video.hintLocal') }}</li>
    </ul>
  </div>

  <!-- FFmpeg fehlt noch -->
  <div v-else-if="videoIntro" class="mx-auto max-w-lg space-y-4 py-4 text-center" data-testid="cape-video-ffmpeg">
    <p class="text-sm text-base-200">{{ t('capes.studio.video.ffmpegLead', { size: formatFileSize(ffmpeg?.downloadBytes ?? 0) }) }}</p>
    <div v-if="ffmpegDownload" class="space-y-2" role="status">
      <RedstoneWire :percent="ffmpegDownload.percent ?? 0" />
      <p class="text-xs text-base-400">{{ t('settings.clips.ffmpegDownloading', { percent: Math.floor(ffmpegDownload.percent ?? 0) }) }}</p>
    </div>
    <div class="flex justify-center gap-2">
      <button class="btn btn-primary" :disabled="!!ffmpegDownload" @click="installFfmpeg">{{ t('settings.clips.ffmpegDownload') }}</button>
      <button class="btn btn-ghost" @click="videoIntro = false">{{ t('capes.studio.video.back') }}</button>
    </div>
  </div>

  <!-- Noch nichts gewählt -->
  <div v-else-if="!loaded" class="grid gap-6 py-2 md:grid-cols-[1fr_16rem]" data-testid="cape-editor-pick">
    <div>
      <p class="text-sm text-base-200">{{ t('capes.editor.pick.lead') }}</p>
      <ul class="mt-3 list-disc space-y-1 pl-4 text-xs text-base-400">
        <li>{{ t('capes.editor.pick.image') }}</li>
        <li>{{ t('capes.editor.pick.gif') }}</li>
        <li>{{ t('capes.editor.pick.frames') }}</li>
        <li>{{ t('capes.editor.pick.sheet') }}</li>
        <li>{{ t('capes.editor.pick.studio') }}</li>
        <li v-if="videoSupported">{{ t('capes.studio.pick.video') }}</li>
      </ul>
      <div class="mt-5 flex flex-wrap gap-2">
        <button class="btn btn-primary" :disabled="picking" data-testid="cape-editor-choose" @click="pick">
          {{ picking ? t('capes.editor.pick.loading') : t('capes.editor.pick.choose') }}
        </button>
        <button v-if="videoSupported" class="btn btn-ghost" :disabled="picking" data-testid="cape-editor-video" @click="startVideo">
          {{ t('capes.studio.pick.chooseVideo') }}
        </button>
      </div>
      <p v-if="mobileUi" class="mt-4 text-[11px] text-base-500">{{ t('capes.studio.pick.mobileHint') }}</p>
    </div>
    <ul class="space-y-1 self-start rounded-lg bg-base-900 p-3 text-[11px] text-base-400">
      <li>{{ t('capes.editor.rules.size') }}</li>
      <li>{{ t('capes.editor.rules.review') }}</li>
      <li>{{ t('capes.editor.rules.rights') }}</li>
    </ul>
  </div>

  <!-- Quelle einrichten -->
  <div v-else class="grid gap-5 md:grid-cols-[minmax(0,1fr)_17rem]" data-testid="cape-editor">
    <div class="min-w-0 space-y-4">
      <div class="flex flex-wrap items-center gap-2 text-xs">
        <span class="min-w-0 flex-1 truncate text-base-200">
          <strong class="text-base-50">{{ loaded.label }}</strong>
          · {{ first?.width }}×{{ first?.height }}
          <template v-if="totalFrames > 1"> · {{ t('capes.editor.frameCount', totalFrames) }}</template>
        </span>
        <button class="btn btn-ghost px-3 py-1 text-xs" :disabled="picking" @click="pick">{{ t('capes.editor.change') }}</button>
        <button v-if="videoSupported" class="btn btn-ghost px-3 py-1 text-xs" :disabled="picking" @click="startVideo">{{ t('capes.studio.pick.chooseVideo') }}</button>
      </div>

      <!-- Reihenfolge der Einzelbilder -->
      <div v-if="loaded.kind === 'images'" class="space-y-1.5">
        <div class="flex items-center justify-between">
          <span class="label mb-0">{{ t('capes.studio.order.label') }}</span>
          <button class="btn btn-ghost px-2 py-0.5 text-[11px]" @click="reverseOrder">{{ t('capes.studio.order.reverse') }}</button>
        </div>
        <ol class="flex gap-1.5 overflow-x-auto pb-1" :aria-label="t('capes.studio.order.label')">
          <li
            v-for="(src, pos) in order"
            :key="src"
            draggable="true"
            class="group relative shrink-0 cursor-grab rounded-md bg-base-900 p-1 ring-1 ring-base-800 active:cursor-grabbing"
            :class="{ 'opacity-50': dragFrom === pos }"
            @dragstart="dragFrom = pos"
            @dragend="dragFrom = null"
            @dragover.prevent
            @drop.prevent="dropAt(pos)"
          >
            <img :src="loaded.urls[src]" alt="" class="h-12 w-auto max-w-16 object-contain" :class="{ '[image-rendering:pixelated]': !hd }" draggable="false" />
            <span class="absolute top-0.5 left-1 text-[10px] font-semibold text-white drop-shadow">{{ pos + 1 }}</span>
            <span class="absolute inset-x-0 bottom-0 hidden justify-between px-0.5 group-hover:flex group-focus-within:flex">
              <button class="rounded bg-black/60 px-1 text-[10px] text-white" :aria-label="t('capes.studio.order.earlier')" @click="nudge(pos, -1)">‹</button>
              <button class="rounded bg-black/60 px-1 text-[10px] text-white" :aria-label="t('capes.studio.order.later')" @click="nudge(pos, 1)">›</button>
            </span>
          </li>
        </ol>
        <p class="text-[11px] text-base-600">{{ t('capes.studio.order.hint') }}</p>
      </div>

      <!-- Stil: Pixel-Art oder HD (automatisch vorgeschlagen, umschaltbar) -->
      <div class="space-y-1.5">
        <div class="flex items-center justify-between gap-2">
          <span class="label mb-0">{{ t('capes.studio.style.label') }}</span>
          <span v-if="styleChoice === null" class="text-[11px] text-base-500">{{ t('capes.studio.style.auto') }}</span>
          <button v-else class="btn btn-ghost px-2 py-0.5 text-[11px]" @click="styleChoice = null">{{ t('capes.studio.style.useAuto') }}</button>
        </div>
        <div class="flex gap-1 rounded-lg bg-base-900 p-1 text-xs" role="radiogroup" :aria-label="t('capes.studio.style.label')" data-testid="cape-style">
          <button
            v-for="st in (['pixel', 'hd'] as const)"
            :key="st"
            class="seg flex-1 rounded-md"
            :class="{ 'seg-on': style === st }"
            role="radio"
            :aria-checked="style === st"
            :data-testid="`cape-style-${st}`"
            @click="styleChoice = st"
          >
            {{ t(`capes.studio.style.${st}`) }}
          </button>
        </div>
        <p class="text-[11px] text-base-500">{{ hd ? t('capes.studio.style.hdHint') : t('capes.studio.style.pixelHint') }}</p>
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

        <!-- Füllen (abschneiden) oder Einpassen (ganzes Bild + Hintergrund) -->
        <div class="mt-3 grid gap-3 sm:grid-cols-2">
          <div>
            <span class="label">{{ t('capes.studio.fit.label') }}</span>
            <div class="flex gap-1 rounded-lg bg-base-900 p-1 text-xs" role="radiogroup" :aria-label="t('capes.studio.fit.label')" data-testid="cape-fit">
              <button
                v-for="m in (['cover', 'contain'] as const)"
                :key="m"
                class="seg flex-1 rounded-md"
                :class="{ 'seg-on': fit === m }"
                role="radio"
                :aria-checked="fit === m"
                :data-testid="`cape-fit-${m}`"
                @click="setFit(m)"
              >
                {{ t(`capes.studio.fit.${m}`) }}
              </button>
            </div>
            <span class="mt-1 block text-[11px] text-base-600">{{ fit === 'cover' ? t('capes.studio.fit.coverHint') : t('capes.studio.fit.containHint') }}</span>
          </div>
          <div v-if="fit === 'contain'">
            <span class="label">{{ t('capes.studio.fit.backdrop') }}</span>
            <div class="flex items-center gap-2">
              <select v-model="backdropKind" class="field min-w-0 flex-1" data-testid="cape-backdrop">
                <option value="color">{{ t('capes.studio.fit.backdropColor') }}</option>
                <option value="blur">{{ t('capes.studio.fit.backdropBlur') }}</option>
                <option value="transparent">{{ t('capes.studio.fit.backdropTransparent') }}</option>
              </select>
              <input
                v-if="backdropKind === 'color'"
                v-model="backdropColor"
                type="color"
                class="h-9 w-12 shrink-0 cursor-pointer rounded border border-base-700 bg-base-900"
                :aria-label="t('capes.studio.fit.backdropColor')"
              />
            </div>
            <span v-if="backdropKind === 'transparent'" class="mt-1 block text-[11px] text-lamp-300">{{ t('capes.studio.fit.transparentHint') }}</span>
          </div>
        </div>
      </div>

      <div class="grid gap-3 sm:grid-cols-2">
        <fieldset v-if="loaded.kind === 'single'" class="block">
          <legend class="label">{{ t('capes.editor.sheet.label') }}</legend>
          <div class="flex items-center gap-1.5 text-xs text-base-400">
            <input v-model.number="grid.cols" type="number" min="1" max="64" class="field w-16" :aria-label="t('capes.studio.sheet.cols')" />
            <span aria-hidden="true">×</span>
            <input v-model.number="grid.rows" type="number" min="1" max="64" class="field w-16" :aria-label="t('capes.studio.sheet.rows')" />
            <span class="ml-1">{{ t('capes.studio.sheet.count') }}</span>
            <input v-model.number="grid.count" type="number" min="1" :max="Math.min(64, grid.cols * grid.rows)" class="field w-16" :aria-label="t('capes.studio.sheet.count')" />
          </div>
          <span class="mt-1 block text-[11px] text-base-600">{{ t('capes.editor.sheet.hint') }}</span>
        </fieldset>
        <div v-if="hd" class="rounded-lg bg-base-900 p-3 text-[11px] text-base-400" data-testid="cape-hd-info">
          <span class="label">{{ t('capes.studio.detail.label') }}</span>
          <p class="text-sm font-semibold text-base-50 tabular-nums">1024×512</p>
          <p class="mt-0.5">{{ t('capes.studio.style.hdDetail') }}</p>
        </div>
        <label v-else class="block">
          <span class="label">{{ t('capes.studio.detail.label') }}</span>
          <select :value="detailChoice ?? 0" class="field" @change="detailChoice = Number(($event.target as HTMLSelectElement).value) || null">
            <option :value="0">{{ t('capes.studio.detail.auto', { w: 64 * autoDetail, h: 32 * autoDetail }) }}</option>
            <option v-for="k in scales" :key="k" :value="k">{{ t('capes.editor.scale.option', { w: 64 * k, h: 32 * k, k }) }}</option>
          </select>
          <span class="mt-1 block text-[11px] text-base-600">{{ t('capes.studio.detail.hint') }}</span>
        </label>
      </div>

      <p v-if="totalFrames > CAPE_MAX_FRAMES" class="text-[11px] text-lamp-300">
        {{ t('capes.editor.sampled', { total: totalFrames, max: CAPE_MAX_FRAMES }) }}
      </p>
    </div>

    <!-- Vorschau -->
    <div class="space-y-3">
      <div class="rounded-lg bg-base-900">
        <SkinViewer
          :skin="null"
          :cape-canvases="previewCanvases"
          :cape-frame-time="previewCanvases.length > 1 ? timing.frameTime : null"
          :cape-smooth="hd"
          focus="cape"
          animation="walk"
          :height="250"
        />
      </div>
      <p class="text-[11px] text-base-400">
        {{ t('capes.studio.importSummary', { n: frames.length, w: 64 * detail, h: 32 * detail }) }}
      </p>
      <div v-if="preparing" class="space-y-1.5" role="status">
        <RedstoneWire :percent="(preparing.done / preparing.total) * 100" />
        <p class="text-[11px] text-base-400">{{ t('capes.studio.preparing', { done: preparing.done, total: preparing.total }) }}</p>
      </div>
    </div>
  </div>

  <p v-if="error" role="alert" class="mt-3 text-xs text-redstone-300">{{ error }}</p>
</template>
