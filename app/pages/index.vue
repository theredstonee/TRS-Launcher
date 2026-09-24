<script setup lang="ts">
import type { Component } from 'vue'
import type { Instance, Server } from '~/types'

// Startseite: oben eine lebendige Redstone-Schaltung als Bühne für die zuletzt
// gespielte Instanz – die Hauptleitung läuft in die Spielen-Lampe und lädt sich
// beim Start auf. Darunter „Weiterspielen“, laufende Spiele, Neuigkeiten und Server.
const instances = useInstancesStore()
const accounts = useAccountsStore()
const servers = useServersStore()
const games = useGamesStore()
const settings = useSettingsStore()
const ui = useUiStore()

const addingServer = ref(false)
const ready = ref(false)
const lamp = ref<HTMLElement | null>(null)

// Die Instanzliste ist nach „zuletzt gespielt“ sortiert.
const featured = computed<Instance | null>(() => instances.items[0] ?? null)
const game = computed(() => (featured.value ? games.state(featured.value.id) : null))
const quick = computed(() => instances.items.slice(1, 9))

// „Weiterspielen“ bleibt eine Reihe: so viele Kacheln, wie nebeneinander passen.
const strip = ref<HTMLElement | null>(null)
const stripWidth = ref(0)
const CARD_MIN = 240
const ADD_TILE = 176
const GAP = 12
const shownQuick = computed(() => {
  if (!stripWidth.value) return quick.value.slice(0, 3)
  const fit = Math.floor((stripWidth.value - ADD_TILE) / (CARD_MIN + GAP))
  return quick.value.slice(0, Math.max(1, fit))
})
let stripObserver: ResizeObserver | undefined
watch(strip, (el, _, onCleanup) => {
  if (!el) return
  stripObserver = new ResizeObserver(([entry]) => (stripWidth.value = entry?.contentRect.width ?? 0))
  stripObserver.observe(el)
  onCleanup(() => stripObserver?.disconnect())
})
const running = computed(() =>
  instances.items.filter((i) => i.id !== featured.value?.id && games.state(i.id).phase !== 'idle'),
)
const totalSeconds = computed(() => instances.items.reduce((sum, i) => sum + i.totalPlaySeconds, 0))
const showPlayTime = computed(() => settings.current?.ui.showPlayTime !== false)

const sceneMode = computed(() =>
  game.value?.phase === 'running' ? 'running' : game.value?.phase === 'preparing' ? 'starting' : 'idle',
)
const sceneProgress = computed(() =>
  game.value?.progress ? overallPercent(game.value.progress.stage, game.value.progress.percent) : 0,
)

const greeting = computed(() => {
  const hour = new Date().getHours()
  const part = hour < 5 ? 'lateNight' : hour < 11 ? 'morning' : hour < 18 ? 'day' : 'evening'
  const name = accounts.active?.name
  return name ? t(`home.greeting.${part}Named`, { name }) : t(`home.greeting.${part}`)
})

