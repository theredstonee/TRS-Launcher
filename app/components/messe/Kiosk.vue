<script setup lang="ts">
// Kiosk-Ebene des Messe-Builds: im Leerlauf läuft die Präsentation (iframe) über dem Launcher. Ein Klick/Tipp/
// Tastendruck gibt den echten Launcher frei; nach einer Weile ohne Eingabe (Standard 90 s) kommt „Noch da? 10 …“,
// danach geht es zurück auf die Startseite und die Präsentation startet neu. Läuft ein Spiel, wartet sie.
// Strg+Umschalt+M öffnet das Personal-Panel (PIN), Alt+F4/Schließen fragt ebenfalls nach der PIN.
import { isTauri } from '@tauri-apps/api/core'
import { getCurrentWindow } from '@tauri-apps/api/window'
import { exit } from '@tauri-apps/plugin-process'
import {
  type IdlePhase,
  IdleWatch,
  isPitchDemoMessage,
  type MesseConfig,
  messeStaffUnlocked,
  pitchUrl,
  readMesseConfig,
  writeMesseConfig,
} from '~/utils/messe'

const games = useGamesStore()
const onboarding = useOnboardingStore()
const ui = useUiStore()
const settings = useSettingsStore()
const packs = usePacksStore()
const bigPicture = useBigPictureStore()
const router = useRouter()

const config = ref<MesseConfig>(readMesseConfig())
const mode = ref<'pitch' | 'live'>('pitch')
const pitchKey = ref(0)
const countdown = ref<number | null>(null)
const staffOpen = ref(false)
/** PIN-Abfrage: wofür (Panel oder Beenden). */
const pinFor = ref<null | 'staff' | 'quit'>(null)
/** Neue PIN festlegen (aus dem schon entsperrten Panel). */
const changingPin = ref(false)
const frame = useTemplateRef<HTMLIFrameElement>('frame')
const gameRunning = computed(() => games.runningCount > 0)
let allowClose = false

const watch_ = new IdleWatch({
  timeoutMs: () => config.value.timeoutS * 1000,
  // Nicht zurück zur Präsentation, solange gespielt, eingerichtet oder vom Personal bedient wird.
  blocked: () =>
    mode.value !== 'live' || gameRunning.value || staffOpen.value || pinFor.value !== null || changingPin.value || onboarding.open,
  onPhase: (p: IdlePhase) => (countdown.value = p.kind === 'countdown' ? p.secondsLeft : null),
  onIdle: () => void startPitch(),
})

/** Alles, was ein Besucher geöffnet hat, schließen und zur Startseite. */
async function resetDemo() {
  messeStaffUnlocked.value = false
  for (let i = 0; i < 40 && closeTopOverlay(); i++) {
    // oberste Ebene nach der anderen
  }
  ui.palette = false
  ui.creating = false
  ui.importing = false
  ui.modpackInstall = null
  settings.dialog = null
  packs.codeDialog = null
  packs.mineOpen = false
  bigPicture.closeLayer()
  if (router.currentRoute.value.path !== '/') await router.push('/').catch(() => {})
  if (settings.current && settings.current.ui.language !== config.value.lang) void settings.setLanguage(config.value.lang).catch(() => {})
  if (document.activeElement instanceof HTMLElement) document.activeElement.blur()
}

async function startPitch() {
  staffOpen.value = false
  countdown.value = null
  await resetDemo()
  pitchKey.value++
  mode.value = 'pitch'
}

function enterLive() {
  if (mode.value !== 'pitch') return
  mode.value = 'live'
  watch_.input()
}

// „Jetzt selbst ausprobieren“ in der Präsentation (Nachricht nur vom eigenen iframe annehmen)
function onMessage(e: MessageEvent) {
  if (isPitchDemoMessage(e, frame.value?.contentWindow)) enterLive()
}
function onFrameLoad() {
  try {
    const win = frame.value?.contentWindow as (Window & { TRSPitch?: { play?: () => void } }) | null | undefined
    win?.addEventListener('trs-pitch:demo', enterLive)
    win?.TRSPitch?.play?.()
  } catch {
    // fremder Ursprung o. Ä. – dann bleibt die postMessage
  }
}

function isStaffKey(e: KeyboardEvent) {
  return (e.ctrlKey || e.metaKey) && e.shiftKey && !e.altKey && e.code === 'KeyM'
}

/** Tasten: Personal-Hotkey überall; während der Präsentation gibt jede Taste den Launcher frei. */
function onKeyCapture(e: KeyboardEvent) {
  if (isStaffKey(e)) {
    e.preventDefault()
    e.stopImmediatePropagation()
    if (!e.repeat && !pinFor.value && !staffOpen.value && !changingPin.value) pinFor.value = 'staff'
    return
  }
  if (pinFor.value || staffOpen.value || changingPin.value) return
  // Während der Start-Animation gehören die Tasten ihr (Überspringen), nicht der Präsentation.
  if (document.querySelector('[data-testid="start-intro"]')) return
  if (mode.value === 'pitch') {
    e.preventDefault()
    e.stopImmediatePropagation()
    if (!e.repeat) enterLive()
    return
  }
  watch_.input()
}

// Jede Eingabe im Launcher setzt die Leerlauf-Uhr zurück (auch Mausbewegung beim Lesen).
let lastMove = 0
function onActivity(e: Event) {
  if (mode.value !== 'live') return
  if (e.type === 'pointermove') {
    const now = Date.now()
    if (now - lastMove < 500) return
    lastMove = now
  }
  watch_.input()
}
const activityEvents = ['pointerdown', 'pointermove', 'wheel', 'touchstart'] as const

