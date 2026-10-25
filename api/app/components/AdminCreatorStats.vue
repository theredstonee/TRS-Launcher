<script setup lang="ts">
// Rangliste aller Creator (§35.14, Reiter „Statistik“ in /admin/creators): sortierbar nach jeder Kennzahl
// (Spaltenköpfe als Knöpfe mit aria-sort), Suche, Stufen- und Zustandsfilter, Summen oben. Nur Zahlen, keine Fan-Namen.
const { a, fill, num, when, day } = useAdminText()
const { api } = useAdmin()
const t = computed(() => a.value.creators.stats)
const ct = computed(() => a.value.creators.roster)

const data = ref<CreatorStatsList | null>(null)
const error = ref('')
const loading = ref(false)
const q = ref('')
const tier = ref<'' | StatTier>('')
const state = ref<'' | StatState>('active')
const sortKey = ref<StatColumn>('fans')
const sortDir = ref<'asc' | 'desc'>('desc')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const p = new URLSearchParams()
    if (q.value.trim()) p.set('q', q.value.trim().slice(0, 32))
    if (tier.value) p.set('tier', tier.value)
    p.set('state', state.value || 'all')
    data.value = await api<CreatorStatsList>(`/v1/admin/creators/stats?${p}`)
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
let timer: ReturnType<typeof setTimeout> | undefined
watch(q, () => {
  clearTimeout(timer)
  timer = setTimeout(() => void load(), 250)
})
watch([tier, state], () => void load())
onMounted(load)

const rows = computed(() => (data.value ? sortStats(data.value.creators, sortKey.value, sortDir.value) : []))
function sortBy(key: StatColumn) {
  if (sortKey.value === key) sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
  else {
    sortKey.value = key
    sortDir.value = key === 'name' ? 'asc' : 'desc'
  }
}
const ariaSort = (key: StatColumn) => (sortKey.value === key ? (sortDir.value === 'asc' ? 'ascending' : 'descending') : 'none')
const tiles = computed(() => {
  const x = data.value?.totals
  if (!x) return []
  return [
    { label: t.value.totals.creators, value: x.creators },
    { label: t.value.totals.fans, value: x.fans },
    { label: t.value.totals.fans30d, value: x.fans30d },
    { label: t.value.totals.newPlayers30d, value: x.newPlayers30d },
    { label: t.value.totals.online7d, value: x.online7d },
    { label: t.value.totals.petRedemptions, value: x.petRedemptions },
  ]
})
const stateTone = (s: StatState) => (s === 'active' ? 'tone-ok' : s === 'banned' ? 'tone-danger' : 'tone-muted')
</script>

<template>
  <section class="mt-6">
    <p class="max-w-3xl text-sm text-base-400">{{ t.lead }}</p>
    <div class="adm-toolbar mt-4">
      <input v-model="q" type="search" class="field adm-search" maxlength="32" :placeholder="t.search" :aria-label="t.search" />
      <select v-model="tier" class="field adm-select" :aria-label="ct.tier">
        <option value="">{{ t.allTiers }}</option>
        <option v-for="x in (['starter', 'partner', 'top'] as const)" :key="x" :value="x">{{ ct.tiers[x] }}</option>
      </select>
      <select v-model="state" class="field adm-select" :aria-label="t.allStates">
        <option value="">{{ t.allStates }}</option>
        <option v-for="x in (['active', 'revoked', 'banned'] as const)" :key="x" :value="x">{{ ct.states[x] }}</option>
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
    <p v-else-if="data && !rows.length" class="adm-empty mt-4"><SiteIcon name="crown" class="size-6" />{{ t.empty }}</p>
    <div v-else-if="data" class="card stats-wrap mt-4" :aria-busy="loading">
      <table class="stats-table w-full text-left text-sm">
        <caption class="sr-only">{{ t.title }}</caption>
        <thead class="text-xs text-base-400">
          <tr>
            <th scope="col" class="sticky-col" :aria-sort="ariaSort('name')">
              <button type="button" class="sort-btn" :aria-label="fill(t.sortBy, { col: t.cols.creator ?? '' })" @click="sortBy('name')">
                {{ t.cols.creator }}<span class="arrow" aria-hidden="true">{{ sortKey === 'name' ? (sortDir === 'asc' ? '▲' : '▼') : '' }}</span>
              </button>
            </th>
            <th v-for="c in STAT_COLUMNS" :key="c.key" scope="col" class="num" :aria-sort="ariaSort(c.key)">
              <button type="button" class="sort-btn" :aria-label="fill(t.sortBy, { col: t.cols[c.key] ?? c.key })" @click="sortBy(c.key)">
                <span class="arrow" aria-hidden="true">{{ sortKey === c.key ? (sortDir === 'asc' ? '▲' : '▼') : '' }}</span>{{ t.cols[c.key] }}
              </button>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(r, i) in rows" :key="r.uuid">
            <th scope="row" class="sticky-col">
              <NuxtLink :to="`/admin/creators/${r.uuid}`" class="flex items-center gap-2.5 hover:underline">
                <span class="w-5 text-right text-xs text-base-500 tabular-nums">{{ i + 1 }}</span>
                <PlayerHead :uuid="r.uuid" :name="r.name" :size="24" />
                <span class="min-w-0">
                  <span class="block truncate font-semibold text-base-50">{{ r.name || r.uuid }}</span>
                  <span class="flex items-center gap-1.5 text-[11px] font-normal text-base-400">
                    {{ ct.tiers[r.tier] }}
                    <span v-if="r.state !== 'active'" class="tone" :class="stateTone(r.state)">{{ ct.states[r.state] }}</span>
                    <span v-else>· {{ day(r.since) }}</span>
                  </span>
                </span>
              </NuxtLink>
            </th>
            <td v-for="c in STAT_COLUMNS" :key="c.key" class="num tabular-nums" :class="{ zero: c.get(r) === 0, active: sortKey === c.key }">{{ num(c.get(r)) }}</td>
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
