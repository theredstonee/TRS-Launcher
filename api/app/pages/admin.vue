<script setup lang="ts">
import '~/assets/css/admin.css'

// Team-Bereich: Anmeldung per Code (im TRS Launcher bestätigt), danach Seitenleiste + Suche und die
// Unterseiten unter /admin/*. Alles über /v1/admin; Rechte prüft der Server, die Oberfläche blendet
// nur aus, was die Rolle nicht darf.
definePageMeta({ layout: false })
useHead({ title: 'Moderation · TRS Launcher', meta: [{ name: 'robots', content: 'noindex, nofollow' }] })

const { m, fill } = useLang()
const { a } = useAdminText()
const { session, counts, load, api, logout, isAdmin } = useAdmin()
const route = useRoute()
const router = useRouter()

const ready = ref(false)
const failure = ref('')

// --- Anmeldung ----------------------------------------------------------------------------
const code = ref<{ code: string, pollSecret: string, expiresAt: string } | null>(null)
const expired = ref(false)
const starting = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null

function stopPolling() {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = null
}

async function startLogin() {
  starting.value = true
  failure.value = ''
  expired.value = false
  try {
    code.value = await api('/v1/web-login/start', { method: 'POST' })
    stopPolling()
    pollTimer = setInterval(poll, 2000)
  } catch (e) {
    failure.value = fill(m.value.admin.failed, { error: apiMessage(e) })
  } finally {
    starting.value = false
  }
}

async function poll() {
  if (!code.value) return
  try {
    const r = await api<{ status: 'pending' | 'expired' | 'approved' }>('/v1/web-login/poll', { method: 'POST', body: { pollSecret: code.value.pollSecret } })
    if (r.status === 'approved') {
      stopPolling()
      code.value = null
      await load()
      void refreshCounts()
    } else if (r.status === 'expired') {
      stopPolling()
      expired.value = true
    }
  } catch (e) {
    stopPolling()
    failure.value = fill(m.value.admin.failed, { error: apiMessage(e) })
  }
}

// --- Zähler für die Seitenleiste ---------------------------------------------------------------
async function refreshCounts() {
  if (!session.value) return
  try {
    const d = await api<DashboardData>('/v1/admin/dashboard')
    counts.value = {
      reports: d.reports.open + d.reports.inReview,
      highPriority: d.reports.highPriority,
      appeals: d.appeals.open,
      uploads: d.uploads.capesPending + d.uploads.cosmeticsPending + d.uploads.capesReported + d.uploads.cosmeticsReported,
    }
  } catch {
    // Zähler sind nur Beiwerk.
  }
}
let countTimer: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  if (await load()) void refreshCounts()
  ready.value = true
  countTimer = setInterval(() => void refreshCounts(), 60_000)
})
onBeforeUnmount(() => {
  stopPolling()
  if (countTimer) clearInterval(countTimer)
})
watch(() => route.fullPath, () => {
  sideOpen.value = false
  void refreshCounts()
})

async function signOut() {
  await logout().catch(() => {})
  code.value = null
  void router.push('/admin')
}

// --- Navigation --------------------------------------------------------------------------------
const sideOpen = ref(false)
const nav = computed(() => [
  { to: '/admin', icon: 'home', label: a.value.nav.overview, exact: true },
  { to: '/admin/reports', icon: 'flag', label: a.value.nav.reports, count: counts.value?.reports, hot: (counts.value?.highPriority ?? 0) > 0 },
  { to: '/admin/appeals', icon: 'appeal', label: a.value.nav.appeals, count: counts.value?.appeals },
  { to: '/admin/players', icon: 'users', label: a.value.nav.players },
  { to: '/admin/sanctions', icon: 'gavel', label: a.value.nav.sanctions },
  { to: '/admin/uploads', icon: 'cape', label: a.value.nav.uploads, count: counts.value?.uploads },
  { to: '/admin/worlds', icon: 'world', label: a.value.nav.worlds },
  { to: '/admin/codes', icon: 'ticket', label: a.value.nav.codes },
  { to: '/admin/word-filter', icon: 'filter', label: a.value.nav.wordFilter },
  ...(isAdmin.value ? [{ to: '/admin/roles', icon: 'key', label: a.value.nav.roles }] : []),
  { to: '/admin/audit', icon: 'list', label: a.value.nav.audit },
])

