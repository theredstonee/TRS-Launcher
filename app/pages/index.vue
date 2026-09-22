<script setup lang="ts">
import type { Server } from '~/types'

const instances = useInstancesStore()
const accounts = useAccountsStore()
const servers = useServersStore()
const games = useGamesStore()
const settings = useSettingsStore()

const selectedId = ref<string | null>(null)
const addingServer = ref(false)
const ready = ref(false)

// Standard: die zuletzt gespielte Instanz (die Liste ist so sortiert).
const selected = computed(
  () => instances.items.find((i) => i.id === selectedId.value) ?? instances.items[0] ?? null,
)
const game = computed(() => (selected.value ? games.state(selected.value.id) : null))
const others = computed(() => instances.items.filter((i) => i.id !== selected.value?.id).slice(0, 5))
const totalSeconds = computed(() => instances.items.reduce((sum, i) => sum + i.totalPlaySeconds, 0))

const greeting = computed(() => {
  const hour = new Date().getHours()
  const word = hour < 5 ? 'Noch wach' : hour < 11 ? 'Guten Morgen' : hour < 18 ? 'Hallo' : 'Guten Abend'
  return accounts.active ? `${word}, ${accounts.active.name}` : word
})

let pingTimer: ReturnType<typeof setInterval> | undefined
onMounted(async () => {
  await Promise.allSettled([instances.load(), servers.load()])
  ready.value = true
  // Spielerzahlen sollen nicht veralten, solange die Startseite offen ist.
  pingTimer = setInterval(() => servers.refresh(), 30_000)
})
onBeforeUnmount(() => clearInterval(pingTimer))

function join(server: Server) {
  if (selected.value) games.launch(selected.value.id, server.id)
}
</script>

