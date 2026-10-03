<script setup lang="ts">
import type { MobileNavItem } from '~/utils/mobileNav'

// Handy: Tab-Leiste unten statt Seitenleiste. „Mehr“ öffnet ein Sheet mit den übrigen Seiten.
const route = useRoute()
const router = useRouter()
const trs = useTrsStore()
const chat = useChatStore()
const games = useGamesStore()
const settings = useSettingsStore()
const ui = useUiStore()
const openDocs = useDocs()

const socialCount = computed(() => trs.incomingCount + chat.unreadTotal)
const active = computed(() => activeMobileTab(route.path))
const moreOpen = ref(false)
const moreItems = computed(() => mobileMoreItems(platformCaps.value))

// Seitenwechsel schließt das Sheet (auch per Zurück-Taste).
watch(
  () => route.fullPath,
  () => (moreOpen.value = false),
)

function pick(item: MobileNavItem) {
  moreOpen.value = false
  if (item.to) {
    void router.push(item.to)
    return
  }
  if (item.action === 'settings') settings.open()
  else if (item.action === 'search') ui.openPalette()
  else if (item.action === 'create') ui.creating = true
  else if (item.action === 'import') ui.importing = true
  else if (item.action === 'docs') openDocs()
}

function isOn(item: MobileNavItem) {
  return item.to ? route.path === item.to || route.path.startsWith(`${item.to}/`) : false
}
</script>

<template>
  <nav
    class="tabbar relative z-30 flex shrink-0 border-t border-base-800 bg-base-900 pr-[var(--safe-right)] pb-[var(--safe-bottom)] pl-[var(--safe-left)]"
    :aria-label="t('nav.mainLabel')"
    data-testid="mobile-tabbar"
  >
    <NuxtLink
      v-for="tab in mobileTabs"
      :key="tab.to"
      :to="tab.to!"
      class="tabbar-btn"
      :class="{ 'tabbar-on': active === tab.to }"
      :aria-current="active === tab.to ? 'page' : undefined"
    >
      <span class="relative">
        <svg viewBox="0 0 24 24" class="size-6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons[tab.icon]" /></svg>
        <span v-if="tab.to === '/instances' && games.runningCount" class="absolute -top-0.5 -right-1 size-2 animate-lamp rounded-full bg-lamp-400" />
        <span
          v-if="tab.to === '/social' && socialCount"
          class="absolute -top-1.5 -right-3 grid min-w-4 place-items-center rounded-full bg-redstone-500 px-1 text-[10px] leading-4 font-bold text-white"
          data-testid="nav-social-badge"
        >
          {{ socialCount > 99 ? '99+' : socialCount }}
        </span>
      </span>
      <span class="max-w-full truncate">{{ t(tab.label) }}</span>
    </NuxtLink>
    <button class="tabbar-btn" :class="{ 'tabbar-on': active === 'more' || moreOpen }" :aria-expanded="moreOpen" data-testid="mobile-more" @click="moreOpen = !moreOpen">
      <svg viewBox="0 0 24 24" class="size-6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons.menu" /></svg>
      <span class="max-w-full truncate">{{ t('mobile.more') }}</span>
    </button>
  </nav>

  <MobileSheet v-if="moreOpen" :title="t('mobile.more')" @close="moreOpen = false">
    <div class="grid grid-cols-3 gap-2" data-testid="mobile-more-sheet">
      <button
        v-for="item in moreItems"
        :key="item.to ?? item.action"
        class="flex min-h-20 flex-col items-center justify-center gap-1.5 rounded-xl border px-1 py-3 text-center text-xs transition-colors active:bg-base-700"
        :class="isOn(item) ? 'border-redstone-500/60 bg-base-800 text-base-50' : 'border-base-800 bg-base-900 text-base-200'"
        @click="pick(item)"
      >
        <svg viewBox="0 0 24 24" class="size-6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="icons[item.icon]" /></svg>
        <span class="line-clamp-2 leading-tight">{{ t(item.label) }}</span>
      </button>
    </div>
  </MobileSheet>
</template>

<style scoped>
@reference "~/assets/css/main.css";

.tabbar-btn {
  @apply relative flex h-[var(--mobile-tabbar)] min-w-0 flex-1 flex-col items-center justify-center gap-0.5 px-1 text-[11px] font-medium text-base-400 transition-colors active:bg-base-850;
}
.tabbar-on {
  @apply text-base-50;
}
/* Aktiver Tab: ein geladenes Stück Redstone an der Oberkante. */
.tabbar-on::before {
  content: "";
  @apply absolute top-0 left-1/2 h-0.5 w-8 -translate-x-1/2 bg-redstone-400;
  box-shadow: 0 0 8px 1px color-mix(in srgb, var(--color-redstone-500) 70%, transparent);
}
.tabbar-on svg {
  @apply text-redstone-400;
}
</style>
