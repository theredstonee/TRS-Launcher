<script setup lang="ts">
import { getCurrentWindow } from '@tauri-apps/api/window'
import { isTauri } from '@tauri-apps/api/core'
import type { Clip, ClipMediaInfo, ClipStrip, TrimMode } from '~/types'

// Player für einen Clip: eigene Bedienleiste (Zeitleiste mit Vorschaubildern,
// Lautstärke, Tempo, Vollbild, Tastenkürzel) und Zuschneiden als neuer Clip.
// Das Video kommt über das Protokoll `trsclip:` (nur Dateien im Clip-Ordner).
const props = defineProps<{
  clip: Clip
  /** Sichtbare Liste (für vorherigen/nächsten Clip). */
  list: Clip[]
  /** Ein Dialog liegt darüber – dann keine Tastenkürzel. */
  keysPaused?: boolean
  /** Direkt mit dem Zuschneiden beginnen. */
  startTrim?: boolean
}>()
const emit = defineEmits<{
  close: []
  select: [clip: Clip]
  rename: [clip: Clip]
  delete: [clip: Clip]
  created: [clip: Clip]
}>()

const toasts = useToasts()
const video = ref<HTMLVideoElement | null>(null)
const root = ref<HTMLElement | null>(null)
const track = ref<HTMLElement | null>(null)

const src = computed(() => clipUrl('v', props.clip))
const playing = ref(false)
const currentMs = ref(0)
const videoDurationMs = ref(0)
const bufferedMs = ref(0)
const rate = ref(1)
const fullscreen = ref(false)
const unsupported = ref(false)
const info = ref<ClipMediaInfo | null>(null)
const strip = ref<ClipStrip | null>(null)
const stripUrl = computed(() => (strip.value ? clipUrl('s', props.clip) : ''))

const durationMs = computed(() => videoDurationMs.value || info.value?.durationMs || props.clip.durationMs || 0)
/** Zuschneiden aktiv (oben deklariert – der Clip-Wechsel setzt es zurück). */
const trimming = ref(false)

// --- Lautstärke (pro Nutzer gemerkt) ------------------------------------------------
const VOLUME_KEY = 'trs.player.volume'
function storedVolume(): { volume: number; muted: boolean } {
  try {
    const raw = JSON.parse(localStorage.getItem(VOLUME_KEY) ?? 'null') as { volume?: unknown; muted?: unknown } | null
    const volume = typeof raw?.volume === 'number' ? clamp(raw.volume, 0, 1) : 1
    return { volume, muted: raw?.muted === true }
  } catch {
    return { volume: 1, muted: false }
  }
}
const volume = ref(storedVolume().volume)
const muted = ref(storedVolume().muted)
watch([volume, muted], () => {
  if (video.value) {
    video.value.volume = volume.value
    video.value.muted = muted.value
  }
  try {
    localStorage.setItem(VOLUME_KEY, JSON.stringify({ volume: volume.value, muted: muted.value }))
  } catch {
    // Kein Speicher – egal.
  }
})

// --- Laden --------------------------------------------------------------------------
let loadToken = 0
async function loadMeta() {
  const token = ++loadToken
  info.value = null
  strip.value = null
  const { instanceId, fileName } = props.clip
  backend
    .clipDetails(instanceId, fileName)
    .then((i) => token === loadToken && (info.value = i))
    .catch(() => {})
  backend
    .clipStrip(instanceId, fileName)
    .then((s) => token === loadToken && (strip.value = s))
    .catch(() => {})
}

watch(
  () => clipKey(props.clip),
  () => {
    currentMs.value = 0
    videoDurationMs.value = 0
    bufferedMs.value = 0
    unsupported.value = typeof document !== 'undefined' && !canPlayMp4((t) => document.createElement('video').canPlayType(t))
    trimming.value = false
    void loadMeta()
  },
  { immediate: true },
)

onMounted(() => {
  if (video.value) {
    video.value.volume = volume.value
    video.value.muted = muted.value
  }
  if (props.startTrim) nextTick(() => startTrimming())
})

