<script setup lang="ts">
import type { Server } from '~/types'

const servers = useServersStore()
const instances = useInstancesStore()
const games = useGamesStore()

const editing = ref<Server | null>(null)
const adding = ref(false)
const instanceId = ref('')
const refreshing = ref(false)

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
    <PageHeader title="Server" subtitle="Einmal eintragen – in jeder Instanz verfügbar, mit Live-Status und Beitritt per Klick.">
      <button class="btn btn-ghost" :disabled="refreshing || !servers.items.length" @click="refresh">
        {{ refreshing ? 'Aktualisiere …' : 'Aktualisieren' }}
      </button>
      <button class="btn btn-primary" @click="adding = true">
        <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M12 5v14M5 12h14" /></svg>
        Server hinzufügen
      </button>
    </PageHeader>

    <label v-if="instances.items.length > 1 && servers.items.length" class="mb-4 flex items-center gap-2 text-xs text-base-400">
      Beitreten mit
      <select v-model="instanceId" class="field w-64 py-1.5">
        <option value="">{{ instances.items[0]?.name }} (zuletzt gespielt)</option>
        <option v-for="i in instances.items.slice(1)" :key="i.id" :value="i.id">{{ i.name }} ({{ i.gameVersion }})</option>
      </select>
    </label>

    <div v-if="!servers.loaded" class="space-y-2">
      <div v-for="i in 3" :key="i" class="skeleton h-[74px]" />
    </div>

    <div v-else-if="servers.items.length" class="space-y-2">
      <ServerCard
        v-for="s in servers.items"
        :key="s.id"
        :server="s"
        :join-disabled="!target || busy"
        :join-hint="target ? `Startet „${target.name}“ und verbindet direkt` : 'Erst eine Instanz anlegen'"
        @join="join"
        @edit="editing = $event"
      />
    </div>

    <RedstoneEmpty
      v-else
      :seed="0x33"
      title="Noch kein Server"
      text="Leg eine Leitung zu deinem Lieblingsserver: Adresse eintragen – der Launcher zeigt Spielerzahl und Ping live an und setzt den Server in die Serverliste jeder Instanz."
    >
      <button class="btn btn-primary" @click="adding = true">Server hinzufügen</button>
    </RedstoneEmpty>

    <ServerDialog v-if="adding" @close="adding = false" />
    <ServerDialog v-if="editing" :server="editing" @close="editing = null" />
  </div>
</template>
