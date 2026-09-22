<script setup lang="ts">
// Fortschritt als Redstone-Leitung: Staub-Segmente laden sich von links auf.
// `powered` = Dauerstrom (Spiel läuft) – dann glimmt die ganze Leitung bernsteinfarben.
const props = withDefaults(defineProps<{ percent?: number; powered?: boolean; segments?: number }>(), {
  percent: 0,
  powered: false,
  segments: 24,
})

const lit = computed(() => (props.powered ? props.segments : Math.round((props.percent / 100) * props.segments)))
</script>

<template>
  <div class="flex items-center gap-[3px]" aria-hidden="true">
    <span
      v-for="i in segments"
      :key="i"
      class="h-1.5 flex-1 rounded-[1px] transition-[background-color,box-shadow] duration-200"
      :class="
        i > lit
          ? 'bg-redstone-900'
          : powered
            ? 'animate-lamp bg-lamp-400 shadow-[0_0_8px_var(--color-lamp-400)]'
            : i === lit
              ? 'bg-redstone-300 shadow-[0_0_10px_var(--color-redstone-400)]'
              : 'bg-redstone-500 shadow-[0_0_6px_var(--color-redstone-600)]'
      "
      :style="powered ? { animationDelay: `${(i % 6) * 120}ms` } : undefined"
    />
  </div>
</template>
