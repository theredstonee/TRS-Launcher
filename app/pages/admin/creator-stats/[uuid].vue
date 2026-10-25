<script setup lang="ts">
import { percent, type ChartPoint, type CreatorStatsDetail, type SeriesDay } from '~/utils/creatorStats'

// Verlauf eines Creators (API §35.14, `creators.manage`): Kennzahlen und je eine
// Tagesreihe für neue Fans, neue Spieler, aktive Fans (online / im Spiel) und
// Haustier-Einlösungen. Aktivität je Tag gibt es erst seit `activitySince` –
// davor bleiben die Linien leer statt 0 zu zeigen. „Dashboard öffnen (Team)“
// springt in den Team-Modus des Creator-Dashboards.
const route = useRoute()
const uuid = computed(() => String(route.params.uuid ?? ''))
const ranges = [30, 90, 180, 365] as const
const days = ref<(typeof ranges)[number]>(90)

const data = ref<CreatorStatsDetail | null>(null)
const error = ref<string | null>(null)
const loading = ref(false)
async function load() {
  loading.value = true
  error.value = null
  try {
    data.value = await backend.creator.adminStatsDetail(uuid.value, days.value)
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch([uuid, days], load)

const c = computed(() => data.value?.creator ?? null)
const series = (pick: (d: SeriesDay) => number | null): ChartPoint[] => (data.value?.series ?? []).map((d) => ({ day: d.day, value: pick(d) }))
const charts = computed(() => [
  { id: 'fans', kind: 'bar' as const, points: series((d) => d.newFans) },
  { id: 'players', kind: 'bar' as const, points: series((d) => d.newPlayers) },
  { id: 'active', kind: 'line' as const, points: series((d) => d.activeFans) },
  { id: 'playing', kind: 'line' as const, points: series((d) => d.playingFans) },
  { id: 'redemptions', kind: 'bar' as const, points: series((d) => d.redemptions) },
  { id: 'pet', kind: 'bar' as const, points: series((d) => d.petRedemptions) },
])
const capePercent = computed(() => (data.value && c.value ? percent(data.value.fanCape.fanHolders, c.value.fans.total) : 0))

function openDashboard() {
  if (!c.value) return
  void navigateTo({ path: '/creator', query: { view: c.value.uuid, team: '1', name: c.value.name } })
}
</script>

<template>
  <section class="space-y-4" :aria-label="t('team.creatorStats.detail.title')" data-testid="admin-creator-stats-detail">
    <NuxtLink to="/admin/creator-stats" class="inline-flex items-center gap-1 text-xs text-base-400 hover:text-base-50">← {{ t('team.creatorStats.detail.back') }}</NuxtLink>

    <div v-if="!data && loading" class="space-y-3">
      <div class="skeleton h-20" />
      <div class="skeleton h-56" />
    </div>
    <div v-else-if="!data && error" class="card px-4 py-5 text-sm" role="alert">
      <p class="font-semibold text-base-50">{{ t('team.creatorStats.error') }}</p>
      <p class="mt-0.5 text-base-400">{{ error }}</p>
      <button class="btn btn-ghost mt-3 px-3 py-1.5 text-xs" @click="load">{{ t('common.actions.retry') }}</button>
    </div>

    <template v-else-if="data && c">
      <!-- Kopf -->
      <section class="card flex flex-wrap items-center gap-4 px-5 py-4">
        <span class="block size-12 shrink-0 overflow-hidden rounded-lg"><PlayerFace :uuid="c.uuid" :name="c.name" /></span>
        <div class="min-w-0 flex-1">
          <div class="flex flex-wrap items-center gap-2">
            <h2 class="display text-2xl leading-none text-base-50">{{ c.name }}</h2>
            <CreatorTierBadge :tier="c.tier" star />
            <span v-if="c.state !== 'active'" class="badge bg-base-800 text-base-400">{{ t(`team.creators.roster.states.${c.state}`) }}</span>
          </div>
          <p class="mt-1 text-xs text-base-400">
            <span v-if="c.mainCode" class="font-mono font-semibold text-redstone-300">{{ c.mainCode }}</span>
            <template v-if="c.since"> · {{ t('creator.dashboard.since', { date: formatDate(c.since) }) }}</template>
            <template v-if="c.pets.pet"> · {{ t('team.creatorStats.detail.pet', { name: c.pets.pet.name }) }}</template>
          </p>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <div class="flex rounded-full bg-base-900 p-1 ring-1 ring-base-800" role="radiogroup" :aria-label="t('team.creatorStats.detail.range')">
            <button
              v-for="r in ranges"
              :key="r"
              class="seg rounded-full text-xs"
              :class="{ 'seg-on': days === r }"
              role="radio"
              :aria-checked="days === r"
              @click="days = r"
            >
              {{ t('team.creatorStats.detail.days', { n: r }) }}
            </button>
          </div>
          <button v-if="c.state === 'active'" class="btn btn-primary px-3 py-1.5 text-xs" data-testid="creator-stats-open-dashboard" @click="openDashboard">
            <SocialIcon name="crown" class="size-4" />{{ t('creator.teamMode.openDashboard') }}
          </button>
        </div>
      </section>

      <!-- Kennzahlen -->
      <dl class="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <div class="card px-4 py-3">
          <dd class="display text-2xl text-base-50 tabular-nums">{{ formatNumber(c.fans.total) }}</dd>
          <dt class="text-xs text-base-400">{{ t('team.creatorStats.detail.tiles.fans') }}</dt>
          <dd class="mt-0.5 text-[11px] text-base-400 tabular-nums">{{ t('team.creatorStats.detail.tiles.fansNew', { today: formatNumber(c.fans.today), d7: formatNumber(c.fans.d7), d30: formatNumber(c.fans.d30) }) }}</dd>
        </div>
        <div class="card px-4 py-3">
          <dd class="display text-2xl text-base-50 tabular-nums">{{ formatNumber(c.newPlayers.total) }}</dd>
          <dt class="text-xs text-base-400">{{ t('team.creatorStats.detail.tiles.newPlayers') }}</dt>
          <dd class="mt-0.5 text-[11px] text-base-400 tabular-nums">{{ t('team.creatorStats.detail.tiles.recent', { d7: formatNumber(c.newPlayers.d7), d30: formatNumber(c.newPlayers.d30) }) }}</dd>
        </div>
        <div class="card px-4 py-3">
          <dd class="display text-2xl text-base-50 tabular-nums">{{ formatNumber(c.activity.online7d) }}</dd>
          <dt class="text-xs text-base-400">{{ t('team.creatorStats.detail.tiles.online') }}</dt>
          <dd class="mt-0.5 text-[11px] text-base-400 tabular-nums">
            {{ t('team.creatorStats.detail.tiles.activity', { online30: formatNumber(c.activity.online30d), played7: formatNumber(c.activity.played7d), played30: formatNumber(c.activity.played30d) }) }}
          </dd>
        </div>
        <div class="card px-4 py-3">
          <dd class="display text-2xl text-base-50 tabular-nums">{{ formatNumber(c.pets.redemptions) }}</dd>
          <dt class="text-xs text-base-400">{{ t('team.creatorStats.detail.tiles.petRedemptions') }}</dt>
          <dd class="mt-0.5 text-[11px] text-base-400 tabular-nums">
            {{ t('team.creatorStats.detail.tiles.petCodes', { codes: formatNumber(c.pets.codes), active: formatNumber(c.pets.activeCodes) }) }}
          </dd>
        </div>
      </dl>

      <!-- Monatsgrenze Haustier-Codes + Fan-Umhang-Anteil -->
      <div class="grid gap-3 md:grid-cols-2">
        <section class="card px-4 py-3" :aria-label="t('team.creatorStats.detail.petMonth')">
          <div class="flex items-baseline gap-2 text-xs">
            <span class="font-semibold text-base-50">{{ t('team.creatorStats.detail.petMonth') }}</span>
            <span v-if="c.pets.month.month" class="text-base-400">{{ c.pets.month.month }}</span>
            <span class="ml-auto text-base-400 tabular-nums">{{ t('team.creatorStats.detail.petMonthText', { used: formatNumber(c.pets.month.used), limit: formatNumber(c.pets.month.limit), redemptions: formatNumber(c.pets.month.redemptions) }) }}</span>
          </div>
          <div
            class="mt-2 h-2 overflow-hidden rounded-full bg-base-800"
            role="progressbar"
            :aria-valuenow="percent(c.pets.month.used, c.pets.month.limit)"
            aria-valuemin="0"
            aria-valuemax="100"
            :aria-label="t('team.creatorStats.detail.petMonth')"
          >
            <div class="h-full rounded-full bg-redstone-500" :style="{ width: `${percent(c.pets.month.used, c.pets.month.limit)}%` }" />
          </div>
          <p v-if="!c.pets.pet" class="mt-1.5 text-[11px] text-base-400">{{ t('team.creatorStats.noPet') }}</p>
        </section>
        <section class="card px-4 py-3" :aria-label="t('team.creatorStats.detail.cape')">
          <div class="flex items-baseline gap-2 text-xs">
            <span class="font-semibold text-base-50">{{ t('team.creatorStats.detail.cape') }}</span>
            <span class="ml-auto text-base-400 tabular-nums">{{ capePercent }} %</span>
          </div>
          <div class="mt-2 h-2 overflow-hidden rounded-full bg-base-800" role="progressbar" :aria-valuenow="capePercent" aria-valuemin="0" aria-valuemax="100" :aria-label="t('team.creatorStats.detail.cape')">
            <div class="h-full rounded-full bg-redstone-500" :style="{ width: `${capePercent}%` }" />
          </div>
          <p class="mt-1.5 text-[11px] text-base-400">
            {{ t('team.creatorStats.detail.capeText', { fanHolders: formatNumber(data.fanCape.fanHolders), fans: formatNumber(c.fans.total), holders: formatNumber(data.fanCape.holders) }) }}
          </p>
        </section>
      </div>

      <!-- Verläufe -->
      <div class="grid gap-4 xl:grid-cols-2">
        <section v-for="ch in charts" :key="ch.id" class="card px-4 py-3" :aria-label="tKey(`team.creatorStats.detail.charts.${ch.id}`)" :data-testid="`creator-stats-chart-${ch.id}`">
          <h3 class="section-title mb-2">{{ tKey(`team.creatorStats.detail.charts.${ch.id}`) }}</h3>
          <AdminStatChart :points="ch.points" :kind="ch.kind" :label="tKey(`team.creatorStats.detail.charts.${ch.id}`)" />
        </section>
      </div>

      <section class="rounded-lg border-l-2 border-redstone-500 bg-base-900 px-3 py-2 text-xs text-base-400" :aria-label="t('team.creatorStats.definitions')">
        <p class="font-semibold text-base-200">{{ t('team.creatorStats.definitions') }}</p>
        <ul class="mt-1 list-disc space-y-0.5 pl-4">
          <li>{{ t('team.creatorStats.defFans') }}</li>
          <li>{{ t('team.creatorStats.defNewPlayers', { hours: data.definitions.newPlayerWindowHours }) }}</li>
          <li>{{ t('team.creatorStats.defActivity') }}</li>
          <li>{{ data.definitions.activitySince ? t('team.creatorStats.activitySince', { date: formatShortDate(`${data.definitions.activitySince}T12:00:00Z`) }) : t('team.creatorStats.activityNever') }}</li>
        </ul>
      </section>
    </template>
  </section>
</template>
