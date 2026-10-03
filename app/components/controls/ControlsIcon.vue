<script setup lang="ts">
import type { ControlIcon } from '~/utils/controls'
import { iconPixels } from '~/utils/controls'

// Ein Pixel-Symbol der Touch-Steuerung (8×8), z. B. in der Symbolauswahl.
const props = defineProps<{ icon: ControlIcon }>()
const pixels = computed(() => iconPixels(props.icon))
</script>

<template>
  <svg viewBox="0 0 8 8" shape-rendering="crispEdges" fill="currentColor" aria-hidden="true">
    <rect v-for="([x, y], i) in pixels" :key="i" :x="x" :y="y" width="1" height="1" />
  </svg>
</template>
