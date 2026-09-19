<script setup lang="ts">
const items = [
  { to: '/', label: 'Instanzen', icon: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z' },
  { to: '/browse', label: 'Entdecken', icon: 'M11 4a7 7 0 1 0 4.2 12.6l4.1 4.1 1.4-1.4-4.1-4.1A7 7 0 0 0 11 4zm0 2a5 5 0 1 1 0 10 5 5 0 0 1 0-10z' },
  { to: '/accounts', label: 'Accounts', icon: 'M12 3a4.5 4.5 0 1 1 0 9 4.5 4.5 0 0 1 0-9zM4 21c0-4 3.6-7 8-7s8 3 8 7z' },
]

const accounts = useAccountsStore()
const games = useGamesStore()
const runningCount = computed(() => Object.values(games.states).filter((s) => s.phase !== 'idle').length)

const info = ref<string | null>(null)
onMounted(async () => {
  try {
    info.value = (await backend.appInfo()).version
  } catch {
    info.value = null
  }
})
</script>

<template>
  <nav class="flex w-52 shrink-0 flex-col border-r border-base-800 bg-base-900 p-2">
    <NuxtLink v-for="item in items" :key="item.to" :to="item.to" class="nav-item" active-class="nav-active">
      <svg viewBox="0 0 24 24" class="size-4 shrink-0" fill="currentColor"><path :d="item.icon" /></svg>
      {{ item.label }}
      <span v-if="item.to === '/' && runningCount" class="ml-auto flex items-center gap-1.5 text-xs text-ok">
        <span class="size-1.5 animate-pulse rounded-full bg-ok" />{{ runningCount }}
      </span>
    </NuxtLink>

    <div class="mt-auto">
      <NuxtLink to="/accounts" class="mb-1 flex items-center gap-2.5 rounded-md border border-base-800 bg-base-850 p-2 transition-colors hover:border-base-700">
        <SkinHead :skin-url="accounts.active?.skinUrl ?? null" :name="accounts.active?.name ?? '?'" :size="28" />
        <div class="min-w-0">
          <p class="truncate text-sm font-medium">{{ accounts.active?.name ?? 'Nicht angemeldet' }}</p>
          <p class="truncate text-[11px] text-base-400">
            {{ accounts.active ? (accounts.items.length > 1 ? `${accounts.items.length} Accounts` : 'Microsoft-Konto') : 'Jetzt anmelden' }}
          </p>
        </div>
      </NuxtLink>
      <NuxtLink to="/settings" class="nav-item" active-class="nav-active">
        <svg viewBox="0 0 24 24" class="size-4 shrink-0" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="3" />
          <path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9 7 7M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1" />
        </svg>
        Einstellungen
      </NuxtLink>
      <p v-if="info" class="px-3 pt-2 pb-1 font-mono text-[11px] text-base-600">v{{ info }}</p>
    </div>
  </nav>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.nav-item {
  @apply mb-0.5 flex items-center gap-3 rounded-md border-l-2 border-transparent px-3 py-2 text-sm text-base-400 transition-colors hover:bg-base-800 hover:text-base-50;
}
.nav-active {
  @apply border-redstone-500 bg-base-800 text-base-50;
}
</style>