function onLoaded() {
  const v = video.value
  if (!v) return
  videoDurationMs.value = Number.isFinite(v.duration) ? Math.round(v.duration * 1000) : 0
  v.playbackRate = rate.value
}
function onTime() {
  const v = video.value
  if (!v) return
  currentMs.value = Math.round(v.currentTime * 1000)
  if (v.buffered.length) bufferedMs.value = Math.round(v.buffered.end(v.buffered.length - 1) * 1000)
  // Beim Zuschneiden nur den gewählten Bereich abspielen.
  if (trimming.value && playing.value && currentMs.value >= range.value.outMs) {
    v.pause()
    seek(range.value.inMs)
  }
}
function onError() {
  const code = video.value?.error?.code
  // 3 = Dekodieren fehlgeschlagen, 4 = Format/Codec nicht unterstützt.
  if (code === 3 || code === 4) unsupported.value = true
}

// --- Steuerung ----------------------------------------------------------------------
function toggle() {
  const v = video.value
  if (!v || unsupported.value) return
  if (v.paused) {
    if (trimming.value && (currentMs.value < range.value.inMs || currentMs.value >= range.value.outMs - 50)) seek(range.value.inMs)
    void v.play().catch(() => {})
  } else v.pause()
}
function seek(ms: number) {
  const v = video.value
  if (!v) return
  const t = clamp(ms, 0, durationMs.value)
  v.currentTime = t / 1000
  currentMs.value = Math.round(t)
}
function frameStep(delta: 1 | -1) {
  const v = video.value
  if (!v) return
  v.pause()
  // Clips haben 30 oder 60 Bilder/s – 1/30 s trifft bei beiden ein neues Bild.
  seek(currentMs.value + delta * (1000 / 30))
}
function setRate(next: number) {
  rate.value = next
  if (video.value) video.value.playbackRate = next
}

async function toggleFullscreen() {
  const el = root.value
  if (!el) return
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen()
    } else {
      await el.requestFullscreen()
    }
  } catch {
    // Vollbild nicht erlaubt – egal.
  }
}
async function onFullscreenChange() {
  fullscreen.value = !!document.fullscreenElement
  // Das Fenster selbst mitnehmen (sonst füllt das Video nur das Launcher-Fenster).
  if (isTauri()) await getCurrentWindow().setFullscreen(fullscreen.value).catch(() => {})
}
onMounted(() => document.addEventListener('fullscreenchange', onFullscreenChange))
onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  if (document.fullscreenElement) void document.exitFullscreen().catch(() => {})
})

function step(delta: number) {
  const next = neighbourClip(props.list, props.clip, delta)
  if (next && clipKey(next) !== clipKey(props.clip)) emit('select', next)
}

function onKey(e: KeyboardEvent) {
  if (props.keysPaused) return
  const target = e.target as HTMLElement | null
  if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable)) {
    if (e.key === 'Escape') target.blur()
    return
  }
  if (e.altKey && (e.key === 'ArrowRight' || e.key === 'ArrowLeft')) {
    e.preventDefault()
    step(e.key === 'ArrowRight' ? 1 : -1)
    return
  }
  const action = playerKeyAction(e, trimming.value)
  if (!action) return
  e.preventDefault()
  switch (action.type) {
    case 'toggle':
      toggle()
      break
    case 'pause':
      video.value?.pause()
      break
    case 'seek':
      seek(currentMs.value + action.deltaMs)
      break
    case 'seekTo':
      seek(timeAtRatio(action.ratio, durationMs.value))
      break
    case 'frame':
      frameStep(action.delta)
      break
    case 'fullscreen':
      void toggleFullscreen()
      break
    case 'mute':
      muted.value = !muted.value
      break
    case 'volume':
      volume.value = clamp(Math.round((volume.value + action.delta) * 10) / 10, 0, 1)
      if (volume.value > 0) muted.value = false
      break
    case 'speed':
      setRate(stepPlaybackRate(rate.value, action.delta))
      break
    case 'markIn':
      markIn()
      break
    case 'markOut':
      markOut()
      break
    case 'close':
      if (document.fullscreenElement) void document.exitFullscreen().catch(() => {})
      else if (trimming.value) trimming.value = false
      else emit('close')
      break
  }
}
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

// --- Zeitleiste: Ziehen + Vorschau beim Überfahren ----------------------------------
const hover = ref<{ ratio: number; ms: number } | null>(null)
type Drag = 'seek' | 'in' | 'out'
let dragging: Drag | null = null
let wasPlaying = false

