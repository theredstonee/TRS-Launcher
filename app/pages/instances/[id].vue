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

// --- Tabs -----------------------------------------------------------------------
type Tab = 'content' | 'files' | 'worlds' | 'screenshots' | 'history' | 'logs' | 'share'
// Nur die Schlüssel – die Beschriftung kommt beim Rendern aus `instance.tabs.*`.
const tabs = computed<Tab[]>(() => {
  const list: Tab[] = ['content', 'files']
  if (ui.value?.worldsTab !== false) list.push('worlds')
  if (ui.value?.screenshotsTab !== false) list.push('screenshots')
  if (ui.value?.historyTab !== false) list.push('history')
  list.push('logs', 'share')
  return list
})
const TAB_ICONS: Record<Tab, string> = {
  content: 'M4 8l8-4 8 4v8l-8 4-8-4zM4 8l8 4 8-4M12 12v8',
  files: 'M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z',
  worlds: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM3 12h18M12 3c2.5 2.5 3.5 5.5 3.5 9s-1 6.5-3.5 9c-2.5-2.5-3.5-5.5-3.5-9s1-6.5 3.5-9z',
  screenshots: icons.screenshots,
  history: 'M12 8v4l3 2M3.5 12a8.5 8.5 0 1 0 2.5-6M3 4v4h4',
  logs: 'M4 5h16M4 10h10M4 15h16M4 20h8',
  share: 'M12 15V3m0 0L8 7m4-4 4 4M5 12v7a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-7',
}

// Letzten Tab je Instanz merken (nur Komfort – ohne Speicher gilt „Inhalte“).
const tabStorageKey = computed(() => `trs.instance.tab.${id.value}`)
function rememberedTab(): Tab | null {
  try {
    const raw = localStorage.getItem(tabStorageKey.value)
    return raw && tabs.value.includes(raw as Tab) ? (raw as Tab) : null
  } catch {
    return null
  }
}
const tab = ref<Tab>('content')
function selectTab(next: Tab, focus = false) {
  tab.value = next
  try {
    localStorage.setItem(tabStorageKey.value, next)
  } catch {
    // Kein Speicher – egal.
  }
  if (focus) nextTick(() => document.getElementById(`instance-tab-${next}`)?.focus())
}
// Ausgeblendeter Tab aktiv? Zurück zu den Inhalten.
watch(tabs, (list) => {
  if (!list.includes(tab.value)) tab.value = 'content'
})

/** Pfeiltasten/Pos1/Ende in der Tab-Leiste (WAI-ARIA Tabs, automatische Aktivierung). */
function onTabKey(e: KeyboardEvent) {
  const list = tabs.value
  const at = list.indexOf(tab.value)
  let next: number | null = null
  if (e.key === 'ArrowRight') next = (at + 1) % list.length
  else if (e.key === 'ArrowLeft') next = (at - 1 + list.length) % list.length
  else if (e.key === 'Home') next = 0
  else if (e.key === 'End') next = list.length - 1
  if (next === null) return
  e.preventDefault()
  selectTab(list[next]!, true)
}

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
  if (wanted && tabs.value.includes(wanted as Tab)) tab.value = wanted as Tab
  else if (game.value.phase !== 'idle') tab.value = 'logs'
  else tab.value = rememberedTab() ?? 'content'
})

