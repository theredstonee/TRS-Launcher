<script setup lang="ts">
// Tageswerte der letzten 7 bzw. 30 Tage als schlanke Säulen (eine Reihe, eine
// Farbe = Akzent). Hover/Fokus zeigt Datum und Wert; Summe und Höchstwert stehen
// als Text daneben, damit nichts nur über die Grafik lesbar ist.
const props = defineProps<{ days: string[]; values: number[]; label: string; range: 7 | 30 }>()

const points = computed(() => {
  const n = Math.min(props.days.length, props.values.length)
  const start = Math.max(0, n - props.range)
  return props.days.slice(start, n).map((day, i) => ({ day, value: props.values[start + i] ?? 0 }))
})
const max = computed(() => Math.max(1, ...points.value.map((p) => p.value)))
const total = computed(() => points.value.reduce((s, p) => s + p.value, 0))
const hover = ref<number | null>(null)

function dayLabel(day: string): string {
  const d = new Date(`${day}T12:00:00Z`)
  return Number.isNaN(d.getTime()) ? day : new Intl.DateTimeFormat(intlLocale(), { day: 'numeric', month: 'short' }).format(d)
}
</script>

<template>
  <figure class="min-w-0" :aria-label="label">
    <div class="mb-2 flex items-baseline gap-3 text-xs text-base-400">
      <span>{{ t('team.dashboard.total', { n: formatNumber(total) }) }}</span>
      <span>{{ t('team.dashboard.peak', { n: formatNumber(max === 1 && !total ? 0 : max) }) }}</span>
      <span v-if="hover !== null && points[hover]" class="ml-auto font-medium text-base-50 tabular-nums" aria-live="polite">
        {{ dayLabel(points[hover]!.day) }} · {{ formatNumber(points[hover]!.value) }}
      </span>
    </div>
    <div class="relative h-28 border-b border-base-700" @mouseleave="hover = null">
      <div class="absolute inset-0 flex items-end gap-[2px]">
        <button
          v-for="(p, i) in points"
          :key="p.day"
          type="button"
          class="group relative flex h-full min-w-0 flex-1 items-end focus-visible:outline-none"
          :aria-label="`${dayLabel(p.day)}: ${formatNumber(p.value)}`"
          @mouseenter="hover = i"
          @focus="hover = i"
        >
          <span
            class="block w-full rounded-t-[4px] transition-colors"
            :class="hover === i ? 'bg-redstone-400' : 'bg-redstone-500/75 group-focus-visible:bg-redstone-400'"
            :style="{ height: p.value ? `${Math.max(3, (p.value / max) * 100)}%` : '0' }"
          />
        </button>
      </div>
    </div>
    <div class="mt-1 flex justify-between text-[10px] text-base-600">
      <span>{{ points[0] ? dayLabel(points[0].day) : '' }}</span>
      <span>{{ points.at(-1) ? dayLabel(points.at(-1)!.day) : '' }}</span>
    </div>
  </figure>
</template>