// Der News-Bereich kommt aus einer eigenen Komponente. `import.meta.glob`
// findet eine fehlende Datei einfach nicht, statt den Build scheitern zu lassen.
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
  <div class="pb-10">
    <!-- Bühne: Redstone-Schaltung, darüber die zuletzt gespielte Instanz. -->
    <section
      class="hero relative isolate overflow-hidden"
      :class="`hero-${sceneMode}`"
      :aria-label="featured ? t('home.lastPlayed', { name: featured.name }) : t('nav.home')"
    >
      <RedstoneScene :mode="sceneMode" :progress="sceneProgress" :anchor="lamp">
        <div class="scrim" />
      </RedstoneScene>

      <div class="relative flex h-full flex-col justify-between gap-6 px-8 pt-7 pb-8">
        <div class="flex items-start justify-between gap-4">
          <p class="display text-base text-base-200">{{ greeting }}</p>
          <span v-if="game?.phase === 'running'" class="badge bg-lamp-400 text-base-950">
            <span class="size-1.5 animate-lamp bg-base-950" />{{ t('play.runningNow') }}
          </span>
        </div>

        <div v-if="!ready" class="flex items-end gap-6">
          <div class="skeleton size-24 rounded-xl" />
          <div class="flex-1 space-y-3"><div class="skeleton h-12 w-96" /><div class="skeleton h-4 w-72" /></div>
          <div class="skeleton h-14 w-72" />
        </div>

        <div v-else-if="featured" class="flex flex-wrap items-end gap-x-6 gap-y-5">
          <NuxtLink
            :to="`/instances/${featured.id}`"
            class="icon-frame shrink-0 outline-none"
            tabindex="-1"
            aria-hidden="true"
          >
            <InstanceIcon :instance="featured" :size="96" />
          </NuxtLink>

          <div class="min-w-64 flex-1">
            <NuxtLink
              :to="`/instances/${featured.id}`"
              class="hero-title display block truncate text-6xl leading-[1.05] text-base-50 transition-colors hover:text-redstone-300"
            >
              {{ featured.name }}
            </NuxtLink>
            <p class="mt-3 flex flex-wrap items-center gap-x-5 gap-y-1 text-sm text-base-200">
              <span class="flex items-center gap-2">
                <span class="size-2.5" :style="{ background: loaderColors[featured.loader.kind] }" />
                <span><span class="font-mono text-base-50">{{ featured.gameVersion }}</span> {{ loaderLabels[featured.loader.kind] }}</span>
              </span>
              <span>{{ formatRelative(featured.lastPlayed) }}</span>
              <span v-if="showPlayTime && featured.totalPlaySeconds >= 60">{{ t('home.playedTime', { time: formatPlayTime(featured.totalPlaySeconds) }) }}</span>
            </p>
          </div>

          <div ref="lamp" class="flex w-full items-center gap-2 sm:w-80">
            <PlayButton :instance-id="featured.id" large />
            <NuxtLink
              :to="`/instances/${featured.id}`"
              class="btn-icon pixel-corners size-14 bg-base-900/85 backdrop-blur"
              style="--notch: 3px"
              :title="t('home.openInstance')"
              :aria-label="t('home.openInstance')"
            >
              <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M13 6l6 6-6 6" /></svg>
            </NuxtLink>
          </div>
          <p v-if="game?.error" role="alert" class="w-full text-sm text-redstone-300">{{ game.error }}</p>
        </div>

        <div v-else class="flex flex-wrap items-end gap-x-8 gap-y-5">
          <div class="min-w-0 flex-1">
            <h1 class="display text-6xl leading-[1.05] text-base-50">{{ t('home.empty.title') }}</h1>
            <p class="mt-3 max-w-md text-sm text-base-200">
              {{ t('home.empty.text') }}
            </p>
          </div>
          <div ref="lamp" class="flex shrink-0 flex-wrap gap-3">
            <button class="btn btn-primary h-12 px-6 text-base" @click="ui.creating = true">{{ t('home.empty.create') }}</button>
            <NuxtLink :to="{ path: '/browse', query: { kind: 'modpack' } }" class="btn btn-ghost h-12 px-6 text-base">{{ t('home.empty.modpacks') }}</NuxtLink>
          </div>
        </div>
      </div>
    </section>

    <div class="space-y-10 px-8">
      <!-- Neuestes Update als Blog-Karte (Banner aus der Redstone-Szene). -->
      <UpdateNewsCard />

      <!-- Weiterspielen: breite Banner-Kacheln, am Ende die Kachel für Neues. -->
      <section v-if="ready && featured" aria-labelledby="continue-heading">
        <div class="mb-3 flex items-end justify-between gap-4">
          <h2 id="continue-heading" class="heading">{{ t('home.continue.title') }}</h2>
          <div class="flex items-center gap-4 text-xs text-base-400">
            <span v-if="showPlayTime && totalSeconds >= 60">{{ t('home.continue.totalPlayed', { time: formatPlayTime(totalSeconds) }) }}</span>
            <NuxtLink to="/instances" class="hover:text-base-50">{{ t('home.continue.toLibrary') }}</NuxtLink>
          </div>
        </div>

        <ul ref="strip" class="strip">
          <li v-for="i in shownQuick" :key="i.id" class="strip-card">
            <article class="tile group card relative h-full overflow-hidden" :class="{ 'tile-live': games.state(i.id).phase !== 'idle' }">
              <NuxtLink :to="`/instances/${i.id}`" class="block outline-none" :aria-label="t('nav.openNamed', { name: i.name })">
                <InstanceBanner :instance="i" shade="none" class="h-28 w-full" />
                <div class="flex items-center gap-3 p-3">
                  <InstanceIcon :instance="i" :size="40" class="-mt-9 shrink-0 ring-2 ring-base-900" />
                  <span class="min-w-0 flex-1">
                    <span class="block truncate text-sm font-semibold text-base-50">{{ i.name }}</span>
                    <span class="block truncate text-xs text-base-400">
                      <span class="font-mono">{{ i.gameVersion }}</span> {{ loaderLabels[i.loader.kind] }}, {{ formatRelative(i.lastPlayed, true) }}
                    </span>
                  </span>
                </div>
              </NuxtLink>
              <button
                class="tile-play pixel-corners"
                style="--notch: 3px"
                :class="{ 'tile-play-on': games.state(i.id).phase !== 'idle' }"
                :disabled="games.state(i.id).phase !== 'idle'"
                :aria-label="t('play.playNamed', { name: i.name })"
                @click="play(i)"
              >
                <svg viewBox="0 0 24 24" class="ml-0.5 size-4" fill="currentColor"><path :d="icons.play" /></svg>
              </button>
              <span v-if="games.state(i.id).phase !== 'idle'" class="badge absolute top-2 left-2 bg-lamp-400 text-base-950">
                <span class="size-1.5 animate-lamp bg-base-950" />{{ games.state(i.id).phase === 'preparing' ? t('play.startingShort') : t('common.status.running') }}
              </span>
            </article>
          </li>

          <!-- Neues: immer da; ohne weitere Instanzen zeigt die Reihe die Wege zur nächsten. -->
          <li :class="quick.length ? 'strip-add' : 'strip-card'">
            <button class="add-tile" @click="ui.creating = true">
              <span class="add-icon"><svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path :d="icons.plus" /></svg></span>
              <span class="text-sm font-semibold text-base-50">{{ t('nav.newInstance') }}</span>
              <span class="text-xs text-base-400">{{ t('home.add.newInstanceHint') }}</span>
            </button>
          </li>
          <template v-if="!quick.length">
            <li class="strip-card">
              <NuxtLink :to="{ path: '/browse', query: { kind: 'modpack' } }" class="add-tile">
                <span class="add-icon"><svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.compass" /></svg></span>
                <span class="text-sm font-semibold text-base-50">{{ t('home.add.discoverModpack') }}</span>
                <span class="text-xs text-base-400">{{ t('home.add.discoverModpackHint') }}</span>
              </NuxtLink>
            </li>
            <li class="strip-card">
              <button class="add-tile" @click="ui.importing = true">
                <span class="add-icon"><svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.install" /></svg></span>
                <span class="text-sm font-semibold text-base-50">{{ t('common.actions.import') }}</span>
                <span class="text-xs text-base-400">{{ t('home.add.importHint') }}</span>
              </button>
            </li>
          </template>
        </ul>
      </section>

      <!-- Weitere laufende Spiele (das oberste zeigt schon die Bühne). -->
      <section v-if="running.length" aria-labelledby="running-heading">
        <h2 id="running-heading" class="heading mb-3">{{ t('play.runningNow') }}</h2>
        <ul class="grid gap-2 md:grid-cols-2">
          <li v-for="i in running" :key="i.id" class="card flex items-center gap-3 px-3 py-2.5">
            <NuxtLink :to="`/instances/${i.id}`" class="flex min-w-0 flex-1 items-center gap-3">
              <span class="relative">
                <InstanceIcon :instance="i" :size="40" />
                <span class="absolute -right-1 -bottom-1 size-2.5 animate-lamp bg-lamp-400 ring-2 ring-base-900" />
              </span>
              <span class="min-w-0">
                <span class="block truncate text-sm font-medium">{{ i.name }}</span>
                <span class="block truncate text-xs text-base-400">{{ games.state(i.id).phase === 'preparing' ? t('play.preparing') : t('common.status.running') }}</span>
              </span>
            </NuxtLink>
            <div class="w-36 shrink-0"><PlayButton :instance-id="i.id" /></div>
          </li>
        </ul>
      </section>

      <div class="lower">
        <component :is="NewsFeed" v-if="NewsFeed" class="@container" />

        <section aria-labelledby="servers-heading">
          <div class="mb-3 flex items-end justify-between gap-4">
            <h2 id="servers-heading" class="heading">{{ t('nav.servers') }}</h2>
            <NuxtLink v-if="servers.items.length" to="/servers" class="text-xs text-base-400 hover:text-base-50">{{ t('home.servers.manageAll') }}</NuxtLink>
          </div>

          <div v-if="!ready" class="servers">
            <div v-for="i in 2" :key="i" class="skeleton h-[74px] rounded-xl" />
          </div>
          <div v-else class="servers">
            <ServerCard
              v-for="s in servers.items.slice(0, 4)"
              :key="s.id"
              :server="s"
              compact
              :join-disabled="!featured || game?.phase !== 'idle'"
              :join-hint="featured ? t('home.servers.joinHint', { name: featured.name }) : t('home.servers.joinHintNone')"
              @join="join"
            />
            <button class="add-tile add-tile-row" @click="addingServer = true">
              <span class="add-icon"><svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path :d="icons.plus" /></svg></span>
              <span class="min-w-0 text-left">
                <span class="block text-sm font-semibold text-base-50">{{ t('home.servers.add') }}</span>
                <span class="block text-xs text-base-400">
                  {{ servers.items.length ? t('home.servers.addHint') : t('home.servers.addHintEmpty') }}
                </span>
              </span>
            </button>
          </div>
        </section>
      </div>

      <ServerDialog v-if="addingServer" @close="addingServer = false" />
    </div>
  </div>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.hero {
  height: 22rem;
  margin-bottom: 1.5rem;
}
/*
 * Schleier über der Schaltung: links (hinter dem Text) dicht, nach rechts
 * offen, unten weich in die Seite – dazu eine leichte Vignette.
 */
