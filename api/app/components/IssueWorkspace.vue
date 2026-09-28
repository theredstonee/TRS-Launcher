<script setup lang="ts">
// Rahmen für alle Issue-Seiten (§28): Seitenleiste „Workspace“ links (einklappbar, Zustand im Browser gemerkt) mit
// Alle Issues, Roadmap, Meine Issues, Gefolgt und – fürs Team – der Team-Liste, jeweils mit Zähler. Auf schmalen
// Bildschirmen wird die Leiste zum Menü (Knopf oben).
import type { IssueSummary } from '#shared/issues'

const props = defineProps<{ active: 'issues' | 'roadmap' | 'mine' | 'following' | 'issue' }>()
const { it, fill } = useIssueText()
const lp = useLocalePath()
const route = useRoute()
const { account, load } = useAccount()

const { data: summary, refresh } = await useAsyncData<IssueSummary | null>(
  'issue-summary',
  () => $fetch<IssueSummary>('/v1/issues/summary', { credentials: 'same-origin' }).catch(() => null),
)
onMounted(async () => {
  if (await load()) void refresh()
})

const KEY = 'trs.issues.sidebar'
const collapsed = ref(false)
const drawer = ref(false)
onMounted(() => {
  try {
    collapsed.value = localStorage.getItem(KEY) === 'collapsed'
  } catch {
    // Speicher gesperrt – dann eben ausgeklappt.
  }
})
function toggle() {
  collapsed.value = !collapsed.value
  try {
    localStorage.setItem(KEY, collapsed.value ? 'collapsed' : 'open')
  } catch {
    // egal
  }
}
watch(() => route.fullPath, () => (drawer.value = false))
function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && drawer.value) drawer.value = false
}
onMounted(() => document.addEventListener('keydown', onKey))
onBeforeUnmount(() => document.removeEventListener('keydown', onKey))

const isTeam = computed(() => !!account.value?.team?.permissions.some((p) => p === 'issues.manage' || p === 'issues.moderate'))
const items = computed(() => {
  const s = summary.value
  const list = [
    { id: 'issues', to: lp('/issues'), icon: 'list', label: it.value.ws.issues, count: s?.open ?? null },
    { id: 'roadmap', to: lp('/roadmap'), icon: 'roadmap', label: it.value.ws.roadmap, count: s ? s.byStatus.planned + s.byStatus.in_progress + s.byStatus.in_review : null },
  ]
  if (account.value) {
    list.push({ id: 'mine', to: lp('/issues/mine?tab=created'), icon: 'user', label: it.value.ws.mine, count: s?.mine ?? null })
    list.push({ id: 'following', to: lp('/issues/mine?tab=following'), icon: 'bell', label: it.value.ws.following, count: s?.following ?? null })
  }
  return list
})
</script>

<template>
  <div class="ws" :class="{ collapsed }">
    <aside class="ws-side" :aria-label="it.ws.workspace">
      <div class="ws-head">
        <span v-if="!collapsed" class="ws-label">{{ it.ws.workspace }}</span>
        <button type="button" class="ws-collapse" :aria-label="collapsed ? it.ws.expand : it.ws.collapse" :title="collapsed ? it.ws.expand : it.ws.collapse" :aria-expanded="!collapsed" @click="toggle">
          <SiteIcon name="back" class="size-4" :class="{ 'rotate-180': collapsed }" />
        </button>
      </div>
      <NuxtLink :to="lp('/issues/new')" class="btn btn-primary ws-new" :title="it.list.newIssue" data-testid="ws-new">
        <SiteIcon name="plus" class="size-4 shrink-0" /><span v-if="!collapsed">{{ it.list.newIssue }}</span>
      </NuxtLink>
      <nav class="mt-3 grid gap-0.5">
        <NuxtLink
          v-for="i in items"
          :key="i.id"
          :to="i.to"
          class="ws-link"
          :aria-current="props.active === i.id ? 'page' : undefined"
          :title="collapsed ? i.label : undefined"
        >
          <SiteIcon :name="i.icon" class="size-4.5 shrink-0" />
          <span v-if="!collapsed" class="min-w-0 flex-1 truncate">{{ i.label }}</span>
          <span v-if="!collapsed && i.count !== null" class="ws-count">{{ i.count }}</span>
        </NuxtLink>
      </nav>
      <template v-if="isTeam">
        <p v-if="!collapsed" class="ws-label mt-5 px-3">{{ it.team.title }}</p>
        <div v-else class="mx-3 my-3 border-t border-base-800" />
        <NuxtLink to="/admin/issues" class="ws-link" :title="collapsed ? it.ws.team : undefined">
          <SiteIcon name="shield" class="size-4.5 shrink-0 text-redstone-400" />
          <span v-if="!collapsed" class="min-w-0 flex-1 truncate">{{ it.ws.team }}</span>
          <span v-if="!collapsed && summary?.team" class="ws-count hot">{{ summary.team.new }}</span>
        </NuxtLink>
      </template>
    </aside>

    <div class="ws-main">
      <button type="button" class="ws-menu" :aria-expanded="drawer" aria-controls="ws-drawer" @click="drawer = true">
        <SiteIcon name="menu" class="size-4" />{{ it.ws.menu }}
      </button>
      <slot :refresh-summary="refresh" />
    </div>

    <Transition name="fade">
      <div v-if="drawer" class="ws-scrim" @click="drawer = false" />
    </Transition>
    <Transition name="slide">
      <nav v-if="drawer" id="ws-drawer" class="ws-drawer" :aria-label="it.ws.workspace">
        <div class="flex items-center justify-between">
          <span class="ws-label">{{ it.ws.workspace }}</span>
          <button type="button" class="btn-icon" :aria-label="it.modal.close" @click="drawer = false"><SiteIcon name="close" class="size-4" /></button>
        </div>
        <NuxtLink :to="lp('/issues/new')" class="btn btn-primary mt-3 w-full"><SiteIcon name="plus" class="size-4" />{{ it.list.newIssue }}</NuxtLink>
        <div class="mt-3 grid gap-0.5">
          <NuxtLink v-for="i in items" :key="i.id" :to="i.to" class="ws-link" :aria-current="props.active === i.id ? 'page' : undefined">
            <SiteIcon :name="i.icon" class="size-4.5 shrink-0" />
            <span class="min-w-0 flex-1 truncate">{{ i.label }}</span>
            <span v-if="i.count !== null" class="ws-count">{{ i.count }}</span>
          </NuxtLink>
          <NuxtLink v-if="isTeam" to="/admin/issues" class="ws-link">
            <SiteIcon name="shield" class="size-4.5 shrink-0 text-redstone-400" />
            <span class="min-w-0 flex-1 truncate">{{ it.ws.team }}</span>
            <span v-if="summary?.team" class="ws-count hot">{{ summary.team.new }}</span>
          </NuxtLink>
        </div>
        <p v-if="summary" class="mt-4 text-xs text-base-400">{{ summary.total === 1 ? it.ws.count1 : fill(it.ws.count, { n: summary.total }) }}</p>
      </nav>
    </Transition>
  </div>
