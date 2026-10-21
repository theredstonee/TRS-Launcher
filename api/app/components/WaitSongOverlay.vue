<script setup lang="ts">
// Vollbild-Musikvideo für die persönliche Wartezeit im Creator-Dashboard („Freitag um sechs am Abend“).
// Ton: echte MP3 (privat über /v1/me/wait-song, Zeitraster aus timeline.json) – fehlt sie, spielt das eingebaute
// Chiptune-Lied als Platzhalter. Bild: Canvas-Motor (app/utils/waitVideo), gesteuert nur von Zeitraster + Audio-Uhr.
// Startet per Klick (Browser spielen ohne Klick keinen Ton). Kein Vorspulen (das ist der Witz), Escape pausiert.
import { parseWaitTimeline, type WaitTimeline } from '#shared/wait-song'
import { WaitVideo } from '~/utils/waitVideo/engine'
import { cueTime } from '~/utils/waitVideo/plan'
import { SONG_TITLE, fallbackTimeline, playWaitSong } from '~/utils/waitSong'

const props = withDefaults(defineProps<{
  name: string
  message: string
  until: string
  skin?: string | null
  /** Nur Team-Vorschau: ab Sekunde `at` starten; `still` = Standbild ohne Ton. */
  preview?: { at?: number, still?: boolean } | null
}>(), { skin: null, preview: null })
const emit = defineEmits<{ close: [] }>()

type Phase = 'ready' | 'playing' | 'done'
const phase = ref<Phase>('ready')
const paused = ref(false)
const muted = ref(false)
/** Läuft gerade Vollbild? Verlässt er es, zeigt das Video einen Knopf zum Zurückkehren. */
const fullscreen = ref(false)
const canFullscreen = ref(false)
function onFullscreenChange() {
  fullscreen.value = !!document.fullscreenElement
}
const progress = ref(0)
const idle = ref(false)
const canvasEl = ref<HTMLCanvasElement | null>(null)
const rootEl = ref<HTMLElement | null>(null)

let video: WaitVideo | null = null
let timeline: WaitTimeline = fallbackTimeline()
let real = false
let audio: HTMLAudioElement | null = null
let ctx: AudioContext | null = null
let gain: GainNode | null = null
let analyser: AnalyserNode | null = null
let mediaNode: MediaElementAudioSourceNode | null = null
let stopChip: (() => void) | null = null
let chipStart = 0
let raf = 0
let autoPaused = false
let idleTimer: ReturnType<typeof setTimeout> | undefined
let ro: ResizeObserver | null = null
let setup: Promise<void> | null = null
const reduced = import.meta.client && window.matchMedia('(prefers-reduced-motion: reduce)').matches

/** Zeitraster der MP3 laden (oder Platzhalter). Wirft nie. */
async function prepare() {
  try {
    const res = await apiFetch<{ audio: boolean, timeline: unknown }>('/v1/me/wait-song/timeline', { credentials: 'same-origin' })
    if (!res.audio) return
    const tl = parseWaitTimeline(res.timeline)
    audio = new Audio()
    audio.preload = 'auto'
    audio.src = '/v1/me/wait-song'
    real = true
    if (tl) timeline = tl
    else {
      // MP3 ohne Zeitraster: Platzhalter-Raster auf die Länge der MP3 strecken (Tempo unbekannt)
      const duration = await new Promise<number>((resolve) => {
        audio!.addEventListener('loadedmetadata', () => resolve(audio!.duration), { once: true })
        audio!.addEventListener('error', () => resolve(0), { once: true })
      })
      if (duration > 10) timeline = stretch(fallbackTimeline(), duration)
      else real = false
    }
  } catch {
    real = false
  }
}

function stretch(tl: WaitTimeline, duration: number): WaitTimeline {
  const k = duration / tl.end
  const out = { ...tl } as Record<string, unknown>
  for (const key of Object.keys(tl)) if (typeof out[key] === 'number') out[key] = (out[key] as number) * k
  delete out.beats
  return out as WaitTimeline
}

const now = () => {
  if (real && audio) return audio.currentTime
  if (ctx) return ctx.currentTime - chipStart
  return 0
}

/** Audio-Hüllkurve 0…1 aus dem Analyser (Bass betont). */
let energy = 0
const bins = new Uint8Array(64)
function readEnergy() {
  if (!analyser) return 0
  analyser.getByteFrequencyData(bins)
  let s = 0
  for (let i = 1; i < 10; i++) s += bins[i]!
  const v = s / (9 * 255)
  energy = energy * 0.8 + v * 0.2
  return energy
}

