<script setup lang="ts">
import type { IconName } from '~/utils/icons'

// Schmale Icon-Leiste wie in der Modrinth App: oben die Bereiche, darunter der
// Schnellstart der zuletzt gespielten Instanzen, unten Suche, Einstellungen und
// Konto. Ausklappbar (dann mit Beschriftung), sonst mit Kurzhinweisen.
interface NavItem {
  to: string
  label: string
  icon: IconName
  /** Seiten, die ein anderer Bereich baut – fehlen sie noch, bleibt der Punkt ruhig stehen. */
  optional?: boolean
}

const items: NavItem[] = [
  { to: '/', label: 'Start', icon: 'home' },
  { to: '/instances', label: 'Bibliothek', icon: 'library' },
  { to: '/browse', label: 'Entdecken', icon: 'compass' },
  { to: '/servers', label: 'Server', icon: 'server' },
  { to: '/screenshots', label: 'Screenshots', icon: 'screenshots', optional: true },
  { to: '/skins', label: 'Skins', icon: 'skins', optional: true },
]

const route = useRoute()
const router = useRouter()
const accounts = useAccountsStore()
const games = useGamesStore()
const instances = useInstancesStore()
const settings = useSettingsStore()
const ui = useUiStore()

const uiSettings = computed(() => settings.current?.ui)
const expanded = computed(() => ui.navExpanded)
// Die Liste ist nach „zuletzt gespielt“ sortiert; laufende Instanzen immer zuerst.
const quick = computed(() => {
  const running = instances.items.filter((i) => games.state(i.id).phase !== 'idle')
  const rest = instances.items.filter((i) => !running.includes(i))
  return [...running, ...rest].slice(0, 7)
})

// Seiten anderer Bereiche erscheinen erst, wenn es sie wirklich gibt.
const visibleItems = computed(() =>
  items.filter((item) => !item.optional || router.resolve(item.to).matched.length > 0),
)

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

function play(id: string) {
  const state = games.state(id)
  if (state.phase === 'idle') games.launch(id)
}
</script>

