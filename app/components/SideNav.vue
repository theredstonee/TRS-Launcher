<script setup lang="ts">
const items = [
  { to: '/', label: 'Start', icon: 'M3 11.5 12 4l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z' },
  { to: '/instances', label: 'Instanzen', icon: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z' },
  { to: '/servers', label: 'Server', icon: 'M4 4h16v6H4zM4 14h16v6H4zM7 7h.01M7 17h.01' },
  { to: '/browse', label: 'Entdecken', icon: 'M11 4a7 7 0 1 0 4.2 12.6l4.1 4.1 1.4-1.4-4.1-4.1A7 7 0 0 0 11 4zm0 2a5 5 0 1 1 0 10 5 5 0 0 1 0-10z' },
]

const route = useRoute()
const accounts = useAccountsStore()
const games = useGamesStore()
const instances = useInstancesStore()
const settings = useSettingsStore()
const ui = computed(() => settings.current?.ui)
// Die Liste ist nach „zuletzt gespielt“ sortiert.
const recent = computed(() => instances.items.slice(0, 4))

// Unterseiten (/instances/abc) sollen den Hauptpunkt weiter markieren.
function isActive(to: string) {
  return to === '/' ? route.path === '/' : route.path.startsWith(to)
}

const version = ref<string | null>(null)
onMounted(async () => {
  try {
    version.value = (await backend.appInfo()).version
  } catch {
    version.value = null
  }
})
</script>

<template>
  <nav class="flex w-52 shrink-0 flex-col border-r border-base-800 bg-base-900 p-2">
    <NuxtLink v-for="item in items" :key="item.to" :to="item.to" class="nav-item" :class="{ 'nav-active': isActive(item.to) }">
      <svg viewBox="0 0 24 24" class="size-4 shrink-0" :fill="item.to === '/servers' || item.to === '/' ? 'none' : 'currentColor'" stroke="currentColor" :stroke-width="item.to === '/servers' || item.to === '/' ? 2 : 0">
        <path :d="item.icon" />
      </svg>
      {{ item.label }}
      <span v-if="item.to === '/instances' && games.runningCount" class="ml-auto flex items-center gap-1.5 text-xs text-lamp-400">
        <span class="size-1.5 animate-lamp rounded-full bg-lamp-400" />{{ games.runningCount }}
      </span>
    </NuxtLink>

    <!-- Schnellzugriff: zuletzt gespielte Instanzen mit Bild -->
    <div v-if="recent.length && ui?.sidebarRecent !== false" class="mt-5">
      <p class="mb-1.5 px-3 text-[11px] font-medium text-base-600">Zuletzt gespielt</p>
      <NuxtLink
        v-for="i in recent"
        :key="i.id"
        :to="`/instances/${i.id}`"
        class="mb-0.5 flex items-center gap-2.5 rounded-md px-2 py-1.5 text-sm text-base-400 transition-colors hover:bg-base-800 hover:text-base-50"
        :class="{ 'bg-base-800 text-base-50': route.path === `/instances/${i.id}` }"
      >
        <span class="relative">
          <InstanceIcon :instance="i" :size="24" />
          <span v-if="games.state(i.id).phase !== 'idle'" class="absolute -right-0.5 -bottom-0.5 size-2 animate-lamp rounded-full bg-lamp-400 ring-2 ring-base-900" />
        </span>
        <span class="min-w-0 flex-1 truncate">{{ i.name }}</span>
      </NuxtLink>
    </div>

    <div class="mt-auto">
      <NuxtLink v-if="ui?.sidebarAccount !== false" to="/accounts" class="mb-1 flex items-center gap-2.5 rounded-md border border-base-800 bg-base-850 p-2 transition-colors hover:border-base-700" :class="{ 'border-redstone-600/60': isActive('/accounts') }">
        <SkinHead :skin-url="accounts.active?.skinUrl ?? null" :name="accounts.active?.name ?? '?'" :size="28" />
        <div class="min-w-0">
          <p class="truncate text-sm font-medium">{{ accounts.active?.name ?? 'Nicht angemeldet' }}</p>
          <p class="truncate text-[11px] text-base-400">
            {{ accounts.active ? (accounts.items.length > 1 ? `${accounts.items.length} Accounts` : 'Microsoft-Konto') : 'Jetzt anmelden' }}
          </p>
        </div>
      </NuxtLink>
      <button class="nav-item w-full" :class="{ 'nav-active': settings.dialog !== null }" @click="settings.open()">
        <svg viewBox="0 0 24 24" class="size-4 shrink-0" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="3" />
          <path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9 7 7M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1" />
        </svg>
        Einstellungen
      </button>
      <p v-if="version" class="px-3 pt-2 pb-1 font-mono text-[11px] text-base-600">v{{ version }}</p>
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
