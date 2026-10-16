<script setup lang="ts">
import { codeShares, milestoneColor, milestoneProgress, recentSum, tierAtLeast, type CreatorSelf } from '~/utils/creator'

// Statistik: nur Zahlen, nie Namen der Fans (§35.3). Neue Fans der letzten 30 Tage als Säulen.
const props = defineProps<{ self: CreatorSelf }>()

const s = computed(() => props.self.stats)
const partner = computed(() => tierAtLeast(props.self.tier, 'partner'))
const fansWeek = computed(() => recentSum(s.value.daily, 'newFans'))
const redemptionsWeek = computed(() => recentSum(s.value.daily, 'redemptions'))
const capeShare = computed(() => (s.value.activeFans ? Math.round((s.value.fanCapeHolders / s.value.activeFans) * 100) : 0))
const days = computed(() => s.value.daily.map((d) => d.day))
const newFans = computed(() => s.value.daily.map((d) => d.newFans))
const shares = computed(() => codeShares(props.self.codes))
const progress = computed(() => milestoneProgress(s.value.activeFans, props.self.nextMilestone, props.self.milestone))
</script>

<template>
  <div class="space-y-4" data-testid="creator-stats">
    <div class="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <div class="card px-4 py-3">
        <p class="display text-2xl text-base-50 tabular-nums">{{ formatNumber(s.activeFans) }}</p>
        <p class="text-xs text-base-400">{{ t('creator.stats.activeFans') }}</p>
        <p v-if="fansWeek" class="mt-0.5 text-[11px] text-ok">{{ t('creator.stats.week', { n: formatNumber(fansWeek) }) }}</p>
      </div>
      <div class="card px-4 py-3">
        <p class="display text-2xl text-base-50 tabular-nums">{{ formatNumber(s.redemptions) }}</p>
        <p class="text-xs text-base-400">{{ t('creator.stats.redemptions') }}</p>
        <p v-if="redemptionsWeek" class="mt-0.5 text-[11px] text-ok">{{ t('creator.stats.week', { n: formatNumber(redemptionsWeek) }) }}</p>
      </div>
      <div class="card px-4 py-3">
        <p class="display text-2xl text-base-50 tabular-nums">{{ partner ? formatNumber(s.fanCapeHolders) : '–' }}</p>
        <p class="text-xs text-base-400">{{ t('creator.stats.capeHolders') }}</p>
        <p v-if="partner && s.activeFans" class="mt-0.5 text-[11px] text-ok">{{ t('creator.stats.capeShare', { n: capeShare }) }}</p>
        <p v-else-if="!partner" class="mt-0.5 text-[11px] text-base-600">{{ t('creator.stats.fromPartner') }}</p>
      </div>
      <div class="card px-4 py-3">
        <p class="display text-2xl text-base-50 tabular-nums">{{ formatNumber(s.newPlayers) }}</p>
        <p class="text-xs text-base-400">{{ t('creator.stats.newPlayers') }}</p>
        <p class="mt-0.5 text-[11px] text-base-600">{{ t('creator.stats.newPlayersHint') }}</p>
      </div>
    </div>

    <!-- Meilenstein -->
    <section class="card px-4 py-3" :aria-label="t('creator.stats.milestoneTitle')">
      <div class="flex flex-wrap items-baseline gap-2 text-xs">
        <span class="font-semibold text-base-50">{{ t('creator.stats.milestoneTitle') }}</span>
        <span v-if="self.milestone" class="badge bg-base-800 text-base-200">
          <span class="inline-block size-2 rounded-full" :style="{ background: milestoneColor[self.milestone] }" />{{ t(`creator.milestones.${self.milestone}`) }}
        </span>
        <span class="ml-auto text-base-400">
          {{
            self.nextMilestone
              ? t('creator.stats.nextMilestone', { name: t(`creator.milestones.${self.nextMilestone.name}`), n: formatNumber(s.activeFans), at: formatNumber(self.nextMilestone.at) })
              : t('creator.stats.allMilestones')
          }}
        </span>
      </div>
      <div class="mt-2 h-2 overflow-hidden rounded-full bg-base-800" role="progressbar" :aria-valuenow="progress" aria-valuemin="0" aria-valuemax="100" :aria-label="t('creator.stats.milestoneTitle')">
        <div class="h-full rounded-full bg-redstone-500 transition-[width] duration-500" :style="{ width: `${progress}%` }" />
      </div>
      <p class="mt-1.5 text-[11px] text-base-400">{{ t('creator.stats.milestoneHint') }}</p>
    </section>

    <section class="card px-4 py-3" :aria-label="t('creator.stats.chartTitle')">
      <h3 class="section-title mb-2">{{ t('creator.stats.chartTitle') }}</h3>
      <AdminChart v-if="days.length" :days="days" :values="newFans" :label="t('creator.stats.chartTitle')" :range="30" />
      <p v-else class="text-xs text-base-400">{{ t('creator.stats.noData') }}</p>
    </section>

    <div class="grid gap-4 md:grid-cols-2">
      <section class="card px-4 py-3" :aria-label="t('creator.stats.byCode')">
        <h3 class="section-title mb-2">{{ t('creator.stats.byCode') }}</h3>
        <table class="w-full text-sm">
          <thead>
            <tr class="text-left text-[11px] tracking-wide text-base-400 uppercase">
              <th class="py-1.5 font-medium">{{ t('creator.codes.code') }}</th>
              <th class="py-1.5 text-right font-medium">{{ t('creator.codes.uses') }}</th>
              <th class="py-1.5 text-right font-medium">{{ t('creator.stats.share') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in shares" :key="c.code" class="border-t border-base-800">
              <td class="py-1.5 font-mono text-xs font-semibold text-redstone-300">{{ c.code }}</td>
              <td class="py-1.5 text-right tabular-nums">{{ formatNumber(c.uses) }}</td>
              <td class="py-1.5 text-right text-base-400 tabular-nums">{{ c.percent }} %</td>
            </tr>
          </tbody>
        </table>
      </section>
      <section class="card px-4 py-3" :aria-label="t('creator.stats.privacyTitle')">
        <h3 class="section-title mb-2">{{ t('creator.stats.privacyTitle') }}</h3>
        <p class="text-xs text-base-400">{{ t('creator.stats.privacy') }}</p>
      </section>
    </div>
  </div>
</template>
