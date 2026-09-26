<script setup lang="ts">
import { sanctionKinds } from '~/utils/sanctions'
import type { Dashboard } from '~/utils/team'

// Übersicht: was gerade Aufmerksamkeit braucht (Meldungen, Einsprüche, Uploads),
// aktive Strafen je Art, Spieler, Welten, 30-Tage-Verlauf und Server-Zustand.
const toasts = useToasts()
const data = ref<Dashboard | null>(null)
const series = ref<'newUsers' | 'messages' | 'reports' | 'sanctions'>('reports')
const range = ref<7 | 30>(30)

async function load() {
  try {
    data.value = await backend.team.dashboard()
  } catch (e) {
    toasts.error(e)
  }
}
onMounted(load)

const since = (iso: string | null) => (iso ? t('team.dashboard.oldest', { date: formatRelative(iso, true) }) : '')
function uptime(sec: number): string {
  const d = Math.floor(sec / 86_400)
  const h = Math.floor((sec % 86_400) / 3600)
  return d ? t('team.dashboard.uptimeDays', { d, h }) : t('team.dashboard.uptimeHours', { h, m: Math.floor((sec % 3600) / 60) })
}
</script>

<template>
  <section class="space-y-5" :aria-label="t('team.nav.overview')" data-testid="admin-dashboard">
    <div v-if="!data" class="grid gap-3 md:grid-cols-3"><div v-for="i in 6" :key="i" class="skeleton h-28" /></div>
    <template v-else>
      <!-- Braucht Aufmerksamkeit -->
      <div class="grid gap-3 md:grid-cols-3">
        <NuxtLink to="/admin/reports" class="card card-hover block px-4 py-3.5" :class="{ 'border-redstone-600/60': data.reports.highPriority }">
          <p class="flex items-center gap-2 text-xs text-base-400"><SocialIcon name="flag" class="size-3.5" />{{ t('team.dashboard.openReports') }}</p>
          <p class="display mt-1 text-4xl text-base-50 tabular-nums">{{ formatNumber(data.reports.open + data.reports.inReview) }}</p>
          <p class="mt-1 flex flex-wrap gap-x-3 text-[11px]">
            <span v-if="data.reports.highPriority" class="font-semibold text-redstone-300">{{ t('team.dashboard.highPriority', { n: data.reports.highPriority }) }}</span>
            <span class="text-base-400">{{ t('team.dashboard.inReview', { n: data.reports.inReview }) }}</span>
            <span class="text-base-600">{{ since(data.reports.oldestOpenAt) }}</span>
          </p>
        </NuxtLink>
        <NuxtLink to="/admin/appeals" class="card card-hover block px-4 py-3.5" :class="{ 'border-lamp-400/40': data.appeals.open }">
          <p class="flex items-center gap-2 text-xs text-base-400"><SocialIcon name="appeal" class="size-3.5" />{{ t('team.dashboard.openAppeals') }}</p>
          <p class="display mt-1 text-4xl tabular-nums" :class="data.appeals.open ? 'text-lamp-300' : 'text-base-50'">{{ formatNumber(data.appeals.open) }}</p>
          <p class="mt-1 text-[11px] text-base-600">{{ since(data.appeals.oldestOpenAt) || t('team.dashboard.nothingOpen') }}</p>
        </NuxtLink>
        <NuxtLink to="/admin/uploads" class="card card-hover block px-4 py-3.5">
          <p class="flex items-center gap-2 text-xs text-base-400"><SocialIcon name="skins" class="size-3.5" />{{ t('team.dashboard.uploads') }}</p>
          <p class="display mt-1 text-4xl text-base-50 tabular-nums">{{ formatNumber(data.uploads.capesPending + data.uploads.cosmeticsPending) }}</p>
          <p class="mt-1 flex flex-wrap gap-x-3 text-[11px] text-base-400">
            <span>{{ t('team.dashboard.capes', { n: data.uploads.capesPending }) }}</span>
            <span>{{ t('team.dashboard.cosmetics', { n: data.uploads.cosmeticsPending }) }}</span>
            <span v-if="data.uploads.capesReported + data.uploads.cosmeticsReported" class="text-lamp-300">
              {{ t('team.dashboard.reported', { n: data.uploads.capesReported + data.uploads.cosmeticsReported }) }}
            </span>
          </p>
        </NuxtLink>
      </div>

      <!-- Aktive Strafen je Art -->
      <div class="card px-4 py-3.5">
        <div class="mb-2 flex items-center gap-2">
          <h2 class="section-title flex-1">{{ t('team.dashboard.activeSanctions') }}</h2>
          <NuxtLink to="/admin/sanctions" class="text-xs text-redstone-300 hover:underline">{{ t('team.dashboard.allSanctions') }}</NuxtLink>
        </div>
        <div class="flex flex-wrap gap-2">
          <NuxtLink
            v-for="k in sanctionKinds"
            :key="k"
            :to="`/admin/sanctions?kind=${k}`"
            class="flex items-center gap-2 rounded-lg border border-base-800 bg-base-850 px-3 py-2 hover:border-base-700"
          >
            <SanctionKindBadge :kind="k" :muted="!data.sanctions[k]" />
            <span class="text-lg font-semibold text-base-50 tabular-nums">{{ formatNumber(data.sanctions[k] ?? 0) }}</span>
          </NuxtLink>
        </div>
      </div>

      <div class="grid gap-5 xl:grid-cols-[minmax(0,1fr)_20rem]">
        <!-- Verlauf -->
        <div class="card p-4">
          <div class="mb-3 flex flex-wrap items-center gap-2">
            <h2 class="section-title flex-1">{{ t('team.dashboard.activity') }}</h2>
            <div class="flex gap-1 rounded-lg bg-base-850 p-1 text-xs" role="group" :aria-label="t('team.dashboard.series.label')">
              <button
                v-for="s in (['reports', 'sanctions', 'newUsers', 'messages'] as const)"
                :key="s"
                class="seg rounded-md"
                :class="{ 'seg-on': series === s }"
                :aria-pressed="series === s"
                @click="series = s"
              >
                {{ t(`team.dashboard.series.${s}`) }}
              </button>
            </div>
            <div class="flex gap-1 rounded-lg bg-base-850 p-1 text-xs" role="group" :aria-label="t('team.dashboard.range')">
              <button v-for="r in ([7, 30] as const)" :key="r" class="seg rounded-md" :class="{ 'seg-on': range === r }" :aria-pressed="range === r" @click="range = r">
                {{ t('team.dashboard.days', { n: r }) }}
              </button>
            </div>
          </div>
          <AdminChart :days="data.series.days" :values="data.series[series]" :label="t(`team.dashboard.series.${series}`)" :range="range" />
        </div>

        <!-- Spieler, Welten, Server -->
        <div class="space-y-3">
          <div class="card grid grid-cols-2 gap-3 p-4 text-sm">
            <div>
              <p class="text-xs text-base-400">{{ t('team.dashboard.players') }}</p>
              <p class="text-xl font-semibold text-base-50 tabular-nums">{{ formatNumber(data.users.total) }}</p>
              <p class="text-[11px] text-base-400">{{ t('team.dashboard.newPlayers', { d: data.users.new24h, w: data.users.new7d }) }}</p>
            </div>
            <div>
              <p class="text-xs text-base-400">{{ t('team.dashboard.online') }}</p>
              <p class="text-xl font-semibold text-ok tabular-nums">{{ formatNumber(data.users.online) }}</p>
              <p class="text-[11px] text-base-400">{{ t('team.dashboard.activePlayers', { d: data.users.active24h, w: data.users.active7d }) }}</p>
            </div>
            <div>
              <p class="text-xs text-base-400">{{ t('team.dashboard.worlds') }}</p>
              <p class="text-xl font-semibold text-base-50 tabular-nums">{{ formatNumber(data.hosting.openRooms) }}</p>
              <p class="text-[11px] text-base-400">{{ t('team.dashboard.worldPlayers', { n: data.hosting.players }) }}</p>
            </div>
            <div>
              <p class="text-xs text-base-400">{{ t('team.dashboard.messages') }}</p>
              <p class="text-xl font-semibold text-base-50 tabular-nums">{{ formatNumber(data.chat.messages24h) }}</p>
            </div>
          </div>
          <div v-if="data.server" class="card space-y-1 p-4 text-xs">
            <p class="section-title mb-1">{{ t('team.dashboard.server') }}</p>
            <p class="flex justify-between"><span class="text-base-400">{{ t('team.dashboard.version') }}</span><span class="text-base-100">{{ data.server.version }} · Node {{ data.server.node }}</span></p>
            <p class="flex justify-between"><span class="text-base-400">{{ t('team.dashboard.uptime') }}</span><span class="text-base-100">{{ uptime(data.server.uptimeSec) }}</span></p>
            <p class="flex justify-between"><span class="text-base-400">{{ t('team.dashboard.db') }}</span><span class="text-base-100">{{ formatBytes(data.server.dbBytes) }}</span></p>
            <p v-if="data.server.disk" class="flex justify-between">
              <span class="text-base-400">{{ t('team.dashboard.disk') }}</span>
              <span :class="data.server.disk.freeBytes < data.server.disk.totalBytes * 0.1 ? 'text-lamp-300' : 'text-base-100'">
                {{ t('team.dashboard.diskOf', { free: formatBytes(data.server.disk.freeBytes), total: formatBytes(data.server.disk.totalBytes) }) }}
              </span>
            </p>
          </div>
        </div>
      </div>

      <!-- Letzte Aktionen -->
      <div class="card p-4">
        <div class="mb-2 flex items-center gap-2">
          <h2 class="section-title flex-1">{{ t('team.dashboard.recent') }}</h2>
          <NuxtLink to="/admin/audit" class="text-xs text-redstone-300 hover:underline">{{ t('team.dashboard.allAudit') }}</NuxtLink>
          <button class="btn btn-ghost px-2.5 py-1 text-xs" @click="load">{{ t('common.actions.refresh') }}</button>
        </div>
        <ul v-if="data.recentAudit.length" class="space-y-1.5">
          <li v-for="e in data.recentAudit" :key="e.id"><AdminAuditLine :entry="e" compact /></li>
        </ul>
        <p v-else class="text-xs text-base-400">{{ t('team.common.empty') }}</p>
      </div>
    </template>
  </section>
</template>
