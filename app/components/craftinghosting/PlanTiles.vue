<script setup lang="ts">
import type { CraftingInfo } from '~/utils/craftinghosting'

// „Was du bekommst“ und „So läuft's“ als Kacheln mit Symbolen.
const props = defineProps<{ info: CraftingInfo; compact?: boolean }>()

const facts = computed(() => [
  { icon: 'memory', value: `${Math.round(props.info.plan.memoryMb / 1024)} GB`, label: t('craftinghosting.stats.memory') },
  { icon: 'cpu', value: `${props.info.plan.cpuPercent} %`, label: t('craftinghosting.stats.cpu') },
  { icon: 'disk', value: `${Math.round(props.info.plan.diskMb / 1024)} GB`, label: t('craftinghosting.stats.disk') },
])
const rules = computed(() => {
  if (props.info.tiles?.length) return props.info.tiles.map((x) => ({ icon: x.icon, text: pickText(x.text, currentLocale.value) }))
  const r = props.info.rules
  if (!r) {
    return [
      { icon: 'launcher', text: t('craftinghosting.rules.runs') },
      { icon: 'clock', text: t('craftinghosting.rules.session', { hours: props.info.limits.sessionMaxHours, pause: props.info.limits.cooldownHours }) },
      { icon: 'calendar', text: t('craftinghosting.rules.idle', { days: props.info.limits.deleteAfterIdleDays }) },
      { icon: 'doc', text: t('craftinghosting.rules.eula') },
    ]
  }
  return [
    { icon: 'launcher', text: t('craftinghosting.rules.runs') },
    { icon: 'clock', text: t('craftinghosting.rules.quota', { hours: r.budgetHours, pause: r.cooldownHours }) },
    { icon: 'clock', text: t('craftinghosting.rules.idleReset', { days: r.idleResetDays, hours: r.budgetHours }) },
    { icon: 'calendar', text: t('craftinghosting.rules.weekly', { hours: r.weeklyOfflineHours }) },
    { icon: 'calendar', text: t('craftinghosting.rules.idle', { days: props.info.limits.deleteAfterIdleDays }) },
    { icon: 'doc', text: t('craftinghosting.rules.eula') },
  ]
})
</script>

<template>
  <div class="space-y-4" data-testid="hosting-plan-tiles">
    <div>
      <h3 class="mb-2 text-xs font-semibold tracking-wide text-base-400 uppercase">{{ t('craftinghosting.plan.title') }}</h3>
      <div class="grid grid-cols-3 gap-2">
        <div v-for="f in facts" :key="f.icon" class="rounded-lg border border-base-800 bg-base-950/50 p-3 text-center">
          <svg viewBox="0 0 24 24" class="mx-auto size-5 text-redstone-300" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <template v-if="f.icon === 'memory'"><rect x="3" y="7" width="18" height="10" rx="1.5" /><path d="M7 10.5v3M11 10.5v3M15 10.5v3M6 17v2M18 17v2" /></template>
            <template v-else-if="f.icon === 'cpu'"><rect x="7" y="7" width="10" height="10" rx="1.5" /><path d="M10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4" /></template>
            <template v-else><ellipse cx="12" cy="6.5" rx="7.5" ry="2.8" /><path d="M4.5 6.5v11c0 1.5 3.4 2.8 7.5 2.8s7.5-1.3 7.5-2.8v-11M4.5 12c0 1.5 3.4 2.8 7.5 2.8s7.5-1.3 7.5-2.8" /></template>
          </svg>
          <p class="mt-1.5 text-base font-semibold text-base-50 tabular-nums">{{ f.value }}</p>
          <p class="text-[11px] text-base-400">{{ f.label }}</p>
        </div>
      </div>
    </div>
    <div>
      <h3 class="mb-2 text-xs font-semibold tracking-wide text-base-400 uppercase">{{ t('craftinghosting.rules.title') }}</h3>
      <ul class="grid gap-2" :class="compact ? '' : 'sm:grid-cols-2'">
        <li v-for="(r, i) in rules" :key="i" class="flex items-start gap-2.5 rounded-lg bg-base-850/60 px-3 py-2.5 text-xs leading-relaxed text-base-200">
          <svg viewBox="0 0 24 24" class="mt-px size-4 shrink-0 text-base-400" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <template v-if="r.icon === 'launcher'"><rect x="3" y="4" width="18" height="13" rx="2" /><path d="M8 21h8M12 17v4M10 9.5l4 1.5-4 1.5z" /></template>
            <template v-else-if="r.icon === 'clock'"><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></template>
            <template v-else-if="r.icon === 'calendar'"><rect x="3.5" y="5" width="17" height="15" rx="2" /><path d="M3.5 9.5h17M8 3v4M16 3v4" /></template>
            <template v-else><path d="M7 3h7l4 4v14H7z" /><path d="M14 3v4h4M10 12h5M10 15.5h3" /></template>
          </svg>
          <span>{{ r.text }}</span>
        </li>
      </ul>
    </div>
  </div>
</template>