function loop() {
  if (!video) return
  const t = props.preview?.still ? props.preview.at ?? 0 : now()
  progress.value = Math.min(1, t / timeline.end)
  video.render(t, readEnergy())
  if (!props.preview?.still && (t >= timeline.end - 0.03 || (real && audio?.ended))) {
    finish()
    return
  }
  raf = requestAnimationFrame(loop)
}

function ensureVideo() {
  if (video || !canvasEl.value) return
  video = new WaitVideo(canvasEl.value, props.name, reduced)
  video.setTimeline(timeline)
  video.preload(props.skin)
  video.resize()
  ro = new ResizeObserver(() => {
    video?.resize()
    if (phase.value !== 'playing' || paused.value) still()
  })
  ro.observe(canvasEl.value)
}

/** Standbild für Start- und Endbildschirm (dezent animiert per CSS). */
function still() {
  if (!video) return
  video.render(phase.value === 'done' ? cueTime(timeline, 'fade') + 3.2 : Math.min(timeline.verse1 * 0.35, 3))
}

/** Automatisch Vollbild (geht nur direkt nach einem Klick; ohne Unterstützung, z. B. iPhone, bleibt es das Overlay). */
function enterFullscreen() {
  const el = rootEl.value as (HTMLElement & { webkitRequestFullscreen?: () => void }) | null
  if (!el || document.fullscreenElement || props.preview?.still) return
  try {
    if (el.requestFullscreen) void el.requestFullscreen({ navigationUI: 'hide' }).catch(() => {})
    else el.webkitRequestFullscreen?.()
  } catch {
    // Vollbild abgelehnt – Overlay deckt die Seite trotzdem ab
  }
}

function exitFullscreen() {
  if (document.fullscreenElement) void document.exitFullscreen().catch(() => {})
}

function close() {
  exitFullscreen()
  emit('close')
}

async function play() {
  enterFullscreen()
  ensureVideo()
  await Promise.race([setup, new Promise((r) => setTimeout(r, 1500))])
  video?.setTimeline(timeline)
  stopAll()
  const start = props.preview?.at ?? 0
  if (props.preview?.still) {
    phase.value = 'playing'
    raf = requestAnimationFrame(loop)
    return
  }
  const AC = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
  ctx ??= new AC()
  await ctx.resume()
  if (!gain) {
    gain = ctx.createGain()
    analyser = ctx.createAnalyser()
    analyser.fftSize = 128
    analyser.smoothingTimeConstant = 0.6
    gain.connect(analyser).connect(ctx.destination)
  }
  gain.gain.value = muted.value ? 0 : 1
  let ok = false
  if (real && audio) {
    try {
      mediaNode ??= ctx.createMediaElementSource(audio)
      mediaNode.disconnect()
      mediaNode.connect(gain)
      audio.currentTime = start
      await audio.play()
      ok = true
    } catch {
      // Datei nicht abspielbar → Platzhalter
      real = false
      timeline = fallbackTimeline()
      video?.setTimeline(timeline)
    }
  }
  if (!ok) {
    chipStart = ctx.currentTime + 0.12 - Math.min(start, timeline.end - 1)
    stopChip = playWaitSong(ctx, chipStart, gain)
  }
  paused.value = false
  phase.value = 'playing'
  poke()
  raf = requestAnimationFrame(loop)
}

function stopAll() {
  cancelAnimationFrame(raf)
  stopChip?.()
  stopChip = null
  audio?.pause()
}

function finish() {
  stopAll()
  phase.value = 'done'
  paused.value = false
  nextTick(still)
}

async function togglePause(auto = false) {
  if (phase.value !== 'playing' || props.preview?.still) return
  if (!paused.value) {
    paused.value = true
    autoPaused = auto
    if (real) audio?.pause()
    else await ctx?.suspend()
    cancelAnimationFrame(raf)
  } else {
    paused.value = false
    autoPaused = false
    await ctx?.resume()
    if (real) await audio?.play().catch(() => {})
    raf = requestAnimationFrame(loop)
  }
}

function toggleMute() {
  muted.value = !muted.value
  if (gain && ctx) gain.gain.setTargetAtTime(muted.value ? 0 : 1, ctx.currentTime, 0.03)
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && phase.value === 'playing') {
    e.preventDefault()
    void togglePause()
  }
}

function onVisibility() {
  if (document.hidden && phase.value === 'playing' && !paused.value) void togglePause(true)
  else if (!document.hidden && autoPaused && paused.value) void togglePause()
}

/** Steuerung bei Mausbewegung kurz einblenden. */
function poke() {
  idle.value = false
  clearTimeout(idleTimer)
  idleTimer = setTimeout(() => (idle.value = true), 2200)
}

