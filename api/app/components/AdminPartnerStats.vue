<script setup lang="ts">
// Rangliste aller Partner (Reiter „Statistik“ in /admin/partners): sortierbar nach jeder Kennzahl (Spaltenköpfe als
// Knöpfe mit aria-sort), Suche, Zustand und Kategorie, Summen oben. Nur Zahlen, keine Spielernamen.
const { api } = useAdmin()
const { p } = usePartnerText()
const { fill, num, when, day } = useAdminText()
const t = computed(() => p.value.admin.stats)

const data = ref<PartnerStatsList | null>(null)
const error = ref('')
const loading = ref(false)
const q = ref('')
const state = ref<'' | 'active' | 'paused'>('')
const category = ref<'' | PartnerCategory>('')
const sortKey = ref<PartnerStatColumn>('supporters')
const sortDir = ref<'asc' | 'desc'>('desc')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const qs = new URLSearchParams()
    if (q.value.trim()) qs.set('q', q.value.trim().slice(0, 40))
    qs.set('state', state.value || 'all')
    if (category.value) qs.set('category', category.value)
    data.value = await api<PartnerStatsList>(`/v1/admin/partners/stats?${qs}`)
  } catch (e) {
    error.value = fill(p.value.admin.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
let timer: ReturnType<typeof setTimeout> | undefined
watch(q, () => {
  clearTimeout(timer)
  timer = setTimeout(() => void load(), 250)
})
watch([state, category], () => void load())
onMounted(load)

const rows = computed(() => (data.value ? sortPartnerStats(data.value.partners, sortKey.value, sortDir.value) : []))
function sortBy(key: PartnerStatColumn) {
  if (sortKey.value === key) sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
  else {
    sortKey.value = key
    sortDir.value = key === 'name' ? 'asc' : 'desc'
  }
}
const ariaSort = (key: PartnerStatColumn) => (sortKey.value === key ? (sortDir.value === 'asc' ? 'ascending' : 'descending') : 'none')
const tiles = computed(() => {
  const x = data.value?.totals
  if (!x) return []
  return (['partners', 'supporters', 'supporters30d', 'redemptions30d', 'newPlayers30d', 'downloads30d'] as const).map((k) => ({ label: t.value.totals[k], value: x[k] }))
})
</script>

<template>
  <section class="mt-6">
    <p class="max-w-3xl text-sm text-base-400">{{ t.lead }}</p>
    <div class="adm-toolbar mt-4">
      <input v-model="q" type="search" class="field adm-search" maxlength="40" :placeholder="t.search" :aria-label="t.search" />
      <select v-model="state" class="field adm-select" :aria-label="t.allStates">
        <option value="">{{ t.allStates }}</option>
        <option v-for="x in (['active', 'paused'] as const)" :key="x" :value="x">{{ p.admin.state[x] }}</option>
      </select>
      <select v-model="category" class="field adm-select" :aria-label="t.allCategories">
        <option value="">{{ t.allCategories }}</option>
        <option v-for="x in PARTNER_CATEGORIES" :key="x" :value="x">{{ p.category[x] }}</option>
      </select>
      <span v-if="data" class="ml-auto text-xs text-base-400">{{ fill(t.generated, { time: when(data.generatedAt) }) }}</span>
    </div>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>

    <ul v-if="tiles.length" class="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
      <li v-for="x in tiles" :key="x.label" class="card p-4">
        <p class="display text-2xl text-base-50 tabular-nums">{{ num(x.value) }}</p>
        <p class="adm-stat-label mt-1">{{ x.label }}</p>
      </li>
    </ul>

    <div v-if="loading && !data" class="skeleton mt-4 h-64 rounded-xl" />
    <p v-else-if="data && !rows.length" class="adm-empty mt-4"><SiteIcon name="hand" class="size-6" />{{ t.empty }}</p>
    <div v-else-if="data" class="card stats-wrap mt-4" :aria-busy="loading">
      <table class="stats-table w-full text-left text-sm">
        <caption class="sr-only">{{ p.admin.tabs.stats }}</caption>
        <thead class="text-xs text-base-400">
          <tr>
            <th scope="col" class="sticky-col" :aria-sort="ariaSort('name')">
              <button type="button" class="sort-btn" :aria-label="fill(t.sortBy, { col: t.cols.partner ?? '' })" @click="sortBy('name')">
                {{ t.cols.partner }}<span class="arrow" aria-hidden="true">{{ sortKey === 'name' ? (sortDir === 'asc' ? '▲' : '▼') : '' }}</span>
              </button>
            </th>
            <th v-for="c in PARTNER_STAT_COLUMNS" :key="c.key" scope="col" class="num" :aria-sort="ariaSort(c.key)">
              <button type="button" class="sort-btn" :aria-label="fill(t.sortBy, { col: t.cols[c.key] ?? c.key })" @click="sortBy(c.key)">
                <span class="arrow" aria-hidden="true">{{ sortKey === c.key ? (sortDir === 'asc' ? '▲' : '▼') : '' }}</span>{{ t.cols[c.key] }}
              </button>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(r, i) in rows" :key="r.slug">
            <th scope="row" class="sticky-col">
              <NuxtLink :to="`/admin/partners/${r.slug}`" class="flex items-center gap-2.5 hover:underline">
                <span class="w-5 text-right text-xs text-base-500 tabular-nums">{{ i + 1 }}</span>
                <PartnerLogo :logo="r.logo" :name="r.name" :size="24" />
                <span class="min-w-0">
                  <span class="block truncate font-semibold text-base-50">{{ r.name }}</span>
                  <span class="flex items-center gap-1.5 text-[11px] font-normal text-base-400">
                    {{ p.category[r.category] }}
                    <span v-if="r.state !== 'active'" class="tone tone-muted">{{ p.admin.state[r.state] }}</span>
                    <span v-else>· {{ day(r.since) }}</span>
                  </span>
                </span>
              </NuxtLink>
            </th>
            <td v-for="c in PARTNER_STAT_COLUMNS" :key="c.key" class="num tabular-nums" :class="{ zero: c.get(r) === 0, active: sortKey === c.key }">{{ num(c.get(r)) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <details class="card mt-4 p-4 text-sm text-base-300">
      <summary class="cursor-pointer font-semibold text-base-100">{{ t.defsTitle }}</summary>
      <ul class="mt-2 list-disc space-y-1 pl-5 text-xs text-base-400">
        <li>{{ fill(t.defNewPlayers, { h: data?.definitions.newPlayerWindowHours ?? 24 }) }}</li>
        <li>{{ t.defActivity }}</li>
        <li>{{ data?.definitions.activitySince ? fill(t.defSince, { date: day(`${data.definitions.activitySince}T12:00:00Z`) }) : t.defNotYet }}</li>
      </ul>
    </details>
  </section>
</template>

<style scoped>
.stats-wrap {
  overflow-x: auto;
}
.stats-table th,
.stats-table td {
  padding: 0.5rem 0.6rem;
  white-space: nowrap;
}
.stats-table thead th {
  border-bottom: 1px solid var(--color-base-800);
  font-weight: 600;
}
.stats-table tbody tr + tr > * {
  border-top: 1px solid var(--color-base-800);
}
.stats-table tbody tr:hover > * {
  background: var(--color-base-850);
}
.stats-table .num {
  text-align: right;
}
.stats-table td.zero {
  color: var(--color-base-600);
}
.stats-table td.active {
  color: var(--color-base-50);
  font-weight: 600;
}
.sticky-col {
  position: sticky;
  left: 0;
  z-index: 1;
  background: var(--color-base-900);
  min-width: 13rem;
  max-width: 16rem;
}
.sort-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.2rem;
  border-radius: 0.375rem;
  padding: 0.1rem 0;
  font-weight: 600;
}
.sort-btn:hover {
  color: var(--color-base-50);
}
.sort-btn:focus-visible {
  outline: 2px solid var(--color-redstone-400);
  outline-offset: 2px;
}
.arrow {
  display: inline-block;
  width: 0.7rem;
  font-size: 0.6rem;
  color: var(--color-redstone-400);
}
</style>
