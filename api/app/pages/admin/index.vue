<script setup lang="ts">
// Übersicht: offene Arbeit (Meldungen, Einsprüche, Uploads), aktive Strafen nach Art, Nutzerzahlen,
// Aktivität (7/30 Tage, inline SVG), Server-Zustand und die letzten Audit-Einträge. Nur Zahlen.
const { a, fill, rel, num, lang, when } = useAdminText()
const { api } = useAdmin()

const data = ref<DashboardData | null>(null)
const error = ref('')
const range = ref<7 | 30>(7)
const series = ref<'newUsers' | 'messages' | 'reports' | 'sanctions'>('newUsers')

async function load() {
  error.value = ''
  try {
    data.value = await api<DashboardData>('/v1/admin/dashboard')
  } catch (e) {
    error.value = fill(a.value.common.failed, { error: apiMessage(e) })
  }
}
onMounted(load)

const chart = computed(() => {
  const d = data.value
  if (!d) return { values: [], days: [] }
  return { values: d.series[series.value].slice(-range.value), days: d.series.days.slice(-range.value) }
})
const nothingOpen = computed(() => {
  const d = data.value
  return !!d && d.reports.open + d.reports.inReview === 0 && d.appeals.open === 0 && d.uploads.capesPending + d.uploads.cosmeticsPending === 0
})
const diskLow = computed(() => {
  const disk = data.value?.server.disk
  return !!disk && disk.totalBytes > 0 && disk.freeBytes / disk.totalBytes < 0.1
})
</script>

