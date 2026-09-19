<script setup lang="ts">
import { isTauri } from '@tauri-apps/api/core'
import { getCurrentWindow } from '@tauri-apps/api/window'

// Fenster ist rahmenlos (`decorations: false`) – Steuerung übernehmen wir selbst.
const win = isTauri() ? getCurrentWindow() : null
</script>

<template>
  <header
    data-tauri-drag-region
    class="flex h-9 shrink-0 items-center justify-between border-b border-base-800 bg-base-900 pl-3"
  >
    <div data-tauri-drag-region class="flex items-center gap-2 text-xs font-semibold tracking-wide text-base-200">
      <img src="/icon.png" alt="" class="pointer-events-none size-4 [image-rendering:pixelated]" />
      <span data-tauri-drag-region>TRS LAUNCHER</span>
    </div>

    <div v-if="win" class="flex h-full">
      <button class="ctl" aria-label="Minimieren" @click="win.minimize()">
        <svg viewBox="0 0 10 10" class="size-2.5"><path d="M0 5h10" stroke="currentColor" /></svg>
      </button>
      <button class="ctl" aria-label="Maximieren" @click="win.toggleMaximize()">
        <svg viewBox="0 0 10 10" class="size-2.5"><rect x=".5" y=".5" width="9" height="9" fill="none" stroke="currentColor" /></svg>
      </button>
      <button class="ctl ctl-close" aria-label="Schließen" @click="win.close()">
        <svg viewBox="0 0 10 10" class="size-2.5"><path d="M0 0l10 10M10 0L0 10" stroke="currentColor" /></svg>
      </button>
    </div>
  </header>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.ctl {
  @apply flex h-full w-11 items-center justify-center text-base-400 transition-colors hover:bg-base-700 hover:text-base-50;
}
.ctl-close {
  @apply hover:bg-redstone-500 hover:text-white;
}
</style>
