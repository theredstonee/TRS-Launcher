<script setup lang="ts">
import type { Component } from 'vue'
import type { Instance, Server } from '~/types'

// Startseite im Stil moderner Launcher: großer Kopf mit der zuletzt gespielten
// Instanz (Banner, Spielzeit, Spielen-Knopf mit Fortschritt), darunter
// Schnellstart, laufende Spiele, Neuigkeiten und die Serverliste.
const instances = useInstancesStore()
const accounts = useAccountsStore()
const servers = useServersStore()
const games = useGamesStore()
const settings = useSettingsStore()
const ui = useUiStore()

const addingServer = ref(false)
const ready = ref(false)

// Die Instanzliste ist nach „zuletzt gespielt“ sortiert.
const featured = computed<Instance | null>(() => instances.items[0] ?? null)
const game = computed(() => (featured.value ? games.state(featured.value.id) : null))
const quick = computed(() => instances.items.slice(1, 9))
const running = computed(() => instances.items.filter((i) => games.state(i.id).phase !== 'idle'))
const totalSeconds = computed(() => instances.items.reduce((sum, i) => sum + i.totalPlaySeconds, 0))
const showPlayTime = computed(() => settings.current?.ui.showPlayTime !== false)

const greeting = computed(() => {
  const hour = new Date().getHours()
  const word = hour < 5 ? 'Noch wach' : hour < 11 ? 'Guten Morgen' : hour < 18 ? 'Hallo' : 'Guten Abend'
  return accounts.active ? `${word}, ${accounts.active.name}` : word
})

// Der News-Bereich wird von einer anderen Baustelle geliefert. Solange es die
// Komponente nicht gibt, steht hier ein Platzhalter – `import.meta.glob` findet
// eine fehlende Datei einfach nicht, statt den Build scheitern zu lassen.
const newsSources = import.meta.glob('../components/NewsFeed.vue')
const NewsFeed = shallowRef<Component | null>(null)

let pingTimer: ReturnType<typeof setInterval> | undefined
onMounted(async () => {
  const loadNews = Object.values(newsSources)[0]
  if (loadNews) {
    loadNews()
      .then((mod) => (NewsFeed.value = (mod as { default: Component }).default))
      .catch(() => (NewsFeed.value = null))
  }
  await Promise.allSettled([instances.load(), servers.load()])
  ready.value = true
  // Spielerzahlen sollen nicht veralten, solange die Startseite offen ist.
  pingTimer = setInterval(() => servers.refresh(), 30_000)
})
onBeforeUnmount(() => clearInterval(pingTimer))

function join(server: Server) {
  if (featured.value) games.launch(featured.value.id, server.id)
}

function play(instance: Instance) {
  if (games.state(instance.id).phase === 'idle') games.launch(instance.id)
}
</script>