function onCountdownTap() {
  watch_.input()
}

async function onPinOk() {
  const purpose = pinFor.value
  pinFor.value = null
  if (purpose === 'staff') staffOpen.value = true
  else if (purpose === 'quit') await quit()
}
function onPinCancel() {
  pinFor.value = null
  changingPin.value = false
}
function onPinChanged() {
  changingPin.value = false
  staffOpen.value = true
}

function updateConfig(next: MesseConfig) {
  const langChanged = next.lang !== config.value.lang
  config.value = next
  writeMesseConfig(next)
  if (langChanged) void settings.setLanguage(next.lang).catch(() => {})
}

async function quit() {
  allowClose = true
  if (!isTauri()) return
  await exit(0).catch(() => getCurrentWindow().destroy())
}

let tick = 0
let unlistenClose: (() => void) | null = null
onMounted(async () => {
  window.addEventListener('keydown', onKeyCapture, true)
  window.addEventListener('message', onMessage)
  for (const ev of activityEvents) window.addEventListener(ev, onActivity, { capture: true, passive: true })
  tick = window.setInterval(() => watch_.tick(), 250)
  if (!isTauri()) return
  // Alt+F4 / Schließen-Knopf: nur mit PIN (Besucher sollen den Launcher nicht beenden)
  unlistenClose = await getCurrentWindow()
    .onCloseRequested((e) => {
      if (allowClose) return
      e.preventDefault()
      staffOpen.value = false
      pinFor.value = 'quit'
    })
    .catch(() => null)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeyCapture, true)
  window.removeEventListener('message', onMessage)
  for (const ev of activityEvents) window.removeEventListener(ev, onActivity, { capture: true })
  clearInterval(tick)
  unlistenClose?.()
})

// Sprache der Präsentation auch für den Launcher übernehmen, sobald die Einstellungen geladen sind.
watch(
  () => settings.current?.ui.language,
  (lang, before) => {
    if (lang && before === undefined && mode.value === 'pitch' && lang !== config.value.lang) void settings.setLanguage(config.value.lang).catch(() => {})
  },
)
</script>

<template>
  <Transition name="messe-pitch">
    <div v-if="mode === 'pitch'" class="messe-pitch" data-testid="messe-pitch">
      <iframe
        ref="frame"
        :key="pitchKey"
        class="messe-pitch-frame"
        :src="pitchUrl(config.lang)"
        sandbox="allow-scripts allow-same-origin"
        tabindex="-1"
        :title="t('messe.pitch.label')"
        @load="onFrameLoad"
      />
      <!-- Fängt jeden Klick/Tipp ab: die Präsentation ist nur zum Anschauen, ein Tipp startet die Demo. -->
      <button type="button" class="messe-pitch-catcher" :aria-label="t('messe.pitch.label')" data-testid="messe-pitch-catcher" @pointerdown.prevent="enterLive" @click="enterLive" />
    </div>
  </Transition>

  <Transition name="messe-pitch">
    <div v-if="countdown !== null" class="messe-countdown" role="alertdialog" aria-live="assertive" data-testid="messe-countdown" @pointerdown.stop.prevent="onCountdownTap">
      <div class="messe-countdown-card">
        <p class="messe-countdown-title">{{ t('messe.countdown.title') }}</p>
        <p class="messe-countdown-number">{{ countdown }}</p>
        <p class="messe-countdown-hint">{{ t('messe.countdown.hint') }}</p>
      </div>
    </div>
  </Transition>

  <MesseStaffPanel
    v-if="staffOpen"
    :config="config"
    :game-running="gameRunning"
    @close="staffOpen = false"
    @update="updateConfig"
    @start-pitch="startPitch"
    @reset="resetDemo().then(() => (staffOpen = false))"
    @change-pin="(staffOpen = false), (changingPin = true)"
    @quit="quit"
  />
  <MessePinDialog v-if="pinFor" :key="pinFor" :purpose="pinFor" @ok="onPinOk" @cancel="onPinCancel" />
  <MessePinDialog v-else-if="changingPin" key="change" purpose="staff" change @ok="onPinChanged" @cancel="onPinCancel" />
</template>

<style scoped>
.messe-pitch {
  position: fixed;
  inset: 0;
  z-index: 2147482000;
  background: #0b0c0f;
}
.messe-pitch-frame {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
}
.messe-pitch-catcher {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  border: 0;
  background: transparent;
  cursor: pointer;
}
.messe-countdown {
  position: fixed;
  inset: 0;
  z-index: 2147482100;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgb(5 5 8 / 0.72);
  backdrop-filter: blur(3px);
  cursor: pointer;
}
.messe-countdown-card {
  text-align: center;
  color: #f2f2f5;
}
.messe-countdown-title {
  font-size: clamp(1.6rem, 3.5vw, 2.6rem);
  font-weight: 700;
}
.messe-countdown-number {
  font-size: clamp(5rem, 14vw, 10rem);
  font-weight: 800;
  line-height: 1;
  color: var(--color-redstone-400, #ff4b3e);
  font-variant-numeric: tabular-nums;
}
.messe-countdown-hint {
  margin-top: 0.75rem;
  font-size: clamp(1rem, 1.6vw, 1.3rem);
  color: rgb(242 242 245 / 0.75);
}
.messe-pitch-enter-active,
.messe-pitch-leave-active {
  transition: opacity 0.35s ease;
}
.messe-pitch-enter-from,
.messe-pitch-leave-to {
  opacity: 0;
}
</style>
