<script setup lang="ts">
import { MESSE } from '~/utils/messe'
import { isTauri } from '@tauri-apps/api/core'
import { getCurrentWindow } from '@tauri-apps/api/window'

// Fenster ist rahmenlos (`decorations: false`) – Steuerung übernehmen wir
// selbst: Ziehbereich, Vor/Zurück, Minimieren, Maximieren, Schließen.
// Doppelklick auf den Ziehbereich maximiert (übernimmt Windows selbst),
// Andocken per Ziehen an den Bildschirmrand und Windows+Pfeil bleibt erhalten.
const win = isTauri() ? getCurrentWindow() : null
const router = useRouter()
const ui = useUiStore()
const openDocs = useDocs()
const bigPicture = useBigPictureStore()
const maximized = ref(false)
const events = useEventsStore()
const design = useDesign()

let stop: (() => void) | undefined
onMounted(async () => {
  if (!win) return
  maximized.value = await win.isMaximized().catch(() => false)
  stop = await win.onResized(async () => {
    maximized.value = await win.isMaximized().catch(() => false)
  })
})
onBeforeUnmount(() => stop?.())

async function toggleMaximize() {
  if (!win) return
  await win.toggleMaximize()
  maximized.value = await win.isMaximized().catch(() => false)
}
</script>

