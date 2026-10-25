<script setup lang="ts">
// Creator-Statistik eines Creators (/admin/creators/<uuid>, §35.14): Kennzahlen + Tagesverläufe (neue Fans, neue
// Spieler, Fans online/im Spiel, Haustier-Einlösungen) über 30–365 Tage. Nur Zahlen. Braucht creators.manage.
const route = useRoute()
const { a, fill, num, when, day, locale } = useAdminText()
const { api } = useAdmin()
const lp = useLocalePath()
const t = computed(() => a.value.creators.stats)
const ct = computed(() => a.value.creators.roster)

const uuid = computed(() => String(route.params.uuid ?? '').replace(/-/g, '').toLowerCase())
const RANGES = [30, 90, 180, 365] as const
const days = ref<number>(90)
const data = ref<CreatorStatsDetail | null>(null)
const error = ref('')
const missing = ref(false)
const loading = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  missing.value = false
  try {
    data.value = await api<CreatorStatsDetail>(`/v1/admin/creators/${uuid.value}/stats?days=${days.value}`)
  } catch (e) {
    data.value = null
    if ((e as { statusCode?: number }).statusCode === 404) missing.value = true
    else error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
watch([uuid, days], load, { immediate: true })

const c = computed(() => data.value?.creator ?? null)
const series = computed(() => data.value?.series ?? [])
const dayList = computed(() => series.value.map((x) => x.day))
const charts = computed(() => [
  { key: 'newFans', label: t.value.charts.newFans, kind: 'bar' as const, tone: 'redstone' as const, values: series.value.map((x) => x.newFans) },
  { key: 'newPlayers', label: t.value.charts.newPlayers, kind: 'bar' as const, tone: 'lamp' as const, values: series.value.map((x) => x.newPlayers) },
  { key: 'activeFans', label: t.value.charts.activeFans, kind: 'line' as const, tone: 'ok' as const, values: series.value.map((x) => x.activeFans) },
  { key: 'playingFans', label: t.value.charts.playingFans, kind: 'line' as const, tone: 'ok' as const, values: series.value.map((x) => x.playingFans) },
  { key: 'petRedemptions', label: t.value.charts.petRedemptions, kind: 'bar' as const, tone: 'lamp' as const, values: series.value.map((x) => x.petRedemptions) },
])
const capeLine = computed(() => {
  const d = data.value
  if (!d || !c.value) return ''
  if (!d.fanCape.holders) return t.value.noFanCape
  return fill(t.value.fanCapeLine, { held: num(d.fanCape.fanHolders), fans: num(c.value.fans.total), p: percentOf(d.fanCape.fanHolders, c.value.fans.total), all: num(d.fanCape.holders) })
})
const monthName = (m: string) => {
  const d = new Date(`${m}-01T12:00:00Z`)
  return Number.isNaN(d.getTime()) ? m : new Intl.DateTimeFormat(locale.value, { month: 'long', year: 'numeric', timeZone: 'UTC' }).format(d)
}
const stateTone = (s: StatState) => (s === 'active' ? 'tone-ok' : s === 'banned' ? 'tone-danger' : 'tone-muted')
</script>

<template>
  <div class="adm-page">
    <NuxtLink to="/admin/creators?tab=stats" class="inline-flex items-center gap-1.5 text-sm text-base-400 hover:text-base-50">
      <SiteIcon name="back" class="size-4" />{{ t.back }}
    </NuxtLink>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <p v-if="missing" class="adm-empty mt-6"><SiteIcon name="crown" class="size-6" />{{ t.notFound }}</p>
    <div v-else-if="!data" class="skeleton mt-6 h-96 rounded-xl" />
    <template v-else-if="c">
      <header class="mt-4 flex flex-wrap items-center gap-4">
        <PlayerHead :uuid="c.uuid" :name="c.name" :size="48" />
        <div class="min-w-0 flex-1">
          <h1 class="adm-title">{{ c.name || c.uuid }}</h1>
          <p class="mt-1 flex flex-wrap items-center gap-2 text-xs text-base-400">
            <span class="tone tone-info">{{ ct.tiers[c.tier] }}</span>
            <span class="tone" :class="stateTone(c.state)">{{ ct.states[c.state] }}</span>
            <span v-if="c.mainCode">{{ ct.code }}: <code class="text-lamp-300">{{ c.mainCode }}</code></span>
            <span>{{ fill(ct.since, { date: day(c.since) }) }}</span>
            <span>· {{ fill(t.generated, { time: when(data.generatedAt) }) }}</span>
          </p>
        </div>
        <label class="flex items-center gap-2 text-sm text-base-400">
          {{ t.range }}
          <select v-model.number="days" class="field adm-select" :disabled="loading">
            <option v-for="r in RANGES" :key="r" :value="r">{{ fill(t.days, { n: r }) }}</option>
          </select>
        </label>
        <a v-if="c.state === 'active'" :href="lp(`/creator?as=${c.uuid}`)" class="btn btn-primary" :title="t.openDashboardHint">
          <SiteIcon name="shield" class="size-4" />{{ t.openDashboard }}
        </a>
      </header>

      <ul class="mt-6 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <li class="card p-4">
          <p class="adm-stat-label">{{ t.tiles.fans }}</p>
          <p class="display mt-1 text-3xl text-base-50 tabular-nums">{{ num(c.fans.total) }}</p>
          <p class="mt-1 text-xs text-base-400 tabular-nums">{{ t.tiles.newFans }}: {{ num(c.fans.today) }} {{ t.today }} · {{ num(c.fans.d7) }} / {{ t.d7 }} · {{ num(c.fans.d30) }} / {{ t.d30 }}</p>
        </li>
        <li class="card p-4">
          <p class="adm-stat-label">{{ t.tiles.newPlayers }}</p>
          <p class="display mt-1 text-3xl text-base-50 tabular-nums">{{ num(c.newPlayers.total) }}</p>
          <p class="mt-1 text-xs text-base-400 tabular-nums">{{ num(c.newPlayers.d7) }} / {{ t.d7 }} · {{ num(c.newPlayers.d30) }} / {{ t.d30 }}</p>
        </li>
        <li class="card p-4">
          <p class="adm-stat-label">{{ t.tiles.online }} · {{ t.tiles.played }}</p>
          <p class="display mt-1 text-3xl text-base-50 tabular-nums">{{ num(c.activity.online7d) }} <span class="text-lg text-base-400">· {{ num(c.activity.played7d) }}</span></p>
          <p class="mt-1 text-xs text-base-400 tabular-nums">{{ t.d7 }} · {{ t.d30 }}: {{ num(c.activity.online30d) }} · {{ num(c.activity.played30d) }}</p>
        </li>
        <li class="card p-4">
          <p class="adm-stat-label">{{ t.tiles.pets }}<template v-if="c.pets.pet"> · {{ c.pets.pet.name }}</template></p>
          <p class="display mt-1 text-3xl text-base-50 tabular-nums">{{ num(c.pets.redemptions) }}</p>
          <p class="mt-1 text-xs text-base-400">{{ c.pets.pet ? fill(t.petCodesLine, { codes: num(c.pets.codes), active: num(c.pets.activeCodes), n: num(c.pets.redemptions) }) : t.noPet }}</p>
          <p v-if="c.pets.pet" class="mt-0.5 text-xs text-base-400">{{ fill(t.petMonth, { month: monthName(c.pets.month.month), used: num(c.pets.month.used), limit: num(c.pets.month.limit), n: num(c.pets.month.redemptions) }) }}</p>
        </li>
      </ul>
      <p class="mt-3 text-sm text-base-300"><span class="font-semibold text-base-100">{{ t.tiles.fanCape }}:</span> {{ capeLine }}</p>

      <div class="mt-6 grid gap-4 lg:grid-cols-2" :aria-busy="loading">
        <section v-for="ch in charts" :key="ch.key" class="card p-4" :class="{ 'lg:col-span-2': ch.key === 'petRedemptions' && !c.pets.pet }">
          <h2 class="text-sm font-semibold text-base-100">{{ ch.label }}</h2>
          <AdminSeriesChart class="mt-3" :values="ch.values" :days="dayList" :label="ch.label" :kind="ch.kind" :tone="ch.tone" :texts="t.chart" />
        </section>
      </div>

      <div class="card mt-6 p-4 text-xs text-base-400">
        <p class="text-sm font-semibold text-base-100">{{ t.defsTitle }}</p>
        <ul class="mt-2 list-disc space-y-1 pl-5">
          <li>{{ fill(t.defNewPlayers, { h: data.definitions.newPlayerWindowHours }) }}</li>
          <li>{{ t.defActivity }}</li>
          <li>{{ data.definitions.activitySince ? fill(t.defSince, { date: day(`${data.definitions.activitySince}T12:00:00Z`) }) : t.defNotYet }}</li>
        </ul>
      </div>
    </template>
  </div>
</template>
