<script setup lang="ts">
import type { IconName } from '~/utils/icons'

// Linien-Icon aus `utils/icons.ts` (24er-Raster).
withDefaults(defineProps<{ name: IconName; stroke?: number; fill?: boolean }>(), { stroke: 2, fill: false })
</script>

<template>
  <svg
    viewBox="0 0 24 24"
    :fill="fill ? 'currentColor' : 'none'"
    stroke="currentColor"
    :stroke-width="stroke"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path :d="icons[name]" />
  </svg>
</template>
