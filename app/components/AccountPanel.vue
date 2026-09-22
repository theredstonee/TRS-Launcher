<script setup lang="ts">
// Rechte Leiste: „Spielt als“ – Account schnell wechseln, neuen hinzufügen,
// dazu die gerade laufenden Instanzen.
const accounts = useAccountsStore()
const instances = useInstancesStore()
const games = useGamesStore()
const toasts = useToasts()
const switching = ref<string | null>(null)

onMounted(() => {
  if (!accounts.loaded) accounts.load().catch(() => {})
})

async function activate(id: string) {
  if (accounts.active?.id === id) return
  switching.value = id
  try {
    await accounts.setActive(id)
  } catch (e) {
    toasts.error(e)
  } finally {
    switching.value = null
  }
}

const running = computed(() => instances.items.filter((i) => games.state(i.id).phase !== 'idle'))
</script>

<template>
  <aside class="w-64 shrink-0 flex-col gap-5 overflow-y-auto border-l border-base-800 bg-base-900/60 p-4" aria-label="Accounts">
    <section>
      <h2 class="mb-2 text-[11px] font-semibold tracking-wider text-base-600 uppercase">Spielt als</h2>
      <ul v-if="accounts.items.length" class="space-y-1">
        <li v-for="a in accounts.items" :key="a.id">
          <button
            class="flex w-full items-center gap-3 rounded-lg px-2 py-2 text-left transition-colors"
            :class="a.active ? 'bg-base-800 ring-1 ring-redstone-500/50' : 'hover:bg-base-800'"
            :aria-pressed="a.active"
            :disabled="switching !== null"
            @click="activate(a.id)"
          >
            <SkinHead :skin-url="a.skinUrl" :name="a.name" :size="32" />
            <span class="min-w-0 flex-1">
              <span class="block truncate text-sm font-medium">{{ a.name }}</span>
              <span class="block text-[11px]" :class="a.active ? 'text-ok' : 'text-base-400'">{{ a.active ? 'Aktiv' : switching === a.id ? 'Wechsle …' : 'Wechseln' }}</span>
            </span>
            <span v-if="a.active" class="size-2 rounded-full bg-ok" />
          </button>
        </li>
      </ul>
      <p v-else class="px-1 text-xs text-base-400">Noch kein Account – ohne Anmeldung startet nur der Demo-Modus.</p>
      <NuxtLink to="/accounts" class="mt-2 flex items-center gap-2 rounded-lg border border-dashed border-base-700 px-3 py-2 text-sm text-base-400 transition-colors hover:border-base-600 hover:text-base-50">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.2"><path d="M12 5v14M5 12h14" /></svg>
        Account hinzufügen
      </NuxtLink>
    </section>

    <section v-if="running.length">
      <h2 class="mb-2 text-[11px] font-semibold tracking-wider text-base-600 uppercase">Läuft gerade</h2>
      <ul class="space-y-1">
        <li v-for="i in running" :key="i.id">
          <NuxtLink :to="`/instances/${i.id}`" class="flex items-center gap-2.5 rounded-lg px-2 py-1.5 text-sm hover:bg-base-800">
            <InstanceIcon :instance="i" :size="28" />
            <span class="min-w-0 flex-1 truncate">{{ i.name }}</span>
            <span class="size-2 animate-lamp rounded-full bg-lamp-400" />
          </NuxtLink>
        </li>
      </ul>
    </section>
  </aside>
</template>
