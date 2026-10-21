<script setup lang="ts">
// Start-Animation beim Öffnen des Launchers. Liegt als Vollbild-Ebene über der App, die darunter ganz normal
// startet (nichts wartet auf die Animation). Übergabe erst, wenn Animation (oder Überspringen) UND App bereit sind.
// Länge: nach Updates lang (immer, nicht abschaltbar), erster Start des Tages mittel, sonst kurz.
// Beim ersten Start fragt ein kleiner Dialog über dem ersten Bild, ob Flackern und Lichtblitze gezeigt werden sollen.
import { getVersion } from '@tauri-apps/api/app'
import { isTauri } from '@tauri-apps/api/core'
import { IntroPlayer } from '~/utils/intro/player'
import { introDay, introReduced, pickIntroLength, shouldAskFlicker, shouldPlayIntro } from '~/utils/intro/select'
import type { IntroLength } from '~/utils/intro/timeline'

const settings = useSettingsStore()
const show = ref(false)
const leaving = ref(false)
const skipHint = ref(false)
/** Erst-Start-Frage nach Flackern offen (wartet mit der Länge, die danach läuft). */
const asking = ref<IntroLength | null>(null)
const cv = useTemplateRef<HTMLCanvasElement>('cv')
const askBox = useTemplateRef<HTMLDivElement>('askBox')
let player: IntroPlayer | null = null
let length: IntroLength = 'short'
let animDone = false
let appReady = false
let safety = 0
let hintTimer = 0

/** Nur im Hauptfenster, nie im Clip-Overlay oder auf der Vorschau-Seite; in Entwicklungs-Builds nur auf Wunsch. */
function eligible(): boolean {
  if (typeof window === 'undefined') return false
  const path = window.location.pathname
  if (path.startsWith('/clip-overlay') || path.startsWith('/intro-preview')) return false
  if (import.meta.dev) {
    try {
      if (localStorage.getItem('trs.intro.dev') !== '1') return false
    } catch {
      return false
    }
  }
  if (isTauri()) {
    const label = (window as { __TAURI_INTERNALS__?: { metadata?: { currentWindow?: { label?: string } } } }).__TAURI_INTERNALS__?.metadata?.currentWindow?.label
    if (label && label !== 'main') return false
  }
  return true
}

const prefs = readIntroPrefs()
// Sofort zudecken (kein Aufblitzen der App vor der Animation); fällt die Entscheidung gegen die Animation, geht die
// Ebene im ersten Takt wieder weg.
show.value = eligible()

async function appVersion(): Promise<string> {
  if (!isTauri()) return ''
  try {
    return await Promise.race([getVersion(), new Promise<string>((r) => setTimeout(() => r(''), 400))])
  } catch {
    return ''
  }
}

const systemReduced = () => typeof matchMedia === 'function' && matchMedia(REDUCED_MOTION_QUERY).matches

function play(len: IntroLength, sound: boolean) {
  const canvas = cv.value
  if (!canvas) return
  player?.stop(0.05)
  length = len
  animDone = false
  leaving.value = false
  skipHint.value = false
  const design = bootIntroDesign()
  const reduced = introReduced(prefs.motion, systemReduced())
  player = new IntroPlayer({
    canvas,
    design,
    length: len,
    reduced,
    calm: !prefs.flicker,
    sound,
    volume: 0.8,
    jingle: introJingleUrl(design.id),
    logo: '/icon.png',
    mobile: isMobile,
    onReady: () => {
      animDone = true
      tryLeave()
    },
  })
  player.start()
  clearTimeout(hintTimer)
  hintTimer = window.setTimeout(() => (skipHint.value = true), 900)
  // Sicherheitsnetz: nie länger als nötig über der App hängen
  clearTimeout(safety)
  safety = window.setTimeout(() => leave(), (player.duration + 12) * 1000)
}

/** Erste Szene als Standbild hinter der Frage (die App lädt darunter weiter). */
async function ask(len: IntroLength) {
  asking.value = len
  await nextTick()
  const canvas = cv.value
  if (canvas) {
    const preview = new IntroPlayer({ canvas, design: bootIntroDesign(), length: len, calm: true })
    preview.still(0.8)
  }
  askBox.value?.querySelector<HTMLButtonElement>('button')?.focus()
}

/** Antwort merken (sofort lokal, im Kern sobald die Einstellungen geladen sind) und die Animation starten. */
function answer(flicker: boolean) {
  const len = asking.value
  if (!len) return
  asking.value = null
  prefs.flicker = flicker
  prefs.flickerAsked = true
  rememberIntroFlicker(flicker)
  const persist = () => {
    const cur = settings.current
    if (!cur) return false
    void settings.save({ ...cur, ui: { ...cur.ui, startIntroFlicker: flicker, startIntroFlickerAsked: true } }).catch(() => {})
    return true
  }
  if (!persist()) {
    const stop = watch(
      () => settings.current,
      (cur) => {
        if (cur && persist()) stop()
      },
    )
  }
  play(len, prefs.sound)
}

/** Tastatur im Dialog: Tab bleibt zwischen den beiden Knöpfen, Esc = ruhig. */
function onAskKey(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    e.preventDefault()
    answer(false)
    return
  }
  if (e.key !== 'Tab') return
  const buttons = [...(askBox.value?.querySelectorAll<HTMLButtonElement>('button') ?? [])]
  if (!buttons.length) return
  const i = buttons.indexOf(document.activeElement as HTMLButtonElement)
  e.preventDefault()
  buttons[(i + (e.shiftKey ? -1 : 1) + buttons.length) % buttons.length]!.focus()
}