<template>
  <nav
    class="relative z-30 flex shrink-0 flex-col border-r border-base-800 bg-base-900 py-2 transition-[width] duration-200"
    :class="expanded ? 'w-56 px-2' : 'w-[4.25rem] items-center px-2'"
    aria-label="Hauptnavigation"
  >
    <NuxtLink
      v-for="item in visibleItems"
      :key="item.to"
      :to="item.to"
      class="rail group"
      :class="[{ 'rail-on': isActive(item.to) }, expanded ? 'rail-wide' : '']"
      :aria-label="item.label"
      :aria-current="isActive(item.to) ? 'page' : undefined"
    >
      <svg viewBox="0 0 24 24" class="size-5 shrink-0" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
        <path :d="icons[item.icon]" />
      </svg>
      <span v-if="expanded" class="min-w-0 flex-1 truncate text-sm">{{ item.label }}</span>
      <span
        v-if="item.to === '/instances' && games.runningCount"
        class="size-1.5 animate-lamp rounded-full bg-lamp-400"
        :class="expanded ? 'ml-auto' : 'absolute top-1.5 right-1.5'"
        :title="`${games.runningCount} laufend`"
      />
      <span v-if="!expanded" class="tip" role="tooltip">{{ item.label }}</span>
    </NuxtLink>

    <div class="my-2 h-px w-full shrink-0 bg-base-800" />

    <!-- Schnellstart: laufende und zuletzt gespielte Instanzen -->
    <div v-if="quick.length && uiSettings?.sidebarRecent !== false" class="min-h-0 flex-1 overflow-y-auto overflow-x-hidden">
      <p v-if="expanded" class="mb-1.5 px-2 text-[11px] font-medium text-base-600">Schnellstart</p>
      <div
        v-for="i in quick"
        :key="i.id"
        class="group relative mb-1"
        :class="expanded ? '' : 'flex justify-center'"
      >
        <NuxtLink
          :to="`/instances/${i.id}`"
          class="flex items-center gap-2.5 rounded-lg p-1 transition-colors hover:bg-base-800"
          :class="[expanded ? 'w-full' : '', route.path === `/instances/${i.id}` ? 'bg-base-800' : '']"
          :aria-label="`${i.name} öffnen`"
        >
          <span class="relative shrink-0">
            <InstanceIcon :instance="i" :size="34" class="transition-transform duration-150 group-hover:scale-[1.06]" />
            <span
              v-if="games.state(i.id).phase !== 'idle'"
              class="absolute -right-0.5 -bottom-0.5 size-2.5 animate-lamp rounded-full bg-lamp-400 ring-2 ring-base-900"
            />
          </span>
          <span v-if="expanded" class="min-w-0 flex-1">
            <span class="block truncate text-sm text-base-200">{{ i.name }}</span>
            <span class="block truncate font-mono text-[11px] text-base-600">{{ i.gameVersion }}</span>
          </span>
        </NuxtLink>
        <button
          class="absolute grid size-5 place-items-center rounded-full bg-redstone-500 text-white opacity-0 shadow-md shadow-black/50 transition-opacity group-hover:opacity-100 focus-visible:opacity-100 disabled:hidden"
          :class="expanded ? 'top-1/2 right-2 -translate-y-1/2' : 'right-1.5 bottom-0'"
          :disabled="games.state(i.id).phase !== 'idle'"
          :aria-label="`${i.name} spielen`"
          @click="play(i.id)"
        >
          <svg viewBox="0 0 24 24" class="ml-px size-3" fill="currentColor"><path :d="icons.play" /></svg>
        </button>
        <span v-if="!expanded" class="tip">{{ i.name }} · {{ i.gameVersion }}</span>
      </div>
    </div>
    <div v-else class="min-h-0 flex-1" />

    <button class="rail group mt-1" :class="expanded ? 'rail-wide' : ''" aria-label="Neue Instanz" @click="ui.creating = true">
      <svg viewBox="0 0 24 24" class="size-5 shrink-0" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path :d="icons.plus" /></svg>
      <span v-if="expanded" class="truncate text-sm">Neue Instanz</span>
      <span v-else class="tip">Neue Instanz</span>
    </button>

    <div class="my-2 h-px w-full shrink-0 bg-base-800" />

    <button class="rail group" :class="expanded ? 'rail-wide' : ''" aria-label="Suchen (Strg+K)" @click="ui.openPalette()">
      <svg viewBox="0 0 24 24" class="size-5 shrink-0" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path :d="icons.search" /></svg>
      <template v-if="expanded">
        <span class="truncate text-sm">Suchen</span>
        <kbd class="ml-auto rounded border border-base-700 px-1.5 py-0.5 font-mono text-[10px] text-base-400">Strg K</kbd>
      </template>
      <span v-else class="tip">Suchen · Strg K</span>
    </button>

    <button class="rail group" :class="[expanded ? 'rail-wide' : '', { 'rail-on': settings.dialog !== null }]" aria-label="Einstellungen" @click="settings.open()">
      <svg viewBox="0 0 24 24" class="size-5 shrink-0" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.gear" /></svg>
      <span v-if="expanded" class="truncate text-sm">Einstellungen</span>
      <span v-else class="tip">Einstellungen</span>
    </button>

    <NuxtLink
      v-if="uiSettings?.sidebarAccount !== false"
      to="/accounts"
      class="rail group"
      :class="[expanded ? 'rail-wide' : '', { 'rail-on': isActive('/accounts') }]"
      :aria-label="accounts.active ? `Konto: ${accounts.active.name}` : 'Anmelden'"
    >
      <SkinHead :skin-url="accounts.active?.skinUrl ?? null" :name="accounts.active?.name ?? '?'" :size="26" class="shrink-0" />
      <span v-if="expanded" class="min-w-0 flex-1 truncate text-sm">{{ accounts.active?.name ?? 'Anmelden' }}</span>
      <span v-else class="tip">{{ accounts.active?.name ?? 'Anmelden' }}</span>
    </NuxtLink>

    <button
      class="rail group"
      :class="expanded ? 'rail-wide' : ''"
      :aria-label="expanded ? 'Leiste einklappen' : 'Leiste ausklappen'"
      :aria-pressed="expanded"
      @click="ui.toggleNav()"
    >
      <svg viewBox="0 0 24 24" class="size-5 shrink-0 transition-transform duration-200" :class="{ 'rotate-180': expanded }" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="m9 6 6 6-6 6" />
      </svg>
      <span v-if="expanded" class="truncate text-sm">Einklappen</span>
      <span v-else class="tip">Ausklappen</span>
    </button>

    <p v-if="version && expanded" class="px-2 pt-1 font-mono text-[11px] text-base-600">v{{ version }}</p>
  </nav>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.rail {
  @apply relative mb-0.5 flex size-11 shrink-0 items-center justify-center rounded-xl text-base-400 transition-[background-color,color,transform] hover:bg-base-800 hover:text-base-50 active:scale-95;
}
.rail-wide {
  @apply size-auto w-full justify-start gap-3 px-2.5 py-2.5;
}
.rail-on {
  @apply bg-base-800 text-base-50;
}
/* Aktiver Bereich bekommt einen Redstone-Streifen am linken Rand. */
.rail-on::before {
  content: "";
  @apply absolute top-1/2 -left-2 h-5 w-1 -translate-y-1/2 rounded-r bg-redstone-500;
}
.tip {
  @apply pointer-events-none absolute top-1/2 left-full z-50 ml-2 -translate-y-1/2 scale-95 rounded-md border border-base-700 bg-base-850 px-2 py-1 text-xs whitespace-nowrap text-base-50 opacity-0 shadow-lg shadow-black/40 transition-[opacity,transform] duration-150;
}
.group:hover .tip,
.group:focus-visible .tip,
.group:focus-within .tip {
  @apply scale-100 opacity-100;
}
</style>
