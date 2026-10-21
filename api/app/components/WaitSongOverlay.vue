<script setup lang="ts">
// Vollbild-Ständchen „Morgen um zwölf“ für die persönliche Wartezeit im Creator-Dashboard.
// Startet per Klick (Browser spielen ohne Klick keinen Ton), zeigt den Text Zeile für Zeile
// und erst am Ende die eigentliche Nachricht.
import { INTRO, LINE, SONG_LENGTH, SONG_LINES, SONG_TITLE, lineStart, playWaitSong } from '~/utils/waitSong'

const props = defineProps<{ name: string, message: string, until: string }>()
const emit = defineEmits<{ close: [] }>()

type Phase = 'ready' | 'playing' | 'done'
const phase = ref<Phase>('ready')
const elapsed = ref(0)
let ctx: AudioContext | null = null
let stop: (() => void) | null = null
let raf = 0
let startedAt = 0

const current = computed(() => {
  if (elapsed.value < INTRO) return -1
  return Math.min(SONG_LINES.length - 1, Math.floor((elapsed.value - INTRO) / LINE))
})
const lineProgress = computed(() => {
  const i = current.value
  if (i < 0) return 0
  return Math.min(1, (elapsed.value - lineStart(i)) / LINE)
})
const progress = computed(() => Math.min(1, elapsed.value / SONG_LENGTH))
const countIn = computed(() => Math.max(1, Math.ceil((INTRO - elapsed.value) / (INTRO / 4))))

function tick() {
  if (!ctx) return
  elapsed.value = ctx.currentTime - startedAt
  if (elapsed.value >= SONG_LENGTH) {
    phase.value = 'done'
    cleanup(false)
    return
  }
  raf = requestAnimationFrame(tick)
}

async function play() {
  cleanup(true)
  const AC = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
  ctx = new AC()
  await ctx.resume()
  startedAt = ctx.currentTime + 0.1
  stop = playWaitSong(ctx, startedAt)
  elapsed.value = 0
  phase.value = 'playing'
  raf = requestAnimationFrame(tick)
}

function cleanup(closeCtx: boolean) {
  cancelAnimationFrame(raf)
  stop?.()
  stop = null
  if (closeCtx && ctx) {
    void ctx.close()
    ctx = null
  }
}

onBeforeUnmount(() => cleanup(true))
</script>

<template>
  <div class="wait-song" role="dialog" aria-modal="true" :aria-label="SONG_TITLE">
    <div class="stars" aria-hidden="true" />
    <div class="rocket" :class="{ launch: phase === 'done' }" aria-hidden="true">🚀</div>

    <div class="relative z-10 mx-auto flex min-h-full max-w-3xl flex-col items-center justify-center px-6 py-16 text-center">
      <template v-if="phase === 'ready'">
        <p class="text-xs font-semibold uppercase tracking-[0.3em] text-redstone-400">Nur für dich</p>
        <h2 class="mt-3 font-pixel text-4xl text-base-50 sm:text-5xl">Hey {{ props.name }}!</h2>
        <p class="mt-4 max-w-xl text-base-300">Wir haben da was für dich vorbereitet. Ton an und los.</p>
        <button type="button" class="btn btn-primary mt-8 px-8 py-3 text-lg" @click="play">
          <SiteIcon name="play" class="size-5" />{{ SONG_TITLE }} abspielen
        </button>
      </template>

      <template v-else-if="phase === 'playing'">
        <p class="text-xs font-semibold uppercase tracking-[0.3em] text-redstone-400">{{ SONG_TITLE }}</p>
        <p v-if="current < 0" class="mt-10 font-pixel text-7xl text-base-50">{{ countIn }}</p>
        <template v-else>
          <p class="mt-2 text-xs uppercase tracking-widest text-base-500">
            {{ SONG_LINES[current]!.part === 'chorus' ? 'Refrain' : SONG_LINES[current]!.part === 'outro' ? 'Outro' : 'Strophe' }}
          </p>
          <p :key="current" class="line mt-6 text-2xl font-semibold leading-snug text-base-50 sm:text-3xl">
            <span class="fill" :style="{ '--p': lineProgress }">{{ SONG_LINES[current]!.text }}</span>
          </p>
          <p v-if="SONG_LINES[current + 1]" class="mt-6 text-base text-base-500">{{ SONG_LINES[current + 1]!.text }}</p>
        </template>
        <div class="mt-12 h-1.5 w-full max-w-md overflow-hidden rounded-full bg-base-800">
          <div class="h-full rounded-full bg-redstone-500" :style="{ width: `${progress * 100}%` }" />
        </div>
      </template>

      <template v-else>
        <p class="text-xs font-semibold uppercase tracking-[0.3em] text-redstone-400">Und jetzt die Nachricht</p>
        <p class="message mt-6 text-2xl font-semibold leading-snug text-base-50 sm:text-3xl">{{ props.message }}</p>
        <p class="mt-3 text-sm text-base-400">Freischaltung: {{ props.until }}</p>
        <div class="mt-8 flex flex-wrap justify-center gap-3">
          <button type="button" class="btn btn-ghost" @click="play"><SiteIcon name="play" class="size-4" />Nochmal hören</button>
          <button type="button" class="btn btn-primary" @click="emit('close')">Okay, ich warte</button>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.wait-song {
  position: fixed;
  inset: 0;
  z-index: 200;
  overflow-y: auto;
  background: radial-gradient(ellipse at 50% 120%, #2a1440 0%, #0b0a14 55%, #05050a 100%);
}
.stars {
  position: absolute;
  inset: 0;
  background-image:
    radial-gradient(1px 1px at 10% 20%, #fff 50%, transparent 51%),
    radial-gradient(1px 1px at 30% 70%, #cbd5ff 50%, transparent 51%),
    radial-gradient(1.5px 1.5px at 55% 35%, #fff 50%, transparent 51%),
    radial-gradient(1px 1px at 75% 15%, #ffe9b0 50%, transparent 51%),
    radial-gradient(1px 1px at 85% 60%, #fff 50%, transparent 51%),
    radial-gradient(1.5px 1.5px at 20% 90%, #cbd5ff 50%, transparent 51%),
    radial-gradient(1px 1px at 65% 85%, #fff 50%, transparent 51%),
    radial-gradient(1px 1px at 45% 10%, #fff 50%, transparent 51%);
  background-size: 300px 300px;
  animation: drift 60s linear infinite;
  opacity: 0.8;
}
.rocket {
  position: absolute;
  right: 8%;
  bottom: 10%;
  font-size: 3.5rem;
  animation: float 4s ease-in-out infinite;
}
.rocket.launch { animation: launch 2.5s ease-in forwards; }
.fill {
  background: linear-gradient(90deg, #ff5a4f calc(var(--p) * 100%), #f4f4f6 calc(var(--p) * 100%));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.line { animation: pop 0.35s ease-out; }
.message { animation: pop 0.6s ease-out; }
@keyframes drift { to { background-position: 0 300px; } }
@keyframes float { 50% { transform: translateY(-14px) rotate(-6deg); } }
@keyframes launch { to { transform: translateY(-120vh) rotate(-12deg); } }
@keyframes pop { from { opacity: 0; transform: translateY(10px) scale(0.98); } }
@media (prefers-reduced-motion: reduce) {
  .stars, .rocket, .line, .message { animation: none; }
}
</style>