<template>
  <div class="adm-page">
    <header class="flex flex-wrap items-end gap-3">
      <div class="min-w-0 flex-1">
        <h1 class="adm-title">{{ a.dashboard.title }}</h1>
        <p class="adm-lead">{{ a.dashboard.lead }}</p>
      </div>
      <button type="button" class="btn btn-ghost" @click="load"><SiteIcon name="refresh" class="size-4" />{{ a.common.refresh }}</button>
    </header>
    <p v-if="error" role="alert" class="mt-4 text-sm text-redstone-300">{{ error }}</p>

    <div v-if="!data && !error" class="mt-6 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
      <div v-for="i in 8" :key="i" class="skeleton h-28 rounded-xl" />
    </div>

    <template v-if="data">
      <!-- Arbeit -->
      <p v-if="nothingOpen" class="mt-6 flex items-center gap-2 rounded-lg border border-base-800 bg-base-900 px-4 py-3 text-sm text-ok">
        <SiteIcon name="check" class="size-4" />{{ a.dashboard.nothing }}
      </p>
      <section class="mt-6 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <NuxtLink to="/admin/reports" class="card card-hover adm-stat" :class="{ hot: data.reports.highPriority > 0, warn: data.reports.open > 0 && data.reports.highPriority === 0 }">
          <p class="adm-stat-label flex items-center gap-1.5"><SiteIcon name="flag" class="size-3.5" />{{ a.dashboard.openReports }}</p>
          <p class="adm-stat-value mt-1">{{ num(data.reports.open + data.reports.inReview) }}</p>
          <p class="mt-1 flex flex-wrap gap-1.5 text-xs">
            <span v-if="data.reports.highPriority" class="tone tone-danger">{{ fill(a.dashboard.highPriority, { n: data.reports.highPriority }) }}</span>
            <span v-if="data.reports.inReview" class="tone tone-muted">{{ fill(a.dashboard.inReview, { n: data.reports.inReview }) }}</span>
            <span v-if="data.reports.oldestOpenAt" class="text-base-400">{{ fill(a.dashboard.oldest, { date: rel(data.reports.oldestOpenAt) }) }}</span>
          </p>
        </NuxtLink>
        <NuxtLink to="/admin/appeals" class="card card-hover adm-stat" :class="{ warn: data.appeals.open > 0 }">
          <p class="adm-stat-label flex items-center gap-1.5"><SiteIcon name="appeal" class="size-3.5" />{{ a.dashboard.openAppeals }}</p>
          <p class="adm-stat-value mt-1">{{ num(data.appeals.open) }}</p>
          <p v-if="data.appeals.oldestOpenAt" class="mt-1 text-xs text-base-400">{{ fill(a.dashboard.oldest, { date: rel(data.appeals.oldestOpenAt) }) }}</p>
        </NuxtLink>
        <NuxtLink to="/admin/uploads" class="card card-hover adm-stat" :class="{ warn: data.uploads.capesPending + data.uploads.cosmeticsPending > 0 }">
          <p class="adm-stat-label flex items-center gap-1.5"><SiteIcon name="cape" class="size-3.5" />{{ a.dashboard.toReview }}</p>
          <p class="adm-stat-value mt-1">{{ num(data.uploads.capesPending + data.uploads.cosmeticsPending) }}</p>
          <p class="mt-1 flex flex-wrap gap-x-2 text-xs text-base-400">
            <span>{{ fill(a.dashboard.capes, { n: data.uploads.capesPending }) }}</span>
            <span>{{ fill(a.dashboard.cosmetics, { n: data.uploads.cosmeticsPending }) }}</span>
            <span v-if="data.uploads.capesReported + data.uploads.cosmeticsReported" class="text-lamp-300">{{ fill(a.dashboard.reported, { n: data.uploads.capesReported + data.uploads.cosmeticsReported }) }}</span>
          </p>
        </NuxtLink>
        <NuxtLink to="/admin/sanctions" class="card card-hover adm-stat">
          <p class="adm-stat-label flex items-center gap-1.5"><SiteIcon name="gavel" class="size-3.5" />{{ a.dashboard.activeSanctions }}</p>
          <p class="adm-stat-value mt-1">{{ num(Object.values(data.sanctions).reduce((s, n) => s + n, 0)) }}</p>
          <p class="mt-1.5 flex flex-wrap gap-1">
            <template v-for="k in SANCTION_KINDS" :key="k">
              <span v-if="data.sanctions[k]" class="tone" :class="kindTone(k)">{{ a.kinds[k] }} {{ data.sanctions[k] }}</span>
            </template>
          </p>
        </NuxtLink>
      </section>

      <!-- Spieler + Aktivität -->
      <section class="mt-3 grid gap-3 lg:grid-cols-[minmax(0,1fr)_20rem]">
        <div class="card p-5">
          <div class="flex flex-wrap items-center gap-2">
            <h2 class="section-title flex-1">{{ a.dashboard.chart }}</h2>
            <div class="adm-seg">
              <button v-for="s in (['newUsers', 'messages', 'reports', 'sanctions'] as const)" :key="s" type="button" :aria-pressed="series === s" @click="series = s">{{ a.dashboard.series[s] }}</button>
            </div>
            <div class="adm-seg">
              <button type="button" :aria-pressed="range === 7" @click="range = 7">{{ a.dashboard.days7 }}</button>
              <button type="button" :aria-pressed="range === 30" @click="range = 30">{{ a.dashboard.days30 }}</button>
            </div>
          </div>
          <div class="mt-4">
            <AdminChart :values="chart.values" :days="chart.days" :label="a.dashboard.series[series] ?? ''" />
          </div>
        </div>
        <div class="card p-5">
          <h2 class="section-title">{{ a.dashboard.users }}</h2>
          <dl class="mt-3 grid grid-cols-2 gap-3">
            <div><dt class="adm-stat-label">{{ a.dashboard.newUsers }} · {{ a.dashboard.h24 }}</dt><dd class="display text-2xl text-base-50 tabular-nums">{{ num(data.users.new24h) }}</dd></div>
            <div><dt class="adm-stat-label">{{ a.dashboard.newUsers }} · {{ a.dashboard.d7 }}</dt><dd class="display text-2xl text-base-50 tabular-nums">{{ num(data.users.new7d) }}</dd></div>
            <div><dt class="adm-stat-label">{{ a.dashboard.active }} · {{ a.dashboard.h24 }}</dt><dd class="display text-2xl text-base-50 tabular-nums">{{ num(data.users.active24h) }}</dd></div>
            <div><dt class="adm-stat-label">{{ a.dashboard.active }} · {{ a.dashboard.d7 }}</dt><dd class="display text-2xl text-base-50 tabular-nums">{{ num(data.users.active7d) }}</dd></div>
            <div><dt class="adm-stat-label">{{ a.dashboard.online }}</dt><dd class="display flex items-center gap-2 text-2xl text-base-50 tabular-nums"><span class="size-2 animate-lamp bg-ok" />{{ num(data.users.online) }}</dd></div>
            <div><dt class="adm-stat-label">{{ a.dashboard.messages }}</dt><dd class="display text-2xl text-base-50 tabular-nums">{{ num(data.chat.messages24h) }}</dd></div>
            <div class="col-span-2">
              <dt class="adm-stat-label">{{ a.dashboard.worlds }}</dt>
              <dd class="text-sm text-base-200"><NuxtLink to="/admin/worlds" class="hover:underline"><span class="display text-2xl text-base-50 tabular-nums">{{ num(data.hosting.openRooms) }}</span> · {{ fill(a.dashboard.playersInWorlds, { n: data.hosting.players }) }}</NuxtLink></dd>
            </div>
          </dl>
          <p class="mt-3 border-t border-base-800 pt-3 text-xs text-base-400">{{ num(data.users.total) }} {{ a.dashboard.users }}</p>
        </div>
      </section>

      <!-- Server + Audit -->
      <section class="mt-3 grid gap-3 lg:grid-cols-[20rem_minmax(0,1fr)]">
        <div class="card p-5">
          <h2 class="section-title flex items-center gap-2"><SiteIcon name="server" class="size-4 text-base-400" />{{ a.dashboard.server }}</h2>
          <dl class="adm-dl mt-3">
            <dt>{{ a.dashboard.version }}</dt><dd class="adm-mono">{{ data.server.version }}</dd>
            <dt>{{ a.dashboard.node }}</dt><dd class="adm-mono">{{ data.server.node }}</dd>
            <dt>{{ a.dashboard.uptime }}</dt><dd>{{ humanDuration(data.server.uptimeSec) }}</dd>
            <dt>{{ a.dashboard.db }}</dt><dd>{{ humanBytes(data.server.dbBytes, lang) }}</dd>
            <dt>{{ a.dashboard.disk }}</dt>
            <dd :class="diskLow ? 'text-redstone-300' : ''">
              <template v-if="data.server.disk">{{ fill(a.dashboard.diskOf, { free: humanBytes(data.server.disk.freeBytes, lang), total: humanBytes(data.server.disk.totalBytes, lang) }) }}</template>
              <template v-else>–</template>
            </dd>
          </dl>
        </div>
        <div class="card p-5">
          <div class="flex items-center gap-2">
            <h2 class="section-title flex-1">{{ a.dashboard.recent }}</h2>
            <NuxtLink to="/admin/audit" class="text-xs text-redstone-300 hover:underline">{{ a.dashboard.allAudit }}</NuxtLink>
          </div>
          <p v-if="!data.recentAudit.length" class="mt-3 text-sm text-base-400">{{ a.common.empty }}</p>
          <ul v-else class="mt-3 divide-y divide-base-800 text-sm">
            <li v-for="e in data.recentAudit" :key="e.id" class="flex flex-wrap items-baseline gap-x-2 py-1.5">
              <span class="w-32 shrink-0 text-xs text-base-400" :title="when(e.at)">{{ rel(e.at) }}</span>
              <span class="text-base-50">{{ e.actorName || (e.actor === 'system' ? a.common.system : e.actor === 'api-key' ? a.common.apiKey : e.actor.slice(0, 8)) }}</span>
              <span class="adm-mono text-base-300">{{ e.action }}</span>
              <NuxtLink v-if="e.target && e.target.length === 32" :to="`/admin/players/${e.target}`" class="truncate text-base-200 hover:underline">{{ e.targetName || e.target.slice(0, 8) }}</NuxtLink>
            </li>
          </ul>
        </div>
      </section>
    </template>
  </div>
</template>
