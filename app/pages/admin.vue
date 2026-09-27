<script setup lang="ts">
import type { IconName } from '~/utils/icons'
import { kindLabel } from '~/utils/sanctions'
import type { SearchResult } from '~/utils/team'
import {
  WEBSITE_LOGIN_URL,
  canSearch,
  landingPath,
  mayOpen,
  roleLabel,
  visibleSections,
  websiteSections,
  type TeamSection,
} from '~/utils/teamAccess'

// Team-Bereich (Moderation v2, API §22; Rechte §24.2): Seitenleiste mit den
// Bereichen und Zählern, globale Suche (`/`), Hilfe (`?`) und die Unterseiten
// unter /admin/*. Jede Team-Rolle sieht ihn – Bereiche und Knöpfe nach
// `me.team.permissions`; Rollen, Bewerbungen und Stellen öffnen die Website.
// Der Server prüft jede Anfrage selbst.
const trs = useTrsStore()
const team = useTeam()
const route = useRoute()
const router = useRouter()
const toasts = useToasts()

const sections = computed(() => visibleSections(team.team.value))
const siteSections = computed(() => visibleSections(team.team.value, websiteSections))
const allowed = computed(() => mayOpen(team.team.value, route.path))
const searchable = computed(() => canSearch(team.team.value))

function countOf(s: TeamSection): number | undefined {
  return s.count ? team.counts.value?.[s.count] : undefined
}
function hot(s: TeamSection): boolean {
  return s.count === 'reports' && (team.counts.value?.highPriority ?? 0) > 0
}
function isActive(s: TeamSection) {
  return s.exact ? route.path === s.to : route.path.startsWith(s.to)
}
function openSite(url: string) {
  void backend.openExternalUrl(url).catch((e) => toasts.error(e))
}

/** Ohne Übersicht landet /admin im ersten erlaubten Bereich. */
function landOnAllowed() {
  if (!trs.isStaff || (route.path !== '/admin' && route.path !== '/admin/') || mayOpen(team.team.value, '/admin')) return
  const next = landingPath(team.team.value)
  if (next && next !== '/admin') void router.replace(next)
}

// --- Globale Suche ----------------------------------------------------------------------
const q = ref('')
const searchInput = useTemplateRef<HTMLInputElement>('searchInput')
const results = ref<SearchResult | null>(null)
const searchOpen = ref(false)
const searching = ref(false)
let searchTimer: ReturnType<typeof setTimeout> | null = null

watch(q, (v) => {
  if (searchTimer) clearTimeout(searchTimer)
  if (!v.trim()) {
    results.value = null
    return
  }
  searchTimer = setTimeout(() => void runSearch(), 250)
})

async function runSearch() {
  const v = q.value.trim()
  if (!v) return
  searching.value = true
  try {
    results.value = await backend.team.search(v.slice(0, 64))
    searchOpen.value = true
  } catch {
    results.value = null
  } finally {
    searching.value = false
  }
}

interface Hit {
  key: string
  icon: IconName
  title: string
  sub: string
  to: string
}

const hits = computed<Hit[]>(() => {
  const r = results.value
  if (!r) return []
  return [
    ...r.players.map((p) => ({
      key: `p${p.uuid}`,
      icon: 'user' as IconName,
      title: p.name ?? p.uuid,
      sub: p.matched ? t('team.search.formerName', { name: p.matched }) : p.role ? t(`team.roles.${p.role}`) : p.uuid,
      to: `/admin/players/${p.uuid}`,
    })),
    ...r.reports.map((x) => ({ key: x.id, icon: 'flag' as IconName, title: `${x.id}`, sub: x.target?.name ?? '', to: `/admin/reports?open=${x.id}` })),
    ...r.sanctions.map((x) => ({
      key: `s${x.id}`,
      icon: 'gavel' as IconName,
      title: `#${x.id} ${kindLabel(x.kind)}`,
      sub: x.player.name ?? x.player.uuid,
      to: `/admin/players/${x.player.uuid}`,
    })),
    ...[...r.capes, ...r.cosmetics].map((c) => ({
      key: `c${c.id}`,
      icon: 'skins' as IconName,
      title: c.name || c.id,
      sub: [c.status, c.owner?.name].filter(Boolean).join(' · '),
      to: c.owner ? `/admin/players/${c.owner.uuid}` : '/admin/uploads',
    })),
  ]
})
const hitIndex = ref(0)
watch(hits, () => (hitIndex.value = 0))