onMounted(() => {
  setup = prepare()
  ensureVideo()
  void setup.then(() => {
    video?.setTimeline(timeline)
    if (phase.value === 'ready') still()
  })
  still()
  document.addEventListener('keydown', onKey)
  document.addEventListener('visibilitychange', onVisibility)
  document.addEventListener('fullscreenchange', onFullscreenChange)
  canFullscreen.value = !!document.fullscreenEnabled && !props.preview?.still
  document.documentElement.style.overflow = 'hidden'
  rootEl.value?.focus()
  if (props.preview?.still) void play()
})

onBeforeUnmount(() => {
  exitFullscreen()
  stopAll()
  ro?.disconnect()
  clearTimeout(idleTimer)
  document.removeEventListener('keydown', onKey)
  document.removeEventListener('visibilitychange', onVisibility)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
  document.documentElement.style.overflow = ''
  if (audio) {
    audio.removeAttribute('src')
    audio.load()
  }
  void ctx?.close()
})
</script>

<template>
  <div
    ref="rootEl"
    class="wait-song"
    :class="{ idle: idle && phase === 'playing' && !paused }"
    role="dialog"
    aria-modal="true"
    :aria-label="SONG_TITLE"
    tabindex="-1"
    @mousemove="poke"
    @touchstart.passive="poke"
  >
    <canvas ref="canvasEl" class="stage" :class="{ dim: phase !== 'playing' }" aria-hidden="true" />

    <div v-if="phase === 'ready'" class="card-wrap">
      <div class="card">
        <p class="eyebrow">Nur für dich</p>
        <h2 class="title font-display">Hey {{ props.name }}!</h2>
        <p class="lead">Wir haben da was für dich vorbereitet. Ton an, Vollbild genießen.</p>
        <button type="button" class="play" @click="play">
          <SiteIcon name="play" class="size-6" />
          <span>Abspielen</span>
        </button>
        <p class="hint">♪ {{ SONG_TITLE }}</p>
      </div>
    </div>

    <template v-else-if="phase === 'playing'">
      <div class="bar" aria-hidden="true"><div class="bar-fill" :style="{ transform: `scaleX(${progress})` }" /></div>
      <div class="controls">
        <button type="button" class="ctl" :aria-label="paused ? 'Weiter' : 'Pause'" @click="togglePause()">
          <SiteIcon :name="paused ? 'play' : 'pause'" class="size-4" />
        </button>
        <button type="button" class="ctl" :aria-label="muted ? 'Ton an' : 'Stumm'" @click="toggleMute">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M11 5 6 9H3v6h3l5 4V5z" fill="currentColor" />
            <template v-if="muted"><path d="m22 9-6 6" /><path d="m16 9 6 6" /></template>
            <template v-else><path d="M15.5 8.5a5 5 0 0 1 0 7" /><path d="M18.5 5.5a9 9 0 0 1 0 13" /></template>
          </svg>
        </button>
      </div>
      <button v-if="canFullscreen && !fullscreen" type="button" class="fs-pill" @click="enterFullscreen">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" /></svg>
        <span>Mit Vollbild</span>
      </button>
      <div v-if="paused" class="paused">
        <button type="button" class="play" @click="togglePause()"><SiteIcon name="play" class="size-6" /><span>Weiter</span></button>
        <button v-if="canFullscreen && !fullscreen" type="button" class="fs-pill static" @click="enterFullscreen(); togglePause()">Weiter mit Vollbild</button>
      </div>
    </template>

    <div v-else class="card-wrap end-wrap">
      <div class="card end">
        <p class="eyebrow">Freitag · 18:00</p>
        <p class="message">{{ props.message }}</p>
        <p class="unlock"><SiteIcon name="clock" class="size-4" />Freischaltung: {{ props.until }}</p>
        <div class="actions">
          <button type="button" class="btn btn-ghost" @click="play"><SiteIcon name="play" class="size-4" />Nochmal abspielen</button>
          <button type="button" class="btn btn-primary" @click="close">Okay, ich warte</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wait-song {
  position: fixed;
  inset: 0;
  z-index: 200;
  overflow: hidden;
  background: #000;
  outline: none;
}
.stage {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
  image-rendering: auto;
  transition: filter 0.8s ease, transform 0.8s ease;
}
.stage.dim {
  filter: blur(2px) brightness(0.6) saturate(1.1);
  transform: scale(1.04);
  animation: kenburns 24s ease-in-out infinite alternate;
}
.idle { cursor: none; }