<template>
  <header
    data-tauri-drag-region
    class="titlebar relative z-40 flex h-9 shrink-0 items-center gap-2 border-b border-base-800 bg-base-900 pl-3"
  >
    <!-- Spinnennetz in der Ecke, hinter Logo und Schrift, fängt keine Klicks. -->
    <svg v-if="events.halloween" class="cobweb" viewBox="0 0 40 40" aria-hidden="true">
      <g fill="none" stroke="currentColor" stroke-width="1">
        <path d="M.5.5H39.5M.5.5V39.5M.5.5L39.5 14.5M.5.5L14.5 39.5M.5.5L39.5 39.5" />
        <path d="M9.5.5V7.5H.5M18.5.5l-3 10-8 3L.5 12.5M27.5.5l-8 13-9 6-6-1L.5 20.5M34.5 4.5l-12 14-10 8-7-2L.5 28.5" />
      </g>
    </svg>
    <div data-tauri-drag-region class="relative z-[1] flex items-center gap-2 text-base-200">
      <img src="/icon.png" alt="" class="pointer-events-none size-4 [image-rendering:pixelated]" />
      <template v-if="events.halloween">
        <HalloweenPixelSprite name="pumpkin" class="pumpkin" />
        <HalloweenPixelSprite name="moonbat" class="moonbat" />
      </template>
      <PixelSnowflake v-else-if="design === 'winter'" />
      <span data-tauri-drag-region class="display text-[13px] leading-none">TRS Launcher</span>
    </div>

    <div class="ml-2 flex items-center gap-0.5">
      <button class="nav-btn" :aria-label="t('common.actions.back')" :title="t('common.actions.back')" @click="router.back()">
        <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="m15 5-7 7 7 7" /></svg>
      </button>
      <button class="nav-btn" :aria-label="t('titleBar.forward')" :title="t('titleBar.forward')" @click="router.forward()">
        <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="m9 5 7 7-7 7" /></svg>
      </button>
    </div>

    <div data-tauri-drag-region class="min-w-0 flex-1" />

    <!-- Messe-Build: keine Updates (feste Fassung am Stand) -->
    <UpdateButton v-if="!MESSE" />

    <!-- Laufende Instanzen und Hintergrund-Aufgaben. -->
    <RunningPill class="mr-1" />
    <TasksButton class="mr-1" />

    <button class="mr-1 hidden items-center gap-2 rounded-md border border-base-800 bg-base-850 px-2.5 py-1 text-[11px] text-base-400 transition-colors hover:border-base-700 hover:text-base-200 sm:flex" @click="ui.openPalette()">
      <svg viewBox="0 0 24 24" class="size-3" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path :d="icons.search" /></svg>
      {{ t('common.actions.search') }}
      <kbd class="rounded border border-base-700 px-1 font-mono text-[10px]">{{ t('titleBar.ctrl') }} K</kbd>
    </button>

    <!-- Big-Picture-Modus (Fernseher, Steam Deck, Controller). -->
    <button class="nav-btn mr-1" :aria-label="t('titleBar.bigPicture')" :title="t('titleBar.bigPicture')" data-testid="titlebar-big-picture" @click="bigPicture.open()">
      <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.gamepad" /></svg>
    </button>

    <!-- Hilfe: Dokumentation auf der Website in der Launcher-Sprache. -->
    <button class="nav-btn mr-1" :aria-label="t('docs.open')" :title="t('docs.open')" data-testid="titlebar-docs" @click="openDocs()">
      <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.help" /></svg>
    </button>

    <AccountMenu />
    <div class="h-4 w-px bg-base-800" />

    <!-- Halloween 2026: Spinnennetz neben den Fensterknöpfen, darin versteckt ein Kürbis (Kürbis-Suche 1/13). -->
    <div v-if="events.halloween" class="web-corner">
      <svg class="web" viewBox="0 0 46 36" aria-hidden="true"><g fill="none" stroke="#cfd5e3" stroke-width=".6"><path d="M46 0 L14 36 M46 0 L28 36 M46 0 L4 20 M46 0 L0 6" /><path d="M38 0 Q36 8 46 10 M28 0 Q26 14 46 18 M18 0 Q16 20 46 28" /></g></svg>
      <HalloweenPumpkin id="launcher-titlebar" :size="11" class="web-pumpkin" />
    </div>
    <div v-if="win" class="flex h-full">
      <button class="ctl" :aria-label="t('titleBar.minimize')" @click="win.minimize()">
        <svg viewBox="0 0 10 10" class="size-2.5"><path d="M0 5h10" stroke="currentColor" /></svg>
      </button>
      <button class="ctl" :aria-label="maximized ? t('titleBar.restore') : t('titleBar.maximize')" @click="toggleMaximize">
        <svg v-if="maximized" viewBox="0 0 10 10" class="size-2.5" fill="none" stroke="currentColor">
          <rect x=".5" y="2.5" width="7" height="7" />
          <path d="M2.5 2.5v-2h7v7h-2" />
        </svg>
        <svg v-else viewBox="0 0 10 10" class="size-2.5"><rect x=".5" y=".5" width="9" height="9" fill="none" stroke="currentColor" /></svg>
      </button>
      <button class="ctl ctl-close" :aria-label="t('common.actions.close')" @click="win.close()">
        <svg viewBox="0 0 10 10" class="size-2.5"><path d="M0 0l10 10M10 0L0 10" stroke="currentColor" /></svg>
      </button>
    </div>
  </header>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.cobweb {
  position: absolute;
  top: 0;
  left: 0;
  z-index: 0;
  width: 36px;
  height: 36px;
  pointer-events: none;
  color: var(--color-base-200);
  opacity: 0.42;
}
.pumpkin {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  pointer-events: none;
  filter: drop-shadow(0 0 4px rgba(224, 138, 60, 0.6));
}
.moonbat {
  width: 18px;
  height: 16px;
  flex-shrink: 0;
  pointer-events: none;
}
.web-corner {
  position: relative;
  width: 46px;
  height: 100%;
  flex-shrink: 0;
}
.web {
  position: absolute;
  inset: 0;
  width: 46px;
  height: 36px;
  opacity: 0.55;
  pointer-events: none;
}
.web-pumpkin {
  position: absolute;
  right: 9px;
  top: 6px;
}
/* Eine Redstone-Leitung unter dem Logo, die nach rechts ausläuft. */
.titlebar::after {
  content: "";
  position: absolute;
  left: 0;
  bottom: -1px;
  width: min(22rem, 40%);
  height: 1px;
  background: linear-gradient(90deg, var(--color-redstone-500), transparent);
  opacity: 0.7;
  pointer-events: none;
}
/* Winternacht: eine Frostlinie mit kleinen Eiskristallen statt der Leitung. */
:root[data-design="winter"]:not([data-event="halloween"]) .titlebar::after {
  height: 2px;
  bottom: -1px;
  background:
    repeating-linear-gradient(90deg, #fff 0 2px, transparent 2px 14px),
    linear-gradient(90deg, var(--color-redstone-400), color-mix(in srgb, var(--color-redstone-300) 50%, transparent) 50%, transparent);
  -webkit-mask-image: linear-gradient(90deg, #000 40%, transparent);
  mask-image: linear-gradient(90deg, #000 40%, transparent);
  opacity: 0.75;
}
.ctl {
  @apply flex h-full w-11 items-center justify-center text-base-400 transition-colors hover:bg-base-700 hover:text-base-50;
}
.ctl-close {
  @apply hover:bg-redstone-500 hover:text-white;
}
.nav-btn {
  @apply grid size-6 place-items-center rounded-md text-base-600 transition-colors hover:bg-base-800 hover:text-base-200;
}
</style>
