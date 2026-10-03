<script setup lang="ts">
import type { Server } from '~/types'

const servers = useServersStore()
const instances = useInstancesStore()
const games = useGamesStore()
/** Eigene lokale Server (Server-Export) – Link zur Konsole. */
const localServers = useLocalServersStore()

const editing = ref<Server | null>(null)
const adding = ref(false)
const instanceId = ref('')
const refreshing = ref(false)
// Nur die Anzeige – die gespeicherte Reihenfolge (und servers.dat) bleibt.
const sortByPingOn = usePingSort('servers')

const shown = computed(() =>
  sortByPingOn.value ? sortByPing(servers.items, (s) => latencyOf(servers.statuses[s.id])) : servers.items,
)

const target = computed(() => instances.items.find((i) => i.id === instanceId.value) ?? instances.items[0] ?? null)
const busy = computed(() => (target.value ? games.state(target.value.id).phase !== 'idle' : false))

onMounted(async () => {
  await Promise.allSettled([servers.load(), instances.items.length ? Promise.resolve() : instances.load()])
})

async function refresh() {
  refreshing.value = true
  await servers.refresh()
  refreshing.value = false
}

function join(server: Server) {
  if (target.value) games.launch(target.value.id, server.id)
}
</script>

<template>
  <div class="mx-auto max-w-3xl p-6">
    <PageHeader :title="t('servers.title')" :subtitle="t('servers.subtitle')">
      <NuxtLink v-if="localServers.items.length" to="/local-servers" class="btn btn-ghost">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.terminal" /></svg>
        {{ t('localServers.title') }}
      </NuxtLink>
      <button class="btn btn-ghost" :disabled="refreshing || !servers.items.length" :title="t('servers.ping.hint')" @click="refresh">
        <svg viewBox="0 0 24 24" class="size-4" :class="{ 'animate-spin': refreshing }" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path :d="icons.sync" /></svg>
        {{ refreshing ? t('servers.ping.running') : t('servers.ping.test') }}
      </button>
      <button class="btn btn-primary" @click="adding = true">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 5v14M5 12h14" /></svg>
        {{ t('servers.add') }}
      </button>
    </PageHeader>

    <div v-if="servers.items.length && (instances.items.length > 1 || servers.items.length > 1)" class="mb-4 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-base-400">
      <label v-if="instances.items.length > 1" class="flex items-center gap-2">
        {{ t('servers.joinWith') }}
        <select v-model="instanceId" class="field w-64 py-1.5">
          <option value="">{{ t('servers.lastPlayed', { name: instances.items[0]?.name ?? '' }) }}</option>
          <option v-for="i in instances.items.slice(1)" :key="i.id" :value="i.id">{{ i.name }} ({{ i.gameVersion }})</option>
        </select>
      </label>
      <label v-if="servers.items.length > 1" class="ml-auto flex cursor-pointer items-center gap-2" :title="t('servers.ping.sortHint')">
        <input v-model="sortByPingOn" type="checkbox" class="size-4 accent-redstone-500" />
        {{ t('servers.ping.sort') }}
      </label>
    </div>

    <div v-if="!servers.loaded" class="space-y-2">
      <div v-for="i in 3" :key="i" class="skeleton h-[74px]" />
    </div>

    <div v-else-if="servers.items.length" class="space-y-2">
      <ServerCard
        v-for="s in shown"
        :key="s.id"
        :server="s"
        :join-disabled="!target || busy"
        :join-hint="target ? t('servers.joinHint', { name: target.name }) : t('servers.noInstance')"
        @join="join"
        @edit="editing = $event"
      />
      <p class="pt-1 text-[11px] text-base-600">{{ t('servers.ping.hint') }}</p>
    </div>

    <RedstoneEmpty
      v-else
      :seed="0x33"
      :title="t('servers.empty.title')"
      :text="t('servers.empty.text')"
    >
      <button class="btn btn-primary" @click="adding = true">{{ t('servers.add') }}</button>
    </RedstoneEmpty>

    <ServerDialog v-if="adding" @close="adding = false" />
    <ServerDialog v-if="editing" :server="editing" @close="editing = null" />
  </div>
</template>
