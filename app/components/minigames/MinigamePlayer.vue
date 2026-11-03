<script setup lang="ts">
// Spielfeld eines Minispiels samt Anzeigen. `compact` ist die kleine Fassung beim Minecraft-Start.
import { errorMessage } from '~/utils/backend'
import type { GameId } from '~/utils/minigames/replay'
import { GameSession, type FinishedRun, type GameHud, type SessionState } from '~/utils/minigames/session'
import { T } from '~/utils/minigames/stapler'
import { loadGameTextures, skinFor, type TextureMap } from '~/utils/minigames/textures'
import type { Stapler } from '~/utils/minigames/stapler'

const props = defineProps<{ game: GameId, compact?: boolean }>()
const emit = defineEmits<{ finished: [run: FinishedRun] }>()

const design = useDesign()
const store = useMinigamesStore()
const skin = computed(() => skinFor(design.value))

const canvas = ref<HTMLCanvasElement | null>(null)
const holdCanvas = ref<HTMLCanvasElement | null>(null)
const nextCanvas = ref<HTMLCanvasElement | null>(null)
const root = ref<HTMLElement | null>(null)
const legend = ref<HTMLCanvasElement[]>([])

const images = shallowRef<TextureMap | null>(null)
const loadError = ref<unknown>(null)
const state = ref<SessionState>('ready')
const hud = shallowRef<GameHud | null>(null)
const soundOn = ref(true)
const lastRun = shallowRef<FinishedRun | null>(null)
const starting = ref(false)
const newBest = ref(false)
let session: GameSession | null = null

const best = computed(() => store.localBest(props.game))
const SIGNAL_CELLS = Array.from({ length: 15 }, (_, i) => i)

const staplerRules = [
  { tile: T.RED, title: 'minigames.stapler.rules.redstoneTitle', text: 'minigames.stapler.rules.redstone' },
  { tile: T.TNT, title: 'minigames.stapler.rules.tntTitle', text: 'minigames.stapler.rules.tnt' },
  { tile: T.LAMP, title: 'minigames.stapler.rules.lampTitle', text: 'minigames.stapler.rules.lamp' },
  { tile: T.PISTON, title: 'minigames.stapler.rules.pistonTitle', text: 'minigames.stapler.rules.piston' },
] as const

function createSession() {
  if (!canvas.value || !images.value) return
  session?.dispose()
  session = new GameSession({
    game: props.game,
    canvas: canvas.value,
    images: images.value,
    skin: skin.value,
    onHud: (value) => {
      hud.value = value
      drawSide()
    },
    onState: (value) => {
      state.value = value
    },
    onFinish: (run) => {
      newBest.value = run.score > store.localBest(run.game)
      lastRun.value = run
      store.recordLocal(run.game, run.score)
      emit('finished', run)
    },
  })
  soundOn.value = session.audio.enabled
  state.value = 'ready'
  hud.value = null
  lastRun.value = null
  nextTick(() => {
    const view = session?.stapler
    if (!view) return
    legend.value.forEach((c, i) => {
      const g = c?.getContext('2d')
      if (g) view.drawTile(g, staplerRules[i]!.tile, 32)
    })
  })
}

function drawSide() {
  const view = session?.stapler
  const sim = session?.current as Stapler | null
  if (!view || !sim) return
  const hold = holdCanvas.value?.getContext('2d')
  if (hold) {
    hold.clearRect(0, 0, 48, 32)
    view.drawMini(hold, sim.hold, 0, 4, 12)
  }
  const next = nextCanvas.value?.getContext('2d')
  if (next) {
    next.clearRect(0, 0, 48, 104)
    sim.queue.slice(0, 3).forEach((p, i) => view.drawMini(next, p, 0, 4 + i * 34, 12))
  }
}

async function play() {
  if (!session || starting.value) return
  starting.value = true
  session.audio.unlock()
  try {
    const seed = await store.nextSeed(props.game)
    session.start(seed)
    root.value?.focus()
  }
  finally {
    starting.value = false
  }
}

function togglePause() {
  session?.pause(state.value === 'playing')
}

function toggleSound() {
  if (!session) return
  session.audio.setEnabled(!session.audio.enabled)
  soundOn.value = session.audio.enabled
}