function ratioAt(e: PointerEvent): number {
  const rect = track.value?.getBoundingClientRect()
  if (!rect || rect.width <= 0) return 0
  return clamp((e.clientX - rect.left) / rect.width, 0, 1)
}
function onTrackMove(e: PointerEvent) {
  const ratio = ratioAt(e)
  hover.value = { ratio, ms: timeAtRatio(ratio, durationMs.value) }
  if (dragging) applyDrag(dragging, hover.value.ms)
}
function onTrackDown(e: PointerEvent, what: Drag = 'seek') {
  if (e.button !== 0) return
  dragging = what
  ;(e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId)
  wasPlaying = !!video.value && !video.value.paused
  video.value?.pause()
  applyDrag(what, timeAtRatio(ratioAt(e), durationMs.value))
}
function onTrackUp() {
  if (dragging === 'seek' && wasPlaying) void video.value?.play().catch(() => {})
  dragging = null
}
function applyDrag(what: Drag, ms: number) {
  if (what === 'seek') seek(ms)
  else {
    range.value = setTrimPoint(range.value, what, ms, durationMs.value)
    seek(what === 'in' ? range.value.inMs : range.value.outMs)
  }
}
function pct(ms: number): string {
  return `${durationMs.value > 0 ? clamp((ms / durationMs.value) * 100, 0, 100) : 0}%`
}
const previewFrame = computed(() => (hover.value && strip.value ? stripFrameAt(strip.value, hover.value.ms) : null))

// --- Zuschneiden --------------------------------------------------------------------
const range = ref({ inMs: 0, outMs: 0 })
const trimMode = ref<TrimMode>('auto')
const trimName = ref('')
const trimBusy = ref(false)
const trimProgress = ref<number | null>(null)

function startTrimming() {
  if (unsupported.value) return
  const d = durationMs.value
  range.value = { inMs: 0, outMs: d }
  trimMode.value = 'auto'
  trimName.value = trimmedName(props.clip.fileName, t('clips.trim.suffix'))
  trimming.value = true
}
function markIn() {
  range.value = setTrimPoint(range.value, 'in', currentMs.value, durationMs.value)
}
function markOut() {
  range.value = setTrimPoint(range.value, 'out', currentMs.value, durationMs.value)
}
// Dauer kommt evtl. erst nach dem Start des Zuschneidens (Metadaten des Videos).
watch(durationMs, (d, before) => {
  if (trimming.value && (range.value.outMs === 0 || range.value.outMs === before)) range.value = { ...range.value, outMs: d }
})

const plan = computed(() =>
  planTrim({ durationMs: durationMs.value, keyframesMs: info.value?.keyframesMs ?? [] }, range.value.inMs, range.value.outMs, trimMode.value),
)
const nameValid = computed(() => isValidClipName(trimName.value))
/** Abstand zum Keyframe davor in Sekunden, mit einer Nachkommastelle in der Sprache der Oberfläche. */
const leadSeconds = computed(() =>
  (plan.value.leadMs / 1000).toLocaleString(intlLocale(), { minimumFractionDigits: 1, maximumFractionDigits: 1 }),
)
const keyframeMarks = computed(() => (trimming.value && info.value ? info.value.keyframesMs.slice(0, 600) : []))

function previewSelection() {
  seek(range.value.inMs)
  void video.value?.play().catch(() => {})
}

async function saveTrim() {
  if (!plan.value.valid || !nameValid.value || trimBusy.value) return
  trimBusy.value = true
  trimProgress.value = plan.value.method === 'reencode' ? 0 : null
  video.value?.pause()
  try {
    const created = await backend.trimClip(
      props.clip.instanceId,
      props.clip.fileName,
      { startMs: range.value.inMs, endMs: range.value.outMs, mode: trimMode.value, name: trimName.value.trim() },
      (p) => (trimProgress.value = p),
    )
    trimming.value = false
    toasts.ok(t('clips.trim.saved', { name: clipStem(created.fileName) }), { label: t('clips.trim.openNew'), run: () => emit('select', created) })
    emit('created', created)
  } catch (e) {
    toasts.error(e)
  } finally {
    trimBusy.value = false
    trimProgress.value = null
  }
}

