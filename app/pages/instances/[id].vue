<script setup lang="ts">
import type { Instance } from '~/types'

const route = useRoute()
const router = useRouter()
const id = computed(() => String(route.params.id))

const games = useGamesStore()
const instances = useInstancesStore()
const settings = useSettingsStore()
const game = computed(() => games.state(id.value))
const ui = computed(() => settings.current?.ui)

const instance = ref<Instance | null>(null)
const loadError = ref<string | null>(null)
type Tab = 'content' | 'history' | 'screenshots' | 'worlds' | 'logs'
const tabs = computed<[Tab, string][]>(() => {
  const list: [Tab, string][] = [['content', 'Inhalte']]
  if (ui.value?.historyTab !== false) list.push(['history', 'Verlauf'])
  if (ui.value?.screenshotsTab !== false) list.push(['screenshots', 'Screenshots'])
  if (ui.value?.worldsTab !== false) list.push(['worlds', 'Welten'])
  list.push(['logs', 'Logs'])
  return list
})
const tab = ref<Tab>('content')
// Ausgeblendeter Tab aktiv? Zurück zu den Inhalten.
watch(tabs, (list) => {
  if (!list.some(([key]) => key === tab.value)) tab.value = 'content'
})

const settingsOpen = ref<string | null>(null)
const changingVersion = ref(false)
const historyKey = ref(0)

// Beim Start direkt zu den Logs springen – da passiert dann etwas.
watch(
  () => game.value.phase,
  (phase, before) => {
    if (before === 'idle' && phase !== 'idle') tab.value = 'logs'
  },
)

async function load() {
  try {
    instance.value = await backend.getInstance(id.value)
  } catch (e) {
    loadError.value = errorMessage(e)
  }
}
onMounted(() => {
  load()
  if (!settings.current) settings.load().catch(() => {})
  if (route.query.settings) settingsOpen.value = String(route.query.settings)
  // Die Befehlspalette springt direkt in einen Bereich (z. B. ?tab=content).
  const wanted = route.query.tab ? String(route.query.tab) : null
  if (wanted && tabs.value.some(([key]) => key === wanted)) tab.value = wanted as Tab
})

// Nach Spielende lädt der Instanz-Store neu – Spielzeit hier mitziehen.
watch(
  () => instances.items.find((i) => i.id === id.value),
  (fresh) => {
    if (fresh && instance.value) {
      instance.value.totalPlaySeconds = fresh.totalPlaySeconds
      instance.value.lastPlayed = fresh.lastPlayed
    }
  },
)

function onUpdated(updated: Instance) {
  instance.value = updated
  historyKey.value++
}

function onDeleted() {
  settingsOpen.value = null
  router.push('/instances')
}

const loader = computed(() => {
  if (!instance.value) return ''
  const { kind, version } = instance.value.loader
  return version ? `${loaderLabels[kind]} ${version}` : loaderLabels[kind]
})

function openFolder() {
  backend.openInstanceDir(id.value).catch(() => {})
}
</script>