<template>
  <div class="flex min-h-full">
  <div class="flex min-w-0 flex-1 flex-col gap-5 p-6">
    <!-- Startrampe: die zuletzt gespielte Instanz, ein Klick bis ins Spiel. -->
    <section class="launchpad relative overflow-hidden rounded-xl border border-base-800">
      <div class="relative flex flex-col gap-6 p-7">
        <p class="text-sm text-base-400">{{ greeting }}</p>

        <div v-if="!ready" class="space-y-3">
          <div class="skeleton h-12 w-80" />
          <div class="skeleton h-4 w-52" />
        </div>

        <template v-else-if="selected">
          <div class="flex min-w-0 items-center gap-5">
            <NuxtLink :to="`/instances/${selected.id}`" class="shrink-0 rounded-xl outline-none focus-visible:ring-2 focus-visible:ring-redstone-500" tabindex="-1">
              <InstanceIcon :instance="selected" :size="96" class="shadow-xl shadow-black/40" />
            </NuxtLink>
            <div class="min-w-0">
              <NuxtLink :to="`/instances/${selected.id}`" class="display block truncate text-5xl leading-tight text-base-50 hover:text-redstone-300">
                {{ selected.name }}
              </NuxtLink>
              <p class="mt-1 flex flex-wrap items-center gap-x-4 text-sm text-base-400">
                <span class="flex items-center gap-1.5">
                  <span class="size-2 rounded-full" :style="{ background: loaderColors[selected.loader.kind] }" />
                  <span class="font-mono text-base-200">{{ selected.gameVersion }}</span> {{ loaderLabels[selected.loader.kind] }}
                </span>
                <span>{{ formatRelative(selected.lastPlayed) }}</span>
                <span v-if="selected.totalPlaySeconds >= 60">{{ formatPlayTime(selected.totalPlaySeconds) }} gespielt</span>
              </p>
            </div>
          </div>

          <div class="flex flex-wrap items-center gap-3">
            <div class="w-72"><PlayButton :instance-id="selected.id" large /></div>
            <select
              v-if="instances.items.length > 1"
              v-model="selectedId"
              class="field h-12 w-56 bg-base-900/80"
              aria-label="Instanz wählen"
              :disabled="game?.phase !== 'idle'"
            >
              <option :value="null" disabled>Andere Instanz …</option>
              <option v-for="i in instances.items" :key="i.id" :value="i.id">{{ i.name }} ({{ i.gameVersion }})</option>
            </select>
          </div>
          <p v-if="game?.error" role="alert" class="-mt-2 text-sm text-redstone-300">{{ game.error }}</p>
        </template>

        <template v-else>
          <div>
            <h1 class="display text-5xl leading-tight">Bereit zum Start</h1>
            <p class="mt-1 max-w-md text-sm text-base-400">
              Lege deine erste Instanz an – Vanilla oder mit Modloader – oder hol dir gleich ein fertiges Modpack.
            </p>
          </div>
          <div class="flex gap-3">
            <NuxtLink to="/instances" class="btn btn-primary h-12 px-6 text-base">Instanz erstellen</NuxtLink>
            <NuxtLink :to="{ path: '/browse', query: { kind: 'modpack' } }" class="btn btn-ghost h-12 px-6 text-base">Modpacks ansehen</NuxtLink>
          </div>
        </template>
      </div>

      <!-- Die Leitung unter der Rampe: lädt beim Start auf, glimmt, solange gespielt wird. -->
      <RedstoneWire
        class="relative px-7 pb-5"
        :segments="64"
        :percent="game?.progress ? overallPercent(game.progress.stage, game.progress.percent) : 0"
        :powered="game?.phase === 'running'"
      />
    </section>

    <div class="grid min-h-0 flex-1 grid-cols-1 gap-5 xl:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
      <section>
        <div class="mb-3 flex items-center justify-between">
          <h2 class="font-semibold">Server</h2>
          <div class="flex items-center gap-2">
            <NuxtLink v-if="servers.items.length" to="/servers" class="text-xs text-base-400 hover:text-base-50">Alle verwalten</NuxtLink>
            <button class="btn btn-ghost px-2.5 py-1 text-xs" @click="addingServer = true">Hinzufügen</button>
          </div>
        </div>

        <div v-if="!ready" class="space-y-2">
          <div v-for="i in 2" :key="i" class="skeleton h-[74px]" />
        </div>
        <div v-else-if="servers.items.length" class="space-y-2">
          <ServerCard
            v-for="s in servers.items.slice(0, 5)"
            :key="s.id"
            :server="s"
            compact
            :join-disabled="!selected || game?.phase !== 'idle'"
            :join-hint="selected ? `Startet „${selected.name}“ und verbindet direkt` : 'Erst eine Instanz anlegen'"
            @join="join"
          />
        </div>
        <div v-else class="card px-5 py-8 text-center">
          <p class="text-sm text-base-200">Deine Server an einem Ort – mit Live-Status und Beitritt per Klick.</p>
          <p class="mt-1 text-xs text-base-400">Sie stehen danach in jeder Instanz in der Serverliste.</p>
          <button class="btn btn-primary mt-4" @click="addingServer = true">Ersten Server hinzufügen</button>
        </div>
      </section>

      <section>
        <div class="mb-3 flex items-center justify-between">
          <h2 class="font-semibold">Weitere Instanzen</h2>
          <span v-if="totalSeconds >= 60" class="text-xs text-base-400">Insgesamt {{ formatPlayTime(totalSeconds) }}</span>
        </div>

        <div v-if="!ready" class="space-y-2">
          <div v-for="i in 3" :key="i" class="skeleton h-14" />
        </div>
        <ul v-else-if="others.length" class="space-y-2">
          <li v-for="i in others" :key="i.id" class="card card-hover group flex items-center gap-3 px-3 py-2.5">
            <NuxtLink :to="`/instances/${i.id}`" class="flex min-w-0 flex-1 items-center gap-3">
              <InstanceIcon :instance="i" :size="40" />
              <span class="min-w-0">
                <span class="block truncate text-sm font-medium group-hover:text-redstone-300">{{ i.name }}</span>
                <span class="block truncate text-xs text-base-400">
                  <span class="font-mono">{{ i.gameVersion }}</span> {{ loaderLabels[i.loader.kind] }} · {{ formatRelative(i.lastPlayed) }}
                </span>
              </span>
            </NuxtLink>
            <div class="w-32 shrink-0"><PlayButton :instance-id="i.id" /></div>
          </li>
        </ul>
        <div v-else class="card px-5 py-8 text-center text-sm text-base-400">
          <NuxtLink to="/instances" class="text-base-200 underline-offset-2 hover:underline">Weitere Instanz anlegen</NuxtLink>
          – zum Beispiel eine zweite Version oder ein Modpack.
        </div>
      </section>
    </div>

    <ServerDialog v-if="addingServer" @close="addingServer = false" />
  </div>
  <AccountPanel v-if="settings.current?.ui.hideRightSidebar !== true" class="hidden lg:flex" />
  </div>
</template>

<style scoped>
/* Deepslate-Kacheln als Hintergrund der Startrampe, nach unten ausgeblendet. */
.launchpad {
  background:
    radial-gradient(120% 140% at 0% 0%, rgb(224 40 30 / 0.16), transparent 55%),
    linear-gradient(to bottom, transparent 40%, var(--color-base-900)),
    repeating-linear-gradient(0deg, rgb(255 255 255 / 0.025) 0 1px, transparent 1px 32px),
    repeating-linear-gradient(90deg, rgb(255 255 255 / 0.025) 0 1px, transparent 1px 32px),
    var(--color-base-850);
}
</style>
