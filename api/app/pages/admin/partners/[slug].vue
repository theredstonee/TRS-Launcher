<script setup lang="ts">
// Statistik eines Partners (/admin/partners/<slug>): Kennzahlen, Vergleich mit dem Zeitraum davor und Tagesverläufe
// (Unterstützer, Einlösungen fest/einmal, neue Spieler, Server-Beitritte, Klicks/Downloads, online/im Spiel).
// Nur Zahlen. Braucht partners.manage.
const route = useRoute()
const { api } = useAdmin()
const { p } = usePartnerText()
const { fill, num, when, day } = useAdminText()
const t = computed(() => p.value.admin.stats)

const slug = computed(() => String(route.params.slug ?? '').toLowerCase())
const RANGES = [30, 90, 180, 365] as const
const days = ref<number>(30)
const data = ref<PartnerStatsDetail | null>(null)
const error = ref('')
const missing = ref(false)
const loading = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  missing.value = false
  try {
    data.value = await api<PartnerStatsDetail>(`/v1/admin/partners/${encodeURIComponent(slug.value)}/stats?days=${days.value}`)
  } catch (e) {
    data.value = null
    if ((e as { statusCode?: number }).statusCode === 404) missing.value = true
    else error.value = fill(p.value.admin.failed, { error: apiMessage(e) })
  } finally {
    loading.value = false
  }
}
watch([slug, days], load, { immediate: true })

const x = computed(() => data.value?.partner ?? null)
const series = computed(() => data.value?.series ?? [])
const dayList = computed(() => series.value.map((s) => s.day))
const pctOf = (a: number, b: number) => (b > 0 ? Math.round((a / b) * 100) : 0)
const charts = computed(() => {
  const c = t.value.charts
  const list = [
    { key: 'newSupporters', label: c.newSupporters!, kind: 'bar' as const, tone: 'redstone' as const, values: series.value.map((s) => s.newSupporters) },
    { key: 'newPlayers', label: c.newPlayers!, kind: 'bar' as const, tone: 'lamp' as const, values: series.value.map((s) => s.newPlayers) },
    { key: 'active', label: c.active!, kind: 'line' as const, tone: 'ok' as const, values: series.value.map((s) => s.activeSupporters) },
    { key: 'playing', label: c.playing!, kind: 'line' as const, tone: 'ok' as const, values: series.value.map((s) => s.playingSupporters) },
    { key: 'serverJoins', label: c.serverJoins!, kind: 'bar' as const, tone: 'lamp' as const, values: series.value.map((s) => s.serverJoins) },
    { key: 'downloads', label: c.downloads!, kind: 'bar' as const, tone: 'lamp' as const, values: series.value.map((s) => s.downloads) },
    { key: 'clicks', label: c.clicks!, kind: 'bar' as const, tone: 'redstone' as const, values: series.value.map((s) => s.clicks) },
    { key: 'left', label: c.left!, kind: 'bar' as const, tone: 'redstone' as const, values: series.value.map((s) => s.left) },
  ]
  return list
})
const compare = computed(() => {
  const d = data.value
  if (!d) return []
  const m = p.value.dash.compare.metrics
  const keys: (keyof PartnerPeriod)[] = ['newSupporters', 'redemptions', 'fixed', 'onetime', 'newPlayers', 'serverJoins', 'clicks', 'downloads', 'left']
  return keys.map((k) => {
    const c = percentChange(d.period[k], d.previous[k])
    return { key: k, label: m[k] ?? k, sub: k === 'fixed' || k === 'onetime', now: d.period[k], before: d.previous[k], change: c === null ? p.value.dash.compare.new : `${c > 0 ? '+' : c < 0 ? '−' : '±'}${Math.abs(c)} %` }
  })
})
</script>

