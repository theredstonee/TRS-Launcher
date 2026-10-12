<script setup lang="ts">
// Hintergrund-Szene des gewählten Designs: Redstone-Schaltung oder Winterlandschaft.
// Gleiche Props wie beide Szenen; ohne Angabe gilt deren eigener Standard.
defineProps<{
  mode?: 'idle' | 'starting' | 'running'
  progress?: number
  anchor?: HTMLElement | null
  fill?: boolean
  seed?: number
}>()

const design = useDesign()
</script>

<template>
  <WinterScene v-if="design === 'winter'" :mode="mode" :progress="progress" :anchor="anchor" :fill="fill" :seed="seed">
    <slot />
  </WinterScene>
  <RedstoneScene v-else :mode="mode" :progress="progress" :anchor="anchor" :fill="fill" :seed="seed">
    <slot />
  </RedstoneScene>
</template>