function onKeyDown(e: KeyboardEvent) {
  if (!session) return
  if (e.code === 'KeyM' && !e.repeat) {
    toggleSound()
    return
  }
  if (state.value === 'ready' || state.value === 'over') {
    if ((e.code === 'Enter' || e.code === 'Space') && !e.repeat) {
      e.preventDefault()
      void play()
    }
    return
  }
  if ((e.code === 'KeyP' || e.code === 'Escape') && !e.repeat) {
    e.preventDefault()
    togglePause()
    return
  }
  if (state.value === 'paused' && (e.code === 'Enter' || e.code === 'Space') && !e.repeat) {
    e.preventDefault()
    togglePause()
    return
  }
  if (e.code === 'KeyR' && !e.repeat) {
    void play()
    return
  }
  if (session.keyDown(e.code, e.repeat)) e.preventDefault()
}

function onKeyUp(e: KeyboardEvent) {
  session?.keyUp(e.code)
}

function onBlur() {
  session?.blur()
}

onMounted(async () => {
  window.addEventListener('blur', onBlur)
  try {
    images.value = (await loadGameTextures()).images
    await nextTick()
    createSession()
  }
  catch (error) {
    loadError.value = error
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('blur', onBlur)
  session?.dispose()
  session = null
})

// Spielwechsel oder neues Launcher-Design baut das Bild neu, eine laufende Partie endet damit.
watch(() => [props.game, skin.value.id], async () => {
  await nextTick()
  createSession()
})

function format(value: number) {
  return value.toLocaleString(currentLocale.value)
}

const signal = computed(() => hud.value?.signal ?? 0)
const signalReady = computed(() => signal.value >= 15)
const canvasAspect = computed(() => (props.game === 'stapler' ? '224 / 384' : '400 / 225'))

defineExpose({ pause: () => session?.pause(true) })
</script>

<template>
  <div
    ref="root"
    class="minigame relative flex min-h-0 flex-1 items-center justify-center gap-4 outline-none"
    :class="{ 'is-compact': compact }"
    :style="{ background: skin.backdrop }"
    tabindex="0"
    role="application"
    :aria-label="t(game === 'stapler' ? 'minigames.stapler.name' : 'minigames.loren.name')"
    @keydown="onKeyDown"
    @keyup="onKeyUp"
  >
    <div v-if="loadError" class="max-w-md p-6 text-center">
      <p class="font-semibold text-base-100">{{ t('minigames.noTexturesTitle') }}</p>
      <p class="mt-2 text-sm text-base-300">{{ errorMessage(loadError) }}</p>
    </div>

    <template v-else>
      <div v-if="game === 'stapler'" class="side">
        <div class="box">
          <div class="lab">{{ t('minigames.stapler.hold') }}</div>
          <canvas ref="holdCanvas" class="mini" width="48" height="32" />
        </div>
        <div class="box">
          <div class="lab">{{ t('minigames.signal') }}</div>
          <div class="meter" :class="{ ready: signalReady }" role="meter" :aria-valuenow="signal" aria-valuemin="0" aria-valuemax="15" :aria-label="t('minigames.signal')">
            <i v-for="i in SIGNAL_CELLS" :key="i" :class="{ on: i < signal }" />
          </div>
          <div class="hint" :class="{ go: signalReady }">{{ signalReady ? t('minigames.stapler.pulseReady') : `${signal} / 15` }}</div>
        </div>
        <div v-if="hud?.kind === 'stapler'" class="box">
          <div class="lab">{{ t('minigames.stapler.lamps') }}</div>
          <div class="val">{{ hud.lamps }}</div>
        </div>
      </div>

      <div class="screen" :style="{ aspectRatio: canvasAspect }" :data-game="game">
        <canvas ref="canvas" class="pixel" />

        <div v-if="game === 'loren' && hud?.kind === 'loren' && state !== 'ready'" class="loren-hud">
          <div class="flex items-start justify-between gap-4">
            <div>
              <div class="lab">{{ t('minigames.score') }}</div>
              <div class="val">{{ format(hud.score) }}</div>
            </div>
            <div class="text-right">
              <div class="lab">{{ t(`minigames.loren.zones.${hud.zone}` as 'minigames.loren.zones.mine') }}</div>
              <div class="tabular-nums text-sm text-base-100">{{ t('minigames.loren.speed', { n: hud.speed }) }} · {{ t('minigames.loren.blocks', { n: format(hud.blocks) }) }}</div>
            </div>
          </div>
          <div class="mt-auto">
            <div class="meter" :class="{ ready: signalReady }" role="meter" :aria-valuenow="signal" aria-valuemin="0" aria-valuemax="15" :aria-label="t('minigames.signal')">
              <i v-for="i in SIGNAL_CELLS" :key="i" :class="{ on: i < signal }" />
            </div>
            <div class="hint" :class="{ go: signalReady || hud.turbo }">
              {{ hud.turbo ? t('minigames.loren.turboActive') : signalReady ? t('minigames.loren.turboReady') : signal >= 5 ? t('minigames.loren.shield', { n: signal }) : `${signal} / 15` }}
            </div>
          </div>
        </div>

        <div v-if="state !== 'playing' && images" class="card-wrap">
          <div class="card" :class="{ 'card-small': compact }">
            <template v-if="state === 'over' && lastRun">
              <h2>{{ t(game === 'stapler' ? 'minigames.stapler.over' : 'minigames.loren.over') }}</h2>
              <p class="score">{{ format(lastRun.score) }}</p>
              <p v-if="newBest" class="text-sm text-redstone-300">{{ t('minigames.newBest') }}</p>
              <slot name="result" :run="lastRun" />
            </template>
            <template v-else-if="state === 'paused'">
              <h2>{{ t('minigames.paused') }}</h2>
            </template>
            <template v-else>
              <h2>{{ t(game === 'stapler' ? 'minigames.stapler.name' : 'minigames.loren.name') }}</h2>
              <p class="text-sm text-base-300">{{ t(game === 'stapler' ? 'minigames.stapler.tagline' : 'minigames.loren.tagline') }}</p>
              <div v-if="game === 'stapler' && !compact" class="legend">
                <template v-for="(rule, i) in staplerRules" :key="rule.tile">
                  <canvas :ref="(el) => { if (el) legend[i] = el as HTMLCanvasElement }" class="pixel" width="32" height="32" />
                  <p><b>{{ t(rule.title) }}</b> {{ t(rule.text) }}</p>
                </template>
              </div>
              <p v-if="!compact" class="keys">{{ t(game === 'stapler' ? 'minigames.stapler.keys' : 'minigames.loren.keys') }}</p>
            </template>
            <button class="btn btn-primary mt-4" type="button" :disabled="starting" @click="state === 'paused' ? togglePause() : play()">
              {{ state === 'paused' ? t('minigames.resume') : state === 'over' ? t('minigames.again') : t('minigames.start') }}
            </button>
            <p class="mt-2 text-xs text-base-400">{{ t('minigames.enterHint') }}</p>
          </div>
        </div>
      </div>

      <div v-if="game === 'stapler'" class="side">
        <div class="box">
          <div class="lab">{{ t('minigames.stapler.next') }}</div>
          <canvas ref="nextCanvas" class="mini" width="48" height="104" />
        </div>
        <div class="box">
          <div class="lab">{{ t('minigames.score') }}</div>
          <div class="val">{{ format(hud?.score ?? 0) }}</div>
        </div>
        <div class="box">
          <div class="lab">{{ t('minigames.localBest') }}</div>
          <div class="val">{{ format(best) }}</div>
        </div>
        <div v-if="hud?.kind === 'stapler'" class="box">
          <div class="lab">{{ t('minigames.stapler.levelLines') }}</div>
          <div class="val">{{ hud.level }} · {{ hud.lines }}</div>
        </div>
        <div v-if="hud?.kind === 'stapler'" class="box">
          <div class="lab">{{ t('minigames.stapler.combo') }}</div>
          <div class="val">{{ hud.combo > 1 ? `×${hud.combo}` : '–' }}</div>
        </div>
      </div>

      <div class="controls">
        <button type="button" class="ctl" :aria-pressed="soundOn" :title="t(soundOn ? 'minigames.soundOff' : 'minigames.soundOn')" @click="toggleSound">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="icons[soundOn ? 'volume' : 'volumeOff']" /></svg>
          <span class="sr-only">{{ t(soundOn ? 'minigames.soundOff' : 'minigames.soundOn') }}</span>
        </button>
        <button v-if="state === 'playing' || state === 'paused'" type="button" class="ctl" :title="t('minigames.pauseKey')" @click="togglePause">
          <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="icons[state === 'paused' ? 'play' : 'pause']" /></svg>
          <span class="sr-only">{{ t('minigames.pauseKey') }}</span>
        </button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.minigame { padding: 12px; border-radius: 12px; overflow: hidden; }
.minigame:focus-visible { box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.28); }
.screen { position: relative; height: 100%; max-height: 100%; max-width: 100%; }
.screen[data-game='loren'] { height: auto; width: 100%; max-height: 100%; }
.pixel { display: block; width: 100%; height: 100%; image-rendering: pixelated; }
.side { display: flex; width: 142px; flex: none; flex-direction: column; gap: 8px; }
.box { background: rgb(0 0 0 / 0.45); border: 2px solid rgb(255 255 255 / 0.1); box-shadow: inset 0 0 0 2px rgb(0 0 0 / 0.4); padding: 8px 10px; }
.lab { font-size: 11px; letter-spacing: 0.12em; text-transform: uppercase; color: rgb(255 255 255 / 0.62); margin-bottom: 4px; }
.val { font-size: 20px; line-height: 1.2; font-weight: 700; color: #fff; font-variant-numeric: tabular-nums; }
.mini { display: block; width: 72px; margin: 0 auto; image-rendering: pixelated; }
.meter { display: grid; grid-template-columns: repeat(15, 1fr); gap: 2px; height: 20px; }
.meter i { display: block; background: rgb(255 255 255 / 0.08); }
.meter i.on { background: var(--color-redstone-500, #ff3b30); box-shadow: 0 0 6px var(--color-redstone-500, #ff3b30); }
.hint { min-height: 16px; margin-top: 5px; font-size: 12px; color: rgb(255 255 255 / 0.62); }
.hint.go { color: #fff; font-weight: 600; }
.loren-hud { position: absolute; inset: 0; display: flex; flex-direction: column; padding: 10px 14px; pointer-events: none; text-shadow: 0 1px 2px #000, 0 0 6px #000; background: linear-gradient(rgb(0 0 0 / 0.55), transparent 22%, transparent 82%, rgb(0 0 0 / 0.6)); }
.loren-hud .lab, .loren-hud .hint { color: rgb(255 255 255 / 0.9); }
.card-wrap { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; background: rgb(0 0 0 / 0.62); padding: 10px; overflow: auto; }
.card { width: min(560px, 100%); text-align: center; color: #fff; }
.card h2 { font-size: 26px; font-weight: 800; letter-spacing: 0.03em; }
.card-small h2 { font-size: 17px; }
.score { font-size: 30px; font-weight: 800; font-variant-numeric: tabular-nums; }
.legend { display: grid; grid-template-columns: 32px 1fr; gap: 8px 10px; margin-top: 12px; align-items: center; text-align: left; font-size: 13px; line-height: 1.35; }
.legend canvas { width: 32px; height: 32px; }
.keys { margin-top: 12px; font-size: 12px; line-height: 1.6; color: rgb(255 255 255 / 0.72); }
.controls { position: absolute; left: 12px; bottom: 12px; display: flex; gap: 6px; }
.loren-hud .mt-auto { margin-left: 84px; }
.ctl { display: grid; place-items: center; width: 32px; height: 32px; border-radius: 6px; color: #fff; background: rgb(0 0 0 / 0.55); border: 1px solid rgb(255 255 255 / 0.15); }
.ctl:hover { background: rgb(0 0 0 / 0.75); }
@media (max-width: 760px) { .side { display: none; } }
@media (max-height: 780px) {
  .side { gap: 5px; }
  .box { padding: 4px 8px; }
  .lab { margin-bottom: 1px; font-size: 10px; }
  .val { font-size: 16px; }
  .mini { width: 52px; }
  .hint { margin-top: 2px; }
  .meter { height: 14px; }
}
@media (prefers-reduced-motion: reduce) { .meter i.on { box-shadow: none; } }
</style>