.card-wrap {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 1rem;
}
.card {
  width: min(100%, 34rem);
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 1.25rem;
  background: linear-gradient(160deg, rgb(26 20 46 / 0.72), rgb(12 10 20 / 0.82));
  box-shadow: 0 30px 90px rgb(0 0 0 / 0.6), inset 0 1px 0 rgb(255 255 255 / 0.06);
  backdrop-filter: blur(14px);
  padding: 2.2rem 1.6rem 1.8rem;
  text-align: center;
  animation: rise 0.9s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.end-wrap {
  place-items: end center;
  padding-bottom: max(7vh, 1.5rem);
}
.eyebrow {
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.32em;
  text-transform: uppercase;
  color: var(--color-redstone-400);
}
.title {
  margin-top: 0.8rem;
  font-size: clamp(2.2rem, 7vw, 3.4rem);
  line-height: 1.05;
  color: #fff;
  text-shadow: 0 0 30px rgb(224 40 30 / 0.45);
}
.lead { margin-top: 0.9rem; color: rgb(220 216 235 / 0.85); }
.hint { margin-top: 1.2rem; font-size: 0.75rem; color: rgb(200 196 220 / 0.55); }
.play {
  margin-top: 1.8rem;
  display: inline-flex;
  align-items: center;
  gap: 0.6rem;
  border-radius: 999px;
  padding: 0.9rem 2rem 0.9rem 1.6rem;
  font-size: 1.1rem;
  font-weight: 700;
  color: #fff;
  background: radial-gradient(circle at 30% 20%, #ff6a5a, #c41d14 70%);
  box-shadow: 0 0 0 0 rgb(224 40 30 / 0.6), 0 12px 40px rgb(224 40 30 / 0.35);
  animation: pulse 2.2s ease-out infinite;
  transition: transform 0.15s ease;
}
.play:hover { transform: scale(1.04); }
.play:focus-visible { outline: 2px solid #fff; outline-offset: 3px; }

.end .message {
  margin-top: 1.1rem;
  font-size: clamp(1.35rem, 4vw, 1.9rem);
  font-weight: 650;
  line-height: 1.3;
  color: #fff;
}
.unlock {
  margin-top: 1rem;
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.85rem;
  color: rgb(220 216 235 / 0.7);
}
.actions { margin-top: 1.8rem; display: flex; flex-wrap: wrap; justify-content: center; gap: 0.75rem; }

.bar {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 3px;
  background: rgb(255 255 255 / 0.08);
}
.bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #b31a12, #ff5a4d);
  transform-origin: left;
  box-shadow: 0 0 10px rgb(255 90 77 / 0.7);
}
.fs-pill {
  position: absolute;
  top: 1rem;
  right: 1rem;
  z-index: 5;
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  border: 1px solid rgb(255 255 255 / 0.25);
  border-radius: 999px;
  background: rgb(229 50 45 / 0.92);
  padding: 0.55rem 1rem;
  font-size: 0.9rem;
  font-weight: 700;
  color: #fff;
  box-shadow: 0 8px 30px rgb(0 0 0 / 0.45);
  animation: fs-pulse 2s ease-in-out infinite;
}
.fs-pill.static { position: static; margin-top: 0.8rem; animation: none; }
@keyframes fs-pulse { 50% { box-shadow: 0 0 0 6px rgb(229 50 45 / 0.25), 0 8px 30px rgb(0 0 0 / 0.45); } }
.controls {
  position: absolute;
  right: 1rem;
  bottom: 1rem;
  display: flex;
  gap: 0.5rem;
  transition: opacity 0.5s ease;
}
.idle .controls { opacity: 0; }
.ctl {
  display: grid;
  place-items: center;
  width: 2.25rem;
  height: 2.25rem;
  border-radius: 999px;
  color: rgb(255 255 255 / 0.85);
  background: rgb(10 8 18 / 0.45);
  border: 1px solid rgb(255 255 255 / 0.12);
  backdrop-filter: blur(6px);
  opacity: 0.6;
  transition: opacity 0.2s ease;
}
.ctl:hover, .ctl:focus-visible { opacity: 1; }
.paused {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background: rgb(0 0 0 / 0.35);
}

@keyframes rise { from { opacity: 0; transform: translateY(18px) scale(0.98); } }
@keyframes pulse {
  0% { box-shadow: 0 0 0 0 rgb(224 40 30 / 0.55), 0 12px 40px rgb(224 40 30 / 0.35); }
  70% { box-shadow: 0 0 0 18px rgb(224 40 30 / 0), 0 12px 40px rgb(224 40 30 / 0.35); }
  100% { box-shadow: 0 0 0 0 rgb(224 40 30 / 0), 0 12px 40px rgb(224 40 30 / 0.35); }
}
@keyframes kenburns { from { transform: scale(1.04); } to { transform: scale(1.1) translate(-1%, -1%); } }
@media (prefers-reduced-motion: reduce) {
  .stage.dim, .card, .play { animation: none; }
}
</style>
