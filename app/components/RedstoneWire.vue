<script setup lang="ts">
// Fortschritt als Redstone-Leitung: Staub-Segmente laden sich von links auf.
// `powered` = Dauerstrom (Spiel läuft) – dann glimmt die ganze Leitung bernsteinfarben.
// `indeterminate` = Fortschritt unbekannt – ein Signal läuft immer wieder durch die Leitung.
const props = withDefaults(defineProps<{ percent?: number; powered?: boolean; segments?: number; indeterminate?: boolean }>(), {
  percent: 0,
  powered: false,
  segments: 24,
  indeterminate: false,
})

const lit = computed(() => (props.powered ? props.segments : Math.round((props.percent / 100) * props.segments)))
</script>

<template>
  <div class="relative flex items-center gap-[3px]" aria-hidden="true">
    <span
      v-for="i in segments"
      :key="i"
      class="h-1.5 flex-1 rounded-[1px] transition-[background-color,box-shadow] duration-200"
      :class="
        indeterminate
          ? 'wire-wait bg-redstone-900'
          : i > lit
            ? 'bg-redstone-900'
            : powered
              ? 'animate-lamp bg-lamp-400 shadow-[0_0_8px_var(--color-lamp-400)]'
              : i === lit
                ? 'bg-redstone-300 shadow-[0_0_10px_var(--color-redstone-400)]'
                : 'bg-redstone-500 shadow-[0_0_6px_var(--color-redstone-600)]'
      "
      :style="
        indeterminate
          ? { animationDelay: `${(i * 1600) / segments}ms` }
          : powered
            ? { animationDelay: `${(i % 6) * 120}ms` }
            : undefined
      "
    />
  </div>
</template>

<style scoped>
/* Ein Signal wandert Segment für Segment durch – wie Staub, der kurz aufleuchtet. */
.wire-wait {
  animation: wire-wait 1.6s steps(1) infinite;
}
@keyframes wire-wait {
  0% {
    background-color: var(--color-redstone-300);
    box-shadow: 0 0 10px var(--color-redstone-400);
  }
  8% {
    background-color: var(--color-redstone-500);
    box-shadow: 0 0 6px var(--color-redstone-600);
  }
  16%,
  100% {
    background-color: var(--color-redstone-900);
    box-shadow: none;
  }
}
:root[data-reduced-motion] .wire-wait {
  animation: none;
  background-color: var(--color-redstone-600);
}
</style>
