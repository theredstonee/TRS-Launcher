<script setup lang="ts">
import type { IconName } from '~/utils/icons'
import type { MessageKey } from '~/utils/i18n'

// Schmale Icon-Leiste: oben die Bereiche, darunter der
// Schnellstart der zuletzt gespielten Instanzen, unten Suche, Einstellungen und
// Konto. Ausklappbar (dann mit Beschriftung), sonst mit Kurzhinweisen.
interface NavItem {
  to: string
  label: MessageKey
  icon: IconName
  /** Seiten, die ein anderer Bereich baut – fehlen sie noch, bleibt der Punkt ruhig stehen. */
  optional?: boolean
  /** Nur für das TRS-Team (Admins und Moderatoren). */
  admin?: boolean
  /** Nur unter Windows (z. B. Clips). */
  windowsOnly?: boolean
}

const items: NavItem[] = [
  { to: '/', label: 'nav.home', icon: 'home' },
  { to: '/instances', label: 'nav.library', icon: 'library' },
  { to: '/browse', label: 'nav.discover', icon: 'compass' },
  { to: '/presets', label: 'nav.presets', icon: 'presets' },
  { to: '/servers', label: 'nav.servers', icon: 'server' },
  { to: '/screenshots', label: 'nav.screenshots', icon: 'screenshots', optional: true },
  { to: '/clips', label: 'nav.clips', icon: 'clips', optional: true, windowsOnly: true },
  { to: '/skins', label: 'nav.skins', icon: 'skins', optional: true },
  { to: '/social', label: 'nav.social', icon: 'chat' },
  { to: '/admin', label: 'nav.admin', icon: 'admin', admin: true },
]

