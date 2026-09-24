<script setup lang="ts">
// Kleiner Update-Knopf in der Titelleiste (wie „Reload to update“ in der Modrinth App).
const updater = useUpdaterStore()
const games = useGamesStore()
onMounted(() => updater.start())

const running = computed(() => games.runningCount > 0)
const label = computed(() => {
  switch (updater.phase) {
    case 'ready':
      return t('updater.restartToUpdate')
    case 'installing':
      return t('updater.installing')
    case 'failed':
      return t('updater.retry')
    case 'external':
      return t('updater.availableExternal', { version: updater.version ?? '' })
    default:
      return ''
  }
})
const title = computed(() => {
  const version = updater.version ?? ''
  if (updater.phase === 'external') return t('updater.externalHint')
  if (updater.phase === 'downloading') return t('updater.downloading', { version, percent: updater.percent })
  if (updater.phase === 'failed') return t('updater.failed', { reason: updater.failReason })
  return running.value ? t('updater.readyRunning', { version }) : t('updater.ready', { version })
})

function openRelease() {
  if (updater.version) backend.openExternalUrl(`https://github.com/theredstonee/TRS-Launcher/releases/tag/v${updater.version}`).catch(() => {})
}
</script>

<template>
  <!-- Während des Downloads nur ein dezenter Fortschrittsring. -->
  <span
    v-if="updater.phase === 'downloading'"
    class="mr-1 grid size-6 place-items-center"
    :title="title"
    role="status"
    :aria-label="title"
  >
    <svg viewBox="0 0 20 20" class="size-4 -rotate-90">
      <circle cx="10" cy="10" r="8" fill="none" stroke="currentColor" stroke-width="2.5" class="text-base-800" />
      <circle
        cx="10"
        cy="10"
        r="8"
        fill="none"
        stroke="currentColor"
        stroke-width="2.5"
        stroke-linecap="round"
        class="text-redstone-500 transition-[stroke-dashoffset] duration-300"
        :stroke-dasharray="50.27"
        :stroke-dashoffset="50.27 * (1 - updater.percent / 100)"
      />
    </svg>
  </span>

  <div v-else-if="label" class="mr-1 flex items-center gap-1.5">
    <button
      v-if="updater.phase === 'failed' && updater.failures > 1"
      class="text-[11px] text-base-400 underline-offset-2 hover:text-base-200 hover:underline"
      @click="openRelease"
    >
      {{ t('updater.downloadInstaller') }}
    </button>
    <button
      class="update-pill"
      :class="{ 'update-pill-failed': updater.phase === 'failed' }"
      :disabled="updater.phase === 'installing'"
      :title="title"
      @click="updater.phase === 'external' ? openRelease() : updater.restart()"
    >
      <svg viewBox="0 0 24 24" class="size-3.5" :class="{ 'animate-spin': updater.phase === 'installing' }" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
        <path d="M20 12a8 8 0 1 1-2.34-5.66" />
        <path d="M20 4v5h-5" />
      </svg>
      {{ label }}
    </button>
  </div>

  <!-- Wie der Start-Ladebildschirm: Das Fenster wird nahtlos zur Aktualisierung. -->
  <Teleport to="body">
    <Transition name="fade">
      <div v-if="updater.phase === 'installing'" class="update-splash" role="status" aria-live="polite">
        <img src="/icon.png" alt="" width="64" height="64" />
        <p class="display text-sm tracking-widest text-base-200 uppercase">TRS Launcher {{ updater.version }}</p>
        <p class="text-xs text-base-400">{{ t('updater.splash') }}</p>
        <div class="update-wire"><span /></div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.update-pill {
  @apply flex items-center gap-1.5 rounded-md bg-redstone-500 px-2.5 py-1 text-[11px] font-semibold text-white shadow-[0_0_12px_-2px_var(--color-redstone-500)] transition-colors hover:bg-redstone-400 disabled:opacity-70;
}
.update-splash {
  @apply fixed inset-0 z-[9999] flex flex-col items-center justify-center gap-3;
  background: radial-gradient(ellipse at center, #1c1a1d 0%, #111114 70%);
}
.update-splash img {
  image-rendering: pixelated;
  filter: drop-shadow(0 0 14px rgb(224 40 30 / 0.55));
}
.update-wire {
  @apply relative mt-1 h-[3px] w-40 overflow-hidden bg-[#3a1512];
}
.update-wire span {
  @apply absolute inset-y-0 w-2/5;
  background: linear-gradient(90deg, transparent, var(--color-redstone-500), #ff6a4d, var(--color-redstone-500), transparent);
  box-shadow: 0 0 10px var(--color-redstone-500);
  animation: update-signal 1.1s steps(16) infinite;
}
@keyframes update-signal {
  from { transform: translateX(-100%); }
  to { transform: translateX(250%); }
}
@media (prefers-reduced-motion: reduce) {
  .update-wire span { animation: none; @apply w-full; }
}
.fade-enter-active { transition: opacity 0.2s ease; }
.fade-enter-from { opacity: 0; }
.update-pill-failed {
  @apply bg-base-800 text-lamp-300 shadow-none hover:bg-base-700;
}
</style>
