<script setup lang="ts">
import { creatorTiers } from '~/utils/creator'
import {
  filterRows,
  nextSort,
  sortRows,
  type CreatorStatRow,
  type CreatorStatsList,
  type SortOrder,
  type StatSortKey,
} from '~/utils/creatorStats'

// Creator-Statistik (API §35.14, `creators.manage`): Rangliste aller Creator –
// Fans (gesamt, neu heute/7/30 Tage), neue Spieler, Haustier-Codes und aktive
// Fans. Die Liste kommt einmal vom Server (bis 60 s zwischengespeichert);
// Suche, Filter und Sortierung laufen hier ohne neue Anfrage. Zeile → Verlauf.
const toasts = useToasts()
const route = useRoute()
const router = useRouter()

const data = ref<CreatorStatsList | null>(null)
const error = ref<string | null>(null)
const loading = ref(false)
async function load() {
  loading.value = true
  error.value = null
  try {
    data.value = await backend.creator.adminStats({ state: 'all', limit: 500 })
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)

// Filter + Sortierung stehen in der Adresse (Zurück aus dem Verlauf behält sie).
const qs = (k: string) => (typeof route.query[k] === 'string' ? (route.query[k] as string) : '')
const q = ref(qs('q'))
const tier = ref(qs('tier') || 'all')
const state = ref(qs('state') || 'all')
const sort = ref<{ key: StatSortKey; order: SortOrder }>({ key: (qs('sort') as StatSortKey) || 'fans', order: (qs('order') as SortOrder) || 'desc' })
watch([q, tier, state, sort], () => {
  void router.replace({
    query: {
      ...(q.value.trim() ? { q: q.value.trim() } : {}),
      ...(tier.value !== 'all' ? { tier: tier.value } : {}),
      ...(state.value !== 'all' ? { state: state.value } : {}),
      ...(sort.value.key !== 'fans' || sort.value.order !== 'desc' ? { sort: sort.value.key, order: sort.value.order } : {}),
    },
  })
}, { deep: true })

const rows = computed(() => {
  const list = data.value?.creators ?? []
  return sortRows(filterRows(list, { q: q.value, tier: tier.value, state: state.value }), sort.value.key, sort.value.order)
})

const totals = computed(() => {
  const r = rows.value
  const sum = (f: (x: CreatorStatRow) => number) => r.reduce((s, x) => s + f(x), 0)
  return [
    { key: 'creators', value: r.length },
    { key: 'fans', value: sum((x) => x.fans.total) },
    { key: 'fans7d', value: sum((x) => x.fans.d7) },
    { key: 'newPlayers30d', value: sum((x) => x.newPlayers.d30) },
    { key: 'petRedemptions', value: sum((x) => x.pets.redemptions) },
    { key: 'online7d', value: sum((x) => x.activity.online7d) },
  ] as const
})

interface Column {
  key: StatSortKey
  group: 'fans' | 'newPlayers' | 'pets' | 'activity' | null
  value: (r: CreatorStatRow) => number
  /** Erste Spalte einer Gruppe bekommt eine Trennlinie. */
  first?: boolean
}
const columns: Column[] = [
  { key: 'fans', group: 'fans', value: (r) => r.fans.total, first: true },
  { key: 'fansToday', group: 'fans', value: (r) => r.fans.today },
  { key: 'fans7d', group: 'fans', value: (r) => r.fans.d7 },
  { key: 'fans30d', group: 'fans', value: (r) => r.fans.d30 },
  { key: 'newPlayers', group: 'newPlayers', value: (r) => r.newPlayers.total, first: true },
  { key: 'newPlayers7d', group: 'newPlayers', value: (r) => r.newPlayers.d7 },
  { key: 'newPlayers30d', group: 'newPlayers', value: (r) => r.newPlayers.d30 },
  { key: 'petCodes', group: 'pets', value: (r) => r.pets.codes, first: true },
  { key: 'petRedemptions', group: 'pets', value: (r) => r.pets.redemptions },
  { key: 'online7d', group: 'activity', value: (r) => r.activity.online7d, first: true },
  { key: 'online30d', group: 'activity', value: (r) => r.activity.online30d },
  { key: 'played7d', group: 'activity', value: (r) => r.activity.played7d },
  { key: 'played30d', group: 'activity', value: (r) => r.activity.played30d },
]
const groups = [
  { id: 'fans', span: 4 },
  { id: 'newPlayers', span: 3 },
  { id: 'pets', span: 3 },
  { id: 'activity', span: 4 },
] as const

function ariaSort(key: StatSortKey): 'ascending' | 'descending' | 'none' {
  return sort.value.key === key ? (sort.value.order === 'asc' ? 'ascending' : 'descending') : 'none'
}
function sortBy(key: StatSortKey) {
  sort.value = nextSort(sort.value, key)
}
function sortIcon(key: StatSortKey): string {
  if (sort.value.key !== key) return '↕'
  return sort.value.order === 'asc' ? '↑' : '↓'
}

function open(r: CreatorStatRow) {
  void navigateTo(`/admin/creator-stats/${r.uuid}`)
}

const stateClass = { active: 'bg-ok/15 text-ok', revoked: 'bg-base-800 text-base-400', banned: 'bg-redstone-900 text-redstone-300' } as const
watch(error, (e) => e && data.value && toasts.error(e))
</script>

<template>
  <section class="space-y-4" :aria-label="t('team.creatorStats.title')" data-testid="admin-creator-stats">
    <div class="flex flex-wrap items-start gap-3">
      <div class="min-w-0 flex-1">
        <h2 class="display text-2xl leading-none text-base-50">{{ t('team.creatorStats.title') }}</h2>
        <p class="mt-1 text-xs text-base-400">{{ t('team.creatorStats.intro') }}</p>
      </div>
      <button class="btn btn-ghost px-3 py-1.5 text-xs" :disabled="loading" @click="load">
        <SocialIcon name="sync" class="size-4" :class="{ 'animate-spin': loading }" />{{ t('creator.refresh') }}
      </button>
    </div>

    <!-- Summen der gefilterten Liste -->
    <dl class="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
      <div v-for="tile in totals" :key="tile.key" class="card px-4 py-3">
        <dd class="display text-2xl text-base-50 tabular-nums">{{ data ? formatNumber(tile.value) : '–' }}</dd>
        <dt class="text-xs text-base-400">{{ tKey(`team.creatorStats.totals.${tile.key}`) }}</dt>
      </div>
    </dl>

    <div class="flex flex-wrap gap-2">
      <input v-model="q" class="field max-w-xs" :placeholder="t('team.creatorStats.search')" :aria-label="t('team.creatorStats.search')" data-testid="creator-stats-search" />
      <select v-model="tier" class="field w-auto" :aria-label="t('team.creatorStats.tier')">
        <option value="all">{{ t('team.creatorStats.allTiers') }}</option>
        <option v-for="x in creatorTiers" :key="x" :value="x">{{ t(`creator.tiers.${x}.name`) }}</option>
      </select>
      <select v-model="state" class="field w-auto" :aria-label="t('team.creators.roster.state')">
        <option v-for="s in ['all', 'active', 'revoked', 'banned'] as const" :key="s" :value="s">{{ t(`team.creators.roster.states.${s}`) }}</option>
      </select>
    </div>

    <div v-if="!data && loading" class="skeleton h-64" />
    <div v-else-if="!data && error" class="card px-4 py-5 text-sm" role="alert">
      <p class="font-semibold text-base-50">{{ t('team.creatorStats.error') }}</p>
      <p class="mt-0.5 text-base-400">{{ error }}</p>
      <button class="btn btn-ghost mt-3 px-3 py-1.5 text-xs" @click="load">{{ t('common.actions.retry') }}</button>
    </div>
    <p v-else-if="data && !rows.length" class="card px-4 py-5 text-sm text-base-400">{{ t('team.creatorStats.empty') }}</p>
    <div v-else-if="data" class="card overflow-x-auto">
      <table class="w-full min-w-[64rem] text-[13px]">
        <caption class="sr-only">{{ t('team.creatorStats.title') }}</caption>
        <thead>
          <tr class="text-[10px] tracking-wide text-base-400 uppercase">
            <th scope="colgroup" colspan="2" />
            <th v-for="g in groups" :key="g.id" scope="colgroup" :colspan="g.span" class="border-l border-base-800 px-2 pt-2 text-left font-semibold">
              {{ tKey(`team.creatorStats.groups.${g.id}`) }}
            </th>
            <th />
          </tr>
          <tr class="text-left text-[11px] tracking-wide text-base-400">
            <th scope="col" class="px-3 py-2 font-medium" :aria-sort="ariaSort('name')">
              <button type="button" class="sort-btn" :aria-label="t('team.creatorStats.sortBy', { column: t('team.creatorStats.columns.name') })" @click="sortBy('name')">
                {{ t('team.creatorStats.columns.name') }} <span aria-hidden="true">{{ sortIcon('name') }}</span>
              </button>
            </th>
            <th scope="col" class="py-2 font-medium">{{ t('team.creatorStats.tier') }}</th>
            <th
              v-for="c in columns"
              :key="c.key"
              scope="col"
              class="px-1.5 py-2 text-right font-medium"
              :class="{ 'border-l border-base-800': c.first }"
              :aria-sort="ariaSort(c.key)"
            >
              <button
                type="button"
                class="sort-btn ml-auto"
                :class="{ 'text-base-50': sort.key === c.key }"
                :aria-label="t('team.creatorStats.sortBy', { column: `${tKey(`team.creatorStats.groups.${c.group}`)} – ${tKey(`team.creatorStats.columns.${c.key}`)}` })"
                :data-testid="`creator-stats-sort-${c.key}`"
                @click="sortBy(c.key)"
              >
                {{ tKey(`team.creatorStats.columns.${c.key}`) }} <span aria-hidden="true">{{ sortIcon(c.key) }}</span>
              </button>
            </th>
            <th scope="col" class="px-2 py-2 text-right font-medium">{{ t('team.creatorStats.columns.petMonth') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(r, i) in rows"
            :key="r.uuid"
            class="group cursor-pointer border-t border-base-800 transition-colors hover:bg-base-850"
            :data-testid="`creator-stats-row-${r.uuid}`"
            @click="open(r)"
          >
            <th scope="row" class="px-3 py-2 text-left font-normal">
              <div class="flex items-center gap-2">
                <span class="w-6 shrink-0 text-right text-[11px] text-base-400 tabular-nums">{{ i + 1 }}</span>
                <span class="block size-6 shrink-0 overflow-hidden rounded"><PlayerFace :uuid="r.uuid" :name="r.name" /></span>
                <NuxtLink :to="`/admin/creator-stats/${r.uuid}`" class="font-medium text-base-50 hover:underline" @click.stop>{{ r.name }}</NuxtLink>
                <span v-if="r.state !== 'active'" class="badge" :class="stateClass[r.state]">{{ t(`team.creators.roster.states.${r.state}`) }}</span>
              </div>
            </th>
            <td class="py-2"><CreatorTierBadge :tier="r.tier" /></td>
            <td
              v-for="c in columns"
              :key="c.key"
              class="px-1.5 py-2 text-right tabular-nums"
              :class="[{ 'border-l border-base-800': c.first }, sort.key === c.key ? 'font-semibold text-base-50' : c.value(r) ? 'text-base-200' : 'text-base-600']"
            >
              {{ formatNumber(c.value(r)) }}
            </td>
            <td class="px-2 py-2 text-right text-xs whitespace-nowrap tabular-nums text-base-400">
              <template v-if="r.pets.pet">{{ formatNumber(r.pets.month.used) }} / {{ formatNumber(r.pets.month.limit) }}</template>
              <template v-else>–</template>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <section v-if="data" class="rounded-lg border-l-2 border-redstone-500 bg-base-900 px-3 py-2 text-xs text-base-400" :aria-label="t('team.creatorStats.definitions')">
      <p class="font-semibold text-base-200">{{ t('team.creatorStats.definitions') }}</p>
      <ul class="mt-1 list-disc space-y-0.5 pl-4">
        <li>{{ t('team.creatorStats.defFans') }}</li>
        <li>{{ t('team.creatorStats.defNewPlayers', { hours: data.definitions.newPlayerWindowHours }) }}</li>
        <li>{{ t('team.creatorStats.defActivity') }}</li>
        <li>{{ data.definitions.activitySince ? t('team.creatorStats.activitySince', { date: formatShortDate(`${data.definitions.activitySince}T12:00:00Z`) }) : t('team.creatorStats.activityNever') }}</li>
      </ul>
      <p v-if="data.generatedAt" class="mt-1">{{ t('team.creatorStats.generated', { time: formatRelative(data.generatedAt, true) }) }}</p>
    </section>
  </section>
</template>

<style scoped>
.sort-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  white-space: nowrap;
  border-radius: 0.25rem;
  text-transform: none;
}
.sort-btn:hover { color: var(--color-base-50); }
.sort-btn:focus-visible { outline: 2px solid var(--color-redstone-400); outline-offset: 2px; }
</style>