.scrim {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    linear-gradient(90deg, color-mix(in srgb, var(--color-base-950) 86%, transparent) 0%, color-mix(in srgb, var(--color-base-950) 58%, transparent) 30%, color-mix(in srgb, var(--color-base-950) 14%, transparent) 62%, transparent 85%),
    linear-gradient(0deg, var(--color-base-950) 0%, color-mix(in srgb, var(--color-base-950) 45%, transparent) 26%, transparent 55%),
    radial-gradient(140% 120% at 70% 40%, transparent 55%, color-mix(in srgb, var(--color-base-950) 70%, transparent) 100%);
}
.hero-title {
  text-shadow: 0 2px 0 color-mix(in srgb, var(--color-base-950) 80%, transparent);
}
/* Instanz-Bild im Pixelrahmen. */
.icon-frame {
  padding: 3px;
  background: var(--color-base-900);
  box-shadow:
    0 0 0 2px var(--color-base-700),
    0 12px 30px -10px rgb(0 0 0 / 0.6);
  transition: transform 0.15s ease;
}
.icon-frame:hover {
  transform: translateY(-2px);
}
.hero-running .icon-frame {
  box-shadow:
    0 0 0 2px var(--color-lamp-400),
    0 0 26px -4px var(--color-lamp-400);
}