<template>
  <div class="flex h-full">
    <div class="flex min-w-0 flex-1 flex-col p-6">
      <NuxtLink to="/instances" class="mb-3 inline-flex w-fit items-center gap-1 text-xs text-base-400 hover:text-base-50">
        <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M15 5l-7 7 7 7" /></svg>
        Bibliothek
      </NuxtLink>

      <p v-if="loadError" role="alert" class="card border-redstone-600/50 px-4 py-3 text-sm text-redstone-300">{{ loadError }}</p>

      <div v-else-if="!instance" class="mb-5 flex items-center gap-5">
        <div class="skeleton size-[88px] rounded-xl" />
        <div class="flex-1 space-y-2.5"><div class="skeleton h-8 w-64" /><div class="skeleton h-4 w-80" /></div>
        <div class="skeleton h-10 w-72" />
      </div>

      <template v-else>
        <!-- Banner als Kopf: Bild der Instanz, darüber Icon, Name und Spielen. -->
        <InstanceBanner :instance="instance" class="mb-5 rounded-2xl border border-base-800">
          <header class="flex flex-wrap items-center gap-5 p-5">
          <button class="shrink-0 rounded-xl outline-none transition-transform duration-150 hover:scale-[1.03] focus-visible:ring-2 focus-visible:ring-redstone-500" aria-label="Einstellungen: Allgemein" @click="settingsOpen = 'general'">
            <InstanceIcon :instance="instance" :size="88" class="shadow-xl shadow-black/50" />
          </button>

          <div class="min-w-56 flex-1">
            <h1 class="display truncate text-3xl leading-tight text-white drop-shadow">{{ instance.name }}</h1>
            <div class="mt-2 flex flex-wrap items-center gap-2 text-xs text-white/75">
              <button
                class="chip gap-1.5 ring-1 ring-base-700 transition-colors hover:bg-base-700 hover:text-base-50 disabled:opacity-60"
                title="Version wechseln"
                :disabled="game.phase !== 'idle'"
                @click="changingVersion = true"
              >
                <span class="size-2 rounded-full" :style="{ background: loaderColors[instance.loader.kind] }" />
                <span class="font-mono text-base-50">{{ instance.gameVersion }}</span> {{ loader }}
                <svg viewBox="0 0 24 24" class="size-3 text-base-400" fill="none" stroke="currentColor" stroke-width="2.5"><path d="m7 10 5 5 5-5" /></svg>
              </button>
              <span v-if="instance.group" class="chip">{{ instance.group }}</span>
              <span v-if="instance.totalPlaySeconds > 0 && ui?.showPlayTime !== false" class="flex items-center gap-1.5">
                <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="8" /><path d="M12 8v4l3 2" /></svg>
                {{ formatPlayTime(instance.totalPlaySeconds) }}
              </span>
              <span>{{ formatRelative(instance.lastPlayed) }}</span>
            </div>
          </div>

          <div class="flex w-80 shrink-0 items-center gap-2">
            <PlayButton :instance-id="instance.id" large />
            <button class="btn-icon size-12 bg-base-900/80 backdrop-blur" title="Einstellungen" aria-label="Einstellungen" @click="settingsOpen = 'general'">
              <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.gear" /></svg>
            </button>
            <button class="btn-icon size-12 bg-base-900/80 backdrop-blur" title="Ordner öffnen" aria-label="Ordner öffnen" @click="openFolder">
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" />
              </svg>
            </button>
          </div>
          </header>
        </InstanceBanner>

        <p v-if="game.error" role="alert" class="card mb-4 border-redstone-600/50 px-4 py-2.5 text-sm text-redstone-300">{{ game.error }}</p>
        <CrashPanel v-else-if="game.lastExit?.crashed" :instance-id="instance.id" :exit-code="game.lastExit.exitCode" :diagnosis="game.lastExit.diagnosis" class="mb-4" />

        <nav class="mb-4 flex flex-wrap gap-1" aria-label="Bereiche">
          <button v-for="[key, label] in tabs" :key="key" class="tab" :class="{ 'tab-on': tab === key }" @click="tab = key">{{ label }}</button>
        </nav>

        <ChangeVersionDialog v-if="changingVersion" :instance="instance" @close="changingVersion = false" @changed="onUpdated" />
        <InstanceSettingsDialog
          v-if="settingsOpen"
          :instance="instance"
          :initial="settingsOpen"
          @close="settingsOpen = null"
          @updated="onUpdated"
          @deleted="onDeleted"
        />

        <ContentList v-if="tab === 'content'" :key="`${instance.gameVersion}-${instance.loader.kind}-${instance.overrides.boost}`" :instance="instance" />
        <HistoryList v-else-if="tab === 'history'" :instance="instance" :refresh-key="historyKey" />
        <InstanceGallery v-else-if="tab === 'screenshots' || tab === 'worlds'" :instance="instance" :mode="tab" @updated="onUpdated" />
        <LogConsole v-else-if="tab === 'logs'" :lines="game.logs" />
      </template>
    </div>

    <AccountPanel v-if="ui?.hideRightSidebar !== true" class="hidden lg:flex" />
  </div>
</template>