<template>
  <div class="flex min-h-full">
    <div class="flex min-w-0 flex-1 flex-col gap-6 p-6">
      <!-- Startrampe: die zuletzt gespielte Instanz, ein Klick bis ins Spiel. -->
      <section v-if="!ready" class="space-y-3">
        <div class="skeleton h-64 rounded-2xl" />
      </section>

      <InstanceBanner
        v-else-if="featured"
        :instance="featured"
        class="hero rounded-2xl border border-base-800"
        :class="{ 'hero-live': game?.phase === 'running' }"
      >
        <div class="flex h-full flex-col justify-between gap-6 p-7">
          <div class="flex items-start justify-between gap-4">
            <p class="text-sm text-white/80 drop-shadow">{{ greeting }}</p>
            <span v-if="game?.phase === 'running'" class="badge bg-lamp-400 text-base-950">
              <span class="size-1.5 animate-lamp rounded-full bg-base-950" />Läuft gerade
            </span>
          </div>

          <div class="flex flex-wrap items-end gap-5">
            <NuxtLink
              :to="`/instances/${featured.id}`"
              class="shrink-0 rounded-2xl outline-none transition-transform duration-150 hover:scale-[1.03] focus-visible:ring-2 focus-visible:ring-redstone-500"
              tabindex="-1"
              aria-hidden="true"
            >
              <InstanceIcon :instance="featured" :size="104" class="shadow-2xl shadow-black/60" />
            </NuxtLink>

            <div class="min-w-64 flex-1">
              <NuxtLink :to="`/instances/${featured.id}`" class="display block truncate text-5xl leading-tight text-white drop-shadow-lg transition-colors hover:text-redstone-300">
                {{ featured.name }}
              </NuxtLink>
              <p class="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-white/85">
                <span class="flex items-center gap-1.5">
                  <span class="size-2 rounded-full" :style="{ background: loaderColors[featured.loader.kind] }" />
                  <span class="font-mono text-white">{{ featured.gameVersion }}</span> {{ loaderLabels[featured.loader.kind] }}
                </span>
                <span>{{ formatRelative(featured.lastPlayed) }}</span>
                <span v-if="showPlayTime && featured.totalPlaySeconds >= 60">{{ formatPlayTime(featured.totalPlaySeconds) }} gespielt</span>
              </p>
            </div>

            <div class="flex w-full items-center gap-2 sm:w-80">
              <PlayButton :instance-id="featured.id" large />
              <NuxtLink
                :to="`/instances/${featured.id}`"
                class="btn-icon size-12 bg-base-900/80 backdrop-blur"
                title="Instanz öffnen"
                aria-label="Instanz öffnen"
              >
                <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M13 6l6 6-6 6" /></svg>
              </NuxtLink>
            </div>
          </div>

          <p v-if="game?.error" role="alert" class="text-sm text-redstone-300">{{ game.error }}</p>
        </div>

        <!-- Die Leitung unter der Rampe: lädt beim Start auf, glimmt, solange gespielt wird. -->
        <RedstoneWire
          v-if="game && game.phase !== 'idle'"
          class="absolute inset-x-0 bottom-0 px-7 pb-4"
          :segments="64"
          :percent="game?.progress ? overallPercent(game.progress.stage, game.progress.percent) : 0"
          :powered="game?.phase === 'running'"
        />
      </InstanceBanner>

      <section v-else class="card flex flex-col items-start gap-6 rounded-2xl p-8 md:flex-row md:items-center">
        <div class="min-w-0 flex-1">
          <p class="text-sm text-base-400">{{ greeting }}</p>
          <h1 class="display mt-1 text-5xl leading-tight">Bereit zum Start</h1>
          <p class="mt-2 max-w-md text-sm text-base-400">
            Lege deine erste Instanz an – Vanilla oder mit Modloader – oder hol dir gleich ein fertiges Modpack.
          </p>
        </div>
        <div class="flex shrink-0 flex-wrap gap-3">
          <button class="btn btn-primary h-12 px-6 text-base" @click="ui.creating = true">Instanz erstellen</button>
          <NuxtLink :to="{ path: '/browse', query: { kind: 'modpack' } }" class="btn btn-ghost h-12 px-6 text-base">Modpacks ansehen</NuxtLink>
        </div>
      </section>

      <!-- Weiterspielen: zuletzt gespielte Instanzen als Kacheln -->
      <section v-if="quick.length">
        <div class="mb-3 flex items-end justify-between gap-4">
          <h2 class="font-semibold">Weiterspielen</h2>
          <div class="flex items-center gap-3 text-xs text-base-400">
            <span v-if="showPlayTime && totalSeconds >= 60">Insgesamt {{ formatPlayTime(totalSeconds) }}</span>
            <NuxtLink to="/instances" class="hover:text-base-50">Alle Instanzen</NuxtLink>
          </div>
        </div>

        <ul class="grid grid-cols-[repeat(auto-fill,minmax(14rem,1fr))] gap-3">
          <li v-for="i in quick" :key="i.id">
            <article class="tile group card relative overflow-hidden">
              <NuxtLink :to="`/instances/${i.id}`" class="block outline-none" :aria-label="`${i.name} öffnen`">
                <InstanceBanner :instance="i" class="h-24 w-full" />
                <div class="flex items-center gap-2.5 p-2.5">
                  <InstanceIcon :instance="i" :size="36" class="-mt-8 shadow-lg shadow-black/50 ring-2 ring-base-900" />
                  <span class="min-w-0 flex-1">
                    <span class="block truncate text-sm font-medium text-base-50">{{ i.name }}</span>
                    <span class="block truncate text-[11px] text-base-400">
                      <span class="font-mono">{{ i.gameVersion }}</span> · {{ formatRelative(i.lastPlayed) }}
                    </span>
                  </span>
                </div>
              </NuxtLink>
              <button
                class="absolute top-16 right-2.5 grid size-9 translate-y-1 place-items-center rounded-full bg-redstone-500 text-white opacity-0 shadow-lg shadow-black/50 transition-[opacity,transform,background-color] group-hover:translate-y-0 group-hover:opacity-100 hover:bg-redstone-400 focus-visible:translate-y-0 focus-visible:opacity-100"
                :class="{ 'translate-y-0 opacity-100': games.state(i.id).phase !== 'idle' }"
                :disabled="games.state(i.id).phase !== 'idle'"
                :aria-label="`${i.name} spielen`"
                @click="play(i)"
              >
                <svg viewBox="0 0 24 24" class="ml-0.5 size-4" fill="currentColor"><path :d="icons.play" /></svg>
              </button>
              <span v-if="games.state(i.id).phase !== 'idle'" class="badge absolute top-2 left-2 bg-lamp-400 text-base-950">
                <span class="size-1.5 animate-lamp rounded-full bg-base-950" />Läuft
              </span>
            </article>
          </li>
        </ul>
      </section>

      <!-- Läuft gerade -->
      <section v-if="running.length">
        <h2 class="mb-3 font-semibold">Läuft gerade</h2>
        <ul class="grid gap-2 md:grid-cols-2">
          <li v-for="i in running" :key="i.id" class="card card-hover flex items-center gap-3 px-3 py-2.5">
            <NuxtLink :to="`/instances/${i.id}`" class="flex min-w-0 flex-1 items-center gap-3">
              <span class="relative">
                <InstanceIcon :instance="i" :size="40" />
                <span class="absolute -right-1 -bottom-1 size-2.5 animate-lamp rounded-full bg-lamp-400 ring-2 ring-base-900" />
              </span>
              <span class="min-w-0">
                <span class="block truncate text-sm font-medium">{{ i.name }}</span>
                <span class="block truncate text-xs text-base-400">{{ games.state(i.id).phase === 'preparing' ? 'Wird vorbereitet …' : 'Läuft' }}</span>
              </span>
            </NuxtLink>
            <div class="w-36 shrink-0"><PlayButton :instance-id="i.id" /></div>
          </li>
        </ul>
      </section>

      <div class="grid min-h-0 flex-1 grid-cols-1 gap-6 xl:grid-cols-2">
        <!-- Neuigkeiten (eigene Komponente, sobald sie da ist) -->
        <section>
          <h2 class="mb-3 font-semibold">Neuigkeiten</h2>
          <component :is="NewsFeed" v-if="NewsFeed" />
          <div v-else class="card px-5 py-10 text-center">
            <p class="text-sm text-base-200">Hier erscheinen Neuigkeiten zum Launcher und zum TRS Client.</p>
            <p class="mt-1 text-xs text-base-400">Updates, neue Versionen und Tipps – direkt auf der Startseite.</p>
          </div>
        </section>

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
              :join-disabled="!featured || game?.phase !== 'idle'"
              :join-hint="featured ? `Startet „${featured.name}“ und verbindet direkt` : 'Erst eine Instanz anlegen'"
              @join="join"
            />
          </div>
          <div v-else class="card px-5 py-8 text-center">
            <p class="text-sm text-base-200">Deine Server an einem Ort – mit Live-Status und Beitritt per Klick.</p>
            <p class="mt-1 text-xs text-base-400">Sie stehen danach in jeder Instanz in der Serverliste.</p>
            <button class="btn btn-primary mt-4" @click="addingServer = true">Ersten Server hinzufügen</button>
          </div>
        </section>
      </div>

      <ServerDialog v-if="addingServer" @close="addingServer = false" />
    </div>

    <AccountPanel v-if="settings.current?.ui.hideRightSidebar !== true" class="hidden lg:flex" />
  </div>
</template>

<style scoped>
.hero {
  min-height: 17rem;
  transition: box-shadow 0.3s ease;
}
/* Läuft das Spiel, glimmt der Kopf wie eine Redstone-Lampe. */
.hero-live {
  box-shadow: 0 0 40px -14px var(--color-lamp-400);
}
.tile {
  transition: transform 0.15s ease, border-color 0.15s ease;
}
.tile:hover {
  transform: translateY(-2px);
  border-color: var(--color-base-700);
}
</style>