/* Weiterspielen: Spalten füllen die ganze Breite, egal wie viele Kacheln. */
.strip {
  display: flex;
  gap: 0.75rem;
}
.strip-card {
  flex: 1 1 0;
  min-width: 0;
}
.strip-add {
  flex: 0 0 11rem;
}
.tile {
  transition: transform 0.15s ease, border-color 0.15s ease, box-shadow 0.2s ease;
}
.tile:hover {
  transform: translateY(-2px);
  border-color: var(--color-base-700);
}
.tile-live {
  border-color: color-mix(in srgb, var(--color-lamp-400) 55%, transparent);
  box-shadow: 0 0 22px -8px var(--color-lamp-400);
}
.tile-play {
  @apply absolute top-[4.25rem] right-3 grid size-10 place-items-center bg-redstone-500 text-white opacity-0 transition-[opacity,transform,background-color] group-hover:opacity-100 hover:bg-redstone-400 focus-visible:opacity-100 disabled:cursor-default;
  box-shadow:
    inset 0 2px 0 rgb(255 255 255 / 0.25),
    inset 0 -3px 0 rgb(0 0 0 / 0.3);
}
.tile-play-on {
  @apply bg-lamp-400 text-base-950 opacity-100;
}

/* Kachel für Neues: gestrichelter Pixelrahmen statt Karte. */
.add-tile {
  @apply flex h-full min-h-[10.5rem] w-full flex-col items-center justify-center gap-1.5 rounded-xl border-2 border-dashed border-base-700 bg-base-900/40 p-4 text-center transition-colors hover:border-redstone-500/70 hover:bg-base-900;
}
.add-tile-row {
  @apply min-h-[4.6rem] flex-row justify-start gap-3 px-3 text-left;
}
.add-icon {
  @apply grid size-10 shrink-0 place-items-center bg-base-800 text-base-200 transition-colors;
}
.add-tile:hover .add-icon {
  @apply bg-redstone-500 text-white;
}

/* Neuigkeiten und Server: nebeneinander, sobald Platz ist; sonst untereinander. */
.lower {
  display: grid;
  gap: 2.5rem;
  grid-template-columns: minmax(0, 1fr);
}
.servers {
  display: grid;
  gap: 0.5rem;
  grid-template-columns: repeat(auto-fill, minmax(22rem, 1fr));
}
@media (min-width: 1500px) {
  .lower {
    grid-template-columns: minmax(0, 1fr) minmax(21rem, 26rem);
    gap: 2rem;
  }
  .servers {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
