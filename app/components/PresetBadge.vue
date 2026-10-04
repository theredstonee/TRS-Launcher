<script setup lang="ts">
// Symbol eines Presets auf seiner Akzentfarbe (Liste, Editor, Auswahl).
const props = withDefaults(defineProps<{ icon: string; color: string; size?: number }>(), { size: 40 })

const hex = computed(() => presetColor(props.color))
const style = computed(() => ({
  width: `${props.size}px`,
  height: `${props.size}px`,
  color: hex.value,
  background: `color-mix(in srgb, ${hex.value} 16%, transparent)`,
  boxShadow: `inset 0 0 0 1px color-mix(in srgb, ${hex.value} 45%, transparent)`,
}))
</script>

<template>
  <span class="grid shrink-0 place-items-center rounded-lg" :style="style" aria-hidden="true">
    <svg viewBox="0 0 24 24" :style="{ width: `${Math.round(size * 0.5)}px`, height: `${Math.round(size * 0.5)}px` }" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
      <path :d="presetIconPath(icon)" />
    </svg>
  </span>
</template>
