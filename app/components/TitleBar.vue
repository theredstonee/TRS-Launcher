<script setup lang="ts">
import { isTauri } from '@tauri-apps/api/core'
import { getCurrentWindow } from '@tauri-apps/api/window'

// Fenster ist rahmenlos (`decorations: false`) – Steuerung übernehmen wir
// selbst: Ziehbereich, Vor/Zurück, Minimieren, Maximieren, Schließen.
// Doppelklick auf den Ziehbereich maximiert (übernimmt Windows selbst),
// Andocken per Ziehen an den Bildschirmrand und Windows+Pfeil bleibt erhalten.
const win = isTauri() ? getCurrentWindow() : null
const router = useRouter()
const ui = useUiStore()
const maximized = ref(false)

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
    <div data-tauri-drag-region class="flex items-center gap-2 text-base-200">
      <img src="/icon.png" alt="" class="pointer-events-none size-4 [image-rendering:pixelated]" />
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

    <UpdateButton />

    <!-- Laufende Instanzen und Hintergrund-Aufgaben (wie in der Modrinth App). -->
    <RunningPill class="mr-1" />
    <TasksButton class="mr-1" />

    <button class="mr-1 hidden items-center gap-2 rounded-md border border-base-800 bg-base-850 px-2.5 py-1 text-[11px] text-base-400 transition-colors hover:border-base-700 hover:text-base-200 sm:flex" @click="ui.openPalette()">
      <svg viewBox="0 0 24 24" class="size-3" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path :d="icons.search" /></svg>
      {{ t('common.actions.search') }}
      <kbd class="rounded border border-base-700 px-1 font-mono text-[10px]">{{ t('titleBar.ctrl') }} K</kbd>
    </button>

    <AccountMenu />
    <div class="h-4 w-px bg-base-800" />

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