// --- Teilen & Datei -------------------------------------------------------------------
async function saveAs() {
  try {
    if (await backend.exportClip(props.clip.instanceId, props.clip.fileName)) toasts.ok(t('clips.share.exported'))
  } catch (e) {
    toasts.error(e)
  }
}
async function copyFile() {
  try {
    await backend.copyClipFile(props.clip.instanceId, props.clip.fileName)
    toasts.ok(t('clips.share.copied'))
  } catch (e) {
    toasts.error(e)
  }
}
function reveal() {
  backend.revealClip(props.clip.instanceId, props.clip.fileName).catch((e) => toasts.error(e))
}
function openExternal() {
  backend.openClipExternal(props.clip.instanceId, props.clip.fileName).catch((e) => toasts.error(e))
}

const hasNeighbours = computed(() => props.list.length > 1)
const resolution = computed(() => (info.value?.height ? `${info.value.height}p` : null))
const volumePct = computed(() => Math.round((muted.value ? 0 : volume.value) * 100))
</script>

<template>
  <div
    ref="root"
    class="fixed inset-0 z-50 flex flex-col bg-black/95 text-base-200"
    role="dialog"
    aria-modal="true"
    :aria-label="t('clips.player.label')"
  >
    <!-- Kopf: Name + Aktionen -->
    <header v-show="!fullscreen" class="flex flex-wrap items-center gap-3 px-4 pt-3 pb-2 text-sm">
      <div class="min-w-0 flex-1">
        <p class="truncate font-medium text-base-50" :title="clip.fileName">{{ clipStem(clip.fileName) }}</p>
        <p class="truncate text-xs text-base-400">
          {{ clip.instanceName }} · {{ formatDate(clip.createdAt) }} · {{ formatClipDuration(durationMs) }}
          <template v-if="resolution"> · {{ resolution }}</template>
          · {{ formatBytes(clip.size) }}
        </p>
      </div>
      <div class="flex shrink-0 flex-wrap items-center gap-1">
        <button class="btn btn-ghost py-1.5 text-xs" :class="{ 'text-redstone-300': trimming }" :disabled="unsupported" @click="trimming ? (trimming = false) : startTrimming()">
          <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="M6 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM20 4 8.1 15.9M14.5 14.5 20 20M8.1 8.1 12 12" /></svg>
          {{ t('clips.trim.button') }}
        </button>
        <button class="btn-icon" :title="t('clips.share.saveAs')" :aria-label="t('clips.share.saveAs')" @click="saveAs">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3v12m0 0-4-4m4 4 4-4M4 17v3h16v-3" /></svg>
        </button>
        <button class="btn-icon" :title="t('clips.share.copy')" :aria-label="t('clips.share.copy')" @click="copyFile">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 9h11v11H9zM5 15H4V4h11v1" /></svg>
        </button>
        <button class="btn-icon" :title="t('clips.showInFolder')" :aria-label="t('clips.showInFolder')" @click="reveal">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" /></svg>
        </button>
        <button class="btn-icon" :title="t('clips.share.openExternal')" :aria-label="t('clips.share.openExternal')" @click="openExternal">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" /></svg>
        </button>
        <button class="btn-icon" :title="t('clips.rename')" :aria-label="t('clips.rename')" @click="emit('rename', clip)">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4" /></svg>
        </button>
        <button class="btn-icon hover:text-redstone-300" :title="t('common.actions.delete')" :aria-label="t('common.actions.delete')" @click="emit('delete', clip)">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></svg>
        </button>
        <button class="btn-icon" :title="t('common.actions.close')" :aria-label="t('clips.player.close')" @click="emit('close')">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path :d="icons.close" /></svg>
        </button>
      </div>
    </header>

    <!-- Bild -->
    <div class="relative flex min-h-0 flex-1 items-center justify-center px-4">
      <div v-if="unsupported" class="card max-w-md px-5 py-4 text-center" role="alert">
        <p class="font-medium text-base-50">{{ t('clips.player.unsupportedTitle') }}</p>
        <p class="mt-1 text-sm text-base-400">{{ isLinux ? t('clips.player.unsupportedLinux') : t('clips.player.unsupportedText') }}</p>
        <button class="btn btn-primary mt-3" @click="openExternal">{{ t('clips.share.openExternal') }}</button>
      </div>
      <video
        v-show="!unsupported"
        ref="video"
        :key="src"
        :src="unsupported ? undefined : src"
        :poster="clipUrl('p', clip)"
        autoplay
        preload="metadata"
        class="max-h-full max-w-full rounded-lg bg-black"
        @click="toggle"
        @dblclick="toggleFullscreen"
        @play="playing = true"
        @pause="playing = false"
        @ended="playing = false"
        @loadedmetadata="onLoaded"
        @timeupdate="onTime"
        @progress="onTime"
        @error="onError"
      />
      <button
        v-if="hasNeighbours && !fullscreen"
        class="btn-icon absolute top-1/2 left-2 -translate-y-1/2 bg-black/40"
        :title="t('clips.player.previous')"
        :aria-label="t('clips.player.previous')"
        @click="step(-1)"
      >
        <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2"><path d="m15 5-7 7 7 7" /></svg>
      </button>
      <button
        v-if="hasNeighbours && !fullscreen"
        class="btn-icon absolute top-1/2 right-2 -translate-y-1/2 bg-black/40"
        :title="t('clips.player.next')"
        :aria-label="t('clips.player.next')"
        @click="step(1)"
      >
        <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2"><path d="m9 5 7 7-7 7" /></svg>
      </button>
    </div>

    <!-- Bedienleiste -->
    <div class="px-4 pt-2 pb-3" :class="{ 'pointer-events-none opacity-50': unsupported }">
      <!-- Zeitleiste -->
      <div
        ref="track"
        class="group/track relative h-5 cursor-pointer touch-none select-none"
        role="slider"
        tabindex="0"
        :aria-label="t('clips.player.timeline')"
        aria-valuemin="0"
        :aria-valuemax="Math.round(durationMs / 1000)"
        :aria-valuenow="Math.round(currentMs / 1000)"
        :aria-valuetext="`${formatTimecode(currentMs)} / ${formatTimecode(durationMs)}`"
        @pointermove="onTrackMove"
        @pointerleave="hover = null"
        @pointerdown="onTrackDown($event)"
        @pointerup="onTrackUp"
        @pointercancel="onTrackUp"
      >
        <div class="absolute inset-x-0 top-1/2 h-1.5 -translate-y-1/2 overflow-hidden rounded-full bg-base-800 group-hover/track:h-2">
          <div class="absolute inset-y-0 left-0 bg-base-600" :style="{ width: pct(bufferedMs) }" />
          <template v-if="trimming">
            <div class="absolute inset-y-0 left-0 bg-black/60" :style="{ width: pct(range.inMs) }" />
            <div class="absolute inset-y-0 bg-lamp-400/35" :style="{ left: pct(range.inMs), width: `calc(${pct(range.outMs)} - ${pct(range.inMs)})` }" />
          </template>
          <div class="absolute inset-y-0 left-0 bg-redstone-500" :style="{ width: pct(currentMs) }" />
        </div>
        <!-- Keyframes (nur beim Zuschneiden: dort ist ein Schnitt ohne Neukodierung möglich) -->
        <span
          v-for="k in keyframeMarks"
          :key="k"
          class="pointer-events-none absolute top-0 h-1 w-px bg-base-400/70"
          :style="{ left: pct(k) }"
          aria-hidden="true"
        />
        <span class="pointer-events-none absolute top-1/2 size-3 -translate-x-1/2 -translate-y-1/2 rounded-full bg-redstone-400 shadow" :style="{ left: pct(currentMs) }" />
        <template v-if="trimming">
          <button
            class="absolute top-1/2 h-5 w-2.5 -translate-x-full -translate-y-1/2 cursor-ew-resize rounded-l bg-lamp-400"
            :style="{ left: pct(range.inMs) }"
            :aria-label="t('clips.trim.start')"
            @pointerdown.stop="onTrackDown($event, 'in')"
          />
          <button
            class="absolute top-1/2 h-5 w-2.5 -translate-y-1/2 cursor-ew-resize rounded-r bg-lamp-400"
            :style="{ left: pct(range.outMs) }"
            :aria-label="t('clips.trim.end')"
            @pointerdown.stop="onTrackDown($event, 'out')"
          />
        </template>
        <!-- Vorschau beim Überfahren -->
        <div
          v-if="hover"
          class="pointer-events-none absolute bottom-full mb-2 -translate-x-1/2 overflow-hidden rounded-md border border-base-700 bg-black shadow-xl"
          :style="{ left: `clamp(84px, ${hover.ratio * 100}%, calc(100% - 84px))` }"
        >
          <div
            v-if="strip && previewFrame"
            class="bg-no-repeat"
            :style="{
              width: `${strip.frameWidth}px`,
              height: `${strip.frameHeight}px`,
              backgroundImage: `url(&quot;${stripUrl}&quot;)`,
              backgroundSize: `${strip.cols * strip.frameWidth}px ${strip.rows * strip.frameHeight}px`,
              backgroundPosition: `-${previewFrame.x}px -${previewFrame.y}px`,
            }"
          />
          <p class="px-1.5 py-0.5 text-center font-mono text-[11px] text-white">{{ formatTimecode(hover.ms) }}</p>
        </div>
      </div>

      <!-- Knöpfe -->
      <div class="mt-1.5 flex items-center gap-1.5 text-sm">
        <button class="btn-icon" :title="playing ? t('clips.player.pause') : t('clips.player.play')" :aria-label="playing ? t('clips.player.pause') : t('clips.player.play')" @click="toggle">
          <svg v-if="playing" viewBox="0 0 24 24" class="size-5" fill="currentColor"><path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" /></svg>
          <svg v-else viewBox="0 0 24 24" class="size-5" fill="currentColor"><path :d="icons.play" /></svg>
        </button>
        <button class="btn-icon" :title="t('clips.player.back5')" :aria-label="t('clips.player.back5')" @click="seek(currentMs - 5000)">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 17 6 12l5-5M18 17l-5-5 5-5" /></svg>
        </button>
        <button class="btn-icon" :title="t('clips.player.forward5')" :aria-label="t('clips.player.forward5')" @click="seek(currentMs + 5000)">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2"><path d="m13 17 5-5-5-5M6 17l5-5-5-5" /></svg>
        </button>
        <span class="ml-1 font-mono text-xs text-base-300 tabular-nums">{{ formatTimecode(currentMs) }} / {{ formatTimecode(durationMs) }}</span>

        <div class="ml-auto flex items-center gap-1.5">
          <button class="btn-icon" :title="muted ? t('clips.player.unmute') : t('clips.player.mute')" :aria-label="muted ? t('clips.player.unmute') : t('clips.player.mute')" @click="muted = !muted">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M4 9v6h4l5 4V5L8 9z" />
              <path v-if="muted || volume === 0" d="m17 9 5 6M22 9l-5 6" />
              <path v-else d="M16.5 8.5a5 5 0 0 1 0 7" />
            </svg>
          </button>
          <input
            v-model.number="volume"
            type="range"
            min="0"
            max="1"
            step="0.05"
            class="w-20 accent-redstone-500"
            :aria-label="t('clips.player.volume')"
            :aria-valuetext="`${volumePct} %`"
            @input="muted = false"
          />
          <select
            :value="rate"
            class="field h-7 w-[4.5rem] py-0 text-xs"
            :aria-label="t('clips.player.speed')"
            :title="t('clips.player.speed')"
            @change="setRate(Number(($event.target as HTMLSelectElement).value))"
          >
            <option v-for="r in PLAYBACK_RATES" :key="r" :value="r">{{ r }}×</option>
          </select>
          <button class="btn-icon" :title="fullscreen ? t('clips.player.exitFullscreen') : t('clips.player.fullscreen')" :aria-label="fullscreen ? t('clips.player.exitFullscreen') : t('clips.player.fullscreen')" @click="toggleFullscreen">
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2">
              <path v-if="fullscreen" d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5" />
              <path v-else d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" />
            </svg>
          </button>
        </div>
      </div>

      <!-- Zuschneiden -->
      <section v-if="trimming" class="card mt-2 space-y-2.5 bg-base-900/90 px-4 py-3" :aria-label="t('clips.trim.title')">
        <div class="flex flex-wrap items-center gap-x-4 gap-y-2 text-xs">
          <div class="flex items-center gap-1.5">
            <span class="text-base-400">{{ t('clips.trim.start') }}</span>
            <span class="font-mono text-base-50 tabular-nums">{{ formatTimecode(range.inMs, true) }}</span>
            <button class="btn btn-ghost px-2 py-1 text-[11px]" :title="t('clips.trim.setHint', { key: 'I' })" @click="markIn">{{ t('clips.trim.setStart') }}</button>
          </div>
          <div class="flex items-center gap-1.5">
            <span class="text-base-400">{{ t('clips.trim.end') }}</span>
            <span class="font-mono text-base-50 tabular-nums">{{ formatTimecode(range.outMs, true) }}</span>
            <button class="btn btn-ghost px-2 py-1 text-[11px]" :title="t('clips.trim.setHint', { key: 'O' })" @click="markOut">{{ t('clips.trim.setEnd') }}</button>
          </div>
          <span class="text-base-400">{{ t('clips.trim.length', { time: formatTimecode(Math.max(0, range.outMs - range.inMs), true) }) }}</span>
          <div class="ml-auto flex overflow-hidden rounded-md border border-base-700" role="radiogroup" :aria-label="t('clips.trim.modeLabel')">
            <button
              v-for="m in (['auto', 'fast', 'exact'] as const)"
              :key="m"
              role="radio"
              :aria-checked="trimMode === m"
              class="px-2.5 py-1 text-[11px]"
              :class="trimMode === m ? 'bg-redstone-600 text-white' : 'text-base-300 hover:bg-base-800'"
              :title="tKey(`clips.trim.modeHint.${m}`)"
              @click="trimMode = m"
            >
              {{ tKey(`clips.trim.mode.${m}`) }}
            </button>
          </div>
        </div>

        <p class="text-xs" :class="plan.method === 'copy' ? 'text-ok' : 'text-lamp-300'" role="status">
          <template v-if="!plan.valid">{{ t('clips.trim.tooShort') }}</template>
          <template v-else-if="plan.method === 'copy' && plan.leadMs > KEYFRAME_TOLERANCE_MS">
            {{ t('clips.trim.explainShift', { seconds: leadSeconds }) }}
          </template>
          <template v-else-if="plan.method === 'copy'">{{ t('clips.trim.explainCopy') }}</template>
          <template v-else-if="trimMode === 'exact'">{{ t('clips.trim.explainExact') }}</template>
          <template v-else>
            {{ t('clips.trim.explainReencode', { seconds: leadSeconds }) }}
            <button class="ml-1 underline hover:text-base-50" @click="trimMode = 'fast'">{{ t('clips.trim.useFast') }}</button>
          </template>
        </p>

        <div class="flex flex-wrap items-center gap-2">
          <label class="sr-only" for="trim-name">{{ t('clips.trim.nameLabel') }}</label>
          <input
            id="trim-name"
            v-model="trimName"
            class="field h-8 min-w-48 flex-1 py-1 text-sm"
            maxlength="170"
            autocomplete="off"
            :placeholder="t('clips.trim.nameLabel')"
            @keydown.enter="saveTrim"
          />
          <span class="text-xs text-base-500">.mp4</span>
          <button class="btn btn-ghost py-1.5 text-xs" :disabled="!plan.valid" @click="previewSelection">{{ t('clips.trim.preview') }}</button>
          <button class="btn btn-ghost py-1.5 text-xs" :disabled="trimBusy" @click="trimming = false">{{ t('common.actions.cancel') }}</button>
          <button class="btn btn-primary py-1.5 text-xs" :disabled="!plan.valid || !nameValid || trimBusy" @click="saveTrim">
            {{ trimBusy ? t('clips.trim.saving') : t('clips.trim.save') }}
          </button>
        </div>
        <p v-if="!nameValid" class="text-xs text-redstone-300">{{ t('clips.renameDialog.invalid') }}</p>
        <div v-if="trimBusy && trimProgress !== null" class="h-1.5 overflow-hidden rounded-full bg-base-800" role="progressbar" :aria-valuenow="trimProgress" aria-valuemin="0" aria-valuemax="100" :aria-label="t('clips.trim.saving')">
          <div class="h-full bg-lamp-400 transition-[width]" :style="{ width: `${trimProgress}%` }" />
        </div>
      </section>

      <p v-show="!fullscreen" class="pt-2 text-center text-[11px] text-base-600">{{ trimming ? t('clips.trim.hint') : t('clips.player.hint') }}</p>
    </div>
  </div>
</template>
