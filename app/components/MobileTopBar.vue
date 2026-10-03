<script setup lang="ts">
// Handy: schmale Kopfzeile statt Titelleiste (keine Fenstersteuerung). Auf Unterseiten
// ein Zurück-Pfeil, sonst das Logo; rechts Aufgaben, Suche und das Konto.
const route = useRoute()
const router = useRouter()
const ui = useUiStore()
const accounts = useAccountsStore()

const isRoot = computed(() => (MOBILE_TAB_ROOTS as readonly string[]).includes(route.path))

function back() {
  if (window.history.length > 1) router.back()
  else router.push('/')
}
</script>

<template>
  <header class="relative z-40 shrink-0 border-b border-base-800 bg-base-900 pt-[var(--safe-top)] pr-[var(--safe-right)] pl-[var(--safe-left)]" data-testid="mobile-topbar">
    <div class="flex h-12 items-center gap-1 px-2">
      <button v-if="!isRoot" class="top-btn" :aria-label="t('common.actions.back')" data-testid="mobile-back" @click="back">
        <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="m15 5-7 7 7 7" /></svg>
      </button>
      <NuxtLink v-else to="/" class="flex h-11 items-center gap-2 px-2 text-base-200" :aria-label="t('nav.home')">
        <img src="/icon.png" alt="" class="pointer-events-none size-5 [image-rendering:pixelated]" />
        <span class="display text-[15px] leading-none">TRS Launcher</span>
      </NuxtLink>

      <div class="min-w-0 flex-1" />

      <TasksButton />
      <button class="top-btn" :aria-label="t('common.actions.search')" @click="ui.openPalette()">
        <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path :d="icons.search" /></svg>
      </button>
      <NuxtLink
        to="/accounts"
        class="top-btn"
        :aria-label="accounts.active ? t('nav.accountNamed', { name: accounts.active.name }) : t('nav.signIn')"
      >
        <SkinHead :skin-url="accounts.active?.skinUrl ?? null" :name="accounts.active?.name ?? '?'" :size="26" />
      </NuxtLink>
    </div>
  </header>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.top-btn {
  @apply grid size-11 shrink-0 place-items-center rounded-xl text-base-200 transition-colors active:bg-base-800;
}
/* Redstone-Leitung unter dem Logo, wie in der Titelleiste am Desktop. */
header::after {
  content: "";
  position: absolute;
  left: 0;
  bottom: -1px;
  width: 45%;
  height: 1px;
  background: linear-gradient(90deg, var(--color-redstone-500), transparent);
  opacity: 0.7;
  pointer-events: none;
}
</style>