<template>
  <div class="adm-page">
    <NuxtLink to="/admin/partners?tab=stats" class="inline-flex items-center gap-1.5 text-sm text-base-400 hover:text-base-50">
      <SiteIcon name="back" class="size-4" />{{ t.back }}
    </NuxtLink>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>
    <p v-if="missing" class="adm-empty mt-6"><SiteIcon name="hand" class="size-6" />{{ t.notFound }}</p>
    <div v-else-if="!data" class="skeleton mt-6 h-96 rounded-xl" />
    <template v-else-if="x">
      <header class="mt-4 flex flex-wrap items-center gap-4">
        <PartnerLogo :logo="x.logo" :name="x.name" :size="48" />
        <div class="min-w-0 flex-1">
          <h1 class="adm-title">{{ x.name }}</h1>
          <p class="mt-1 flex flex-wrap items-center gap-2 text-xs text-base-400">
            <span class="tone tone-info">{{ p.category[x.category] }}</span>
            <span class="tone" :class="x.state === 'active' ? 'tone-ok' : 'tone-muted'">{{ p.admin.state[x.state] }}</span>
            <span class="tone" :class="x.serverAllowed ? 'tone-ok' : 'tone-muted'">{{ x.serverAllowed ? t.serverOn : t.serverOffTag }}</span>
            <span v-if="x.code">{{ p.admin.code }}: <code class="break-all text-lamp-300">{{ x.code }}</code></span>
            <span>· {{ day(x.since) }}</span>
            <span>· {{ fill(t.generated, { time: when(data.generatedAt) }) }}</span>
          </p>
        </div>
        <label class="flex items-center gap-2 text-sm text-base-400">
          {{ t.range }}
          <select v-model.number="days" class="field adm-select" :disabled="loading">
            <option v-for="r in RANGES" :key="r" :value="r">{{ fill(t.days, { n: r }) }}</option>
          </select>
        </label>
        <NuxtLink :to="`/partner/${x.slug}`" class="btn btn-primary"><SiteIcon name="shield" class="size-4" />{{ t.openDashboard }}</NuxtLink>
      </header>

      <ul class="mt-6 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <li class="card p-4">
          <p class="adm-stat-label">{{ t.tiles.supporters }}</p>
          <p class="display mt-1 text-3xl text-base-50 tabular-nums">{{ num(x.supporters.total) }}</p>
          <p class="mt-1 text-xs text-base-400 tabular-nums">{{ t.tiles.newSupporters }}: {{ num(x.supporters.today) }} {{ t.today }} · {{ num(data.period.newSupporters) }} {{ t.period }}</p>
        </li>
        <li class="card p-4">
          <p class="adm-stat-label">{{ t.tiles.redemptions }}</p>
          <p class="display mt-1 text-3xl text-base-50 tabular-nums">{{ num(data.period.redemptions) }}</p>
          <p class="mt-1 text-xs text-base-400 tabular-nums">{{ fill(t.fixedOnetime, { fixed: num(data.period.fixed), onetime: num(data.period.onetime) }) }} · Σ {{ num(x.redemptions.total) }}</p>
        </li>
        <li class="card p-4">
          <p class="adm-stat-label">{{ t.tiles.newPlayers }}</p>
          <p class="display mt-1 text-3xl text-base-50 tabular-nums">{{ num(data.period.newPlayers) }}</p>
          <p class="mt-1 text-xs text-base-400 tabular-nums">{{ t.period }} · Σ {{ num(x.newPlayers.total) }}</p>
        </li>
        <li class="card p-4">
          <p class="adm-stat-label">{{ t.tiles.activity }}</p>
          <p class="display mt-1 text-3xl text-base-50 tabular-nums">{{ num(x.activity.onlineToday) }} <span class="text-lg text-base-400">· {{ num(x.activity.playedToday) }}</span></p>
          <p class="mt-1 text-xs text-base-400 tabular-nums">7 d: {{ num(x.activity.online7d) }}</p>
        </li>
        <li class="card p-4">
          <p class="adm-stat-label">{{ t.tiles.serverJoins }}</p>
          <p class="display mt-1 text-3xl text-base-50 tabular-nums">{{ num(data.period.serverJoins) }}</p>
          <p class="mt-1 text-xs text-base-400 tabular-nums">{{ t.period }} · 7 d: {{ num(x.serverJoins.d7) }}</p>
        </li>
        <li class="card p-4">
          <p class="adm-stat-label">{{ t.tiles.links }}</p>
          <p class="display mt-1 text-3xl text-base-50 tabular-nums">{{ num(data.period.clicks) }} <span class="text-lg text-base-400">· {{ num(data.period.downloads) }}</span></p>
          <p class="mt-1 text-xs text-base-400 tabular-nums">{{ t.period }}</p>
        </li>
        <li class="card p-4 sm:col-span-2">
          <p class="adm-stat-label">{{ t.tiles.worn }}</p>
          <p class="display mt-1 text-3xl text-base-50 tabular-nums">{{ pctOf(x.worn.cape, x.supporters.total) }} %</p>
          <p class="mt-1 text-xs text-base-400 tabular-nums">{{ fill(t.wornLine, { cape: pctOf(x.worn.cape, x.supporters.total), cosmetic: pctOf(x.worn.cosmetic, x.supporters.total) }) }} · {{ t.cols.left30d }}: {{ num(x.left30d) }}</p>
        </li>
      </ul>

      <div class="mt-6 grid gap-4 lg:grid-cols-2" :aria-busy="loading">
        <section class="card p-4">
          <h2 class="text-sm font-semibold text-base-100">{{ p.dash.charts.redemptions }}</h2>
          <PartnerStackChart class="mt-3" :a="series.map((s) => s.fixed)" :b="series.map((s) => s.onetime)" :days="dayList" :labels="[t.charts.fixed!, t.charts.onetime!]" :title="p.dash.charts.redemptions" :texts="p.dash.charts.table" />
        </section>
        <section v-for="ch in charts" :key="ch.key" class="card p-4">
          <h2 class="text-sm font-semibold text-base-100">{{ ch.label }}</h2>
          <AdminSeriesChart class="mt-3" :values="ch.values" :days="dayList" :label="ch.label" :kind="ch.kind" :tone="ch.tone" :texts="p.dash.charts.table" />
        </section>
      </div>

      <div class="card mt-6 overflow-x-auto p-4">
        <h2 class="text-sm font-semibold text-base-100">{{ t.compareTitle }}</h2>
        <table class="cmp mt-3 w-full text-sm">
          <thead><tr><th>{{ p.dash.compare.metric }}</th><th class="num">{{ fill(t.days, { n: days }) }}</th><th class="num">{{ p.dash.compare.previous.replace('30', String(days)) }}</th><th class="num">{{ p.dash.compare.change }}</th></tr></thead>
          <tbody>
            <tr v-for="r in compare" :key="r.key">
              <td :class="r.sub ? 'pl-6 text-base-400' : 'text-base-200'">{{ r.label }}</td>
              <td class="num font-semibold text-base-50 tabular-nums">{{ num(r.now) }}</td>
              <td class="num text-base-300 tabular-nums">{{ num(r.before) }}</td>
              <td class="num text-base-300 tabular-nums">{{ r.change }}</td>
            </tr>
          </tbody>
        </table>
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

<style scoped>
.cmp th {
  text-align: left;
  font-size: 0.7rem;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--color-base-400);
  padding: 0.45rem 0.5rem;
  border-bottom: 1px solid var(--color-base-800);
}
.cmp td {
  padding: 0.45rem 0.5rem;
  border-bottom: 1px solid var(--color-base-850);
}
.cmp .num {
  text-align: right;
}
</style>