function pick(i = hitIndex.value) {
  const h = hits.value[i]
  if (!h) return
  searchOpen.value = false
  q.value = ''
  searchInput.value?.blur()
  void router.push(h.to)
}
function onSearchBlur() {
  setTimeout(() => (searchOpen.value = false), 150)
}
function onSearchKey(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    hitIndex.value = Math.min(hits.value.length - 1, hitIndex.value + 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    hitIndex.value = Math.max(0, hitIndex.value - 1)
  } else if (e.key === 'Enter') {
    e.preventDefault()
    pick()
  } else if (e.key === 'Escape') {
    searchOpen.value = false
    searchInput.value?.blur()
  }
}

// --- Tastenkürzel, Zähler ---------------------------------------------------------------
const help = ref(false)
useAdminKeys({
  '/': () => searchInput.value?.focus(),
  '?': () => (help.value = true),
})

let countTimer: ReturnType<typeof setInterval> | null = null
onMounted(async () => {
  if (!trs.status) await trs.init()
  landOnAllowed()
  if (trs.isStaff) void team.refreshCounts()
  countTimer = setInterval(() => {
    if (trs.isStaff && document.visibilityState === 'visible') void team.refreshCounts()
  }, 60_000)
})
onBeforeUnmount(() => {
  if (countTimer) clearInterval(countTimer)
})
watch(() => route.fullPath, () => {
  landOnAllowed()
  if (trs.isStaff) void team.refreshCounts()
})
watch(() => trs.team, () => {
  landOnAllowed()
  if (trs.isStaff) void team.refreshCounts()
})
</script>

