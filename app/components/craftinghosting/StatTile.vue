<script setup lang="ts">
// Live-Wert als kleine Kachel mit Balken (grün → gelb → rot nach Auslastung).
const props = defineProps<{ label: string; value: string; detail?: string; percent: number | null }>()

const barClass = computed(() => {
  const p = props.percent ?? 0
  return p >= 90 ? 'bg-redstone-500' : p >= 70 ? 'bg-lamp-400' : 'bg-ok'
})
</script>

<template>
  <div class="rounded-xl border border-base-800 bg-base-900 p-3.5">
    <div class="flex items-center gap-2 text-base-400">
      <slot name="icon" />
      <span class="text-[11px] font-semibold tracking-wide uppercase">{{ label }}</span>
    </div>
    <p class="mt-1.5 truncate text-xl font-semibold text-base-50 tabular-nums">{{ value }}</p>
    <p v-if="detail" class="truncate text-[11px] text-base-400 tabular-nums">{{ detail }}</p>
    <div
      class="mt-2.5 h-1.5 overflow-hidden rounded-full bg-base-800"
      role="progressbar"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-valuenow="percent == null ? undefined : Math.round(percent)"
      :aria-label="label"
    >
      <div class="h-full rounded-full transition-[width] duration-500" :class="barClass" :style="{ width: `${percent ?? 0}%` }" />
    </div>
  </div>
</template>