const route = useRoute()
const router = useRouter()
const accounts = useAccountsStore()
const games = useGamesStore()
const instances = useInstancesStore()
const settings = useSettingsStore()
const ui = useUiStore()
const trs = useTrsStore()
const chat = useChatStore()
/** Anfragen + Umhang-Angebote + ungelesene Nachrichten. */
const socialCount = computed(() => trs.incomingCount + chat.unreadTotal)

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
  items.filter(
    (item) =>
      (!item.optional || router.resolve(item.to).matched.length > 0) &&
      (!item.admin || trs.isStaff) &&
      // Clips (Spielaufnahme) gibt es vorerst nur unter Windows.
      (!item.windowsOnly || !isLinux),
  ),
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
    :aria-label="t('nav.mainLabel')"
  >
    <NuxtLink
      v-for="item in visibleItems"
      :key="item.to"
      :to="item.to"
      class="rail group"
      :class="[{ 'rail-on': isActive(item.to) }, expanded ? 'rail-wide' : '']"
      :aria-label="t(item.label)"
      :aria-current="isActive(item.to) ? 'page' : undefined"
    >
      <svg viewBox="0 0 24 24" class="size-5 shrink-0" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
        <path :d="icons[item.icon]" />
      </svg>
      <span v-if="expanded" class="min-w-0 flex-1 truncate text-sm">{{ t(item.label) }}</span>
      <span
        v-if="item.to === '/instances' && games.runningCount"
        class="size-1.5 animate-lamp rounded-full bg-lamp-400"
        :class="expanded ? 'ml-auto' : 'absolute top-1.5 right-1.5'"
        :title="t('nav.runningCount', games.runningCount)"
      />
      <span
        v-if="item.to === '/social' && socialCount"
        class="grid min-w-4 place-items-center rounded-full bg-redstone-500 px-1 text-[10px] leading-4 font-bold text-white"
        :class="expanded ? 'ml-auto' : 'absolute top-0.5 right-0.5'"
        :title="t('nav.socialBadge', { messages: chat.unreadTotal, requests: trs.incomingCount })"
        data-testid="nav-social-badge"
      >
        {{ socialCount > 99 ? '99+' : socialCount }}
      </span>
      <span v-if="!expanded" class="tip" role="tooltip">{{ t(item.label) }}</span>
    </NuxtLink>

    <div class="my-2 h-px w-full shrink-0 bg-base-800" />

    <!-- Schnellstart: laufende und zuletzt gespielte Instanzen -->
    <div v-if="quick.length && uiSettings?.sidebarRecent !== false" class="min-h-0 flex-1 overflow-y-auto overflow-x-hidden">
      <p v-if="expanded" class="mb-1.5 px-2 text-[11px] font-medium text-base-600">{{ t('nav.quickLaunch') }}</p>
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
          :aria-label="t('nav.openNamed', { name: i.name })"
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
          :aria-label="t('play.playNamed', { name: i.name })"
          @click="play(i.id)"
        >
          <svg viewBox="0 0 24 24" class="ml-px size-3" fill="currentColor"><path :d="icons.play" /></svg>
        </button>
        <span v-if="!expanded" class="tip">{{ i.name }} · {{ i.gameVersion }}</span>
      </div>
    </div>
    <div v-else class="min-h-0 flex-1" />

    <button class="rail group mt-1" :class="expanded ? 'rail-wide' : ''" :aria-label="t('nav.newInstance')" @click="ui.creating = true">
      <svg viewBox="0 0 24 24" class="size-5 shrink-0" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path :d="icons.plus" /></svg>
      <span v-if="expanded" class="truncate text-sm">{{ t('nav.newInstance') }}</span>
      <span v-else class="tip">{{ t('nav.newInstance') }}</span>
    </button>

    <div class="my-2 h-px w-full shrink-0 bg-base-800" />

    <button class="rail group" :class="expanded ? 'rail-wide' : ''" :aria-label="t('nav.searchShortcut')" @click="ui.openPalette()">
      <svg viewBox="0 0 24 24" class="size-5 shrink-0" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path :d="icons.search" /></svg>
      <template v-if="expanded">
        <span class="truncate text-sm">{{ t('common.actions.search') }}</span>
        <kbd class="ml-auto rounded border border-base-700 px-1.5 py-0.5 font-mono text-[10px] text-base-400">{{ t('titleBar.ctrl') }} K</kbd>
      </template>
      <span v-else class="tip">{{ t('nav.searchTip') }}</span>
    </button>

    <button class="rail group" :class="[expanded ? 'rail-wide' : '', { 'rail-on': settings.dialog !== null }]" :aria-label="t('nav.settings')" @click="settings.open()">
      <svg viewBox="0 0 24 24" class="size-5 shrink-0" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.gear" /></svg>
      <span v-if="expanded" class="truncate text-sm">{{ t('nav.settings') }}</span>
      <span v-else class="tip">{{ t('nav.settings') }}</span>
    </button>

    <NuxtLink
      v-if="uiSettings?.sidebarAccount !== false"
      to="/accounts"
      class="rail group"
      :class="[expanded ? 'rail-wide' : '', { 'rail-on': isActive('/accounts') }]"
      :aria-label="accounts.active ? t('nav.accountNamed', { name: accounts.active.name }) : t('nav.signIn')"
    >
      <SkinHead :skin-url="accounts.active?.skinUrl ?? null" :name="accounts.active?.name ?? '?'" :size="26" class="shrink-0" />
      <span v-if="expanded" class="min-w-0 flex-1 truncate text-sm">{{ accounts.active?.name ?? t('nav.signIn') }}</span>
      <span v-else class="tip">{{ accounts.active?.name ?? t('nav.signIn') }}</span>
    </NuxtLink>

    <button
      class="rail group"
      :class="expanded ? 'rail-wide' : ''"
      :aria-label="expanded ? t('nav.collapseLabel') : t('nav.expandLabel')"
      :aria-pressed="expanded"
      @click="ui.toggleNav()"
    >
      <svg viewBox="0 0 24 24" class="size-5 shrink-0 transition-transform duration-200" :class="{ 'rotate-180': expanded }" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="m9 6 6 6-6 6" />
      </svg>
      <span v-if="expanded" class="truncate text-sm">{{ t('nav.collapse') }}</span>
      <span v-else class="tip">{{ t('nav.expand') }}</span>
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
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--color-redstone-500) 28%, transparent);
}
/* Aktiver Bereich: ein geladenes Stück Redstone am linken Rand, das leicht glüht. */
.rail-on::before {
  content: "";
  @apply absolute top-1/2 -left-2 h-6 w-1 -translate-y-1/2 bg-redstone-400;
  box-shadow: 0 0 8px 1px color-mix(in srgb, var(--color-redstone-500) 70%, transparent);
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
