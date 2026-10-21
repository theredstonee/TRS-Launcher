<script setup lang="ts">
// Start-Animation beim Öffnen des Launchers. Liegt als Vollbild-Ebene über der App, die darunter ganz normal
// startet (nichts wartet auf die Animation). Übergabe erst, wenn Animation (oder Überspringen) UND App bereit sind.
// Länge: nach Updates lang, erster Start des Tages mittel (beide immer), sonst kurz (abschaltbar).
// Ob Flackern/Lichtblitze laufen, steuert die Einstellung (ruhige Fassung); den Erst-Hinweis zeigt StartIntroHint.
import { getVersion } from '@tauri-apps/api/app'
import { isTauri } from '@tauri-apps/api/core'
import { IntroPlayer } from '~/utils/intro/player'
import { introDay, introReduced, pickIntroLength, shouldPlayIntro } from '~/utils/intro/select'
import type { IntroLength } from '~/utils/intro/timeline'

const settings = useSettingsStore()
const show = ref(false)
const leaving = ref(false)
const skipHint = ref(false)
const cv = useTemplateRef<HTMLCanvasElement>('cv')
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

function tryLeave() {
  if (animDone && appReady) leave()
}

function leave() {
  if (!show.value || leaving.value) return
  leaving.value = true
  skipHint.value = false
  introPlayedThisSession.value = true
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
  if (!show.value || leaving.value) return
  if (!player) return leave()
  player.skip()
}

function onKey(e: KeyboardEvent) {
  if (!show.value) return
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
  play(len, prefs.sound)
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
    // Erst nach der Entscheidung über die Länge (sonst gälte noch der Vorgabewert „kurz“)
    if (!ui || !show.value || leaving.value || !player) return
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
    <span class="start-intro-skip" :class="{ on: skipHint }" aria-hidden="true">{{ t('startIntro.skip') }}</span>
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
</style>