function tryLeave() {
  if (animDone && appReady) leave()
}

function leave() {
  if (!show.value || leaving.value) return
  leaving.value = true
  skipHint.value = false
  player?.fadeAudio(0.4)
  window.setTimeout(() => {
    player?.stop(0.1)
    player = null
    show.value = false
    leaving.value = false
    clearTimeout(safety)
  }, 480)
}

/** Klick/Taste: aufs fertige Logo springen; die Übergabe folgt, sobald die App bereit ist. */
function skip() {
  if (!show.value || leaving.value || asking.value) return
  if (!player) return leave()
  player.skip()
}

function onKey(e: KeyboardEvent) {
  if (!show.value) return
  // Während der Frage gehören die Tasten dem Dialog
  if (asking.value) return
  // Tasten gehören während der Animation nur ihr (kein Strg+K o. Ä. darunter)
  e.preventDefault()
  e.stopImmediatePropagation()
  if (!e.repeat) skip()
}

onNuxtReady(() => {
  appReady = true
  tryLeave()
})

onMounted(async () => {
  window.addEventListener('keydown', onKey, true)
  if (!show.value) return
  const version = await appVersion()
  const last = readIntroMemory()
  const today = introDay(new Date())
  const len = pickIntroLength({ last, today, version, mobile: isMobile })
  writeIntroMemory({ day: today, version: version || last?.version || '' })
  if (!shouldPlayIntro(len, prefs.enabled)) {
    show.value = false
    return
  }
  await nextTick()
  if (shouldAskFlicker(prefs, introReduced(prefs.motion, systemReduced()))) void ask(len)
  else play(len, prefs.sound)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey, true)
  clearTimeout(safety)
  clearTimeout(hintTimer)
  player?.stop(0.05)
})

// Echte Einstellungen kommen später aus dem Kern: abgeschaltet → sofort weg (außer nach Updates), Ton aus → leise.
watch(
  () => settings.current?.ui,
  (ui) => {
    if (!ui || !show.value || leaving.value) return
    if (ui.startIntro === false && !shouldPlayIntro(length, false)) skip()
    if (ui.startIntroSound === false) player?.fadeAudio(0.2)
  },
)

// „Jetzt abspielen“ in den Einstellungen
watch(
  () => introReplay.value.n,
  async () => {
    appReady = true
    show.value = true
    await nextTick()
    play(introReplay.value.length, settings.current?.ui.startIntroSound !== false)
  },
)
</script>

<template>
  <div
    v-if="show"
    class="start-intro"
    :class="{ leaving }"
    role="img"
    :aria-label="t('startIntro.label')"
    data-testid="start-intro"
    @pointerdown.prevent="skip"
  >
    <canvas ref="cv" class="start-intro-canvas" />
    <span class="start-intro-skip" :class="{ on: skipHint && !asking }" aria-hidden="true">{{ t('startIntro.skip') }}</span>
    <div v-if="asking" class="start-intro-ask" @pointerdown.stop>
      <div
        ref="askBox"
        class="start-intro-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="start-intro-ask-title"
        aria-describedby="start-intro-ask-text"
        data-testid="start-intro-ask"
        @keydown="onAskKey"
      >
        <h2 id="start-intro-ask-title" class="display start-intro-ask-title">{{ t('startIntro.flickerTitle') }}</h2>
        <p id="start-intro-ask-text" class="start-intro-ask-text">{{ t('startIntro.flickerQuestion') }}</p>
        <div class="start-intro-ask-actions">
          <button type="button" class="btn btn-primary" data-testid="start-intro-flicker-on" @click="answer(true)">{{ t('startIntro.flickerOn') }}</button>
          <button type="button" class="btn btn-ghost" data-testid="start-intro-flicker-off" @click="answer(false)">{{ t('startIntro.flickerOff') }}</button>
        </div>
        <p class="start-intro-ask-hint">{{ t('startIntro.flickerLater') }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.start-intro {
  position: fixed;
  inset: 0;
  z-index: 2147483000;
  background: #050508;
  cursor: pointer;
  opacity: 1;
  transition: opacity 0.45s ease;
  contain: strict;
}
.start-intro.leaving {
  opacity: 0;
  pointer-events: none;
}
.start-intro-canvas {
  display: block;
  width: 100%;
  height: 100%;
  image-rendering: pixelated;
}
.start-intro-skip {
  position: absolute;
  right: 18px;
  bottom: 14px;
  font-size: 12px;
  letter-spacing: 0.04em;
  color: rgb(255 255 255 / 0.55);
  opacity: 0;
  transition: opacity 0.6s ease;
  pointer-events: none;
  user-select: none;
}
.start-intro-skip.on {
  opacity: 1;
}
.start-intro-ask {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 16px;
  background: rgb(4 4 8 / 0.45);
  cursor: default;
}
.start-intro-card {
  width: min(440px, 100%);
  padding: 22px 22px 16px;
  border-radius: 14px;
  background: rgb(18 18 24 / 0.92);
  border: 1px solid rgb(255 255 255 / 0.1);
  box-shadow: 0 20px 60px rgb(0 0 0 / 0.6);
  color: #e8e6e3;
}
.start-intro-ask-title {
  font-size: 18px;
  margin: 0 0 6px;
}
.start-intro-ask-text {
  margin: 0 0 16px;
  opacity: 0.85;
  font-size: 14px;
  line-height: 1.45;
}
.start-intro-ask-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.start-intro-ask-actions .btn {
  flex: 1 1 160px;
}
.start-intro-ask-hint {
  margin: 12px 0 0;
  font-size: 12px;
  opacity: 0.55;
}
</style>