// --- Globale Suche ---------------------------------------------------------------------------------
const q = ref('')
const searchInput = shallowRef<HTMLInputElement | null>(null)
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
    results.value = await api<SearchResult>(`/v1/admin/search?q=${encodeURIComponent(v.slice(0, 64))}`)
    searchOpen.value = true
  } catch {
    results.value = null
  } finally {
    searching.value = false
  }
}
const hits = computed(() => {
  const r = results.value
  if (!r) return []
  return [
    ...r.players.map((p) => ({ key: `p${p.uuid}`, icon: 'user', title: p.name || p.uuid, sub: p.matched ? `${p.matched} → ${p.name}` : p.uuid, to: `/admin/players/${p.uuid}` })),
    ...r.reports.map((x) => ({ key: x.id, icon: 'flag', title: `${m.value.admin.mod.reasons[x.reason]} · ${x.id}`, sub: x.target?.name ?? '', to: `/admin/reports/${x.id}` })),
    ...r.sanctions.map((x) => ({ key: `s${x.id}`, icon: 'gavel', title: `#${x.id} ${a.value.kinds[x.kind]}`, sub: x.player.name ?? x.player.uuid, to: `/admin/players/${x.player.uuid}` })),
    ...r.capes.map((c) => ({ key: `c${c.id}`, icon: 'cape', title: c.name, sub: `${c.kind} · ${c.status}${c.owner?.name ? ` · ${c.owner.name}` : ''}`, to: c.owner ? `/admin/players/${c.owner.uuid}` : `/admin/uploads?q=${encodeURIComponent(c.name)}` })),
    ...r.cosmetics.map((c) => ({ key: `k${c.id}`, icon: 'blocks', title: c.name, sub: `${c.slot} · ${c.status}${c.owner?.name ? ` · ${c.owner.name}` : ''}`, to: c.owner ? `/admin/players/${c.owner.uuid}` : `/admin/uploads?kind=cosmetics&q=${encodeURIComponent(c.name)}` })),
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

// --- Tastenkürzel ------------------------------------------------------------------------------------
const help = ref(false)
useAdminKeys({
  '/': () => searchInput.value?.focus(),
  '?': () => (help.value = true),
})
</script>

<template>
  <div class="adm-shell">
    <div v-if="!ready" class="grid min-h-dvh place-items-center">
      <div class="skeleton h-24 w-72 rounded-xl" />
    </div>

    <!-- Anmeldung -->
    <main v-else-if="!session" class="deepslate grid min-h-dvh place-items-center px-4 py-10">
      <section class="w-full max-w-lg">
        <NuxtLink to="/" class="mb-8 flex items-center gap-2.5 text-base-50">
          <img src="/icon.png" alt="" width="32" height="32" class="size-8 [image-rendering:pixelated]" />
          <span class="display text-xl">TRS Launcher</span>
        </NuxtLink>
        <h1 class="display text-5xl text-base-50">{{ a.brand }}</h1>
        <p class="mt-3 text-base-400">{{ m.admin.lead }}</p>
        <div class="card mt-8 p-6">
          <template v-if="code && !expired">
            <p class="text-xs tracking-[0.18em] text-base-400 uppercase">{{ m.admin.code }}</p>
            <p class="login-code display mt-2 select-all">{{ code.code }}</p>
            <ol class="mt-6 space-y-2 text-sm text-base-200">
              <li v-for="(s, i) in m.admin.steps" :key="i" class="flex gap-3">
                <span class="step">{{ i + 1 }}</span><span>{{ s }}</span>
              </li>
            </ol>
            <p class="mt-6 flex items-center gap-2 text-sm text-lamp-300"><span class="size-2 animate-lamp bg-lamp-400" />{{ m.admin.waiting }}</p>
          </template>
          <template v-else>
            <p v-if="expired" class="mb-4 text-sm text-lamp-300">{{ m.admin.expired }}</p>
            <button type="button" class="btn btn-primary h-12 w-full text-base" :disabled="starting" @click="startLogin">
              <SiteIcon name="user" class="size-5" />{{ expired ? m.admin.newCode : m.admin.start }}
            </button>
          </template>
        </div>
        <p v-if="failure" role="alert" class="mt-4 text-sm text-redstone-300">{{ failure }}</p>
      </section>
    </main>

    <!-- Bereich -->
    <template v-else>
      <div v-if="sideOpen" class="adm-scrim lg:hidden" @click="sideOpen = false" />
      <aside class="adm-side" :data-open="sideOpen" :aria-label="a.nav.menu">
        <div class="flex h-15 items-center gap-2.5 border-b border-base-800 px-4">
          <img src="/icon.png" alt="" width="28" height="28" class="size-7 [image-rendering:pixelated]" />
          <div class="leading-tight">
            <p class="display text-lg text-base-50">TRS</p>
            <p class="text-[11px] tracking-[0.14em] text-base-400 uppercase">{{ a.brand }}</p>
          </div>
          <button type="button" class="btn-icon ml-auto size-8 lg:hidden" :aria-label="a.common.close" @click="sideOpen = false"><SiteIcon name="close" class="size-4" /></button>
        </div>
        <nav class="flex-1 space-y-0.5 overflow-y-auto p-3">
          <NuxtLink v-for="n in nav" :key="n.to" :to="n.to" class="adm-nav-link" :class="{ 'adm-exact': n.exact }">
            <SiteIcon :name="n.icon" class="size-4.5 shrink-0" />
            <span class="truncate">{{ n.label }}</span>
            <span v-if="n.count" class="adm-nav-count" :class="{ hot: n.hot }">{{ n.count }}</span>
          </NuxtLink>
        </nav>
        <div class="border-t border-base-800 p-3">
          <div class="flex items-center gap-2.5 px-1">
            <PlayerHead v-if="session.uuid" :uuid="session.uuid" :name="session.name" :size="30" />
            <div class="min-w-0 flex-1 leading-tight">
              <p class="truncate text-sm font-semibold text-base-50">{{ session.name }}</p>
              <p class="text-xs text-base-400">{{ a.role[session.role] }}</p>
            </div>
            <button type="button" class="btn-icon size-8" :aria-label="a.common.logout" :title="a.common.logout" @click="signOut"><SiteIcon name="logout" class="size-4" /></button>
          </div>
          <div class="mt-3 flex items-center justify-between px-1">
            <LangSwitch />
            <button type="button" class="flex items-center gap-1.5 text-xs text-base-400 hover:text-base-50" @click="help = true"><SiteIcon name="keyboard" class="size-4" /><span class="adm-kbd">?</span></button>
          </div>
        </div>
      </aside>

      <div class="adm-main">
        <header class="adm-top">
          <button type="button" class="btn-icon lg:hidden" :aria-label="a.nav.menu" @click="sideOpen = true"><SiteIcon name="menu" class="size-5" /></button>
          <div class="relative w-full max-w-xl">
            <SiteIcon name="search" class="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-base-400" />
            <input
              ref="searchInput"
              v-model="q"
              type="search"
              class="field pr-10 pl-9"
              maxlength="64"
              :placeholder="a.common.search"
              :aria-label="a.common.searchShort"
              autocomplete="off"
              @focus="searchOpen = !!results"
              @blur="onSearchBlur"
              @keydown="onSearchKey"
            />
            <span class="adm-kbd pointer-events-none absolute top-1/2 right-2.5 hidden -translate-y-1/2 sm:inline-grid">/</span>
            <div v-if="searchOpen && q.trim()" class="menu top-full right-0 left-0 mt-1 max-h-[70vh] overflow-y-auto" role="listbox">
              <p v-if="searching && !hits.length" class="px-3 py-2 text-sm text-base-400">{{ a.common.loading }}</p>
              <p v-else-if="!hits.length" class="px-3 py-2 text-sm text-base-400">{{ a.common.noResults }}</p>
              <button
                v-for="(h, i) in hits"
                :key="h.key"
                type="button"
                role="option"
                :aria-selected="i === hitIndex"
                class="menu-item"
                :class="{ 'bg-base-700 text-base-50': i === hitIndex }"
                @mousedown.prevent="pick(i)"
              >
                <SiteIcon :name="h.icon" class="size-4 shrink-0 text-base-400" />
                <span class="min-w-0 flex-1">
                  <span class="block truncate">{{ h.title }}</span>
                  <span class="block truncate text-xs text-base-400">{{ h.sub }}</span>
                </span>
              </button>
            </div>
          </div>
          <span class="ml-auto hidden text-xs text-base-400 md:block">{{ fill(a.common.signedInAs, { name: session.name, role: a.role[session.role] ?? '' }) }}</span>
        </header>
        <NuxtPage />
      </div>

      <AdminDialog v-if="help" :title="a.keys.title" size="sm" @close="help = false">
        <dl class="adm-dl items-center">
          <dt><span class="adm-kbd">/</span></dt><dd>{{ a.keys.search }}</dd>
          <dt><span class="adm-kbd">j</span></dt><dd>{{ a.keys.next }}</dd>
          <dt><span class="adm-kbd">k</span></dt><dd>{{ a.keys.prev }}</dd>
          <dt><span class="adm-kbd">↵</span></dt><dd>{{ a.keys.open }}</dd>
          <dt><span class="adm-kbd">x</span></dt><dd>{{ a.keys.select }}</dd>
          <dt><span class="adm-kbd">a</span></dt><dd>{{ a.keys.accept }}</dd>
          <dt><span class="adm-kbd">r</span></dt><dd>{{ a.keys.reject }}</dd>
          <dt><span class="adm-kbd">?</span></dt><dd>{{ a.keys.help }}</dd>
          <dt><span class="adm-kbd">Esc</span></dt><dd>{{ a.keys.close }}</dd>
        </dl>
      </AdminDialog>
    </template>
  </div>
</template>

<style scoped>
.login-code {
  font-size: 3rem;
  letter-spacing: 0.12em;
  color: var(--color-lamp-300);
  text-shadow: 0 0 24px color-mix(in srgb, var(--color-lamp-400) 45%, transparent);
}
.step {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  width: 1.5rem;
  height: 1.5rem;
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--color-base-50);
  background: var(--color-redstone-600);
}
.h-15 {
  height: 3.75rem;
}
</style>
