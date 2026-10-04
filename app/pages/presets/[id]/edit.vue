<script setup lang="ts">
// Eigenes Preset bearbeiten.
const route = useRoute()
const id = computed(() => String(route.params.id))
</script>

<template>
  <PresetEditor :key="id" :preset-id="id" />
</template>