// Titelleiste „Logs öffnen“, während die Seite schon offen ist.
watch(
  () => route.query.tab,
  (wanted) => {
    if (typeof wanted === 'string' && tabs.value.includes(wanted as Tab)) tab.value = wanted as Tab
  },
)

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
        {{ t('library.title') }}
      </NuxtLink>

      <p v-if="loadError" role="alert" class="card border-redstone-600/50 px-4 py-3 text-sm text-redstone-300">{{ loadError }}</p>

      <div v-else-if="!instance" class="mb-5 flex items-center gap-5">
        <div class="skeleton size-[88px] rounded-xl" />
        <div class="flex-1 space-y-2.5"><div class="skeleton h-8 w-64" /><div class="skeleton h-4 w-80" /></div>
        <div class="skeleton h-10 w-72" />
      </div>

      <template v-else>
        <!-- Banner als Kopf: Bild der Instanz, darüber Icon, Name und Spielen. -->
        <InstanceBanner :instance="instance" class="mb-4 shrink-0 rounded-2xl border border-base-800">
          <header class="flex flex-wrap items-center gap-5 p-5">
            <button class="shrink-0 rounded-xl outline-none transition-transform duration-150 hover:scale-[1.03] focus-visible:ring-2 focus-visible:ring-redstone-500" :aria-label="t('instance.settingsGeneral')" @click="settingsOpen = 'general'">
              <InstanceIcon :instance="instance" :size="88" class="shadow-xl shadow-black/50" />
            </button>

            <div class="min-w-56 flex-1">
              <h1 class="display truncate text-3xl leading-tight text-white drop-shadow">{{ instance.name }}</h1>
              <div class="mt-2 flex flex-wrap items-center gap-2 text-xs text-white/75">
                <button
                  class="chip gap-1.5 ring-1 ring-base-700 transition-colors hover:bg-base-700 hover:text-base-50 disabled:opacity-60"
                  :title="t('instance.changeVersion')"
                  :disabled="game.phase !== 'idle'"
                  @click="changingVersion = true"
                >
                  <span class="size-2 rounded-full" :style="{ background: loaderColors[instance.loader.kind] }" />
                  <span class="font-mono text-base-50">{{ instance.gameVersion }}</span> {{ loader }}
                  <svg viewBox="0 0 24 24" class="size-3 text-base-400" fill="none" stroke="currentColor" stroke-width="2.5"><path d="m7 10 5 5 5-5" /></svg>
                </button>
                <span v-if="instance.group" class="chip">{{ instance.group }}</span>
                <span v-if="instance.totalPlaySeconds > 0 && ui?.showPlayTime !== false" class="flex items-center gap-1.5" :title="t('instance.playTime')">
                  <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="8" /><path d="M12 8v4l3 2" /></svg>
                  {{ formatPlayTime(instance.totalPlaySeconds) }}
                </span>
                <span class="flex items-center gap-1.5" :title="t('instance.lastPlayed')">
                  <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M4 5h16v15H4zM4 9h16M8 3v4M16 3v4" /></svg>
                  {{ formatRelative(instance.lastPlayed) }}
                </span>
                <span v-if="game.phase === 'running'" class="flex items-center gap-1.5 text-redstone-300">
                  <span class="size-2 animate-lamp rounded-full bg-redstone-500 shadow-[0_0_8px_var(--color-redstone-500)]" />
                  {{ t('instance.running') }}
                </span>
              </div>
            </div>

            <div class="flex w-80 shrink-0 items-center gap-2">
              <PlayButton :instance-id="instance.id" large />
              <button class="btn-icon size-12 bg-base-900/80 backdrop-blur" :title="t('instance.settings')" :aria-label="t('instance.settings')" @click="settingsOpen = 'general'">
                <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.gear" /></svg>
              </button>
              <button class="btn-icon size-12 bg-base-900/80 backdrop-blur" :title="t('common.actions.openFolder')" :aria-label="t('common.actions.openFolder')" @click="openFolder">
                <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M3 6a1 1 0 0 1 1-1h5l2 2h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z" />
                </svg>
              </button>
            </div>
          </header>
        </InstanceBanner>

        <p v-if="game.error" role="alert" class="card mb-4 shrink-0 border-redstone-600/50 px-4 py-2.5 text-sm text-redstone-300">{{ game.error }}</p>
        <CrashPanel v-else-if="game.lastExit?.crashed" :instance-id="instance.id" :exit-code="game.lastExit.exitCode" :diagnosis="game.lastExit.diagnosis" class="mb-4 shrink-0" />
        <FpsBoostHint :instance="instance" />

        <!-- Tab-Leiste: Redstone-Leitung, der aktive Tab „leuchtet“. -->
        <div class="tabbar mb-4 shrink-0" role="tablist" :aria-label="t('instance.tabsLabel')" @keydown="onTabKey">
          <button
            v-for="key in tabs"
            :id="`instance-tab-${key}`"
            :key="key"
            role="tab"
            class="itab"
            :class="{ 'itab-on': tab === key }"
            :aria-selected="tab === key"
            :aria-controls="`instance-panel-${key}`"
            :tabindex="tab === key ? 0 : -1"
            @click="selectTab(key)"
          >
            <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path :d="TAB_ICONS[key]" /></svg>
            <span>{{ t(`instance.tabs.${key}`) }}</span>
            <span v-if="key === 'logs' && game.phase === 'running'" class="size-1.5 animate-lamp rounded-full bg-redstone-500 shadow-[0_0_6px_var(--color-redstone-500)]" :title="t('instance.running')" />
          </button>
        </div>

        <ChangeVersionDialog v-if="changingVersion" :instance="instance" @close="changingVersion = false" @changed="onUpdated" />
        <InstanceSettingsDialog
          v-if="settingsOpen"
          :instance="instance"
          :initial="settingsOpen"
          @close="settingsOpen = null"
          @updated="onUpdated"
          @deleted="onDeleted"
        />

        <div :id="`instance-panel-${tab}`" role="tabpanel" :aria-labelledby="`instance-tab-${tab}`" class="flex min-h-0 flex-1 flex-col">
          <ContentList v-if="tab === 'content'" :key="`${instance.gameVersion}-${instance.loader.kind}-${instance.overrides.boost}`" :instance="instance" />
          <FileBrowser v-else-if="tab === 'files'" :instance="instance" />
          <WorldsPanel v-else-if="tab === 'worlds'" :instance="instance" />
          <InstanceGallery v-else-if="tab === 'screenshots'" :instance="instance" @updated="onUpdated" />
          <HistoryList v-else-if="tab === 'history'" :instance="instance" :refresh-key="historyKey" />
          <LogViewer v-else-if="tab === 'logs'" :instance-id="instance.id" :running="game.phase === 'running'" :lines="game.logs" :log-total="game.logTotal" />
          <SharePanel v-else-if="tab === 'share'" :instance="instance" @navigate="selectTab" />
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.tabbar {
  @apply relative flex flex-wrap gap-0.5 border-b border-base-800;
}
.itab {
  @apply relative -mb-px inline-flex items-center gap-2 rounded-t-lg px-3.5 py-2 text-sm font-medium text-base-400 transition-colors outline-none hover:bg-base-900 hover:text-base-50 focus-visible:bg-base-900 focus-visible:text-base-50;
}
.itab:focus-visible {
  outline: 2px solid var(--color-redstone-400);
  outline-offset: -2px;
}
/* Aktiver Tab: Leitung darunter „unter Strom“. */
.itab-on {
  @apply text-base-50;
}
.itab-on::after {
  content: "";
  position: absolute;
  inset-inline: 0.5rem;
  bottom: 0;
  height: 2px;
  background: linear-gradient(90deg, var(--color-redstone-600), var(--color-redstone-400), var(--color-redstone-600));
  box-shadow: 0 0 8px color-mix(in srgb, var(--color-redstone-500) 80%, transparent);
}
.itab-on svg {
  @apply text-redstone-400;
  filter: drop-shadow(0 0 4px color-mix(in srgb, var(--color-redstone-500) 60%, transparent));
}
</style>