<template>
  <div class="mx-auto max-w-[96rem] p-6" data-testid="admin-shell">
    <header class="mb-5 flex flex-wrap items-center gap-3">
      <div class="min-w-0 flex-1">
        <h1 class="display text-3xl leading-none text-base-50">{{ t('team.title') }}</h1>
        <p class="mt-1 text-sm text-base-400">
          {{ t('team.subtitle') }}
          <span
            v-if="team.role.value"
            class="badge ml-1"
            :style="{ backgroundColor: `${team.role.value.color}26`, color: team.role.value.color }"
            data-testid="admin-role"
          >{{ roleLabel(team.role.value) }}</span>
        </p>
      </div>
      <div v-if="trs.isStaff && searchable" class="relative w-full max-w-md">
        <SocialIcon name="search" class="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-base-400" />
        <input
          ref="searchInput"
          v-model="q"
          class="field py-2 pr-10 pl-9"
          :placeholder="t('team.search.placeholder')"
          :aria-label="t('team.search.label')"
          maxlength="64"
          data-testid="admin-search"
          @focus="searchOpen = !!results"
          @blur="onSearchBlur"
          @keydown="onSearchKey"
        />
        <kbd class="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 rounded border border-base-700 px-1.5 font-mono text-[10px] text-base-400">/</kbd>
        <div v-if="searchOpen && q.trim()" class="menu top-full right-0 left-0 mt-1 max-h-96 overflow-y-auto" data-testid="admin-search-results">
          <p v-if="searching && !hits.length" class="px-3 py-2 text-xs text-base-400">{{ t('team.common.loading') }}</p>
          <p v-else-if="!hits.length" class="px-3 py-2 text-xs text-base-400">{{ t('team.search.none') }}</p>
          <button
            v-for="(h, i) in hits"
            :key="h.key"
            class="menu-item"
            :class="{ 'bg-base-700 text-base-50': i === hitIndex }"
            @mousedown.prevent="pick(i)"
            @mouseenter="hitIndex = i"
          >
            <SocialIcon :name="h.icon" class="size-4 shrink-0 text-base-400" />
            <span class="min-w-0 flex-1">
              <span class="block truncate text-sm text-base-50">{{ h.title }}</span>
              <span class="block truncate text-[11px] text-base-400">{{ h.sub }}</span>
            </span>
          </button>
        </div>
      </div>
      <button v-if="trs.isStaff" class="btn-icon" :title="t('team.keys.title')" :aria-label="t('team.keys.title')" @click="help = true">?</button>
      <button
        v-if="trs.isStaff"
        class="btn btn-ghost px-3 py-2 text-xs"
        :title="t('websiteLogin.hint')"
        data-testid="admin-website-login"
        @click="openSite(WEBSITE_LOGIN_URL)"
      >
        {{ t('websiteLogin.button') }} <SocialIcon name="external" class="size-3.5" />
      </button>
    </header>

    <div v-if="!trs.isStaff" class="card px-4 py-6 text-center text-sm text-base-400">{{ t('team.notStaff') }}</div>

    <div v-else class="flex flex-col gap-6 lg:flex-row">
      <nav class="flex shrink-0 gap-1 overflow-x-auto lg:w-52 lg:flex-col lg:overflow-visible" :aria-label="t('team.nav.label')">
        <NuxtLink
          v-for="s in sections"
          :key="s.to"
          :to="s.to"
          class="section-link"
          :class="{ 'section-on': isActive(s) }"
          :aria-current="isActive(s) ? 'page' : undefined"
          :data-testid="`admin-nav-${s.to.split('/')[2] ?? 'overview'}`"
        >
          <SocialIcon :name="s.icon" class="size-4 shrink-0" />
          <span class="flex-1 truncate">{{ t(s.label) }}</span>
          <span v-if="countOf(s)" class="rounded-full px-1.5 text-[10px] font-bold tabular-nums" :class="hot(s) ? 'bg-redstone-500 text-white' : 'bg-base-700 text-base-50'">
            {{ countOf(s) }}
          </span>
        </NuxtLink>
        <template v-if="siteSections.length">
          <p class="mt-3 hidden px-3 pb-1 text-[10px] font-semibold tracking-wide text-base-600 uppercase lg:block">{{ t('team.nav.website') }}</p>
          <button
            v-for="s in siteSections"
            :key="s.to"
            class="section-link text-left"
            :title="t('team.nav.websiteHint')"
            :data-testid="`admin-site-${s.to.split('/').pop()}`"
            @click="openSite(s.to)"
          >
            <SocialIcon :name="s.icon" class="size-4 shrink-0" />
            <span class="flex-1 truncate">{{ t(s.label) }}</span>
            <span v-if="countOf(s)" class="rounded-full bg-base-700 px-1.5 text-[10px] font-bold text-base-50 tabular-nums">{{ countOf(s) }}</span>
            <SocialIcon name="external" class="size-3.5 shrink-0 text-base-600" />
          </button>
        </template>
        <p class="mt-4 hidden px-3 text-[11px] leading-snug text-base-600 lg:block">{{ t('team.nav.keysHint') }}</p>
      </nav>
      <div class="min-w-0 flex-1">
        <NuxtPage v-if="allowed" />
        <div v-else class="card px-4 py-6 text-center text-sm text-base-400" data-testid="admin-no-permission">
          {{ sections.length ? t('team.common.noPermission') : t('team.common.nothingAllowed') }}
        </div>
      </div>
    </div>

    <AdminHelp v-if="help" @close="help = false" />
  </div>
</template>

<style scoped>
.section-link {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  white-space: nowrap;
  border-radius: 0.5rem;
  padding: 0.5rem 0.75rem;
  font-size: 0.8125rem;
  color: var(--color-base-400);
  transition: background-color 0.15s, color 0.15s;
}
.section-link:hover {
  background: var(--color-base-850);
  color: var(--color-base-50);
}
.section-on {
  background: var(--color-base-800);
  color: var(--color-base-50);
  box-shadow: inset 2px 0 0 var(--color-redstone-500);
}
</style>