</template>

<style scoped>
.ws {
  display: grid;
  grid-template-columns: 15rem minmax(0, 1fr);
  min-height: calc(100vh - 4rem);
}
.ws.collapsed {
  grid-template-columns: 4rem minmax(0, 1fr);
}
.ws-side {
  position: sticky;
  top: 4rem;
  align-self: start;
  height: calc(100vh - 4rem);
  overflow-y: auto;
  padding: 1rem 0.75rem;
  border-right: 1px solid var(--color-base-800);
  background: color-mix(in srgb, var(--color-base-900) 55%, transparent);
}
.ws-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 0.25rem 0.75rem 0.5rem;
}
.collapsed .ws-head {
  justify-content: center;
  padding-left: 0.25rem;
}
.ws-label {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--color-base-400);
}
.ws-collapse {
  display: grid;
  place-items: center;
  width: 1.75rem;
  height: 1.75rem;
  border-radius: 0.375rem;
  color: var(--color-base-400);
  border: 1px solid var(--color-base-800);
}
.ws-collapse:hover {
  color: var(--color-base-50);
  background: var(--color-base-800);
}
.ws-new {
  width: 100%;
}
.collapsed .ws-new {
  padding-inline: 0;
}
.ws-link {
  position: relative;
  display: flex;
  align-items: center;
  gap: 0.65rem;
  padding: 0.5rem 0.75rem;
  border-radius: 0.45rem;
  font-size: 0.875rem;
  color: var(--color-base-200);
  transition: background-color 0.12s, color 0.12s;
}
.collapsed .ws-link {
  justify-content: center;
  padding-inline: 0;
}
.ws-link:hover {
  background: var(--color-base-800);
  color: var(--color-base-50);
}
.ws-link[aria-current='page'] {
  background: color-mix(in srgb, var(--color-redstone-500) 14%, transparent);
  color: var(--color-base-50);
}
.ws-link[aria-current='page']::before {
  content: '';
  position: absolute;
  left: -0.75rem;
  top: 0.4rem;
  bottom: 0.4rem;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--color-redstone-500);
  box-shadow: 0 0 8px var(--color-redstone-500);
}
.ws-count {
  min-width: 1.5rem;
  padding: 0 0.4rem;
  border-radius: 999px;
  background: var(--color-base-800);
  font-size: 11px;
  line-height: 1.25rem;
  text-align: center;
  color: var(--color-base-200);
  font-variant-numeric: tabular-nums;
}
.ws-count.hot {
  background: color-mix(in srgb, var(--color-lamp-400) 20%, transparent);
  color: var(--color-lamp-300);
}
.ws-main {
  min-width: 0;
}
.ws-menu {
  display: none;
}
.ws-scrim {
  position: fixed;
  inset: 0;
  z-index: 40;
  background: rgb(0 0 0 / 0.55);
}
.ws-drawer {
  position: fixed;
  top: 0;
  bottom: 0;
  left: 0;
  z-index: 41;
  width: min(18rem, 85vw);
  padding: 1rem;
  overflow-y: auto;
  background: var(--color-base-900);
  border-right: 1px solid var(--color-base-800);
}
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
.slide-enter-active,
.slide-leave-active {
  transition: transform 0.18s ease-out;
}
.slide-enter-from,
.slide-leave-to {
  transform: translateX(-100%);
}
@media (max-width: 1023px) {
  .ws,
  .ws.collapsed {
    grid-template-columns: minmax(0, 1fr);
  }
  .ws-side {
    display: none;
  }
  .ws-menu {
    display: inline-flex;
    align-items: center;
    gap: 0.5rem;
    margin: 1rem 1rem 0;
    padding: 0.4rem 0.75rem;
    border-radius: 0.45rem;
    border: 1px solid var(--color-base-800);
    font-size: 0.8rem;
    font-weight: 600;
    color: var(--color-base-200);
  }
}
@media (prefers-reduced-motion: reduce) {
  .slide-enter-active,
  .slide-leave-active,
  .fade-enter-active,
  .fade-leave-active {
    transition: none;
  }
}
</style>
